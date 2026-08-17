# Dsh for Android

在安卓手机上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）的轻量应用。

应用内置 Node.js 运行环境；**第一次打开时自动执行 dsh 启动命令**（通过 npm 安装 `@deepseek-ai/dsh` 并启动其 `web` 服务），随后在内置 WebView 中自动进入 dsh 的完整网页界面。应用还提供 DeepSeek 官方 API 连接页与完整的应用内介绍。

## 需求对照

| 需求 | 实现 |
| --- | --- |
| 1. 简化包体，可运行 dsh 启动命令，第一次打开执行 `npx @deepseek-ai/dsh web` | APK 只内置 Node.js 运行时（release ≈ 42 MB）；dsh 及全部依赖在首次启动时通过 npm 安装到应用私有目录（等效于执行 npx 安装并运行 dsh 命令），不随 APK 分发 |
| 2. 包含 Web UI，启动 dsh 后自动进入 | dsh 服务启动后自动检测端口并加载内置 WebView |
| 3. 连接 API 界面提供 DeepSeek 官网 API 入口 | 「API 连接」页：API Key 输入 + 一键跳转 platform.deepseek.com/api_keys |
| 4. 界面美观、简洁 | Jetpack Compose + Material 3，三页式底栏（对话 / API 连接 / 帮助） |
| 5. 完整的应用内介绍 | 首次启动 4 页引导 + 「帮助」页完整说明、FAQ、运行日志 |

## 工作原理

```
┌────────────────────────── 应用（com.dsh.android） ──────────────────────────┐
│ Compose UI（对话 WebView / API 设置 / 帮助）                                 │
│ NodeManager ── 状态机、进程管理（StateFlow 驱动 UI）                          │
│ NodeService（前台服务，specialUse 类型，保持 dsh 存活）                       │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ spawn（LD_LIBRARY_PATH / HOME / DSH_HOME / DEEPSEEK_API_KEY …）
                                   ▼
  嵌入式 Node.js 24（Termux 构建；可执行文件 libnode.so 位于 nativeLibraryDir，
  运行 assets/bootstrap/bootstrap.js）
   ├─ install：npm install --ignore-scripts @deepseek-ai/dsh
   ├─         ：替换 sharp 与 dsh-sandbox-windows-acl 为安卓桩；
   │           arm64 上再把 node-pty 换成随包内置的安卓预编译分支（见下）
   └─ serve  ：node …/dsh/lib/bin.js web --host 127.0.0.1 --port 3080
               （写 dsh-status.json，NDJSON 事件流上报给 Kotlin 侧）
```

### 为什么需要桩（stub）与内置分支

dsh 的 web 配置加载了三个依赖原生二进制模块的包，它们**没有**官方的安卓构建：

| 包 | 原生依赖 | 安卓处理 |
| --- | --- | --- |
| `sharp`（图片附件处理） | libvips 二进制，无 Android 支持 | 安装后替换为桩：模块可加载，图片附件调用时抛出明确错误（纯文本对话不受影响） |
| `@deepseek-ai/dsh-sandbox-windows-acl`（Win32 沙箱） | koffi（Windows FFI） | 安装后替换为桩：该沙箱只在 Windows 上被选中，安卓上永不使用 |
| `node-pty`（伪终端） | 编译型 C++ 插件，加载期即 require 原生 | **arm64**：替换为随包内置的安卓 ARM64 预编译分支（`@mmmbuto/node-pty-android-arm64`）；**其它 ABI**（x86_64 模拟器等）：替换为桩（终端不可用，对话正常） |

安装时使用 `--ignore-scripts`，避免 koffi/node-pty 在设备上触发源码编译（设备无编译器）。dsh 以
`DSH_PERMISSION_MODE=danger-full-access` 运行（手机为个人设备，文件工具在无沙箱模式下直接可用）。

### 为什么 node 可执行文件放在 jniLibs

Android 10+ 的 SELinux W^X 策略禁止 `execve()` 应用数据目录（`getFilesDir()`，标签 `app_data_file`）下的
文件——`chmod +x` 无法绕过（会报 `error=13 Permission denied`）。因此 node 二进制以
`jniLibs/<abi>/libnode.so` 随 APK 分发，安装时由系统解压到 `nativeLibraryDir`（标签 `exec_type`，允许执行），
应用从该目录直接执行。构建配置 `jniLibs { useLegacyPackaging = true }` 强制安装时解压到磁盘。

### 运行时关键修复（真机 + 模拟器实测得出）

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| 共享库找不到 | `CANNOT LINK EXECUTABLE ...: library "libz.so.1" not found` | ① termux 库是完整版本名（`libz.so.1.3.2`），而 bionic 链接器按 **DT_NEEDED 精确文件名**查找 → `scripts/patch-elf-names.mjs` 原地改写 DT_NEEDED/SONAME 为纯 `.so` 名并重命名文件；② exec 出的子进程不在 app 的 linker namespace 里 → 显式设置 `LD_LIBRARY_PATH=nativeLibraryDir` |
| verneed 校验失败 | `cannot find "libcrypto.so" from verneed[0] in DT_NEEDED list` | bionic 用**已加载库的 SONAME** 校验 verneed：改名后必须同步改写每个库文件的 SONAME（补丁脚本对每个文件运行一遍） |
| OpenSSL 配置 | node 启动报 `BIO_new_file .../openssl.cnf: Permission denied` | termux 版 OpenSSL 默认配置路径不存在 → 设置 `OPENSSL_CONF=/dev/null` |
| aapt 丢弃下划线资产 | npm 报 `Cannot find module './__generated__/envelope'` | AAPT 打包 assets 时忽略 `_` 开头条目 → `aaptOptions { ignoreAssetsPattern = ... }` 覆盖默认忽略规则 |
| HMR 启动失败 | `--expose-internals is required for HMR service` | 启动 dsh 子进程时加 `--expose-internals` |
| npm 子进程环境 | npm 静默 exit 1 | bootstrap 里 `spawn` 的 `env:` 会**整体替换**环境 → 必须 `{ ...process.env, ... }` 合并 |
| 并发启动 | `EADDRINUSE 127.0.0.1:3080` | NodeManager 加互斥 + Service 仅在空闲态启动 |

## 构建 APK

前置：Windows 10+（本仓库已在 Windows 上验证）、网络。

```powershell
# 1) 拉取安卓 Node.js 运行时（自动完成 ELF 改名 + 依赖闭包校验）
#    - node 可执行文件 + 全部共享库（改名为纯 .so 名）→ app/src/main/jniLibs/<abi>/
#    - npm CLI 树 → app/src/main/assets/node/lib
powershell -ExecutionPolicy Bypass -File scripts\fetch-node-runtime.ps1 -Arch arm64

# 2) 安装 Android SDK 与 JDK 21（首次，下载约 400 MB，装到项目内 .android-sdk / .tools）
powershell -ExecutionPolicy Bypass -File scripts\setup-android-sdk.ps1

# 3) 构建
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk（release 版用 assembleRelease，约 40 MB）
```

> 手动方式：安装 Android SDK（platform 35 + build-tools 35.0.0）与 JDK 17+，
> 在项目根创建 `local.properties`：`sdk.dir=D:\\path\\to\\android-sdk`，
> 再执行 `gradlew assembleDebug`。
> 若本机 JDK 高于 21 导致 Gradle 报错，`setup-android-sdk.ps1` 会自动下载并使用 JDK 21。

### 支持的 ABI

- `arm64-v8a`：真机（绝大多数安卓手机）。Node 运行时脚本用 `-Arch arm64` 获取。
- `x86_64`：模拟器。改用 `-Arch x64` 重新拉取运行时即可（x86_64 上 node-pty 无预编译，替换为桩，终端功能不可用，对话正常）。

## 测试环境（本仓库已在真机 + 模拟器上跑通）

**真机**：`adb install -r app-release.apk`，首次启动会自动完成「解压 Node → npm 安装 dsh → 启动服务 → 载入 Web UI」。

**本地模拟器（Windows）**：
```powershell
# 一次性：管理员 PowerShell 启用 WHPX 并重启
Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All   # 然后重启

# 之后（本仓库 .android-sdk 已装好 SDK；如未装先跑 scripts\setup-android-sdk.ps1）
sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n dsh-test -k "system-images;android-35;google_apis;x86_64" -d pixel_5 --force
emulator -avd dsh-test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot

# 用 x86_64 运行时重建 + 安装 + 启动
powershell -ExecutionPolicy Bypass -File scripts\fetch-node-runtime.ps1 -Arch x64
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.dsh.android/.MainActivity
```

验证点：`adb logcat -s NodeManager:*` 看到 `dsh web UI ready at http://127.0.0.1:3080`；
`adb forward tcp:13080 tcp:3080` 后从主机访问 `http://127.0.0.1:13080/` 应返回 200 与 `__DSH_BOOT__` 页面；
`adb shell run-as com.dsh.android cat files/dsh-status.json` 应显示 `"state":"running"`。

## 首次使用（用户视角）

1. 打开应用 → 4 页引导（欢迎 / 内置运行环境 / 连接 API / 开始使用）。
2. 点击「开始使用」→ 自动安装 dsh 运行环境（需联网，约 50–150 MB，界面显示进度）。
3. 进入「API 连接」→ 粘贴 DeepSeek API Key（可点击「获取 API Key」直达开放平台）→ 保存并重启服务。
4. 回到「对话」→ dsh 网页界面自动加载，开始对话。

## 数据与隐私

- 全部数据位于应用私有目录（`files/`）：`dsh-runtime/`（dsh 安装）、`.dsh/`（DSH_HOME 配置与会话）、
  `dsh-status.json`、`logs/dsh.log`。
- API Key 仅保存在本机（DataStore + 注入 `DEEPSEEK_API_KEY`），不上传第三方。
- 遥测默认关闭（`DSH_TELEMETRY_MODE=DISABLED`）。

## 已知限制

- 图片附件处理不可用（sharp 无安卓构建）。
- 需要 bash 的终端类工具不可用（安卓无 bash）；文件类工具在 `danger-full-access` 模式下正常。
- x86_64 模拟器上 node-pty 被桩替换（终端不可用），arm64 真机上使用内置预编译分支。
- 首次安装需要网络；安装完成后可离线启动。
- 若 dsh 发布新版，可在 `app/src/main/assets/bootstrap/bootstrap.js` 顶部调整 `DSH_ANDROID_DSH_VERSION`。

## 目录结构

```
app/src/main/
├── assets/
│   ├── node/              ← scripts/fetch-node-runtime.ps1 生成（npm CLI 树）
│   └── bootstrap/
│       ├── bootstrap.js   ← 设备端引导：npm 安装 + 桩补丁 + 启动 + 状态上报
│       └── node-pty-android/ ← 安卓 ARM64 node-pty 预编译分支
├── jniLibs/<abi>/         ← node 可执行文件（libnode.so）+ 全部共享库（已改名 .so）
├── java/com/dsh/android/
│   ├── MainActivity.kt    ← 单 Activity + Compose 导航（引导 / 主页）
│   ├── DshApplication.kt  ← 通知渠道
│   ├── data/SettingsStore.kt
│   ├── node/NodeManager.kt ← 运行时解压 / 进程 / NDJSON 状态机
│   ├── node/NodeService.kt ← 前台服务
│   └── ui/                ← Onboarding / Home / ChatWebView / API / Help
└── res/                   ← 主题、图标、网络安全配置（仅允许 127.0.0.1 明文）
scripts/
├── fetch-node-runtime.ps1  ← 从 Termux 仓库拉取并裁剪 Node 运行时
├── patch-elf-names.mjs     ← 原地改写 ELF DT_NEEDED/SONAME 为纯 .so 名
├── check-elf-deps.mjs      ← ELF 依赖闭包校验
└── setup-android-sdk.ps1   ← 一键安装 SDK/JDK 到项目内
```
