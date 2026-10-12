# AutoGLM-For-Android 导入 / 编译 / 安装 Runbook

> 配套文档：[UI改版说明.md](UI改版说明.md)（改版内容与文件清单）
> 本文只讲「怎么把改好的源码跑到手机上」，不涉及任何 Agent 逻辑改动。

---

## 0. 你的机器现状体检（实测，不是模板）

我在这台机器上实际检查过，结果如下 —— 这决定了你**必须从第 1 步开始**，不能直接跳到编译：

| 检查项 | 实测结果 | 结论 |
|---|---|---|
| `JAVA_HOME` | `C:\Program Files\Java\jdk-17` | ✅ 已就绪 |
| `javac`（是否真 JDK 而非 JRE） | `C:\Program Files\Common Files\Oracle\Java\javapath\javac.exe` | ✅ 有编译器 |
| Android Studio | `C:\Program Files\Android\Android Studio` → **不存在** | ❌ **必须先安装** |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` → **不存在** | ❌ **必须先安装** |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | 均为空 | ❌ 命令行编译会失败 |
| `adb` | 不在 PATH 中 | ❌ 需配置 |
| `local.properties` | 项目里**没有** | ❌ 命令行编译会报 `SDK location not found` |
| Gradle Wrapper | `gradle-8.13-bin.zip`，`networkTimeout=120000`，`distributionUrl` 已指向腾讯云镜像 | ✅ 已按 2.7 节改好（无需再改） |
| `release.keystore` | 不存在 | ℹ️ Release 会自动退化为 debug 签名（不影响 Debug 包） |
| `dev_profiles.json` | 不存在（只有 `dev_profiles_demo.json`） | ℹ️ 构建脚本会打印 "not found, skipping"，**不会失败** |
| 依赖仓库（`settings.gradle.kts`） | `google()` + `mavenCentral()` + `jitpack.io` | ℹ️ Markwon 来自 Maven Central，Sherpa-ONNX 来自 JitPack |

**改版规模**（供你核对）：已跟踪文件改动 13 个，新增文件 19 个（789 行），**合计 32 个文件**。
（另外 `gradle/wrapper/gradle-wrapper.properties` 也按本文档第 2.6/2.7 节的建议改过，属于环境配置而非 UI 改版。）


---

## 1. 前置准备清单

### 1.1 Android Studio（版本要求很关键）

- 下载：<https://developer.android.com/studio> （选 Windows 64-bit 安装包）
- **版本要求：Narwhal（2025.1.1）或更新。**
  本工程用 **AGP 8.13.2**，旧版 Studio 会直接提示
  `The project is using an incompatible version of the Android Gradle Plugin` 并拒绝同步。
- 安装时勾选：`Android Studio` + `Android Virtual Device`（可选，有真机就不需要模拟器）。

### 1.2 JDK 17

- 你机器上**已经有了**：`C:\Program Files\Java\jdk-17`
- 验证命令（在 PowerShell 里跑）：

```powershell
java -version      # 期望看到 17.x
javac -version     # 期望看到 17.x（有 javac 说明是完整 JDK）
$env:JAVA_HOME     # 期望看到 C:\Program Files\Java\jdk-17
```

- Android Studio 内部用的 JDK 在 `File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 里选：
  - 想省事：选 **Embedded JDK (JetBrains Runtime 21)**
  - 想和命令行完全一致：选 **`C:\Program Files\Java\jdk-17`**（推荐，避免两套 JDK 造成行为差异）

### 1.3 Android SDK（Studio 会帮你装，但要确认装对）

需要的组件：

| 组件 | 版本 | 说明 |
|---|---|---|
| SDK Platform | **Android 15 (API 35)** | `compileSdk = 35` 必需 |
| SDK Platform | Android 14 (API 34) | `targetSdk = 34`（一般随 35 一起装） |
| SDK Build-Tools | 35.x | 默认最新即可 |
| SDK Platform-Tools | 最新 | **adb 就在这里** |
| SDK Command-line Tools | 最新 | 命令行接受许可、装组件用 |

安装方式：`Tools → SDK Manager`，勾选上表 → Apply。
默认安装路径：`C:\Users\17357\AppData\Local\Android\Sdk`

### 1.4 adb 环境配置 + 检查方法

装完 SDK 后，adb 在：

```
C:\Users\17357\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

**方式 A：临时用绝对路径（不推荐长期用）**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
```

**方式 B：加入 PATH（推荐，一次配好永久生效）**

```powershell
# 当前窗口临时生效
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb version

# 永久生效（写入用户环境变量，之后重开终端即可）
[Environment]::SetEnvironmentVariable(
  "Path",
  [Environment]::GetEnvironmentVariable("Path","User") + ";$env:LOCALAPPDATA\Android\Sdk\platform-tools",
  "User"
)
```

**方式 C：直接用 Android Studio 底部的 Terminal**（它自带 SDK 环境，最省事）。

**检查清单**（三条命令都通过才算环境 OK）：

```powershell
adb version                 # 期望：Android Debug Bridge version 1.0.41 ...
adb devices                 # 期望：插上手机后能列出序列号 + device
where.exe java              # 期望：能找到 java.exe
```

> 若 `adb devices` 显示 `unauthorized`，去手机屏幕上点「允许 USB 调试」。
> 若显示 `offline`，执行 `adb kill-server; adb start-server`。

### 1.5 其他

- 磁盘空间：首次同步 + 编译建议预留 **15 GB**（Gradle 发行包 ~130MB、依赖缓存、构建产物、SDK）。
- 网络：首次要下载 Gradle 8.13 + AGP + Markwon 等，国内建议直接配上第 2.4 节的镜像。
- 手机：Android 7.0 以上，一根**能传数据**的 USB 线（很多充电线只有电源线芯）。

---

## 2. Android Studio 图形界面导入 + 同步失败全套解决方案

### 2.1 导入步骤

1. 启动 Android Studio → 欢迎页点 **Open**（若已打开别的项目：`File → Open`）。
2. 在文件选择框里定位到：
   ```
   C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android
   ```
   ⚠️ **一定要选这一层**（里面能看到 `settings.gradle.kts`、`app`、`gradle`）。
   不要选 `app` 子目录，否则 Studio 会把它当成独立项目，Navigation / 依赖全部报错。
3. 弹窗 `Trust Project?` → 点 **Trust Project**。
4. 右下角出现 `Gradle Sync` 进度条，耐心等（首次 5～20 分钟，取决于网络）。
5. 同步成功后左侧应出现 `app/src/main/java/com/kevinluo/autoglm/...` 完整目录树。

### 2.2 首次同步会被自动装/下载的东西

- Gradle 8.13 发行包（~130 MB）
- 项目已有依赖（Shizuku、OkHttp、Retrofit、Sherpa-ONNX…）
- **本次改版新增的 6 个依赖**：

```
androidx.recyclerview:recyclerview:1.3.2
androidx.drawerlayout:drawerlayout:1.2.0
io.noties.markwon:core:4.6.2
io.noties.markwon:linkify:4.6.2
io.noties.markwon:ext-tables:4.6.2
io.noties.markwon:ext-strikethrough:4.6.2
```

- 若缺 SDK，Studio 会弹 `Install missing SDK package`，点 **Install**（会自动接受许可）。

### 2.3 同步失败的定位方法（先看这里，别乱改代码）

打开 `Build` 面板（左下角 Build 标签），**从最上面第一条红色错误看起**，而不是最后一行。

| 报错关键字 | 原因 | 解决 |
|---|---|---|
| `SDK location not found` | 没有 SDK 路径 | 2.5 节 |
| `Could not resolve io.noties.markwon:core:4.6.2` | Maven Central 访问不了 | 2.4 节镜像 |
| `Could not resolve com.github.k2-fsa:sherpa-onnx` | JitPack 访问不了 | 2.4 节镜像（含 jitpack） |
| `Connection timed out` / `Read timed out` | 网络慢 | 2.4 + 2.6 节 |
| `incompatible version of the Android Gradle Plugin` | Studio 太旧 | 升级 Studio 到 Narwhal+ |
| `Unsupported class file major version 65` | JDK 不对 | Gradle JDK 改成 17 |
| `Failed to install the following Android SDK packages ... licenses` | 许可未接受 | 2.5 节 |
| `Could not download gradle-8.13-bin.zip` | Gradle 发行包下载失败 | 2.7 节 |
| `OutOfMemoryError` / `Java heap space` | 内存不够 | 2.8 节 |

### 2.4 国内镜像配置（解决 90% 的同步失败）

本工程用 `dependencyResolutionManagement` + `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，
所以**镜像必须加在 `settings.gradle.kts` 里**，加到 `app/build.gradle.kts` 是无效的。

打开 `settings.gradle.kts`，把内容替换为下面这份（只加了镜像，其余原样）：

```kotlin
pluginManagement {
    repositories {
        // ↓↓↓ 国内镜像放最前面，优先命中 ↓↓↓
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // ↑↑↑ 镜像 ↑↑↑

        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ↓↓↓ 国内镜像放最前面 ↓↓↓
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // ↑↑↑ 镜像 ↑↑↑

        google()
        mavenCentral()
        // JitPack 没有国内镜像，Sherpa-ONNX 只能走它；如失败见 2.6 节代理方案
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AutoGLM for Android"
include(":app")
```

> 腾讯云镜像 `https://mirrors.cloud.tencent.com/nexus/repository/maven-public/` 也可以整体替代。

改完点右上角 **Sync Now**（或大象图标 `Sync Project with Gradle Files`）。

### 2.5 SDK 路径与许可

同步后若提示 `SDK location not found`：在项目根目录**新建** `local.properties`（此文件不要提交到 git）：

```properties
sdk.dir=C\:\\Users\\17357\\AppData\\Local\\Android\\Sdk
```

> 注意：Windows 路径在 properties 文件里，`:` 和 `\` 都要转义。

接受许可（命令行工具所在目录）：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
# 一路输入 y 回车

# 缺 SDK Platform 35 时手动装：
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

### 2.6 网络超时 / 需要代理

如果你本地有代理（如 Clash 7890 端口），在项目根目录 `gradle.properties` **末尾追加**：

```properties
# 走本地代理下载依赖
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
# 不要对 localhost 走代理
systemProp.http.nonProxyHosts=localhost|127.0.0.1
```

同时把 Gradle Wrapper 的超时从 10 秒放宽（`gradle/wrapper/gradle-wrapper.properties`）：

```properties
networkTimeout=120000
```

### 2.7 Gradle 发行包（gradle-8.13-bin.zip）下载失败

**方案 A：换镜像下载地址** —— 编辑 `gradle/wrapper/gradle-wrapper.properties`：

```properties
# 腾讯云镜像
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip
```

**方案 B：手动放置** —— 下载 `gradle-8.13-bin.zip` 后放到：

```
C:\Users\17357\.gradle\wrapper\dists\gradle-8.13-bin\<随机串>\
```

（`<随机串>` 目录在你第一次同步失败后会生成，把 zip 放进去后重新 Sync，Gradle 会自动解压。）

### 2.8 内存不足

`gradle.properties` 里原工程写的是 `-Xmx2048m`。AGP 8.13 + Kotlin 2.1 建议加到 4G：

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### 2.9 还是同步不过？按顺序做这三件事

1. `File → Invalidate Caches... → Invalidate and Restart`
2. 关闭 Studio，删除项目下的 `.gradle`、`.idea` 和 `app/build`，重新打开
3. 删除全局缓存重下：删 `C:\Users\17357\.gradle\caches\modules-2` 目录（会重新下载依赖，慢但干净）

---

## 3. Windows 终端执行 `gradlew.bat assembleDebug`

### 3.1 前置：让 gradlew 找得到 SDK（最容易踩的坑）

项目里目前**没有** `local.properties`。命令行编译必须满足其一：

**做法 1（推荐）**：先在 Android Studio 里同步一次（它会自动生成 `local.properties`），再回命令行。

**做法 2**：手动在当前 PowerShell 窗口设置环境变量：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

否则会报：

```
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
variable or by setting the sdk.dir path in your project's local.properties file
```

### 3.2 完整操作步骤

```powershell
# ① 进入项目根目录（有 gradlew.bat 的那一层）
cd C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android

# ② 先确认 Gradle 能起来（这步会下载 Gradle 8.13，首次比较久）
.\gradlew.bat --version

# ③ 确认 SDK 路径被识别（应输出 SDK 目录，而不是报错）
.\gradlew.bat -q androidSdkDir 2>$null ; if (-not $?) { Write-Host "（该任务不存在是正常的，直接看 ④）" }

# ④ 正式编译 Debug 包
.\gradlew.bat assembleDebug

# ⑤ 如果失败，带上详细日志重跑，方便定位
.\gradlew.bat assembleDebug --stacktrace --info
```

> PowerShell 里也可以写 `./gradlew.bat assembleDebug`；用 `.\` 更保险（避免被当成命令名解析）。
> 若提示“在此系统上禁止运行脚本”，用 `cmd /c gradlew.bat assembleDebug` 绕过。

**首次编译耗时会很长**（10～30 分钟）：要下载 Gradle、AGP、Kotlin 编译器、Markwon、Shizuku、Sherpa-ONNX 等。

### 3.3 产物位置

```
app\build\outputs\apk\debug\AutoGLM-0.0.6-debug.apk
```

确认命令：

```powershell
Get-ChildItem .\app\build\outputs\apk\debug\ | Select-Object Name, Length, LastWriteTime
```

> 文件名是原工程的 `outputFileName = "AutoGLM-${versionName}-${buildType}.apk"` 规则生成的。
> 如果只看到 `app-debug.apk`，说明构建脚本的自定义命名没生效（不影响使用，直接装那个也行）。

### 3.4 常用参数

| 参数 | 用途 |
|---|---|
| `--stacktrace` | 出错时打印完整调用栈，定位问题必备 |
| `--info` | 详细日志（看卡在哪一步） |
| `--offline` | 断网时用本地缓存编译 |
| `--no-daemon` | 不走常驻进程，排除守护进程状态污染 |
| `clean assembleDebug` | 先清理再编译（怀疑缓存问题时用） |
| `-x lint` | 跳过 lint 检查（lint 报错但代码没问题时救急） |

### 3.5 编译报错排查表

| 报错 | 原因 | 处理 |
|---|---|---|
| `SDK location not found` | 没 local.properties / ANDROID_HOME | 见 3.1 |
| `Failed to install the following SDK components ... licenses not accepted` | 许可未接受 | 见 2.5 |
| `Unsupported class file major version 65/66` | JDK 版本不对 | 设 `JAVA_HOME=C:\Program Files\Java\jdk-17` 后重开终端 |
| `Could not resolve io.noties.markwon:...` | 依赖没下下来 | 见 2.4；或 `--offline` 排除网络 |
| `configureTheme overrides nothing` / `Unresolved reference: codeBlockBackgroundColor` | **Markwon 版本被换过** | 见 3.6（本次重点） |
| `Duplicate resources` / `Duplicate class R` | 资源名冲突 | 检查是否手工改过 `strings.xml`（本次新增资源都在 `strings_ui.xml`） |
| `Unresolved reference: R` | 资源编译失败的前置错误 | 往上找第一条红色错误，通常才是真因 |
| `Java heap space` | 内存不足 | 见 2.8 |
| `Execution failed for task ':app:processDebugResources'` | 资源/aapt2 问题 | 看该任务上面的 aapt2 具体报错行 |
| `> Task :app:compileDebugKotlin FAILED` | Kotlin 编译错误 | 报错里会给出 `文件:行号`，直接跳过去看 |

### 3.6 ⚠️ 重点：Markwon 4.6.2 与 `configureTheme` 代码块

改版代码里，AI 气泡的 Markdown 渲染在：

```
app\src\main\java\com\kevinluo\autoglm\ui\ChatMessageAdapter.kt
```

其中**只有 `.usePlugin(object : AbstractMarkwonPlugin() { override fun configureTheme(...) })` 这一段**
依赖 Markwon 4.6.2 的具体方法名：

```kotlin
.codeTextColor(codeText)
.codeBackgroundColor(codeBackground)
.codeBlockTextColor(codeText)
.codeBlockBackgroundColor(codeBackground)
.codeTypeface(Typeface.MONOSPACE)
.linkColor(linkColor)
```

**只要你不改 `libs.versions.toml` 里的 `markwon = "4.6.2"`，这段就是安全的。**

如果将来升级/降级 Markwon，出现下面这类报错：

- `'configureTheme' overrides nothing`
- `Unresolved reference: codeBlockBackgroundColor`
- `Unresolved reference: MarkwonTheme`

**处理办法：把这一个 `.usePlugin(...)` 块整段删掉即可**，其余 Markdown 功能（加粗、列表、链接、表格、删除线）完全不受影响 —— 只是代码块不再用自定义配色，会用 Markwon 默认样式。

删除范围（保留前后）示意：

```kotlin
    private val markwon: Markwon =
        Markwon
            .builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            // ↓↓↓ 只删下面这一整块（从 .usePlugin( 到它对应的 ), ）↓↓↓
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        ...
                    }
                },
            )
            // ↑↑↑ 只删上面这一整块 ↑↑↑
            .build()
```

同时可以把文件顶部这两个 import 一并删掉（否则只是“未使用导入”警告，不影响编译）：
`import io.noties.markwon.AbstractMarkwonPlugin`、`import io.noties.markwon.core.MarkwonTheme`
（`import android.graphics.Typeface` 若也不再用到，同样可删。）

代码里这一段上方已经写了中文注释标注同一件事。

---

## 4. adb 安装

### 4.1 手机端准备

1. 设置 → 关于手机 → 连续点「版本号/OS 版本」7 次 → 开启开发者模式。
2. 设置 → 更多设置 → 开发者选项 → 打开 **USB 调试**。
3. **小米/Redmi 额外两步**（否则装不上）：
   - 开发者选项里打开 **「USB 安装」**
   - 打开 **「USB 调试（安全设置）」**（需登录小米账号并插卡，部分机型还要求账号在线 7 天）
4. 插上数据线，USB 用途选 **「传输文件 / MTP」**（不要选「仅充电」）。
5. 手机弹出「允许 USB 调试吗？」→ 勾选「一律允许」→ 确定。

### 4.2 检查连接

```powershell
adb kill-server
adb start-server
adb devices
```

期望输出：

```
List of devices attached
ABCD1234        device
```

### 4.3 安装命令

```powershell
cd C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android

# 方式一：相对路径
adb install -r .\app\build\outputs\apk\debug\AutoGLM-0.0.6-debug.apk

# 方式二：绝对路径（推荐，避免路径拼错）
adb install -r "C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android\app\build\outputs\apk\debug\AutoGLM-0.0.6-debug.apk"
```

- `-r` = replace，覆盖安装并**保留数据**（同签名才行）。
- 成功输出：`Success`
- 想顺便启动：`adb shell am start -n com.kevinluo.autoglm/.MainActivity`

### 4.4 找不到设备

| 现象 | 处理 |
|---|---|
| `adb devices` 列表为空 | ① 换一根**能传数据**的线，换 USB 口（优先主板后置口/直连，别用扩展坞）<br>② 确认 USB 用途是「传输文件」<br>③ 重新打开 USB 调试开关<br>④ `adb kill-server` 再 `adb devices` |
| 显示出设备但状态 `unauthorized` | 手机屏幕上点「允许 USB 调试」；若没弹窗：开发者选项 → 「撤销 USB 调试授权」→ 拔插重来 |
| 状态 `offline` | `adb kill-server; adb start-server`；重启手机 |
| 设备管理器里有黄色感叹号 | 装厂商 USB 驱动（小米：MiFlash/小米助手；或装 Google USB Driver） |
| 无线调试（不想插线） | 开发者选项 → 无线调试 → 使用配对码配对：<br>`adb pair 手机IP:配对端口` → 输入 6 位配对码<br>`adb connect 手机IP:调试端口` |
| `adb` 不是内部或外部命令 | 见 1.4，把 `platform-tools` 加进 PATH |

### 4.5 安装失败报错表

| 报错 | 原因 | 解决 |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`<br>`signatures do not match` | 手机上已有同包名但**签名不同**的包（例如你原来装的是官方 release 签名包，现在装的是 debug 签名包） | `adb uninstall com.kevinluo.autoglm` 后重装（⚠️ 会清掉 App 内的设置/API Key，先记下来） |
| `INSTALL_FAILED_USER_RESTRICTED` | MIUI 禁止 USB 安装 | 开发者选项打开「USB 安装」 |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | 系统安装校验拦截 | 关闭「MIUI 优化」或安装时点「仍然安装」 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 手机空间不足 | 清理空间（APK + 语音模型占地方） |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK 未签名/损坏 | 重新 `assembleDebug`，确认 APK 完整 |
| `INSTALL_FAILED_OLDER_SDK` | 手机系统低于 minSdk 24 | 需 Android 7.0 以上 |
| `error: no devices/emulators found` | 设备没连上 | 见 4.4 |
| `Failed to install ... INSTALL_CANCELED_BY_USER` | 手机上点了取消 | 重新执行并在手机上点「继续安装」 |

### 4.6 另一种装法：Android Studio 直接 Run

选中设备 → 点绿色 ▶ `Run 'app'`。它会自动编译 + 安装 + 启动，调试日志更直观。
（但产物仍会生成在 `app/build/outputs/apk/debug/`。）

---

## 5. 安装完成后手机端配置

> 这三步和 UI 改版无关，是 AutoGLM 一直以来的必要授权；顺序建议照下面来。

### 5.1 Shizuku（负责模拟点击/滑动）

1. 手机上装 Shizuku：<https://github.com/RikkaApps/Shizuku/releases>
2. **用无线调试激活（不需要电脑）**：
   - 设置 → 更多设置 → 开发者选项 → 打开「**无线调试**」
   - 点「使用配对码配对设备」，记下 6 位配对码（弹窗别关）
   - 打开 Shizuku → 「通过无线调试启动」→ 输入配对码
   - 回到 Shizuku 点「启动」，显示「**正在运行**」即成功
   - 手机重启后需重新配对一次（正常现象）
3. 打开 AutoGLM → 会自动请求 Shizuku 权限 → 点「允许」
4. 新 UI 里怎么看是否连上：
   - **顶部状态条**会显示 `已连接 · 空闲`
   - 或点左上角 ☰ 打开 **Agent 面板**，看「任务状态」圆点

### 5.2 悬浮窗权限（显示任务进度小窗）

- App 内点「悬浮窗」按钮（对话页右上角 ▤ 或侧栏底部按钮）时会引导授权；
- 或手动：设置 → 应用 → AutoGLM → 权限 → 打开「**显示在其他应用上层**」；
- 授权后，侧边面板和对话页的悬浮窗按钮会变为可用。

### 5.3 键盘权限（用于自动输入文字）

- 设置 → 语言和输入法 → 管理键盘 → 打开 **AutoGLM Keyboard**
- 或 App 内点「启用键盘」跳转
- 兜底：也可安装 ADB Keyboard 并启用（原工程支持）

### 5.4 电池优化（可选但强烈建议）

设置 → 应用 → AutoGLM → 电池 → 选「**无限制 / 不优化**」，
否则息屏后 Agent 容易被系统杀掉。

### 5.5 模型配置（关键）

App 内：**设置** Tab → 模型配置，填：

| 配置项 | 填写内容 |
|---|---|
| Base URL | `https://open.bigmodel.cn/api/paas/v4` |
| Model | `autoglm-phone` |
| API Key | 智谱开放平台 `https://bigmodel.cn` → API Keys 里创建的那串 |

填完点「测试连接」，成功后再回对话页。

### 5.6 验证新 UI 是否生效（自检清单）

1. 进入 App → 顶部应显示 **AutoGLM Agent**，左上角有三横线图标，右上角有 🌙；
2. 对话页应有一条 **AI 欢迎气泡**（含加粗标题 + 列表 + 引用），说明 Markdown 渲染正常；
3. 点 ☰ → 左侧滑出 **Agent 面板**（任务状态 / 屏幕识别 / 当前动作 / 执行日志）；
4. 点 🌙 → 全局立刻切深色（气泡、输入框、面板一起变），再点一下切回浅色；
5. 底部输入框输入「打开微信，给文件传输助手发消息：测试」→ 点圆形 ➤ 按钮；
   - 补充：任务启动后 App 会自动最小化，进度看悬浮窗或侧边面板（这是原逻辑，未改）。

---

## 6. 回滚方案

> ⚠️ 三条铁律：**先备份 → 先预演（dry-run）→ 再执行**。
> 下面所有命令都请在项目根目录执行，并且先确认你确实要丢弃改版代码。

### 6.1 先做一次安全备份（强烈建议，1 分钟）

```powershell
cd C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android

# ① 把改版导出成补丁（万一以后想恢复，一条命令就能打回来）
git diff > ..\ui-redesign-tracked.patch

# ② 把新增的 19 个文件整体复制到备份目录
$bak = "..\autoglm-ui-backup-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Path $bak | Out-Null
Copy-Item app\src\main\res\values\strings_ui.xml $bak -Force
Copy-Item app\src\main\res\values-night\colors.xml $bak -Force
Copy-Item -Recurse app\src\main\res\values-night-v27 $bak -Force
Copy-Item app\src\main\res\layout\item_message.xml $bak -Force
Copy-Item -Recurse app\src\main\res\drawable\bg_*.xml $bak -Force
Copy-Item app\src\main\res\drawable\ic_menu.xml,app\src\main\res\drawable\ic_send.xml,app\src\main\res\drawable\ic_dark_mode.xml,app\src\main\res\drawable\ic_light_mode.xml $bak -Force
Copy-Item app\src\main\java\com\kevinluo\autoglm\ui\ChatMessage.kt $bak -Force
Copy-Item app\src\main\java\com\kevinluo\autoglm\ui\ChatMessageAdapter.kt $bak -Force
Copy-Item app\src\main\java\com\kevinluo\autoglm\ui\ChatSessionStore.kt $bak -Force
Copy-Item app\src\main\java\com\kevinluo\autoglm\ui\ThemeController.kt $bak -Force
Copy-Item UI改版说明.md $bak -Force
Write-Host "备份完成 -> $bak"
```

### 6.2 方案 A：Git 一键还原（最快）

```powershell
cd C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android

# ① 预演：先看会被删掉哪些未跟踪文件（这一步不会真的删！）
git clean -nd app/src/main

# ② 还原 11 个已跟踪文件的改动
git checkout -- app gradle

# ③ 删除 19 个新增文件（确认 ① 的列表没问题后再执行）
git clean -fd app/src/main

# ④ 顺手删掉说明文档（不想删就跳过）
Remove-Item UI改版说明.md -ErrorAction SilentlyContinue
```

还原后验证：

```powershell
git status --short        # 期望：无输出（工作区干净）
```

> `git clean -fd` **不可恢复**，务必先跑 `git clean -nd` 看清单。

### 6.3 方案 B：用补丁反向还原

```powershell
# 还原已跟踪文件
git apply -R ..\ui-redesign-tracked.patch

# 新增文件仍需手动删除（git apply -R 对未跟踪文件无能为力）
git clean -nd app/src/main   # 先看
git clean -fd app/src/main   # 再删
```

### 6.4 方案 C：重新 clone 一份纯净仓库（最干净、最推荐）

```powershell
cd C:\Users\17357\Documents\deepseek-harness\default-workspace

# 注意：必须带 -c http.sslBackend=openssl，否则本机会报 schannel 凭证错误
git -c http.sslBackend=openssl clone --depth 1 https://github.com/Luokavin/AutoGLM-For-Android.git AutoGLM-original

cd AutoGLM-original
git log --oneline -1     # 确认是最新 main
```

这样你同时拥有：`AutoGLM-For-Android`（改版版）和 `AutoGLM-original`（原版），
想用哪个用哪个，互不干扰。

### 6.5 方案 D：手机上直接退回官方 APK（不碰代码）

```powershell
# 注意：官方 release 包与本地 debug 包签名不同，覆盖安装会失败，通常需要先卸载
adb uninstall com.kevinluo.autoglm
adb install -r "C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-0.0.6.apk"
```

> ⚠️ `adb uninstall` 会清空 App 数据：**Shizuku 授权、悬浮窗授权、API Key、任务模板全部要重新配**。
> 建议先记下 API Key 和模型配置再卸载。

### 6.6 只想临时“关掉”新 UI 而不回滚代码

| 想做的事 | 做法 |
|---|---|
| 只想回到深色观感 | 点顶部 🌙 切深色即可（配色会回到接近原版的深色） |
| 不想看到侧边面板 | 不点 ☰ 就行，面板默认是收起的，不占地方 |
| 想让对话列表不可用 | 不建议修改；会破坏 UI 一致性且无意义 |

---

## 7. 全流程一句话串起来

```powershell
# 1) 装 Android Studio (Narwhal+) → Open 项目 → 选 jdk-17 → Sync
# 2) 命令行编译
cd C:\Users\17357\Documents\deepseek-harness\default-workspace\AutoGLM-For-Android
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
# 3) 安装
adb install -r .\app\build\outputs\apk\debug\AutoGLM-0.0.6-debug.apk
# 4) 手机上：启动 Shizuku(无线调试配对) → 授悬浮窗 → 启键盘 → 填模型配置 → 测连接
```
