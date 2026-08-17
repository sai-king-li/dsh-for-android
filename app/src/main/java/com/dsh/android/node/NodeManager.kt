package com.dsh.android.node

import android.content.Context
import android.util.Log
import com.dsh.android.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.Process
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the embedded Node.js runtime and the dsh server process.
 *
 * Lifecycle is tied to [NodeService]: the service keeps the app alive in the
 * foreground while the bootstrap (and its dsh child) run. The activity only
 * observes [state].
 */
object NodeManager {

    private const val TAG = "NodeManager"

    enum class Status { IDLE, PREPARING, INSTALLING, STARTING, RUNNING, STOPPED, ERROR }

    data class NodeState(
        val status: Status = Status.IDLE,
        val phase: String = "",
        val pct: Int = 0,
        val port: Int = 0,
        val url: String = "",
        val message: String = "",
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(NodeState())
    val state: StateFlow<NodeState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serveProcess: Process? = null
    private var serveJob: Job? = null
    @Volatile
    private var starting = false
    private var appContext: Context? = null
    private lateinit var settings: SettingsStore

    private val filesDir: File get() = appContext!!.filesDir
    private val nodeDir: File get() = File(filesDir, "node")
    private val nodeLibDir: File get() = File(nodeDir, "lib")
    /**
     * The node executable ships as jniLibs/<abi>/libnode.so. Android extracts
     * native libs to nativeLibraryDir at install time; that directory has the
     * exec_type SELinux label — the only place an app may execve() a bundled
     * binary on Android 10+ (files under getFilesDir() are labeled
     * app_data_file and W^X blocks execve there).
     */
    private val nodeBin: File get() = File(appContext!!.applicationInfo.nativeLibraryDir, "libnode.so")
    private val bootstrapDir: File get() = File(filesDir, "bootstrap")
    private val bootstrapJs: File get() = File(bootstrapDir, "bootstrap.js")
    private val statusFile: File get() = File(filesDir, "dsh-status.json")
    // v3: re-extracts the npm tree so aapt-ignored underscore dirs
    // (e.g. @sigstore __generated__) are present after the aapt fix.
    private val extractedMarker: File get() = File(nodeDir, ".extracted-v3")
    private val logFile: File get() = File(filesDir, "logs/dsh.log")

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            settings = SettingsStore(context.applicationContext)
        }
    }

    // ------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------

    /** True while our bootstrap (or an adopted server) is up. */
    val isRunning: Boolean get() = serveProcess?.isAlive == true || _state.value.status == Status.RUNNING

    /** Starts the dsh server. Safe to call repeatedly (serialized). */
    fun startServer() {
        val ctx = appContext ?: return // service restarted before activity init
        if (starting || serveProcess?.isAlive == true) return
        starting = true
        serveJob?.cancel()
        serveJob = scope.launch {
            try {
                runCatching { adoptExistingServer() }
                if (_state.value.status == Status.RUNNING) return@launch
                ensureBootstrapCopied()
                ensureNodeRuntime { pct, phase ->
                    _state.update {
                        it.copy(status = Status.PREPARING, pct = pct, phase = phase)
                    }
                }
                spawnServe()
            } finally {
                starting = false
            }
        }
    }

    /** Stops the dsh server (and any adopted dsh child we recorded). */
    fun stopServer() {
        serveJob?.cancel()
        val proc = serveProcess
        serveProcess = null
        if (proc?.isAlive == true) {
            runCatching { proc.destroy() } // SIGTERM → bootstrap forwards to dsh
        }
        // If we adopted a previously-running server, kill it by recorded pid.
        val pid = readStatusPid()
        if (pid > 0 && serveProcess == null) {
            runCatching { android.os.Process.killProcess(pid) }
        }
        _state.update { it.copy(status = Status.STOPPED, message = "stopped by user", url = "", port = 0) }
    }

    /** True if a dsh server is reachable on the given port. */
    fun isHttpUp(port: Int): Boolean = try {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        conn.connectTimeout = 1200
        conn.readTimeout = 1200
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (_: Exception) {
        false
    }

    /** Current state, re-read from disk (used to adopt an existing server). */
    private fun readStatus(): JSONObject? = try {
        if (statusFile.exists()) JSONObject(statusFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun readStatusPid(): Int = try {
        readStatus()?.optInt("pid", 0) ?: 0
    } catch (_: Exception) {
        0
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun adoptExistingServer() {
        val status = readStatus() ?: return
        if (status.optString("state") != "running") return
        val port = status.optInt("port", 0)
        if (port > 0 && isHttpUp(port)) {
            _state.update {
                it.copy(
                    status = Status.RUNNING,
                    port = port,
                    url = "http://127.0.0.1:$port",
                    message = "reusing running server",
                )
            }
            Log.i(TAG, "adopted existing dsh server on port $port")
        }
    }

    private suspend fun ensureBootstrapCopied() {
        runCatching {
            // Copy the whole bootstrap asset tree (bootstrap.js + node-pty fork).
            val assets = appContext!!.assets
            val files = mutableListOf<String>()
            collectAssetPaths(assets, "bootstrap", files)
            for (rel in files) {
                val target = File(bootstrapDir, rel.removePrefix("bootstrap/"))
                target.parentFile?.mkdirs()
                assets.open(rel).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            bootstrapJs.setExecutable(true)
        }.onFailure {
            Log.w(TAG, "failed to copy bootstrap assets", it)
        }
    }

    /** Copies the bundled Node runtime (shared libs + npm) from assets to files dir (once). */
    private suspend fun ensureNodeRuntime(onProgress: (pct: Int, phase: String) -> Unit) {
        if (nodeLibDir.exists() && extractedMarker.exists()) return
        onProgress(0, "解压运行环境")
        val assets = appContext!!.assets
        val assetRoot = "node"
        val files = mutableListOf<String>()
        collectAssetPaths(assets, assetRoot, files)
        val total = files.size
        var done = 0
        for (rel in files) {
            val target = File(nodeDir, rel.removePrefix("$assetRoot/"))
            target.parentFile?.mkdirs()
            assets.open(rel).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            done++
            if (done % 20 == 0 || done == total) {
                onProgress(done * 100 / total, "解压运行环境")
            }
        }
        extractedMarker.writeText("extracted ${System.currentTimeMillis()}\n")
        onProgress(100, "解压运行环境")
        Log.i(TAG, "node runtime extracted: ${files.size} files")
    }

    private fun collectAssetPaths(assets: android.content.res.AssetManager, path: String, out: MutableList<String>) {
        val children = assets.list(path) ?: return
        if (children.isEmpty()) {
            out += path
        } else {
            for (child in children) collectAssetPaths(assets, "$path/$child", out)
        }
    }

    private suspend fun spawnServe() {
        // Belt-and-braces: the extracted native lib should already be
        // executable, but make sure the exec bits are set before spawning.
        if (nodeBin.exists() && !nodeBin.canExecute()) {
            runCatching { nodeBin.setExecutable(true, false) }
        }
        val builder = ProcessBuilder(nodeBin.absolutePath, bootstrapJs.absolutePath, "serve", "--files", filesDir.absolutePath)
        val env = builder.environment()
        env["HOME"] = filesDir.absolutePath
        env["DSH_HOME"] = File(filesDir, ".dsh").absolutePath
        // Termux-built OpenSSL defaults to a config path that does not exist on
        // a stock device; point it at /dev/null (OpenSSL falls back to built-in
        // defaults, and Node's TLS still works).
        env["OPENSSL_CONF"] = "/dev/null"
        // Shared libraries live in nativeLibraryDir. An execve()'d child's
        // linker does not get the app's namespace search path automatically, so
        // point LD_LIBRARY_PATH at the native lib dir explicitly (the env var
        // IS honored for app-spawned processes).
        env["LD_LIBRARY_PATH"] = appContext!!.applicationInfo.nativeLibraryDir
        env["PATH"] = listOf(
            appContext!!.applicationInfo.nativeLibraryDir,
            "/system/bin", "/system/xbin", "/vendor/bin", "/data/local/bin",
        ).joinToString(":")
        env["npm_config_cache"] = File(filesDir, ".npm-cache").absolutePath
        env["DSH_TELEMETRY_MODE"] = "DISABLED"
        env["DSH_PERMISSION_MODE"] = "danger-full-access"
        val key = try {
            settings.snapshot().apiKey
        } catch (e: Exception) {
            ""
        }
        if (key.isNotBlank()) env["DEEPSEEK_API_KEY"] = key
        builder.redirectErrorStream(false)

        _state.update { it.copy(status = Status.STARTING, message = "启动 dsh 中…") }
        try {
            val proc = builder.start()
            serveProcess = proc
            readProcessOutput(proc)
        } catch (e: Exception) {
            Log.e(TAG, "failed to start node", e)
            _state.update { it.copy(status = Status.ERROR, message = "启动失败: ${e.message}") }
        }
    }

    private suspend fun readProcessOutput(proc: Process) {
        val outReader = BufferedReader(InputStreamReader(proc.inputStream))
        val errReader = BufferedReader(InputStreamReader(proc.errorStream))

        val outJob = scope.launch {
            while (true) {
                val line = outReader.readLine() ?: break
                handleEventLine(line)
            }
        }
        val errJob = scope.launch {
            val tail = ArrayDeque<String>()
            while (true) {
                val line = errReader.readLine() ?: break
                tail.addLast(line)
                if (tail.size > 60) tail.removeFirst()
                appendLog(line)
            }
            if (tail.isNotEmpty()) {
                _state.update { it.copy(message = "服务日志: ${tail.last()}") }
            }
        }
        outJob.join()
        errJob.join()
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        Log.i(TAG, "bootstrap exited with $code")
        if (_state.value.status == Status.RUNNING) {
            _state.update { it.copy(status = Status.STOPPED, message = "服务已停止 (exit $code)") }
        } else if (_state.value.status == Status.STARTING) {
            _state.update { it.copy(status = Status.ERROR, message = "dsh 启动失败 (exit $code)，请查看日志") }
        }
        serveProcess = null
    }

    private fun handleEventLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        appendLog(trimmed)
        val obj = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return
        }
        when (obj.optString("event")) {
            "log" -> {
                val level = obj.optString("level", "info")
                val message = obj.optString("message", "")
                if (level == "error") {
                    _state.update { it.copy(status = Status.ERROR, message = message) }
                }
                Log.d(TAG, "[$level] $message")
            }
            "progress" -> {
                _state.update {
                    it.copy(
                        status = if (it.status == Status.RUNNING || it.status == Status.STARTING) it.status else Status.INSTALLING,
                        phase = obj.optString("phase", it.phase),
                        pct = obj.optInt("pct", it.pct),
                        message = "安装 dsh 运行环境…",
                    )
                }
            }
            "state" -> {
                when (val s = obj.optString("state")) {
                    "installed" -> _state.update { it.copy(status = Status.INSTALLING, pct = 100, message = "已安装，正在启动…") }
                    "starting" -> _state.update { it.copy(status = Status.STARTING, message = "正在启动 dsh…") }
                    "running" -> {
                        val port = obj.optInt("port", 0)
                        _state.update {
                            it.copy(
                                status = Status.RUNNING,
                                port = port,
                                url = "http://127.0.0.1:$port",
                                message = "dsh 正在运行",
                            )
                        }
                    }
                    "stopped" -> _state.update { it.copy(status = Status.STOPPED, message = obj.optString("reason", "stopped")) }
                    "error" -> _state.update { it.copy(status = Status.ERROR, message = obj.optString("error", "unknown error")) }
                }
            }
            "error" -> {
                _state.update { it.copy(status = Status.ERROR, message = obj.optString("message", "未知错误")) }
            }
        }
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(300)) }
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n")
        }
    }
}
