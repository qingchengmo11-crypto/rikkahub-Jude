package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant

private val WEEK_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 时间提醒注入转换器
 *
 * 在每一条用户消息开头加一行 [2026-10-05 周一 14:32 · 距上一条消息5分钟]，
 * 让 AI 每次都知道对方是几点发的、隔了多久。只改发给模型的内容，不改聊天界面里显示的消息。
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        return applyTimeReminder(messages)
    }
}

internal fun applyTimeReminder(messages: List<UIMessage>): List<UIMessage> {
    val tz = TimeZone.currentSystemDefault()
    val zone = java.time.ZoneId.systemDefault()

    var prevUserMillis: Long? = null
    return messages.map { message ->
        if (message.role != MessageRole.USER) return@map message
        val instant = message.createdAt.toInstant(tz)
        val nowMillis = instant.toEpochMilliseconds()
        val stamp = buildStamp(instant.toJavaInstant().atZone(zone), prevUserMillis?.let { nowMillis - it })
        prevUserMillis = nowMillis
        message.copy(parts = prependStamp(message.parts, stamp))
    }
}

private fun prependStamp(parts: List<UIMessagePart>, stamp: String): List<UIMessagePart> {
    val firstText = parts.indexOfFirst { it is UIMessagePart.Text }
    if (firstText < 0) return listOf(UIMessagePart.Text(stamp)) + parts
    val text = parts[firstText] as UIMessagePart.Text
    return parts.toMutableList().also { it[firstText] = text.copy(text = "$stamp\n${text.text}") }
}

private fun buildStamp(time: ZonedDateTime, gapMillis: Long?): String {
    var s = "${time.format(DATE_FORMAT)} ${WEEK_NAMES[time.dayOfWeek.value - 1]} ${time.format(TIME_FORMAT)}"
    if (gapMillis != null) {
        val mins = Math.round(gapMillis / 60000.0)
        s += when {
            mins < 2 -> " · 刚刚还在聊"
            mins < 60 -> " · 距上一条消息${mins}分钟"
            mins < 48 * 60 -> " · 距上一条消息${Math.round(mins / 60.0)}小时"
            else -> " · 已经${Math.round(mins / 1440.0)}天没聊了"
        }
    }
    return "[$s]"
}
