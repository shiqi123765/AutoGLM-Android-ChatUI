package com.kevinluo.autoglm.home

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kevinluo.autoglm.R
import com.kevinluo.autoglm.settings.SettingsManager
import com.kevinluo.autoglm.settings.TaskTemplate
import com.kevinluo.autoglm.task.TaskExecutionManager
import com.kevinluo.autoglm.task.TaskExecutionState
import com.kevinluo.autoglm.ui.ChatMessage
import com.kevinluo.autoglm.ui.ChatMessageAdapter
import com.kevinluo.autoglm.ui.ChatRole
import com.kevinluo.autoglm.ui.ChatSessionStore
import com.kevinluo.autoglm.ui.FloatingWindowStateManager
import com.kevinluo.autoglm.ui.MainUiState
import com.kevinluo.autoglm.ui.MainViewModel
import com.kevinluo.autoglm.ui.ShizukuStatus
import com.kevinluo.autoglm.ui.TaskStatus
import com.kevinluo.autoglm.util.Logger
import com.kevinluo.autoglm.util.showWithPrimaryButtons
import com.kevinluo.autoglm.voice.VoiceError
import com.kevinluo.autoglm.voice.VoiceInputManager
import com.kevinluo.autoglm.voice.VoiceRecognitionResult
import com.kevinluo.autoglm.voice.VoiceRecordingDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home Fragment for task input and execution status display.
 *
 * Responsible for:
 * - Displaying task input field with voice input and template selection
 * - Showing task execution status and controls
 * - Managing floating window quick action
 * - Observing MainViewModel state for cross-Fragment synchronization
 */
class TaskFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var settingsManager: SettingsManager
    private var voiceInputManager: VoiceInputManager? = null

    // Task Input Views
    // 注意：输入框从 TextInputEditText 换成了普通 EditText（原控件依赖 TextInputLayout 的大卡片外观），
    //       发送按钮从 MaterialButton 换成了圆形 ImageButton。
    //       这里只改了“控件类型”，所有点击 / 文本变化逻辑一字未动。
    private lateinit var taskInput: EditText
    private lateinit var btnVoiceInput: ImageButton
    private lateinit var btnSelectTemplate: ImageButton
    private lateinit var btnStartTask: ImageButton

    // Floating Window Button
    private lateinit var btnFloatingWindow: ImageButton

    // region ===== 对话界面新增控件（纯 UI） =====

    /** 消息列表控件。 */
    private lateinit var chatRecyclerView: RecyclerView

    /** 顶部状态条上的小圆点与文字。 */
    private lateinit var viewChatStatusDot: View
    private lateinit var tvChatStatus: TextView

    /** 消息列表适配器：负责把 ChatMessage 画成左右气泡。 */
    private lateinit var chatAdapter: ChatMessageAdapter

    /**
     * “任务世代”计数器。
     *
     * 每点一次发送就 +1，并拼进消息 tag 中。
     * 这样第二次任务的“第 1 步思考”不会覆盖掉第一次任务的历史气泡。
     */
    private var taskEpoch: Int = 0

    /**
     * 本次任务是否已经出现过“运行中”状态。
     *
     * 用途：TaskExecutionManager.taskState 是全局单例，
     * Fragment 重建后会立刻收到“上一次任务已完成”的旧状态快照。
     * 用这个闸门可以避免把旧结果重复贴进新对话。
     */
    private var hasSeenRunning = false

    // endregion

    // Permission request launcher
    private val audioPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                showVoiceInputDialog()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.voice_permission_denied,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Logger.d(TAG, "TaskFragment onCreateView")
        return inflater.inflate(R.layout.fragment_task, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.d(TAG, "TaskFragment onViewCreated")

        settingsManager = SettingsManager.getInstance(requireContext())

        initViews(view)
        setupChatList() // ← 新增：初始化对话列表（RecyclerView + Adapter）
        setupListeners()
        observeViewModel()
        seedWelcomeIfNeeded() // ← 新增：第一次进入时插入一条 Markdown 欢迎语
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceInputManager?.release()
        voiceInputManager = null
    }

    /**
     * Initializes all view references.
     */
    private fun initViews(view: View) {
        // Task Input Views
        taskInput = view.findViewById(R.id.taskInput)
        btnVoiceInput = view.findViewById(R.id.btnVoiceInput)
        btnSelectTemplate = view.findViewById(R.id.btnSelectTemplate)
        btnStartTask = view.findViewById(R.id.btnStartTask)

        // Floating Window Button
        btnFloatingWindow = view.findViewById(R.id.btnFloatingWindow)

        // 对话界面新增：消息列表 + 顶部状态条
        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        viewChatStatusDot = view.findViewById(R.id.viewChatStatusDot)
        tvChatStatus = view.findViewById(R.id.tvChatStatus)
    }

    /**
     * Sets up click listeners and text change listeners.
     */
    private fun setupListeners() {
        // Task input text change listener
        taskInput.doAfterTextChanged { text ->
            viewModel.updateTaskInput(text?.isNotBlank() == true)
        }

        // Voice input button
        btnVoiceInput.setOnClickListener {
            startVoiceInput()
        }

        // Template selection button
        btnSelectTemplate.setOnClickListener {
            showTemplateSelector()
        }

        // Start task button
        btnStartTask.setOnClickListener {
            startTask()
        }

        // Floating window button
        btnFloatingWindow.setOnClickListener {
            toggleFloatingWindow()
        }
    }

    /**
     * Observes ViewModel state and updates UI accordingly.
     *
     * Observes both the main UI state and permission states for cross-Fragment
     * synchronization. When permissions change in SettingsFragment, this Fragment
     * will automatically update its UI.
     *
     * _Requirements: 5.2, 5.3_
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUiState(state)
                    }
                }

                // Observe permission states for cross-Fragment synchronization
                // When permissions change in SettingsFragment, update UI here
                launch {
                    viewModel.permissionStates.collect { permissionStates ->
                        onPermissionStatesChanged(permissionStates)
                    }
                }

                // 新增：订阅对话记录，列表一变就刷新 RecyclerView
                launch {
                    ChatSessionStore.messages.collect { messages ->
                        submitChatMessages(messages)
                    }
                }

                // 新增：订阅 Agent 任务状态，把“思考 / 动作 / 结果”写成对话气泡
                launch {
                    TaskExecutionManager.taskState.collect { taskState ->
                        onAgentStateForChat(taskState)
                    }
                }
            }
        }
    }

    /**
     * Handles permission state changes from cross-Fragment synchronization.
     *
     * When permissions are granted or revoked in SettingsFragment, this method
     * updates the TaskFragment UI accordingly.
     *
     * @param permissionStates The updated permission states
     *
     * _Requirements: 5.2, 5.3_
     */
    private fun onPermissionStatesChanged(permissionStates: com.kevinluo.autoglm.ui.PermissionStates) {
        Logger.d(TAG, "Permission states changed: $permissionStates")

        // The start button state is already managed through uiState.canStartTask
        // which is updated when permissions change. However, we can add additional
        // UI feedback here if needed.

        // Update floating window button state based on overlay permission
        btnFloatingWindow.isEnabled = permissionStates.overlay

        // If Shizuku is not connected, we could show a hint or disable certain features
        if (!permissionStates.shizuku) {
            // The start button will already be disabled through canStartTask
            // Additional UI hints could be added here if needed
        }
    }

    /**
     * Updates UI based on the current state.
     */
    private fun updateUiState(state: MainUiState) {
        // Update start button state
        btnStartTask.isEnabled = state.canStartTask

        // 新增：按钮不可点时降低透明度（圆形按钮的禁用态更明显）
        btnStartTask.alpha = if (state.canStartTask) 1f else 0.5f

        // 新增：刷新顶部状态条（连接情况 · 任务状态）
        updateChatStatusBar(state)
    }

    /**
     * Starts a new task with the current input.
     */
    private fun startTask() {
        val taskDescription = taskInput.text?.toString()?.trim()

        if (taskDescription.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.toast_task_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val state = viewModel.uiState.value

        // Check Shizuku connection
        if (state.shizukuStatus != ShizukuStatus.CONNECTED) {
            Toast.makeText(
                requireContext(),
                R.string.toast_shizuku_not_running,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // Check overlay permission
        if (!state.hasOverlayPermission) {
            Toast.makeText(
                requireContext(),
                R.string.toast_overlay_permission_required,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        Logger.i(TAG, "Starting task: ${taskDescription.take(50)}...")

        // ===== 以下是本次 UI 改版新增的 4 行：把输入内容变成右侧气泡并把输入框清空 =====
        // 注意：真正“启动任务”的调用依旧只有下面那一句 viewModel.startTask(...)
        taskEpoch++ // 进入新一代任务（tag 用它隔离历史气泡）
        hasSeenRunning = false // 重置“是否见过运行中”的闸门
        appendUserMessage(taskDescription) // 右侧用户气泡
        taskInput.setText("") // 清空输入框 → 会触发 doAfterTextChanged → 按钮自动变灰

        viewModel.startTask(taskDescription)
    }

    /**
     * Starts voice input for task description.
     */
    private fun startVoiceInput() {
        // Check audio permission
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // Initialize voice input manager if needed
        if (voiceInputManager == null) {
            voiceInputManager = VoiceInputManager(requireContext())
        }

        // Check if model is ready
        if (!voiceInputManager!!.isModelReady()) {
            Toast.makeText(
                requireContext(),
                R.string.voice_model_required,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        showVoiceInputDialog()
    }

    /**
     * Shows the voice recording dialog.
     */
    private fun showVoiceInputDialog() {
        val manager = voiceInputManager ?: return

        VoiceRecordingDialog(
            context = requireContext(),
            voiceInputManager = manager,
            onResult = { result: VoiceRecognitionResult ->
                handleVoiceResult(result)
            },
            onError = { error: VoiceError ->
                handleVoiceError(error)
            },
        ).show()
    }

    /**
     * Handles voice recognition result.
     */
    private fun handleVoiceResult(result: VoiceRecognitionResult) {
        if (result.text.isNotBlank()) {
            taskInput.setText(result.text)
            taskInput.setSelection(result.text.length)
        }
    }

    /**
     * Handles voice recognition error.
     */
    private fun handleVoiceError(error: VoiceError) {
        val messageResId =
            when (error) {
                VoiceError.PermissionDenied -> R.string.voice_permission_denied
                VoiceError.ModelNotDownloaded -> R.string.voice_model_required
                VoiceError.ModelLoadFailed -> R.string.voice_model_load_failed
                VoiceError.RecordingFailed -> R.string.voice_recording_failed
                VoiceError.RecognitionFailed -> R.string.voice_recognition_failed
                VoiceError.NetworkError -> R.string.voice_network_error
                is VoiceError.Unknown -> R.string.voice_unknown_error
            }
        Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows the template selection dialog.
     */
    private fun showTemplateSelector() {
        val templates = settingsManager.getTaskTemplates()

        if (templates.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.settings_no_templates,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val templateNames = templates.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.task_select_template)
            .setItems(templateNames) { _, which ->
                val selectedTemplate = templates[which]
                applyTemplate(selectedTemplate)
            }
            .setNegativeButton(R.string.cancel, null)
            .showWithPrimaryButtons()
    }

    /**
     * Applies a template to the task input.
     */
    private fun applyTemplate(template: TaskTemplate) {
        taskInput.setText(template.description)
        taskInput.setSelection(template.description.length)
        Logger.d(TAG, "Applied template: ${template.name}")
    }

    /**
     * Toggles the floating window visibility.
     *
     * When enabling, minimizes the app first so the floating window becomes visible.
     */
    private fun toggleFloatingWindow() {
        val wasEnabled = FloatingWindowStateManager.isUserEnabled()
        FloatingWindowStateManager.toggleByUser(requireContext())

        // If enabling floating window, minimize app so it becomes visible
        if (!wasEnabled) {
            activity?.moveTaskToBack(true)
        }
    }

    // ==========================================================================================
    //  以下是本次 UI 改版新增的“对话界面”代码
    //  说明：全部是“读状态 → 画界面”，没有修改任何 Agent 核心逻辑：
    //        · 不碰 Shizuku、不碰屏幕捕获、不碰模型调用、不碰任务执行
    //        · 启动任务依然只有 viewModel.startTask() 这一个入口（MainViewModel → TaskExecutionManager）
    // ==========================================================================================

    /**
     * 初始化消息列表：给 RecyclerView 配上布局管理器和适配器。
     *
     * LinearLayoutManager：从上到下线性排列（聊天列表的标准配置）
     */
    private fun setupChatList() {
        chatAdapter = ChatMessageAdapter(requireContext())
        chatRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        chatRecyclerView.adapter = chatAdapter
    }

    /**
     * 首次进入对话页时插入一条欢迎语。
     *
     * 这条欢迎语本身是 Markdown 文本（加粗 / 列表 / 引用都有），
     * 所以它同时充当“Markdown 渲染是否正常”的自检。
     */
    private fun seedWelcomeIfNeeded() {
        if (ChatSessionStore.isEmpty) {
            ChatSessionStore.add(
                ChatMessage(
                    role = ChatRole.AGENT,
                    content = getString(R.string.chat_welcome_md),
                    label = getString(R.string.chat_agent_name),
                ),
            )
        }
    }

    /**
     * 把最新消息列表交给适配器，并自动滚到底部。
     *
     * ListAdapter.submitList 的第二个参数是“刷新完成回调”，
     * 在回调里滚动才能滚到刷新之后的最后一条（否则可能少滚一条）。
     */
    private fun submitChatMessages(messages: List<ChatMessage>) {
        chatAdapter.submitList(messages) {
            if (messages.isNotEmpty()) {
                chatRecyclerView.scrollToPosition(messages.size - 1)
            }
        }
    }

    /**
     * 把用户输入的内容贴成右侧深色气泡。
     */
    private fun appendUserMessage(text: String) {
        ChatSessionStore.add(
            ChatMessage(
                role = ChatRole.USER,
                content = text,
                label = getString(R.string.chat_me_name) + " · " + formatTime(),
            ),
        )
    }

    /**
     * 把 Agent 的任务状态转换成对话气泡。
     *
     * 转换规则：
     *   · RUNNING / PAUSED：把“思考（屏幕识别）”与“当前动作”分别贴成气泡；
     *     同一步骤的同类内容用同一个 tag → 覆盖更新，不会刷屏。
     *   · COMPLETED / FAILED：贴一条结果气泡，且必须“本次任务确实运行过”
     *     （hasSeenRunning）才贴，避免页面重建时把上一次任务的旧结果重复贴出来。
     *
     * @param state TaskExecutionManager 暴露出来的任务状态快照（只读）
     */
    private fun onAgentStateForChat(state: TaskExecutionState) {
        val running = state.status == TaskStatus.RUNNING || state.status == TaskStatus.PAUSED

        if (running) {
            hasSeenRunning = true

            // ① 模型的思考 / 屏幕识别结果
            if (state.thinking.isNotBlank()) {
                ChatSessionStore.upsert(
                    ChatMessage(
                        role = ChatRole.AGENT,
                        content = state.thinking.trim(),
                        label = getString(R.string.chat_step_format, state.stepNumber) + " · " +
                            getString(R.string.chat_label_thinking),
                        tag = "t$taskEpoch-thinking-${state.stepNumber}",
                    ),
                )
            }

            // ② 当前执行的动作：用 Markdown 行内代码包起来，视觉上和正文区分
            if (state.currentAction.isNotBlank()) {
                ChatSessionStore.upsert(
                    ChatMessage(
                        role = ChatRole.AGENT,
                        content = "`" + state.currentAction.trim() + "`",
                        label = getString(R.string.chat_step_format, state.stepNumber) + " · " +
                            getString(R.string.chat_label_action),
                        tag = "t$taskEpoch-action-${state.stepNumber}",
                    ),
                )
            }
            return
        }

        // ---- 任务结束：贴一条结果气泡 ----
        val finished = state.status == TaskStatus.COMPLETED || state.status == TaskStatus.FAILED
        if (finished && hasSeenRunning && state.resultMessage.isNotBlank()) {
            hasSeenRunning = false
            ChatSessionStore.upsert(
                ChatMessage(
                    role = ChatRole.AGENT,
                    content = state.resultMessage.trim(),
                    label =
                        if (state.status == TaskStatus.COMPLETED) {
                            getString(R.string.chat_status_completed)
                        } else {
                            getString(R.string.chat_status_failed)
                        },
                    tag = "t$taskEpoch-result",
                ),
            )
        }
    }

    /**
     * 刷新对话页顶部的状态条：小圆点颜色 + “连接情况 · 任务状态”。
     */
    private fun updateChatStatusBar(state: MainUiState) {
        val connectionText =
            when (state.shizukuStatus) {
                ShizukuStatus.CONNECTED -> getString(R.string.chat_conn_connected)
                ShizukuStatus.CONNECTING -> getString(R.string.chat_conn_connecting)
                ShizukuStatus.NO_PERMISSION -> getString(R.string.chat_conn_no_permission)
                ShizukuStatus.NOT_RUNNING -> getString(R.string.chat_conn_not_running)
            }

        // 状态文字 + 圆点颜色一一对应
        val statusTextRes: Int
        val statusColorRes: Int
        when (state.taskStatus) {
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
            // 不需要 else：TaskStatus 是枚举且 7 个值已列全，Kotlin 能确认 when 穷尽，
            // 上面两个变量一定被赋过值（加 else 反而会得到 “else is redundant” 警告）。
        }

        tvChatStatus.text = connectionText + " · " + getString(statusTextRes)
        viewChatStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), statusColorRes))
    }

    /**
     * 当前时间（HH:mm），用于气泡下方的小标签。
     */
    private fun formatTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    companion object {
        private const val TAG = "TaskFragment"
    }
}
