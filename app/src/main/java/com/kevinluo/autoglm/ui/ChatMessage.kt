package com.kevinluo.autoglm.ui

/**
 * ============================================================================
 * ChatMessage —— 对话消息的数据模型（纯 UI，不涉及任何 Agent 逻辑）
 * ============================================================================
 * 零基础说明：
 *   data class 是 Kotlin 的“数据类”，编译器会自动帮你生成
 *   equals / hashCode / toString / copy，非常适合用来描述一条数据。
 *
 * @property id        每条消息的唯一编号。RecyclerView 靠它判断“是不是同一条消息”，
 *                     用它做列表局部刷新，避免整屏闪烁。
 * @property role      消息类型：用户 / AI / 系统提示。
 * @property content   消息正文。AI 消息里是 Markdown 文本。
 * @property label     气泡下方的小字，例如 “AutoGLM Agent · 第 2 步”。
 * @property tag       去重标记。同一个 tag 的消息会被“覆盖更新”而不是重复追加，
 *                     这样模型流式输出思考过程时不会刷出一堆气泡。
 * @property timeMillis 时间戳，用来显示 HH:mm。
 * @property asMarkdown 是否按 Markdown 渲染（AI 消息 = true，系统提示 = false）。
 */
data class ChatMessage(
    val id: Long = nextId(),
    val role: ChatRole,
    val content: String,
    val label: String = "",
    val tag: String = "",
    val timeMillis: Long = System.currentTimeMillis(),
    val asMarkdown: Boolean = role == ChatRole.AGENT,
) {
    companion object {
        /** 自增计数器，保证每条消息 id 唯一。AtomicLong 保证多线程下也不会撞号。 */
        private val counter = java.util.concurrent.atomic.AtomicLong(1L)

        private fun nextId(): Long = counter.getAndIncrement()
    }
}

/**
 * 消息角色。
 *
 * · [USER]   用户自己发的（靠右、深色气泡）
 * · [AGENT]  AI 回复（靠左、浅灰气泡、支持 Markdown）
 * · [SYSTEM] 系统提示（靠左、灰色小字，例如“任务已开始”）
 */
enum class ChatRole {
    USER,
    AGENT,
    SYSTEM,
}
