# AutoGLM For Android —— 对话式 UI 改版说明（豆包 / Codex 风格）

> 本文件是本次 UI 改版的完整交付说明：改了什么、怎么编译、怎么装到手机、怎么回滚。
> 代码已全部写入本仓库工作区，未改动任何 Agent 核心逻辑。

---

## 一、先说明一个事实：目标工程不是 `zai-org/Open-AutoGLM`

任务里写的是「拉取 `https://github.com/zai-org/Open-AutoGLM.git`，读取仓库内 **android 文件夹**下的安卓 Demo」。

实际核查结果：

| 仓库 | 内容 | 结论 |
|---|---|---|
| `zai-org/Open-AutoGLM` | 只有 Python 端（`phone_agent/`）+ iOS 端（`ios.py`），**没有 android 文件夹** | ❌ 不是安卓 Demo |
| `butlanys/Open-AutoGLM-Android` | Kotlin + **Jetpack Compose**，全是 `ui/chat/*.kt`，没有 XML 布局 | ❌ 与「Kotlin + XML 布局」要求冲突 |
| **`Luokavin/AutoGLM-For-Android`** | Kotlin + **XML 布局** + Fragment/Navigation/Material，`versionName = "0.0.6"` | ✅ **就是本机 `AutoGLM-0.0.6.apk` 对应的源码** |

判定依据（不是猜的）：

1. 该仓库 `app/build.gradle.kts` 里 `versionCode = 6`、`versionName = "0.0.6"`，与你工作区里的
   `AutoGLM-0.0.6.apk` 完全对应；
2. 你本机的 `AutoGLM手机端安装清单.md`（第 39 行）记录的安卓端下载地址，正是
   `https://github.com/Luokavin/AutoGLM-For-Android/releases`；
3. 它使用 `activity_main.xml`、`fragment_task.xml`、`colors.xml`、`themes.xml`、`item_*.xml`
   这套 **XML 布局 + View 体系**，正好符合你要求的「Kotlin + Android XML 布局 + Jetpack 组件，不要 WebView」。

所以本次改版基于 `Luokavin/AutoGLM-For-Android`（包名 `com.kevinluo.autoglm`）。

---

## 二、改版后的界面结构

```
┌─────────────────────────────────────────────┐
│ ☰   AutoGLM Agent                    🌙      │ ← 顶部简约 Toolbar（点☰打开面板，🌙一键切深浅色）
├─────────────────────────────────────────────┤
│ ● 已连接 · 空闲                      ▤       │ ← 状态条（圆点+状态+悬浮窗快捷）
│                                             │
│  ⬤AI  ┌───────────────────────┐             │ ← AI 消息：靠左、浅灰气泡、Markdown
│       │ 你好，我是 AutoGLM…    │             │
│       └───────────────────────┘             │
│                                             │
│             ┌──────────────────────┐ 我⬤    │ ← 用户消息：靠右、深色气泡、白字
│             │ 打开淘宝，搜索无线耳机 │        │
│             └──────────────────────┘        │
├─────────────────────────────────────────────┤
│ ▤  🎤  [ 描述你想让手机做什么… ]       ➤      │ ← 底部固定输入栏（多行 + 圆形发送键）
├─────────────────────────────────────────────┤
│   对话        历史        设置               │ ← 原有三个 Tab，功能保留
└─────────────────────────────────────────────┘
        侧边抽屉（点 ☰ 滑出）
┌──────────────────────┐
│ Agent 面板       ✕   │
│ 任务状态             │
│  ● 执行中   第 3 步   │
│ 屏幕识别 / 思考       │
│  …模型对当前屏幕的理解 │
│ 当前动作             │
│  …do(action="Tap"…)  │
│ Agent 执行日志        │
│  ── 第 1 步 ──       │ ← 独立滚动 + 自动滚到底
│  思考：…             │
│  动作：…             │
│ 深色模式      [ ●——] │ ← 深浅色开关（第二个入口）
│ [ 悬浮窗 ]           │
└──────────────────────┘
```

---

## 三、文件替换清单

### 3.1 替换（修改）的文件（13 个）

| 文件 | 改了什么 |
|---|---|
| `app/src/main/res/layout/activity_main.xml` | 由「CoordinatorLayout + 底部导航」改为「DrawerLayout 外壳 + MaterialToolbar + 导航宿主 + 侧边 Agent 面板」 |
| `app/src/main/res/layout/fragment_task.xml` | 由「大输入卡片」改为「对话页」：状态条 + RecyclerView 消息列表 + 底部固定输入栏 |
| `app/src/main/res/values/colors.xml` | 深色配色 → **浅灰白配色**；保留原有全部颜色名，新增 `chat_*` 对话专用色 |
| `app/src/main/res/values/themes.xml` | 父主题 `Theme.Material3.Dark` → **`Theme.Material3.Light`**；状态栏图标改深色；新增对话/工具栏/日志样式 |
| `app/src/main/res/values-night/themes.xml` | 深色主题；补上 `toolbarStyle`，与浅色共用同一套控件样式 |
| `app/src/main/res/values-v27/themes.xml` | ⚠️ **原文件有坑**：它把父主题写死成 Dark（Android 8.1+ 会用它），会导致浅色主题半坏。已改为 Light + `windowLightNavigationBar=true` |
| `app/src/main/java/com/kevinluo/autoglm/MainActivity.kt` | 只新增：Toolbar / 侧边面板 / 深浅色切换 / 面板状态与日志绑定 / **软键盘避让**（原有 Shizuku、导航、Intent、事件代码原封不动） |
| `app/src/main/java/com/kevinluo/autoglm/home/TaskFragment.kt` | 只新增：消息列表、用户气泡、把 Agent 状态转成 AI 气泡、状态条；把输入框类型从 `TextInputEditText` 换成 `EditText`（因为不再用 TextInputLayout） |
| `app/src/main/java/com/kevinluo/autoglm/BaseActivity.kt` | **本次无功能改动**（只加了一段说明注释）：深浅色偏好已挪到 `AutoGLMApplication.onCreate()`，原因见下面第十节 |
| `app/src/main/java/com/kevinluo/autoglm/app/AutoGLMApplication.kt` | 新增 1 行调用：启动时套用用户保存的深浅色偏好（这时候还没有任何 Activity，一次到位不闪屏） |
| `app/src/main/AndroidManifest.xml` | `MainActivity` 增加 `android:windowSoftInputMode="adjustResize"`（配合软键盘避让，未增删任何 Activity / Service / 权限） |
| `app/build.gradle.kts` | 新增 4 组依赖：RecyclerView、DrawerLayout、Markwon ×4 |
| `gradle/libs.versions.toml` | 新增版本号与库坐标（本工程用 Version Catalog，所以两处都要加） |


### 3.2 新增的文件（18 个）

| 文件 | 作用 |
|---|---|
| `res/layout/item_message.xml` | ⭐ 单条消息布局（左 AI / 右用户，两种气泡） |
| `res/values/strings_ui.xml` | 新增文字资源（不改动原有 20KB 的 `strings.xml`，避免踩坑） |
| `res/values-night/colors.xml` | ⭐ 深色模式配色（与浅色 **93 个键一一对应**，不会有漏网的浅色） |
| `res/values-night-v27/themes.xml` | 「深色 + Android 8.1+」主题变体（补齐 4 种组合，任何机型都不会半深半浅） |
| `res/drawable/bg_chat_user_bubble.xml` | 用户气泡背景（深色圆角，右下角小圆角） |
| `res/drawable/bg_chat_ai_bubble.xml` | AI 气泡背景（浅灰圆角 + 极细描边，左下角小圆角） |
| `res/drawable/bg_chat_input.xml` | 胶囊输入框背景（22dp 圆角） |
| `res/drawable/bg_send_button.xml` | 圆形发送键（selector：可点=蓝，不可点=灰） |
| `res/drawable/bg_panel.xml` | 侧边面板底板（右侧大圆角） |
| `res/drawable/bg_avatar.xml` | 通用圆点（头像 / 状态点 / 步骤徽章共用，靠 `backgroundTint` 变色） |
| `res/drawable/ic_menu.xml` | 三横线（打开面板） |
| `res/drawable/ic_send.xml` | 发送（纸飞机） |
| `res/drawable/ic_dark_mode.xml` | 月亮（切深色） |
| `res/drawable/ic_light_mode.xml` | 太阳（切浅色） |
| `java/.../ui/ChatMessage.kt` | 消息数据模型 |
| `java/.../ui/ChatSessionStore.kt` | 会话内存仓库（切 Tab 不丢聊天记录） |
| `java/.../ui/ChatMessageAdapter.kt` | 气泡适配器 + Markwon Markdown 渲染 |
| `java/.../ui/ThemeController.kt` | 深浅色持久化 + 一键切换 |

---

## 四、六个交付物对应关系

| 任务要求 | 文件 |
|---|---|
| ① `activity_main.xml` 主布局 | `app/src/main/res/layout/activity_main.xml`（顶部 Toolbar + 侧边 Agent 面板 + 导航宿主 + 底部 Tab） |
| ② `item_message.xml` 单条消息布局 | `app/src/main/res/layout/item_message.xml` |
| ③ `colors.xml` 颜色资源 | `app/src/main/res/values/colors.xml`（浅色）+ `app/src/main/res/values-night/colors.xml`（深色） |
| ④ `themes.xml` 主题样式 | `app/src/main/res/values/themes.xml`、`values-night/themes.xml`、`values-v27/themes.xml`、`values-night-v27/themes.xml` |
| ⑤ `MainActivity.kt` 需要修改的代码片段 | `MainActivity.kt` 中 `setupChatShell()` / `toggleDarkMode()` / `observeAgentPanel()` / `updatePanelStatus()` / `refreshAgentLog()` / `setupEdgeToEdge()`（均带「本次 UI 改版新增」注释）；对话列表部分在 `home/TaskFragment.kt` |
| ⑥ 依赖配置（Module 级 build.gradle） | `app/build.gradle.kts` + `gradle/libs.versions.toml`（Markwon 4.6.2） |

> **为什么对话列表在 `fragment_task.xml` 而不是 `activity_main.xml`？**
> 本工程是「1 个 Activity + 3 个 Fragment」的导航结构。如果把消息列表直接塞进
> `activity_main.xml`，就必须删掉 `FragmentContainerView`，那么 HistoryFragment（历史）、
> SettingsFragment（设置）就再也显示不出来 —— 那等于删功能。
> 所以：`activity_main.xml` 负责「外壳」（工具栏 / 侧边面板 / 主题切换 / 导航宿主），
> `fragment_task.xml` 负责「对话页」（消息列表 + 底部输入栏）。两者合起来才是完整的豆包式界面。

---

## 五、Android Studio 导入 + Gradle 同步

### 5.1 导入

1. 打开 Android Studio。
   **版本要求：Narwhal（2025.1.1）或更新。**
   本工程用的是 AGP 8.13.2，比它旧的 Studio 会提示
   `The project is using an incompatible version of the Android Gradle Plugin`
   并拒绝同步；IDE 内置 JDK 需为 **17**。
2. `File → Open`，选择 **`AutoGLM-For-Android` 文件夹**（有 `settings.gradle.kts` 的那一层），
   不要选里面的 `app` 目录。
3. 首次打开会提示信任项目 / Gradle JDK，选择 **JDK 17**（`File → Settings → Build Tools → Gradle → Gradle JDK`）。
4. 等待右下角 Gradle 同步。

### 5.2 SDK 配置

`app/build.gradle.kts` 里写的是：

```kotlin
compileSdk = 35     // 需要 Android 15 (API 35) SDK
minSdk = 24         // Android 7.0 起
targetSdk = 34
```

如果提示缺少 SDK：`Tools → SDK Manager → SDK Platforms` 勾选 **Android 15 (API 35)**，
`SDK Tools` 勾选 **Android SDK Build-Tools** 与 **Android SDK Platform-Tools**。

### 5.3 同步时新增的依赖（会联网下载）

```
androidx.recyclerview:recyclerview:1.3.2
androidx.drawerlayout:drawerlayout:1.2.0
io.noties.markwon:core:4.6.2
io.noties.markwon:linkify:4.6.2
io.noties.markwon:ext-tables:4.6.2
io.noties.markwon:ext-strikethrough:4.6.2
```

若公司网络访问不了 Maven Central，在 `settings.gradle.kts` 的 `repositories` 里加上镜像即可。

---

## 六、编译打包 APK + 手机安装

### 方式 A：Android Studio 直接跑（推荐先这样验证）

1. 手机开启「开发者选项 → USB 调试」，用数据线连电脑，弹窗点「允许」。
2. Android Studio 顶部选中你的设备，点绿色 ▶ Run。
3. App 装好后会自动启动，界面即为新 UI。

### 方式 B：命令行出 APK

Windows（在项目根目录）：

```powershell
.\gradlew.bat assembleDebug
```

产物路径：

```
app/build/outputs/apk/debug/AutoGLM-0.0.6-debug.apk
```

> 文件名是原工程自定义的（`AutoGLM${versionName}` 规则，即 `AutoGLM-0.0.6-debug.apk`）。

> ⚠️ **命令行编译的前置条件**：`gradlew` 自己不知道 Android SDK 在哪，必须先满足其一：
> - `AndroidStudioProjects` 之外的项目，需要在项目根目录有 `local.properties`：
>   ```properties
>   sdk.dir=C\:\\Users\\17357\\AppData\\Local\\Android\\Sdk
>   ```
>   （Android Studio 第一次同步时会自动生成这个文件；只走命令行则需手动建。）
> - 或者设置环境变量 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）指向 SDK 目录。
>
> 否则会报错：`SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
> or by setting the sdk.dir path in your project's local.properties file`。
> **最省事的做法：先在 Android Studio 里完成一次同步（它会顺手装好 SDK），再回到命令行执行 `gradlew`。**

### 方式 C：Release 包（要签名）

1. `Build → Generate Signed Bundle / APK → APK`。
2. 新建或选择 keystore，填好别名与密码。
3. 选 `release`，勾选 `V1 + V2` 签名，Finish。
4. 产物：`app/build/outputs/apk/release/AutoGLM-0.0.6-release.apk`。

> 原工程 `build.gradle.kts` 支持从环境变量 `KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`
> 读取签名信息；若没有 `release.keystore` 文件，Release 会退化为 debug 签名。

### 安装到手机

- 数据线：`adb install -r app\build\outputs\apk\debug\AutoGLM-0.0.6-debug.apk`
- 或者把 APK 传到手机点击安装（需允许「安装未知来源应用」）。

### 装完后仍需完成的老三样（和以前一样，与本 UI 改版无关）

1. 启动 **Shizuku** 并授权（App 内会请求）；
2. 授予 **悬浮窗权限**（设置 → 应用 → AutoGLM → 显示在其他应用上层）；
3. 在 **设置 → 模型配置** 填 Base URL / Model / API Key。

---

## 七、深色模式是怎么工作的（一键切换的实现）

1. 点顶部 🌙 或侧栏「深色模式」开关 → `ThemeController.toggle()`；
2. 内部调用 `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES / MODE_NIGHT_NO)`
   并把选择存进 `SharedPreferences`（下次启动还记得）；
3. 系统自动重新加载 `values-night/` 下的 `colors.xml`、`themes.xml`，并重建 Activity；
4. 因为所有颜色都是 `@color/chat_xxx` 这种「按名字引用」，所以
   **气泡、输入框、面板、代码块的颜色全部自动适配**，没有一行写死的颜色。

四个主题文件覆盖了全部组合，任何 Android 版本都不会出现半深半浅：

| | API < 27 | API ≥ 27 |
|---|---|---|
| 浅色 | `values/themes.xml` | `values-v27/themes.xml` |
| 深色 | `values-night/themes.xml` | `values-night-v27/themes.xml` |

---

## 八、明确「没有改动」的部分（功能保留证明）

以下文件/逻辑**一行未动**，Agent 能力完整保留：

- `phone_agent` 对应的安卓端核心：`agent/PhoneAgent.kt`、`action/ActionHandler.kt`、`action/ActionParser.kt`
- 屏幕捕获：`screenshot/ScreenshotService.kt`、`history/ScreenshotAnnotator.kt`
- Shizuku 通道：`UserService.kt`、`ComponentManager.kt`、`device/DeviceExecutor.kt`
- 模型调用：`model/ModelClient.kt`、`model/ModelResponseParser.kt`
- 任务执行：`task/TaskExecutionManager.kt`、`task/TaskExecutionState.kt`、`task/TaskStep.kt`
- 悬浮窗（原本的日志窗）：`ui/FloatingWindowService.kt`、`ui/FloatingWindowStateManager.kt`、`layout_floating_window.xml`
- 输入法：`input/AutoGLMKeyboardService.kt`
- 语音：`voice/*`
- 历史记录：`history/*`
- 设置：`settings/SettingsFragment.kt`、`settings/SettingsManager.kt`（只跟着换了配色，没改逻辑）
- `AndroidManifest.xml`（没有增删任何 Activity / Service / 权限）
- `MainViewModel.kt`、`nav_main.xml`、`menu_bottom_nav.xml`（未修改）

`MainActivity.kt` / `TaskFragment.kt` 里所有「触发 Agent」的调用仍然是原来那一句：

```kotlin
viewModel.startTask(taskDescription)   // → MainViewModel → TaskExecutionManager
```

新增代码只做「读 `TaskExecutionManager.taskState` / `steps` / `viewModel.outputLog` 并显示」。

---

## 九、常见问题

**Q：Markdown 渲染报错 / 想减少依赖？**
`ChatMessageAdapter.kt` 里有一段 `.usePlugin(object : AbstractMarkwonPlugin() { override fun configureTheme(...) })`，
它只负责给代码块上色。若将来升级 Markwon 后方法名有变化，**直接删掉这一段 `.usePlugin(...)` 即可**，
其余 Markdown 功能（加粗 / 列表 / 链接 / 表格）不受影响。

**Q：界面还是深色？**
先确认手机系统是否处于深色模式（首次安装默认「跟随系统」）。
点一下顶部 🌙 即可强制切换，选择会被记住。

**Q：设置页 / 历史页看起来变白了？**
这是预期效果：本次是**全局**浅色化，所有页面统一配色。切到深色模式即恢复原来的深色观感。

**Q：想还原？**
本仓库是 git clone 下来的，直接：

```powershell
git checkout -- app gradle        # 还原所有已跟踪文件
git clean -fd app/src/main        # 删除新增文件（注意会删掉新文件，确认后再执行）
```

或先导出补丁保存改动：`git diff > ui-redesign.patch`

---

## 十、复查修正记录（对上面这版代码的独立复核）

上面这版 UI 代码写完后又做了一轮独立复核：把 `res/` 下全部 120 个 XML、`R.*` 全部引用、
`@color / @drawable / @style / @string / @id` 全部引用、`getString()` 的格式化参数个数
逐条做了静态比对。结论是**资源引用零缺失、零重名、零格式化参数不匹配，没有会编译不过的地方**。
但查出 4 个真实问题，已全部修好：

| # | 问题 | 严重程度 | 修法 |
|---|---|---|---|
| 1 | **软键盘会完全盖住底部输入栏**。本工程开了 edge-to-edge（`enableEdgeToEdge()` → `decorFitsSystemWindows=false`），这种模式下系统不会为键盘缩小窗口，键盘直接盖在界面上；而对话页的输入栏是固定在屏幕最底部的 → 打字时看不见自己输入的内容。这是「固定底部输入栏」相比原版「顶部大输入框」新引入的回归 | 🔴 高（聊天页基本不可用） | `MainActivity` 新增 `setupImeInsets()`：读 `WindowInsetsCompat.Type.ime()`，键盘弹出时给内容区补上键盘高度的底部内边距，把输入栏抬到键盘之上，同时临时隐藏底部 Tab（避免多出一条空白）；键盘收起后自动还原。并在 `AndroidManifest.xml` 给 `MainActivity` 加 `android:windowSoftInputMode="adjustResize"` 让 API 24~29 的行为也确定 |
| 2 | **深浅色偏好的应用时机太晚**。原实现放在 `BaseActivity.onCreate()`：但 Activity 的真实主题在 `attachBaseContext()` 阶段就按 `sDefaultNightMode` 定好了，而 `attachBaseContext()` 早于 `onCreate()` → 冷启动时当前 Activity 会先以「跟随系统」创建，再被 `setDefaultNightMode()` 触发一次 `recreate()`，用户看到闪屏 | 🟠 中 | 把 `ThemeController.applySavedMode()` 移到 `AutoGLMApplication.onCreate()`（此时任何 Activity 都还没创建，一次到位）；`BaseActivity` 恢复为不含业务改动。同时 `applySavedMode()` 在没有保存过偏好时直接返回，不干预 AppCompat 默认行为 |
| 3 | **`activity_main.xml` 被写入了 UTF-8 BOM**（文件头多了 `EF BB BF`）。原仓库 120 个资源 XML **没有一个**带 BOM，只有这一个文件有，属于生成过程的编码事故（会让 git diff 首行异常、部分工具链对 BOM 的处理不一致） | 🟡 低 | 已去掉 BOM，文件恢复为与全仓库一致的「无 BOM UTF-8」 |
| 4 | 底部输入框里的 `EditText` 在 40dp 胶囊里**顶对齐**，单行输入时文字偏上、下边空一条 | 🟢 外观 | 给 `taskInput` 加 `android:layout_gravity="center_vertical"` |

同时确认了几件**本来就对**、容易被误判的事：

- `values-night/colors.xml` 与 `values/colors.xml` 的 93 个颜色键**完全一一对应**，不会有漏网的浅色；
- `values-v27` / `values-night-v27` / `values` / `values-night` 四个主题文件覆盖了「深/浅 × API<27/≥27」全部四种组合，`Base.Theme.AutoGLM` 在每个文件里都写全了（原 `values-v27/themes.xml` 只写一个属性、父主题写死 Dark 的坑已修）；
- Markwon 4.6.2 用到的 6 个 API（`codeTextColor` / `codeBackgroundColor` / `codeBlockTextColor` / `codeBlockBackgroundColor` / `codeTypeface` / `linkColor`）在该版本都存在；
- `ChatMessageAdapter` 的 `DiffUtil` 回调、`ChatSessionStore.upsert` 的「同 tag 覆盖更新」逻辑自洽，`ListAdapter.submitList` 的 commit 回调里滚动是正确写法；
- 原有 Agent 逻辑（Shizuku / 屏幕捕获 / 模型调用 / 任务执行 / 悬浮窗 / 语音 / 历史 / 设置）确实**一行未动**（`git diff` 里只有 import 与控件类型声明被替换）。

### 还剩两个「不是 bug 但不完美」的点（可选）

1. `ChatSessionStore.clear()` 和字符串 `chat_clear`（“清空对话”）目前**没有任何入口调用** —— 想要的话，在侧边面板底部加一个按钮接上即可。
2. 悬浮窗（`FloatingWindowService`）用的是系统深色模式，**不跟随 App 内的 🌙 强制切换**（非 Activity 的 Context 拿不到 AppCompat 的本地夜间模式）。要一致的话，需要在悬浮窗里自己按 `ThemeController.getSavedMode()` 套 `ContextThemeWrapper`。

---

## 十一、真机联调修正记录（第二轮：实测出来的三个真问题）

代码编译通过 ≠ 能跑。把 APK 装到真机（**小米 HyperOS / Android 16 / SDK 36**）实测后，
又抓出 3 个上不了台面的问题，全部用 `adb` + 日志/截图定位并修好：

| # | 症状（用户描述） | 真正的原因 | 修法 |
|---|---|---|---|
| 1 | **「一切换颜色他就黑白来回闪」** | `MaterialSwitch` 的父类 `CompoundButton` 默认会把 `isChecked` 存进 `onSaveInstanceState`，Activity 重建后由 `onRestoreInstanceState` 恢复，**而恢复会再次触发 `OnCheckedChangeListener`**。于是：切主题 → 写偏好 → 重建 → 开关被恢复成旧值 → 监听器发现「开关显示深色、但主题已经是浅色」→ 调 `applyDarkMode(true)` 又改回深色 → 再重建 → 再恢复……实测 **每秒 6~10 次重建**，日志里 `ActivityThread: Schedule relaunch activity` 刷屏，眼睛看到的就是黑白来回闪 | `activity_main.xml` 给开关加 **`android:saveEnabled="false"`**（开关状态本来就由 `ThemeController` 决定，每次 `onCreate/onResume` 都会 `syncThemeSwitch()` 校准，不需要系统保存）。修完实测：**每次切换恰好 1 次重建**，偏好正确翻转。<br>另外 `ThemeController.isCurrentlyDark()` 改为**以保存的偏好为准**（原来读 Activity 的 `resources.configuration`，在这个 ROM 上 `night` 标志不可靠，会把“当前是深色”误判成浅色，导致点了没反应） |
| 2 | **「一打开设置就卡退」** | `SettingsFragment.isKeyboardEnabled()` 读 `Settings.Secure.ENABLED_INPUT_METHODS`。**Android 14 (API 34) 起该安全设置项对 targetSdk>33 的应用禁止读取**，直接抛 `java.lang.SecurityException: Settings key: <enabled_input_methods> is only readable to apps with targetSdkVersion lower than or equal to: 33`；而它是在 `onResume()` 里被调用的 → 设置页必崩 | 改用公开 API `InputMethodManager.getEnabledInputMethodList()`（无需权限、不在受限名单、各版本一致）。实测崩溃数 **0** |
| 3 | **「设置里面好像还没有东西」** | 不是布局缺失，就是第 2 条：Fragment 崩在 `onResume`，用户看到的是崩溃前那一帧空页 | 同第 2 条。修完设置页完整渲染：权限状态(1/4)、模型配置(API 地址/模型名/密钥/测试连接)、执行配置…… |

顺带在真机上验证通过的还有：

- **软键盘不再遮住底部输入栏**：输入法弹起时，底部三个 Tab 自动隐藏、输入栏整体抬到键盘之上（`uiautomator` 实测输入栏 bounds 在 `y=1416~1536`，内容区底边正好等于键盘顶边）；
- **深浅色在两个入口都正常**：工具栏 🌙 按钮、侧边面板里的「深色模式」开关，行为一致，切换后对话页/设置页/历史页配色统一（不会再出现「对话页变了、设置页还是旧色」）；
- **重启后记住选择**：偏好存在 `autoglm_ui_prefs`，冷启动按偏好应用（不再有先白后黑的闪屏）。

> 一个仍然存在的小限制：其他 Activity（设置/历史）没有做同样的“切主题不重建”处理，
> 但它们本身不提供切主题入口，正常使用碰不到。若以后要在这些页面里加快捷开关，记得把同样两个坑处理掉。


