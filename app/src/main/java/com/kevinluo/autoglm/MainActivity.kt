package com.kevinluo.autoglm

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.task.TaskExecutionState
import com.kevinluo.autoglm.task.TaskStep
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import com.kevinluo.autoglm.ui.MainUiEvent
import com.kevinluo.autoglm.ui.MainViewModel
import com.kevinluo.autoglm.ui.ShizukuStatus
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.ui.ThemeController
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Main Activity that hosts the bottom navigation and Fragment container.
 *
 * Responsible for:
 * - Setting up Navigation with BottomNavigationView
 * - Managing Shizuku service connection
 * - Coordinating cross-Fragment state via MainViewModel
 */
class MainActivity : BaseActivity() {
    private lateinit var navController: NavController
    private lateinit var bottomNav: BottomNavigationView
    private val viewModel: MainViewModel by viewModels()

    // region ===== 对话界面（本次 UI 改版新增的控件引用，全部只是“界面”，不涉及业务逻辑） =====

    /** 外层抽屉容器：负责左右滑出侧边 Agent 面板。 */
    private lateinit var rootLayout: DrawerLayout

    /** 顶部简约工具栏（标题：AutoGLM Agent）。 */
    private lateinit var toolbar: MaterialToolbar

    /** 工具栏右侧的“一键切换深浅色”按钮。 */
    private lateinit var btnThemeToggle: ImageButton

    /** 侧边 Agent 面板本体。 */
    private lateinit var agentPanel: View

    /** 面板右上角的收起按钮。 */
    private lateinit var btnClosePanel: ImageButton

    /** 面板底部的深色模式开关。 */
    private lateinit var switchDarkMode: MaterialSwitch

    /** 面板底部的“悬浮窗”快捷按钮。 */
    private lateinit var btnPanelFloatingWindow: MaterialButton

    // 面板内的状态展示控件
    private lateinit var viewStatusDot: View
    private lateinit var tvTaskStatus: TextView
    private lateinit var tvTaskStep: TextView
    private lateinit var tvTaskDescription: TextView
    private lateinit var tvScreenUnderstanding: TextView
    private lateinit var tvCurrentAction: TextView
    private lateinit var tvAgentLog: TextView
    private lateinit var logScroll: ScrollView

    /** 缓存最近一次的分布执行记录，用于拼装日志文本。 */
    private var latestSteps: List<TaskStep> = emptyList()

    /** 缓存最近一次的文本日志（开始 / 取消 / 暂停 / 完成等）。 */
    private var latestOutputLog: String = ""

    // endregion

    private val componentManager: ComponentManager by lazy {
        ComponentManager.getInstance(applicationContext)
    }

    // Shizuku service connection
    private var userService: IUserService? = null
    private var isServiceBound = false

    private val userServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Logger.i(TAG, "UserService connected")
                userService = IUserService.Stub.asInterface(service)
                userService?.let { svc ->
                    componentManager.onServiceConnected(svc)
                    // TaskExecutionManager is now the sole PhoneAgentListener
                    viewModel.updateShizukuStatus(ShizukuStatus.CONNECTED)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Logger.i(TAG, "UserService disconnected")
                userService = null
                componentManager.onServiceDisconnected()
                viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
            }
        }

    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                UserService::class.java.name,
            ),
        ).daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val onRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Logger.i(TAG, "Shizuku permission granted")
                    Toast.makeText(this, R.string.toast_shizuku_permission_granted, Toast.LENGTH_SHORT).show()
                    bindUserService()
                } else {
                    Logger.w(TAG, "Shizuku permission denied")
                    Toast.makeText(this, R.string.toast_shizuku_permission_denied, Toast.LENGTH_SHORT).show()
                    viewModel.updateShizukuStatus(ShizukuStatus.NO_PERMISSION)
                }
            }
        }

    private val onBinderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            Logger.i(TAG, "Shizuku binder received")
            checkShizukuPermission()
        }

    private val onBinderDeadListener =
        Shizuku.OnBinderDeadListener {
            Logger.w(TAG, "Shizuku binder dead")
            viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
            unbindUserService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.d(TAG, "MainActivity created")

        setupNavigation()
        setupChatShell() // ← 新增：顶部工具栏 + 侧边 Agent 面板 + 深浅色切换
        // 注意顺序：setupEdgeToEdge() 会给 toolbarContainer / agentPanel 补状态栏内边距，
        // 所以必须放在 setupChatShell()（里面 findViewById 拿到这些控件）之后
        setupEdgeToEdge()
        setupShizuku()
        observeEvents()
        observeAgentPanel() // ← 新增：把 Agent 状态 / 执行日志喂给侧边面板
        checkOverlayPermission()

        // Handle incoming Intent for backward compatibility
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Handles incoming Intents and routes to the appropriate Fragment.
     *
     * Supports backward compatibility with existing deep links and Intents
     * that previously targeted HistoryActivity or SettingsActivity.
     *
     * @param intent The incoming Intent to handle
     *
     * _Requirements: 6.5_
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val targetFragment = intent.getStringExtra(EXTRA_TARGET_FRAGMENT)

        Logger.d(TAG, "Handling intent: action=$action, targetFragment=$targetFragment")

        // Determine target destination based on action or extra
        val destinationId =
            when {
                action == ACTION_NAVIGATE_SETTINGS -> R.id.settingsFragment
                action == ACTION_NAVIGATE_HISTORY -> R.id.historyFragment
                targetFragment == TARGET_SETTINGS -> R.id.settingsFragment
                targetFragment == TARGET_HISTORY -> R.id.historyFragment
                targetFragment == TARGET_TASK -> R.id.taskFragment
                else -> null
            }

        // Navigate to the target destination if specified
        destinationId?.let { destId ->
            // Post navigation to ensure NavController is ready
            bottomNav.post {
                try {
                    if (navController.currentDestination?.id != destId) {
                        navController.navigate(destId)
                        Logger.d(TAG, "Navigated to destination: $destId")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to navigate to destination: $destId", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        // 从深色模式切回来 / 页面重建后，把开关和图标校准到当前真实状态
        syncThemeSwitch()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "MainActivity destroyed")

        Shizuku.removeRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.removeBinderReceivedListener(onBinderReceivedListener)
        Shizuku.removeBinderDeadListener(onBinderDeadListener)

        unbindUserService()
    }

    /**
     * Sets up the Navigation component with BottomNavigationView.
     *
     * Configures state preservation to ensure Fragment state is maintained
     * when switching between tabs.
     *
     * _Requirements: 1.4, 5.1_
     */
    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        bottomNav = findViewById(R.id.bottom_nav)

        // Configure BottomNavigationView to save and restore Fragment state
        // This ensures that when switching tabs, the previous Fragment's state is preserved
        bottomNav.setupWithNavController(navController)

        // Set up item selection listener to handle state preservation
        bottomNav.setOnItemSelectedListener { item ->
            // Use NavigationUI to handle navigation with state saving
            val builder =
                androidx.navigation.NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(true)
                    .setPopUpTo(
                        navController.graph.startDestinationId,
                        inclusive = false,
                        saveState = true,
                    )

            val options = builder.build()

            try {
                navController.navigate(item.itemId, null, options)
                true
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "Navigation failed for item: ${item.itemId}", e)
                false
            }
        }

        // Sync the selected item with the current destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            bottomNav.menu.findItem(destination.id)?.isChecked = true
        }

        Logger.d(TAG, "Navigation setup complete with state preservation")
    }

    /**
     * 处理全面屏（edge-to-edge）的“避让区域”。
     *
     * 原实现：把状态栏高度加到整个根布局上——这样顶部会空出一条和背景同色的横条。
     * 新实现：把状态栏高度补到“工具栏容器”上、把导航栏高度补到“底部 Tab”和“侧边面板”上，
     * 于是工具栏背景、面板背景可以一直铺到屏幕最边缘，视觉更干净（豆包 / Codex 同款做法）。
     */
    private fun setupEdgeToEdge() {
        // 根布局：不补系统栏内边距（状态栏交给 toolbarContainer、导航栏交给底部 Tab），
        // 但由它统一处理「软键盘」，详见 setupImeInsets()
        setupImeInsets()
        // 顶部：状态栏高度 → 工具栏容器（背景色因此延伸到状态栏后面）
        setupEdgeToEdgeInsets(findViewById<View>(R.id.toolbarContainer), applyTop = true, applyBottom = false)
        // 底部：导航栏高度 → 底部 Tab，避免被手势条压住
        setupEdgeToEdgeInsets(bottomNav, applyTop = false, applyBottom = true)
        // 侧边面板：上下都要避让
        setupEdgeToEdgeInsets(agentPanel, applyTop = true, applyBottom = true)
    }

    /**
     * 处理「软键盘遮住底部输入栏」的问题。
     *
     * 为什么必须处理：
     *   本工程开了 edge-to-edge（BaseActivity 里调用了 enableEdgeToEdge()），
     *   也就是 decorFitsSystemWindows = false。这种模式下系统**不会**为软键盘缩小窗口，
     *   键盘是直接盖在界面上的。对话页的输入栏是固定在屏幕最底部的，
     *   所以不处理就会被键盘完全挡住 —— 用户根本看不到自己输入的字。
     *
     * 处理办法（和豆包 / 微信聊天页一致）：
     *   · 键盘弹出时：给内容区补上「键盘高度」的底部内边距，把输入栏整体抬到键盘之上；
     *     同时把底部 Tab 临时隐藏，避免在键盘与输入栏之间留出一条空白。
     *   · 键盘收起时：内边距归零、底部 Tab 恢复显示。
     *
     * 兼容性说明：
     *   ime() 内边距在 API 30+ 是真实键盘高度；在 API < 30 上由 androidx 用
     *   systemWindowInsetBottom 近似 —— 若窗口已被 adjustResize 缩小（键盘不再压住界面），
     *   该值等于导航栏高度，此时下面判断为「没有重叠」，不会重复抬升。
     */
    private fun setupImeInsets() {
        val content = findViewById<View>(R.id.mainContent)
        val initialContentPaddingBottom = content.paddingBottom
        val initialContentPaddingTop = content.paddingTop
        val initialContentPaddingLeft = content.paddingLeft
        val initialContentPaddingRight = content.paddingRight

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, windowInsets ->
            val barsBottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeBottom = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            // 只有「键盘确实压住了内容」才抬升（ime > 导航栏高度）
            val imeOverlap = if (imeBottom > barsBottom) imeBottom else 0

            content.updatePadding(
                left = initialContentPaddingLeft,
                top = initialContentPaddingTop,
                right = initialContentPaddingRight,
                bottom = initialContentPaddingBottom + imeOverlap,
            )
            bottomNav.isVisible = imeOverlap == 0

            // 原样返回：insets 继续向下分发给 toolbarContainer / bottomNav / agentPanel
            windowInsets
        }
        ViewCompat.requestApplyInsets(rootLayout)
    }

    /**
     * Sets up Shizuku listeners and checks initial state.
     */
    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.addBinderReceivedListener(onBinderReceivedListener)
        Shizuku.addBinderDeadListener(onBinderDeadListener)

        // Check initial Shizuku state
        if (Shizuku.pingBinder()) {
            checkShizukuPermission()
        } else {
            viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
        }
    }

    /**
     * Checks Shizuku permission and binds service if granted.
     */
    private fun checkShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Logger.w(TAG, "Shizuku version too low")
                Toast.makeText(this, R.string.toast_shizuku_version_low, Toast.LENGTH_SHORT).show()
                viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
                return
            }

            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    Logger.i(TAG, "Shizuku permission already granted")
                    bindUserService()
                }

                Shizuku.shouldShowRequestPermissionRationale() -> {
                    Logger.i(TAG, "Should show Shizuku permission rationale")
                    viewModel.updateShizukuStatus(ShizukuStatus.NO_PERMISSION)
                }

                else -> {
                    Logger.i(TAG, "Requesting Shizuku permission")
                    viewModel.updateShizukuStatus(ShizukuStatus.NO_PERMISSION)
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error checking Shizuku permission", e)
            viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
        }
    }

    /**
     * Binds to the UserService via Shizuku.
     */
    private fun bindUserService() {
        if (isServiceBound) {
            Logger.d(TAG, "UserService already bound")
            return
        }

        try {
            viewModel.updateShizukuStatus(ShizukuStatus.CONNECTING)
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            isServiceBound = true
            Logger.i(TAG, "UserService bind requested")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to bind UserService", e)
            viewModel.updateShizukuStatus(ShizukuStatus.NOT_RUNNING)
        }
    }

    /**
     * Unbinds from the UserService.
     */
    private fun unbindUserService() {
        if (!isServiceBound) return

        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            isServiceBound = false
            Logger.i(TAG, "UserService unbound")
        } catch (e: Exception) {
            Logger.e(TAG, "Error unbinding UserService", e)
        }
    }

    /**
     * Checks and updates overlay permission status.
     */
    private fun checkOverlayPermission() {
        val hasPermission = Settings.canDrawOverlays(this)
        viewModel.updateOverlayPermission(hasPermission)
    }

    /**
     * Observes ViewModel events and handles them.
     */
    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    /**
     * Handles UI events from the ViewModel.
     */
    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.ShowToast -> {
                Toast.makeText(this, event.messageResId, Toast.LENGTH_SHORT).show()
            }

            is MainUiEvent.ShowToastText -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            is MainUiEvent.TaskCompleted -> {
                Logger.i(TAG, "Task completed: ${event.message}")
            }

            is MainUiEvent.TaskFailed -> {
                Logger.w(TAG, "Task failed: ${event.error}")
            }

            is MainUiEvent.MinimizeApp -> {
                moveTaskToBack(true)
            }
        }
    }

    // ==========================================================================================
    //  以下是本次 UI 改版新增的代码
    //  说明：这里只做“界面绑定”，没有修改任何 Agent 核心逻辑：
    //        · 不碰 Shizuku 连接、权限申请（上面的代码原封不动）
    //        · 不碰屏幕捕获、模型调用、任务执行（仍然由 TaskExecutionManager / PhoneAgent 负责）
    //        这里只是把 TaskExecutionManager 已经暴露出来的状态“读出来显示”
    // ==========================================================================================

    /**
     * 初始化对话式外壳：
     *   1. 顶部工具栏（标题 + 打开面板 + 深浅色切换）
     *   2. 侧边 Agent 面板（收起按钮、深色模式开关、悬浮窗快捷入口）
     */
    private fun setupChatShell() {
        // 找到 XML 里定义的各个控件
        rootLayout = findViewById(R.id.rootLayout)
        toolbar = findViewById(R.id.toolbar)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        agentPanel = findViewById(R.id.agentPanel)
        btnClosePanel = findViewById(R.id.btnClosePanel)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        btnPanelFloatingWindow = findViewById(R.id.btnPanelFloatingWindow)

        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvTaskStatus = findViewById(R.id.tvTaskStatus)
        tvTaskStep = findViewById(R.id.tvTaskStep)
        tvTaskDescription = findViewById(R.id.tvTaskDescription)
        tvScreenUnderstanding = findViewById(R.id.tvScreenUnderstanding)
        tvCurrentAction = findViewById(R.id.tvCurrentAction)
        tvAgentLog = findViewById(R.id.tvAgentLog)
        logScroll = findViewById(R.id.logScroll)

        // ① 点左上角“三横线”图标 → 从左侧滑出 Agent 面板
        //    openDrawer 第二个参数 true 表示带动画
        toolbar.setNavigationOnClickListener {
            rootLayout.openDrawer(agentPanel, true)
        }

        // ② 面板右上角的收起按钮
        btnClosePanel.setOnClickListener {
            rootLayout.closeDrawer(agentPanel, true)
        }

        // ③ 工具栏右侧按钮：一键切换深色 / 浅色
        btnThemeToggle.setOnClickListener {
            toggleDarkMode()
        }

        // ④ 面板底部的深色模式开关（和工具栏按钮是同一个功能，两个入口）
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            // 只在“开关状态和当前实际主题不一致”时才动作，
            // 这样代码里同步开关状态时不会造成来回抖动
            if (isChecked != ThemeController.isCurrentlyDark(this)) {
                applyDarkMode(isChecked)
            }
        }

        // ⑤ 面板里的“悬浮窗”按钮：和对话页右上角的悬浮窗按钮同一套逻辑
        btnPanelFloatingWindow.setOnClickListener {
            toggleFloatingWindowFromPanel()
        }

        // 初始化时把图标 / 开关校准一次
        syncThemeSwitch()
    }

    /**
     * 一键切换深色 / 浅色。
     *
     * 原理：ThemeController 内部调用 AppCompatDelegate.setDefaultNightMode()，
     * 系统会重新加载 values-night/ 下的颜色与主题，并自动重建 Activity。
     * 因此气泡、输入框、面板的颜色都是“自动适配”的，不需要逐控件改颜色。
     */
    private fun toggleDarkMode() {
        val nowDark = ThemeController.toggle(this)
        Toast.makeText(
            this,
            if (nowDark) R.string.chat_theme_switched_dark else R.string.chat_theme_switched_light,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 直接指定深浅色（供面板开关使用）。
     */
    private fun applyDarkMode(dark: Boolean) {
        ThemeController.setMode(
            this,
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
    }

    /**
     * 把工具栏按钮的图标、面板开关的状态，和“当前真实主题”对齐。
     */
    private fun syncThemeSwitch() {
        val dark = ThemeController.isCurrentlyDark(this)

        // 浅色时显示“月亮”（点一下变深色）；深色时显示“太阳”（点一下变浅色）
        btnThemeToggle.setImageResource(
            if (dark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode,
        )

        if (switchDarkMode.isChecked != dark) {
            switchDarkMode.isChecked = dark
        }
    }

    /**
     * 面板里的悬浮窗开关。
     *
     * 逻辑和 TaskFragment 里的 toggleFloatingWindow 完全一致（复用同一个管理器），
     * 只是把入口挪到了侧边面板，方便任务运行中查看。
     */
    private fun toggleFloatingWindowFromPanel() {
        val wasEnabled = FloatingWindowStateManager.isUserEnabled()
        FloatingWindowStateManager.toggleByUser(this)

        if (!wasEnabled) {
            // 打开悬浮窗后把 App 切到后台，否则悬浮窗会被自己的界面挡住
            moveTaskToBack(true)
        }
        rootLayout.closeDrawer(agentPanel, true)
    }

    /**
     * 订阅 Agent 的运行状态，实时刷新侧边面板。
     *
     * 数据来源（全部是原有代码已经暴露出来的只读状态，没有任何侵入）：
     *   · TaskExecutionManager.taskState → 状态 / 步骤号 / 思考(屏幕识别) / 当前动作
     *   · TaskExecutionManager.steps     → 每一步的历史记录
     *   · MainViewModel.outputLog        → 带时间戳的文本日志
     */
    private fun observeAgentPanel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // (1) 任务状态
                launch {
                    TaskExecutionManager.taskState.collect { state ->
                        updatePanelStatus(state)
                    }
                }

                // (2) 分布执行记录
                launch {
                    TaskExecutionManager.steps.collect { steps ->
                        latestSteps = steps
                        refreshAgentLog()
                    }
                }

                // (3) 文本日志（开始 / 取消 / 暂停 / 完成）
                launch {
                    viewModel.outputLog.collect { text ->
                        latestOutputLog = text
                        refreshAgentLog()
                    }
                }
            }
        }
    }

    /**
     * 根据任务状态刷新面板上半部分的三个卡片。
     *
     * @param state TaskExecutionManager 暴露出来的任务状态快照
     */
    private fun updatePanelStatus(state: TaskExecutionState) {
        // ---- 状态文字与圆点颜色 ----
        val statusTextRes: Int
        val statusColorRes: Int
        when (state.status) {
            TaskStatus.IDLE -> {
                statusTextRes = R.string.chat_status_idle
                statusColorRes = R.color.status_idle
            }

            TaskStatus.RUNNING -> {
                statusTextRes = R.string.chat_status_running
                statusColorRes = R.color.status_running
            }

            TaskStatus.PAUSED -> {
                statusTextRes = R.string.chat_status_paused
                statusColorRes = R.color.status_paused
            }

            TaskStatus.COMPLETED -> {
                statusTextRes = R.string.chat_status_completed
                statusColorRes = R.color.status_completed
            }

            TaskStatus.FAILED -> {
                statusTextRes = R.string.chat_status_failed
                statusColorRes = R.color.status_failed
            }

            TaskStatus.WAITING_CONFIRMATION -> {
                statusTextRes = R.string.chat_status_waiting_confirmation
                statusColorRes = R.color.status_waiting
            }

            TaskStatus.WAITING_TAKEOVER -> {
                statusTextRes = R.string.chat_status_waiting_takeover
                statusColorRes = R.color.status_waiting
            }
            // 这里不需要 else 分支：TaskStatus 是枚举且 7 个值已全部列全，
            // Kotlin 能静态确认 when 是穷尽的，上面两个变量一定被赋过值。
        }

        tvTaskStatus.setText(statusTextRes)
        viewStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, statusColorRes))

        // ---- 步数（0 表示还没开始，就不显示） ----
        tvTaskStep.text =
            if (state.stepNumber > 0) {
                getString(R.string.chat_step_format, state.stepNumber)
            } else {
                ""
            }

        // ---- 任务描述 ----
        tvTaskDescription.isVisible = state.taskDescription.isNotBlank()
        if (state.taskDescription.isNotBlank()) {
            tvTaskDescription.text = state.taskDescription
        }

        // ---- 屏幕识别 / 模型思考 ----
        tvScreenUnderstanding.text =
            state.thinking.ifBlank { getString(R.string.chat_panel_empty) }

        // ---- 当前正在执行的动作 ----
        tvCurrentAction.text =
            state.currentAction.ifBlank { getString(R.string.chat_panel_empty) }
    }

    /**
     * 把“分布执行记录 + 文本日志”拼成一段纯文本，显示在面板下方并自动滚到最新。
     *
     * 之所以拼成纯文本而不是做多级列表，是为了：
     *   · 显示密度高，像终端一样一眼看全；
     *   · 可以长按选中复制，方便排错；
     *   · 不引入额外的 Adapter / 布局，改动面最小。
     */
    private fun refreshAgentLog() {
        val builder = StringBuilder()

        if (latestSteps.isNotEmpty()) {
            latestSteps.forEach { step ->
                builder.append("── ").append(getString(R.string.chat_step_format, step.stepNumber)).append(" ──\n")
                if (step.thinking.isNotBlank()) {
                    builder.append("思考：").append(step.thinking.trim()).append('\n')
                }
                if (step.action.isNotBlank()) {
                    builder.append("动作：").append(step.action.trim()).append('\n')
                }
            }
            builder.append('\n')
        }

        if (latestOutputLog.isNotBlank()) {
            builder.append(latestOutputLog)
        }

        if (builder.isEmpty()) {
            tvAgentLog.setText(R.string.chat_panel_log_empty)
        } else {
            tvAgentLog.text = builder.toString()
        }

        // 自动滚到最底部（终端式体验）
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

        // Intent actions for backward compatibility
        const val ACTION_NAVIGATE_SETTINGS = "com.kevinluo.autoglm.NAVIGATE_SETTINGS"
        const val ACTION_NAVIGATE_HISTORY = "com.kevinluo.autoglm.NAVIGATE_HISTORY"

        // Extra key for specifying target fragment
        const val EXTRA_TARGET_FRAGMENT = "target_fragment"

        // Target fragment values
        const val TARGET_TASK = "task"
        const val TARGET_HISTORY = "history"
        const val TARGET_SETTINGS = "settings"
    }
}
