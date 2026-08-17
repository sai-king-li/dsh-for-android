#!/usr/bin/env node
/**
 * bootstrap.js — dsh-for-Android runtime bootstrap.
 *
 * Runs under the bundled Termux Node.js runtime. Two modes:
 *
 *   node bootstrap.js install [--files <dir>] [--dsh-version <ver>]
 *       One-shot: create the dsh npm project, install @deepseek-ai/dsh with
 *       install scripts disabled (native addons that cannot build on Android),
 *       then patch the installed tree with Android stubs for the packages
 *       whose native binaries have no Android build:
 *         - sharp                      (image processing, windows/mac/linux only)
 *         - @deepseek-ai/dsh-sandbox-windows-acl  (Win32 sandbox, koffi-based)
 *       node-pty is redirected to the Android/ARM64 fork via npm overrides.
 *
 *   node bootstrap.js serve [--files <dir>]
 *       Long-running: ensure install, pick a free port (3080 first), spawn
 *       `dsh web --host 127.0.0.1 --port <port>`, supervise the child, and
 *       publish status. Killed (SIGTERM) → forwards to the dsh child.
 *
 * Protocol: every event is written as one JSON object per line on stdout:
 *   {"event":"log","level":"info","message":"..."}
 *   {"event":"progress","phase":"installing","pct":<0-100>}
 *   {"event":"state","state":"running","port":3080,"url":"http://127.0.0.1:3080"}
 *   {"event":"error","message":"..."}
 * A durable copy of the latest state is kept at <files>/dsh-status.json.
 */
import { get as httpGet } from "node:http";
import { spawn } from "node:child_process";
import {
  appendFileSync, chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync,
  readdirSync, rmSync, writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = fileURLToPath(new URL(".", import.meta.url));
const NODE_BIN = process.execPath;
const DSHD_VERSION = process.env.DSH_ANDROID_DSH_VERSION ?? "0.1.0-rc.6";
const NODE_PTY_ANDROID = "@mmmbuto/node-pty-android-arm64@1.1.2";
const NODE_PTY_BUNDLED_DIR = join(SCRIPT_DIR, "node-pty-android");

// ---------------------------------------------------------------------------
// args
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = { files: process.env.DSH_ANDROID_FILES ?? null };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--files") out.files = argv[++i];
    else if (a === "--dsh-version") out.dshVersion = argv[++i];
    else out.mode = a;
  }
  return out;
}

const args = parseArgs(process.argv);
if (!args.mode || (args.mode !== "install" && args.mode !== "serve")) {
  fail(`usage: node bootstrap.js <install|serve> [--files <dir>]`);
}

const FILES = args.files
  ? resolve(args.files)
  : (process.env.HOME ? join(process.env.HOME, "dsh-files") : resolve("."));
const RUNTIME_DIR = join(FILES, "dsh-runtime");
const DSH_HOME = process.env.DSH_HOME ?? join(FILES, ".dsh");
const NPM_CACHE = join(FILES, ".npm-cache");
const LOG_FILE = join(FILES, "logs", "dsh.log");
const STATUS_FILE = join(FILES, "dsh-status.json");
const PATCH_MARKER = join(RUNTIME_DIR, ".android-patched-v1");
const NODE_MODULES = join(RUNTIME_DIR, "node_modules");

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
function emit(obj) {
  const line = JSON.stringify(obj);
  process.stdout.write(line + "\n");
  if (obj.event !== "log" || obj.level === "error" || obj.level === "warn") {
    appendLog(line);
  }
}
function log(level, message) {
  emit({ event: "log", level, message });
}
function progress(phase, pct) {
  emit({ event: "progress", phase, pct });
}
function state(obj) {
  const payload = { event: "state", ...obj, ts: Date.now() };
  emit(payload);
  try {
    writeFileSync(STATUS_FILE, JSON.stringify({ ...payload, files: FILES }, null, 2));
  } catch { /* non-fatal */ }
}
function error(message) {
  emit({ event: "error", message });
  try {
    writeFileSync(STATUS_FILE, JSON.stringify({ event: "state", state: "error", error: message, ts: Date.now() }, null, 2));
  } catch { /* non-fatal */ }
}
function appendLog(line) {
  try {
    mkdirSync(join(FILES, "logs"), { recursive: true });
    appendFileSync(LOG_FILE, line + "\n");
  } catch { /* non-fatal */ }
}
function fail(message) {
  emit({ event: "error", message });
  process.exit(2);
}

function run(cmd, argsArr, opts = {}) {
  return new Promise((resolvePromise) => {
    const child = spawn(cmd, argsArr, {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...opts.env },
      ...opts,
    });
    let out = "";
    let err = "";
    child.stdout.on("data", (d) => { out += d; });
    child.stderr.on("data", (d) => { err += d; });
    child.on("close", (code) => resolvePromise({ code, out, err }));
  });
}

async function healthOk(port, timeoutMs = 2000) {
  return new Promise((resolvePromise) => {
    const r = httpGet({ host: "127.0.0.1", port, path: "/", timeout: timeoutMs }, (res) => {
      res.resume();
      resolvePromise(res.statusCode === 200);
    });
    r.on("error", () => resolvePromise(false));
    r.on("timeout", () => { r.destroy(); resolvePromise(false); });
  });
}

// ---------------------------------------------------------------------------
// install
// ---------------------------------------------------------------------------
async function writeRuntimePackageJson(version) {
  mkdirSync(RUNTIME_DIR, { recursive: true });
  const pkg = {
    name: "dsh-android-runtime",
    version: "1.0.0",
    private: true,
    description: "npm project hosting the dsh runtime for the Dsh for Android app",
    dependencies: { "@deepseek-ai/dsh": version },
  };
  writeFileSync(join(RUNTIME_DIR, "package.json"), JSON.stringify(pkg, null, 2) + "\n");
}

async function npmInstall() {
  mkdirSync(RUNTIME_DIR, { recursive: true });
  log("info", `installing @deepseek-ai/dsh@${DSHD_VERSION} (this can take a few minutes on first launch)`);
  progress("installing", 5);
  // The bundled npm CLI lives inside the extracted Node runtime tree.
  const bundledNpmCli = join(FILES, "node", "lib", "node_modules", "npm", "bin", "npm-cli.js");
  let npmCommand = NODE_BIN;
  let npmArgs = [bundledNpmCli, "install", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error"];
  if (!existsSync(bundledNpmCli)) {
    // Dev fallback: use the platform npm directly.
    npmCommand = process.platform === "win32" ? "npm.cmd" : "npm";
    npmArgs = ["install", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error"];
  }
  const result = await run(npmCommand, npmArgs, {
    cwd: RUNTIME_DIR,
    // IMPORTANT: `env` replaces the whole environment — merge process.env so
    // the child keeps LD_LIBRARY_PATH / OPENSSL_CONF / PATH etc.
    env: {
      ...process.env,
      npm_config_cache: NPM_CACHE,
      npm_config_ignore_scripts: "true",
      npm_config_audit: "false",
      npm_config_fund: "false",
    },
  });
  progress("installing", 60);
  if (result.code !== 0) {
    const tail = (result.err || result.out).split("\n").filter(Boolean).slice(-12).join("\n");
    error(`npm install failed (exit ${result.code}):\n${tail}`);
    process.exit(3);
  }
  log("info", "npm install finished");
  progress("installing", 70);
}

function writeSharpStub() {
  const dir = join(NODE_MODULES, "sharp");
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "package.json"), JSON.stringify({
    name: "sharp",
    version: "0.35.3-android-stub",
    description: "Stub replacing sharp on Android (native image processing has no Android build). Chat works normally; image attachment processing is unavailable.",
    main: "index.js",
    type: "commonjs",
  }, null, 2) + "\n");
  writeFileSync(join(dir, "index.js"), `"use strict";
// Android stub for sharp. The real sharp has no Android binary; dsh only
// uses it for image-attachment processing, which is unavailable on Android.
function sharpUnavailable() {
  throw new Error("dsh-android: image processing (sharp) is not available on Android. Text chat works normally; image attachments are not supported.");
}
module.exports = sharpUnavailable;
module.exports.default = sharpUnavailable;
`);
}

function writeSandboxWindowsAclStub() {
  const dir = join(NODE_MODULES, "@deepseek-ai", "dsh-sandbox-windows-acl");
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "package.json"), JSON.stringify({
    name: "@deepseek-ai/dsh-sandbox-windows-acl",
    version: "0.1.0-rc.6-android-stub",
    description: "Android stub for the Win32 ACL sandbox (koffi native is Windows-only). The web profile never selects this sandbox rung on Android.",
    type: "module",
    main: "index.js",
    exports: {
      ".": "./index.js",
      "./runner": "./runner.js",
      "./src/*": "./src/*",
      "./package.json": "./package.json",
    },
  }, null, 2) + "\n");
  writeFileSync(join(dir, "index.js"), `// Android stub for @deepseek-ai/dsh-sandbox-windows-acl.
// The real package binds Win32 APIs through the koffi native module, which has
// no Android build. None of its runners are ever selected on Android, so these
// exports only need to exist and fail loudly if something tries to use them.
export class Win32Error extends Error {
  constructor(api, win32Code, detail) {
    super(\`\${api} failed (Win32 \${win32Code})\${detail === undefined ? "" : ": " + detail}\`);
    this.name = "Win32Error";
    this.api = api;
    this.win32Code = win32Code;
  }
}

function unavailable() {
  return new Error("dsh-android: the Windows ACL sandbox is unavailable on Android (stubbed by dsh-for-Android)");
}

export class AclWriteGrant {
  static create() { throw unavailable(); }
}
export class AclSandbox {
  constructor() { throw unavailable(); }
}
export function assertTempRootOutsideWorkspace() { throw unavailable(); }
export function tempWriteSid() { throw unavailable(); }
export function workspaceWriteSid() { throw unavailable(); }
export function quoteArg(value) { return value; }
`);
  writeFileSync(join(dir, "runner.js"), `// Android stub runner for the Win32 ACL sandbox.
console.error("dsh-android: windows-acl runner is unavailable on Android");
process.exit(127);
`);
  mkdirSync(join(dir, "src"), { recursive: true });
  writeFileSync(join(dir, "src", "runner.ts"), `// Android stub (see ../index.js)\n`);
}

/**
 * Handle node-pty for the running ABI.
 * - arm64: replace with the bundled Android/ARM64 fork (prebuilds/android-arm64).
 * - other ABIs (x86_64 emulator etc.): no Android prebuilt exists, and the
 *   module loads its native at require time, so replace it with a stub that
 *   exposes the API but throws when a terminal is actually requested. Boot
 *   succeeds; terminal features are unavailable on those ABIs.
 */
function writeNodePtyPatch() {
  const dest = join(NODE_MODULES, "node-pty");
  if (process.arch === "arm64") {
    if (!existsSync(join(NODE_PTY_BUNDLED_DIR, "package.json"))) {
      log("warn", "bundled node-pty android fork missing; leaving installed node-pty as-is");
      return;
    }
    rmSync(dest, { recursive: true, force: true });
    mkdirSync(dest, { recursive: true });
    copyDir(NODE_PTY_BUNDLED_DIR, dest);
    log("info", "node-pty replaced with the Android/ARM64 fork");
    return;
  }
  // Non-arm64: no prebuilt pty.node — stub the module (terminals unavailable).
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(dest, { recursive: true });
  writeFileSync(join(dest, "package.json"), JSON.stringify({
    name: "node-pty",
    version: "1.1.0-android-stub",
    description: "Android stub for node-pty on ABIs without a prebuilt pty.node. Terminal features are unavailable; chat works normally.",
    main: "index.js",
    type: "commonjs",
  }, null, 2) + "\n");
  writeFileSync(join(dest, "index.js"), `"use strict";
// Android stub for node-pty on ABIs without a prebuilt native module.
function unavailable() {
  throw new Error("dsh-android: node-pty (terminal) is unavailable on this ABI");
}
module.exports = { spawn: unavailable, fork: unavailable, open: unavailable, createTerminal: unavailable, native: null };
`);
  log("info", `node-pty replaced with a stub (no prebuilt pty.node for ${process.arch})`);
}

function copyDir(src, dest) {
  for (const entry of readdirSync(src, { withFileTypes: true })) {
    const s = join(src, entry.name);
    const d = join(dest, entry.name);
    if (entry.isDirectory()) {
      mkdirSync(d, { recursive: true });
      copyDir(s, d);
    } else {
      copyFileSync(s, d);
    }
  }
}

function applyAndroidPatches() {
  log("info", "applying Android compatibility patches");
  writeSharpStub();
  writeSandboxWindowsAclStub();
  writeNodePtyPatch();
  writeFileSync(PATCH_MARKER, `patched ${new Date().toISOString()} dsh=${DSHD_VERSION}\n`);
  progress("installing", 95);
}

async function doInstall() {
  mkdirSync(FILES, { recursive: true });
  mkdirSync(NPM_CACHE, { recursive: true });
  mkdirSync(DSH_HOME, { recursive: true });
  await writeRuntimePackageJson(args.dshVersion ?? DSHD_VERSION);
  await npmInstall();
  if (existsSync(PATCH_MARKER)) {
    log("info", "patches already applied; verifying stub presence");
    writeSharpStub();
    writeSandboxWindowsAclStub();
    writeNodePtyPatch();
  } else {
    applyAndroidPatches();
  }
  const bin = join(NODE_MODULES, "@deepseek-ai", "dsh", "lib", "bin.js");
  if (!existsSync(bin)) {
    error("install finished but dsh bin.js is missing — unexpected package layout");
    process.exit(4);
  }
  progress("installing", 100);
  state({ state: "installed", dshVersion: DSHD_VERSION });
  log("info", "dsh runtime installed; ready to serve");
}

// ---------------------------------------------------------------------------
// serve
// ---------------------------------------------------------------------------
function dshEnv() {
  return {
    ...process.env,
    HOME: FILES,
    DSH_HOME,
    DSH_PERMISSION_MODE: "danger-full-access",
    DSH_TELEMETRY_MODE: process.env.DSH_TELEMETRY_MODE ?? "DISABLED",
    OPENSSL_CONF: process.env.OPENSSL_CONF ?? "/dev/null",
    // LD_LIBRARY_PATH is inherited from the app (points at nativeLibraryDir,
    // where the bundled shared libraries live).
    PATH: [
      "/system/bin",
      "/system/xbin",
      "/vendor/bin",
      "/data/local/bin",
    ].join(":"),
    npm_config_cache: NPM_CACHE,
  };
}

async function pickPort() {
  for (let port = 3080; port < 3100; port++) {
    if (!(await healthOk(port))) return port;
  }
  return 0; // let the OS assign
}

async function doServe() {
  if (!existsSync(join(NODE_MODULES, "@deepseek-ai", "dsh", "lib", "bin.js"))) {
    log("info", "runtime not installed yet; running install first");
    await doInstall();
  } else {
    // Re-apply compatibility patches on every serve: they are idempotent, and
    // this picks up stub/fork changes without a full reinstall.
    writeSharpStub();
    writeSandboxWindowsAclStub();
    writeNodePtyPatch();
  }
  const bin = join(NODE_MODULES, "@deepseek-ai", "dsh", "lib", "bin.js");
  const port = await pickPort();
  log("info", `starting dsh web on 127.0.0.1:${port}`);
  state({ state: "starting", port, url: `http://127.0.0.1:${port}` });

  // --expose-internals: the cordis HMR service (mounted by the base profile)
  // requires ctx.loader.internal, which is only available under this flag.
  const child = spawn(NODE_BIN, ["--expose-internals", bin, "web", "--host", "127.0.0.1", "--port", String(port)], {
    cwd: RUNTIME_DIR,
    env: dshEnv(),
    stdio: ["ignore", "pipe", "pipe"],
  });

  let announced = false;
  let bootLog = [];
  const onChunk = (chunk) => {
    const text = chunk.toString();
    appendLog("dsh: " + text.trimEnd());
    for (const line of text.split("\n")) {
      const t = line.trim();
      if (!t) continue;
      bootLog.push(t);
      if (bootLog.length > 200) bootLog.shift();
      const m = t.match(/http:\/\/127\.0\.0\.1:(\d+)/);
      if (m && !announced) {
        announced = true;
        const actualPort = Number(m[1]);
        state({ state: "running", port: actualPort, url: `http://127.0.0.1:${actualPort}`, pid: child.pid });
        log("info", `dsh web UI ready at http://127.0.0.1:${actualPort}`);
      }
    }
  };
  child.stdout.on("data", onChunk);
  child.stderr.on("data", onChunk);

  // Fallback readiness probe in case the port line is not printed in a parseable form.
  const probe = setInterval(async () => {
    if (announced) return;
    if (await healthOk(port)) {
      announced = true;
      state({ state: "running", port, url: `http://127.0.0.1:${port}`, pid: child.pid });
      log("info", `dsh web UI ready at http://127.0.0.1:${port}`);
    }
  }, 1500);

  const stop = () => {
    clearInterval(probe);
    child.kill("SIGTERM");
  };
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);

  child.on("error", (err) => {
    clearInterval(probe);
    error(`failed to start dsh: ${err.message}`);
    process.exit(5);
  });
  child.on("close", (code, signal) => {
    clearInterval(probe);
    const reason = signal ? `signal ${signal}` : `exit ${code}`;
    state({ state: "stopped", reason, tail: bootLog.slice(-20) });
    log("warn", `dsh process stopped (${reason})`);
    process.exit(code ?? 1);
  });

  // Readiness watchdog: if nothing is announced within 120s, report a boot failure.
  const watchdog = setTimeout(async () => {
    if (announced) return;
    clearInterval(probe);
    error(`dsh did not become ready within 120s. Last log:\n${bootLog.slice(-30).join("\n")}`);
    child.kill("SIGTERM");
  }, 120_000);
  watchdog.unref?.();
}

// ---------------------------------------------------------------------------
// entry
// ---------------------------------------------------------------------------
log("info", `bootstrap ${args.mode} (node ${process.version}, platform=${process.platform}, arch=${process.arch})`);
log("info", `env LD_LIBRARY_PATH=${process.env.LD_LIBRARY_PATH ?? "(unset)"} OPENSSL_CONF=${process.env.OPENSSL_CONF ?? "(unset)"} execPath=${process.execPath}`);
if (process.env.DSH_DEBUG_SPAWN === "1") {
  // Diagnostics: does a spawned child inherit LD_LIBRARY_PATH and link?
  const { spawnSync } = await import("node:child_process");
  const r = spawnSync(process.execPath, ["-e", "console.log('child env LD=' + (process.env.LD_LIBRARY_PATH || '(unset)'))"], { encoding: "utf8", env: process.env });
  log("info", `spawn-test status=${r.status} stdout=${String(r.stdout).trim()} stderr=${String(r.stderr).trim().slice(0, 200)}`);
}
if (args.mode === "install") {
  await doInstall();
} else {
  await doServe();
}
