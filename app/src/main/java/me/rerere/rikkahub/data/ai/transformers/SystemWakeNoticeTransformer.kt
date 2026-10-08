package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 自动唤醒时发出的那条助手消息上，记着当时 app 给他看的「系统唤醒通知」原文。 */
const val SYSTEM_WAKE_NOTICE_METADATA_KEY = "system_wake_notice"

/**
 * 系统唤醒通知还原转换器
 *
 * 自动唤醒时 app 给模型看的那条「[系统自动唤醒 …」通知不存成聊天里的一条消息（不然会像是用户说的话），
 * 而是记在他醒来发的那条消息上。之后每次把上下文发给模型时，在那条消息前面把通知放回去，
 * 这样他回头看就知道那一次是系统叫醒的、不是用户叫的。只改发给模型的内容，不改聊天界面。
 */
object SystemWakeNoticeTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = restoreSystemWakeNotices(messages)
}

internal fun restoreSystemWakeNotices(messages: List<UIMessage>): List<UIMessage> {
    if (messages.none { it.systemWakeNotice() != null }) return messages
    return buildList {
        messages.forEach { message ->
            message.systemWakeNotice()?.let { notice ->
                add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(notice))))
            }
            add(message)
        }
    }
}

fun UIMessage.systemWakeNotice(): String? {
    if (role != MessageRole.ASSISTANT) return null
    return parts.firstNotNullOfOrNull { part ->
        part.metadata?.get(SYSTEM_WAKE_NOTICE_METADATA_KEY)
            ?.let { it as? JsonPrimitive }?.contentOrNull?.takeIf(String::isNotBlank)
    }
}

/** 把通知原文记到这条消息的第一个能带标记的部分上；没有能带的就原样返回。 */
fun UIMessage.withSystemWakeNotice(notice: String): UIMessage {
    var tagged = false
    val newParts = parts.map { part ->
        if (tagged) return@map part
        val metadata = JsonObject(part.metadata.orEmpty() + (SYSTEM_WAKE_NOTICE_METADATA_KEY to JsonPrimitive(notice)))
        when (part) {
            is UIMessagePart.Text -> part.copy(metadata = metadata).also { tagged = true }
            is UIMessagePart.Reasoning -> part.copy(metadata = metadata).also { tagged = true }
            is UIMessagePart.Tool -> part.copy(metadata = metadata).also { tagged = true }
            else -> part
        }
    }
    return if (tagged) copy(parts = newParts) else this
}
