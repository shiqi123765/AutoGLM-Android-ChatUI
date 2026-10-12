package com.kevinluo.autoglm.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * ChatSessionStore —— 对话记录的“内存仓库”（纯 UI）
 * ============================================================================
 * 为什么需要它？
 *   原始工程用底部 Tab 切换页面，切换时 Fragment 会被销毁重建，
 *   如果消息只存在 Fragment 里，切到“设置”再切回来，聊天记录就没了。
 *   所以把消息放在这个全局单例里，Activity/Fragment 重建后依然能看到历史。
 *
 * 为什么不用数据库？
 *   本次任务只改 UI，不引入新的存储结构。这个仓库只活在进程内存中，
 *   杀掉 App 后清空——这符合“对话界面”最轻量的预期，也完全不碰原有 History 数据库。
 *
 * 注意：这是一个“只读给别人、只写给自己”的 StateFlow 容器：
 *   · 外部（Fragment / Adapter）只能通过 [messages] 订阅最新列表；
 *   · 想改内容必须调用下面的 add / upsert / clear 方法。
 */
object ChatSessionStore {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** 对外暴露的消息列表（响应式，列表一变，UI 自动刷新）。 */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 当前是否一条消息都没有。 */
    val isEmpty: Boolean
        get() = _messages.value.isEmpty()

    /**
     * 追加一条消息到列表末尾。
     */
    fun add(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    /**
     * “有则覆盖、无则追加”。
     *
     * 用途：AI 的思考文字是一段段吐出来的，同一个 [ChatMessage.tag]
     * 应该更新同一个气泡，而不是每来一段就新建一个气泡。
     *
     * @param message 新消息；若已存在相同 tag 的消息，会保留原来的 id（避免整屏闪动）
     */
    fun upsert(message: ChatMessage) {
        if (message.tag.isEmpty()) {
            add(message)
            return
        }

        val current = _messages.value
        val index = current.indexOfLast { it.tag == message.tag }
        _messages.value =
            if (index >= 0) {
                // 复制出一个新列表再替换，保证 StateFlow 能检测到“内容变了”
                current.toMutableList().also { list ->
                    list[index] = message.copy(id = current[index].id)
                }
            } else {
                current + message
            }
    }

    /** 清空全部对话（“清空对话”按钮用）。 */
    fun clear() {
        _messages.value = emptyList()
    }
}
