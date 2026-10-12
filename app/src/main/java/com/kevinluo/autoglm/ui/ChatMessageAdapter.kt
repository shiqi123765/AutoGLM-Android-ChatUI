package com.kevinluo.autoglm.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kevinluo.autoglm.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * ============================================================================
 * 差量刷新规则（文件级私有常量）
 * ============================================================================
 * 放在类外面（而不是 companion object 里）是一个刻意选择：
 *   · 父类构造调用 `ListAdapter<...>(CHAT_DIFF_CALLBACK)` 里引用本文件内的顶层常量，
 *     在任何 Kotlin 版本上都绝对安全，不受“类初始化顺序”影响。
 *
 *   areItemsTheSame   —— “是不是同一条消息”（比较唯一 id）
 *   areContentsTheSame—— “内容有没有变化”（data class 自动生成的 equals）
 */
private val CHAT_DIFF_CALLBACK =
    object : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }

/**
 * ============================================================================
 * ChatMessageAdapter —— 对话列表适配器（纯 UI）
 * ============================================================================
 * 零基础说明：
 *   1. RecyclerView（列表控件）本身不知道“怎么把一条数据画到屏幕上”，
 *      这个 Adapter 就是它的“翻译官”：把 [ChatMessage] 翻译成 item_message.xml 的界面。
 *   2. 继承 [ListAdapter]（而不是原始的 RecyclerView.Adapter），好处是它内置了
 *      DiffUtil 差量刷新——新旧列表对比，只重画变化的那几条，界面不闪。
 *   3. AI 消息用 Markwon 做 Markdown 渲染（代码块 / 列表 / 加粗 / 链接 / 表格）。
 */
class ChatMessageAdapter(private val context: Context) :
    ListAdapter<ChatMessage, ChatMessageViewHolder>(CHAT_DIFF_CALLBACK) {

    /**
     * Markwon：Android 上最常用的 Markdown 渲染库。
     *
     * 一次构建、反复使用（构建有开销，千万不要在 onBindViewHolder 里每次都建）。
     * 这里配置了：
     *   · LinkifyPlugin       → 自动把 http:// 识别成可点链接
     *   · TablePlugin         → 支持 Markdown 表格
     *   · StrikethroughPlugin → 支持 ~~删除线~~
     *   · configureTheme      → 行内代码 / 代码块使用我们自己的配色，
     *                           这样浅色、深色模式下代码块都不会“看不见”
     *
     * 提示：如果将来升级 Markwon 后 configureTheme 里的方法名有变化，
     *       只需删掉 .usePlugin(...) 这一段，其余渲染功能不受影响。
     */
    private val markwon: Markwon =
        Markwon
            .builder(context)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        val codeBackground = ContextCompat.getColor(context, R.color.chat_code_background)
                        val codeText = ContextCompat.getColor(context, R.color.chat_code_text)
                        val linkColor = ContextCompat.getColor(context, R.color.chat_link)

                        builder
                            // 行内代码：`like this`
                            .codeTextColor(codeText)
                            .codeBackgroundColor(codeBackground)
                            // 代码块：``` 围起来的整段代码
                            .codeBlockTextColor(codeText)
                            .codeBlockBackgroundColor(codeBackground)
                            // 代码统一等宽字体，符号对齐才好看
                            .codeTypeface(Typeface.MONOSPACE)
                            // 链接颜色
                            .linkColor(linkColor)
                    }
                },
            ).build()

    /**
     * 创建“空的气泡壳子”（只做一次，滚动时反复复用）。
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return ChatMessageViewHolder(view, context, markwon)
    }

    /**
     * 把第 position 条数据填进壳子里。
     */
    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/**
 * ============================================================================
 * ChatMessageViewHolder —— 一条消息对应的所有控件引用（纯 UI）
 * ============================================================================
 * 在 onCreateViewHolder 里 findViewById 一次，之后滚动时复用，性能好。
 * 写成文件级类（而不是 inner class）是为了让泛型声明最简单、最不容易出错。
 *
 * @param itemView item_message.xml 膨胀出来的视图
 * @param context  用来读取颜色资源
 * @param markwon  Markdown 渲染器（由 Adapter 传入，避免重复构建）
 */
class ChatMessageViewHolder(
    itemView: View,
    private val context: Context,
    private val markwon: Markwon,
) : RecyclerView.ViewHolder(itemView) {
    private val aiContainer: View = itemView.findViewById(R.id.aiContainer)
    private val aiAvatar: TextView = itemView.findViewById(R.id.aiAvatar)
    private val aiText: TextView = itemView.findViewById(R.id.aiText)
    private val aiLabel: TextView = itemView.findViewById(R.id.aiLabel)

    private val userContainer: View = itemView.findViewById(R.id.userContainer)
    private val userText: TextView = itemView.findViewById(R.id.userText)
    private val userLabel: TextView = itemView.findViewById(R.id.userLabel)

    /**
     * 把一条消息画到界面上。
     */
    fun bind(message: ChatMessage) {
        // 第一步：先决定这一行是“左侧 AI”还是“右侧用户”
        val isUser = message.role == ChatRole.USER
        aiContainer.isVisible = !isUser
        userContainer.isVisible = isUser

        if (isUser) {
            // ---------- 用户消息：纯文本，靠右，深色气泡 ----------
            userText.text = message.content
            userLabel.isVisible = message.label.isNotEmpty()
            userLabel.text = message.label
            return
        }

        // ---------- AI / 系统消息：靠左 ----------
        // 注意：ViewHolder 会被复用，所以下面每一项都必须显式重设，
        // 不能假设“上次是什么样式”，否则快速滚动时会出现样式串味。
        aiLabel.isVisible = message.label.isNotEmpty()
        aiLabel.text = message.label

        if (message.role == ChatRole.SYSTEM) {
            // 系统提示：不显示头像，去掉气泡背景，用小号灰字，安静地待在左边
            aiAvatar.isVisible = false
            aiText.background = null
            aiText.setTextColor(ContextCompat.getColor(context, R.color.chat_system_text))
            aiText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            aiText.text = message.content
            return
        }

        // 正常 AI 消息：恢复气泡背景与正文样式
        aiAvatar.isVisible = true
        aiText.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_ai_bubble)
        aiText.setTextColor(ContextCompat.getColor(context, R.color.chat_ai_text))
        aiText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)

        if (message.asMarkdown) {
            // 关键一步：把 Markdown 文本交给 Markwon 渲染后显示
            // （setMarkdown 内部完成：解析 → 转成 Span → setText）
            try {
                markwon.setMarkdown(aiText, message.content)
            } catch (e: Exception) {
                // 万一某段 Markdown 过于畸形导致解析异常，
                // 退回纯文本显示，绝不让界面崩掉
                aiText.text = message.content
            }
        } else {
            aiText.text = message.content
        }
    }
}
