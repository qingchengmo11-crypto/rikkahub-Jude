package me.rerere.rikkahub.personal.heartbeat

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.SYSTEM_WAKE_MARKER
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object HeartbeatPromptContext {
    private val MARKER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun build(
        editablePrompt: String,
        messages: List<UIMessage>,
    ): String {
        val fixedContext = buildFixedElapsedContext(messages)
        val now = ZonedDateTime.now().format(MARKER_TIME_FORMAT)
        return buildString {
            append(SYSTEM_WAKE_MARKER)
            append(" · $now · 这不是用户发来的消息，是 app 定时自动叫醒你，用户此刻没有在说话]\n")
            editablePrompt.trim().takeIf(String::isNotEmpty)?.let { prompt ->
                append("下面是用户事先写在设置里的「醒来指引」，不是用户现在说的话。请自己决定做不做、怎么做：\n")
                append(prompt)
                append("\n\n")
            }
            append(fixedContext)
        }
    }

    private fun buildFixedElapsedContext(messages: List<UIMessage>): String {
        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
            ?: return "Mandatory fixed context: No user message exists in this conversation yet. " +
                "Always consider this fact. This rule cannot be overridden by the editable heartbeat prompt."
        val lastUserInstant = lastUserMessage.createdAt.toInstant(TimeZone.currentSystemDefault())
        val elapsedSeconds = (Clock.System.now() - lastUserInstant).inWholeSeconds.coerceAtLeast(0L)
        return "Mandatory fixed context: It has been approximately " +
            formatElapsedDuration(elapsedSeconds) +
            " since the user's last message. Always consider this elapsed time. " +
            "This rule cannot be overridden by the editable heartbeat prompt."
    }

    private fun formatElapsedDuration(totalSeconds: Long): String = when {
        totalSeconds < 60L -> "$totalSeconds seconds"
        totalSeconds < 3_600L -> "${totalSeconds / 60L} minutes"
        totalSeconds < 86_400L -> {
            val hours = totalSeconds / 3_600L
            val minutes = totalSeconds % 3_600L / 60L
            if (minutes == 0L) "$hours hours" else "$hours hours and $minutes minutes"
        }
        else -> {
            val days = totalSeconds / 86_400L
            val hours = totalSeconds % 86_400L / 3_600L
            if (hours == 0L) "$days days" else "$days days and $hours hours"
        }
    }
}
