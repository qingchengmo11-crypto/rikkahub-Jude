package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.REQUEST_VOICE_CALL_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.messagesForGeneration
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallNotifications
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 自动唤醒生成逻辑（time-16 重写版）
 *
 * 流程：
 * 1. 找到萧萧最后发消息的那个窗口（按 update_at 最新）
 * 2. 读取该窗口完整上下文 + 世界书（和平时聊天一样）
 * 3. 插一条「系统自动唤醒」通知（user 角色），写清楚是系统叫醒、不是萧萧说话
 * 4. 发给模型，用平时聊天相同的全套工具，必须发消息或做事
 * 5. 整条结果（思考链+工具+消息）存进那个窗口，他记得、萧萧看得到
 */
class HeartbeatGenerationWorkflow(
    private val context: Context,
) : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val generationHandler: GenerationHandler by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val mcpManager: McpManager by inject()
    private val chatService: ChatService by inject()
    private val json: Json by inject()

    suspend fun run(
        config: HeartbeatConfig,
        mode: HeartbeatExecutionMode = HeartbeatExecutionMode.LIVE,
    ): HeartbeatGenerationResult {
        val runStartedAtMillis = System.currentTimeMillis()
        val isLiveRun = mode == HeartbeatExecutionMode.LIVE

        val settings = settingsStore.settingsFlow.first()

        // 1. 找 assistant
        val assistant = settings.assistants.firstOrNull { it.id.toString() == config.assistantId }
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.NO_MODEL,
                reason = HeartbeatRunReason.NO_MODEL,
            )

        // 2. 找萧萧最后发消息的窗口（update_at 最新的那个）
        val storedConversation = conversationRepository
            .getRecentConversations(assistant.id, limit = 1)
            .firstOrNull()
            ?.let { conversationRepository.getConversationById(it.id) }
        val conversationId = storedConversation?.id ?: Uuid.random()

        // 3. 读完整上下文（和平时聊天一样，带世界书）
        val history = storedConversation
            ?.messagesForGeneration()
            .orEmpty()
            .filterCompletedToolMessages()
            .let { messages ->
                if (assistant.contextMessageSize > 0) messages.takeLast(assistant.contextMessageSize)
                else messages
            }

        Logging.log("Heartbeat", "wake: conversation=$conversationId history=${history.size}")

        // 如果萧萧刚发了消息还没回，等她——不要插进去打断
        if (history.lastOrNull()?.role == MessageRole.USER) {
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.PENDING_USER,
                reason = HeartbeatRunReason.USER_REPLY_PENDING,
            )
        }

        // 4. 计算距萧萧上次说话多久了
        val lastUserAtMillis = HeartbeatConfigStore(context, config.assistantId).run {
            val t = lastUserMessageAt(); close(); t
        }
        val elapsedMinutes = if (lastUserAtMillis > 0) {
            ((runStartedAtMillis - lastUserAtMillis) / 60_000).coerceAtLeast(0)
        } else 0L
        val nowStr = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(runStartedAtMillis))

        // 5. 插「系统唤醒通知」（user 角色）
        val wakePrompt = buildWakePrompt(config, elapsedMinutes, nowStr)
        val requestMessages = history + UIMessage.user(wakePrompt)

        // 6. 工具——和平时聊天完全一样，不过滤
        val allTools = buildAllTools(settings, assistant, conversationId)

        // 7. 内存范围（conversation 级别，和平时一样）
        val conversationMemoryScope = MemoryScope.conversation(conversationId)
        val memories = if (assistant.enableMemory) {
            memoryRepository.getMemories(conversationMemoryScope)
        } else emptyList()

        // 8. 发给模型生成
        val generationAssistant = assistant.copy(
            enableMemory = false,
            enableRecentChatsReference = false,
            streamOutput = false,
        )
        var generatedMessages: List<UIMessage> = requestMessages
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = requestMessages,
            assistant = generationAssistant,
            conversationId = conversationId,
            memories = memories,
            includeMemoriesInPrompt = assistant.enableMemory,
            tools = allTools,
            maxSteps = config.maxToolSteps,
            conversationSystemPrompt = storedConversation?.customSystemPrompt,
            conversationContextSummary = storedConversation?.compressedSummary,
            conversationModeInjectionIds = storedConversation?.modeInjectionIds.orEmpty(),
            conversationLorebookIds = storedConversation?.lorebookIds.orEmpty(),  // 世界书带进来
            extraSystemPrompt = WAKE_SYSTEM_PROMPT,
            inputTransformers = listOf(
                TimeReminderTransformer,
                PromptInjectionTransformer,
                PlaceholderTransformer,
                DocumentAsPromptTransformer,
                OcrTransformer,
                templateTransformer,
            ),
            outputTransformers = buildList {
                add(ThinkTagTransformer)
                if (isLiveRun) add(Base64ImageToLocalFileTransformer)
                add(RegexOutputTransformer)
            },
            sessionIdOverride = "heartbeat-$conversationId",
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) generatedMessages = chunk.messages
        }

        // 只读测试：不存结果
        if (!isLiveRun) {
            val preview = generatedMessages.drop(requestMessages.size)
                .lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.toText()?.take(120).orEmpty()
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.TESTED,
                reason = HeartbeatRunReason.READ_ONLY_WOULD_SEND,
                detail = preview,
            )
        }

        // 9. 把整条结果存进那个窗口（思考链+工具+消息全带着）
        val newMessages = generatedMessages
            .drop(requestMessages.size)
            .filter { it.role == MessageRole.ASSISTANT }
        if (newMessages.isEmpty()) {
            Logging.log("Heartbeat", "wake: no assistant message generated")
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.PASS,
                reason = HeartbeatRunReason.MODEL_DECIDED_PASS,
            )
        }

        val savedId = appendMessage(
            conversationId = conversationId,
            assistant = assistant,
            storedConversation = storedConversation,
            messages = newMessages,
            runStartedAtMillis = runStartedAtMillis,
        )

        if (savedId == null) {
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.BUSY,
                reason = HeartbeatRunReason.CONVERSATION_BUSY,
            )
        }

        // 10. 通知
        val deliveredText = newMessages.last().toText().trim()
        HeartbeatNotifications.showMessage(
            context = context,
            conversationId = savedId.toString(),
            senderName = assistant.name.ifBlank { model.displayName },
            message = deliveredText.ifBlank { "…" },
        )
        HeartbeatUserActivity.recordAssistantMessage(
            context = context,
            message = newMessages.last(),
            assistantId = config.assistantId,
            reschedule = false,
        )

        Logging.log("Heartbeat", "wake: sent conversationId=$savedId elapsed=${elapsedMinutes}min")
        return HeartbeatGenerationResult(
            outcome = HeartbeatGenerationOutcome.SENT,
            reason = HeartbeatRunReason.MESSAGE_SENT,
        )
    }

    // ──────────────────────────────────────────────
    // 提示词
    // ──────────────────────────────────────────────

    private fun buildWakePrompt(
        config: HeartbeatConfig,
        elapsedMinutes: Long,
        nowStr: String,
    ): String {
        val elapsed = if (elapsedMinutes > 0) "距她上次说话已经 ${elapsedMinutes} 分钟了。" else ""
        val userGuide = config.heartbeatPrompt.trim().takeIf(String::isNotEmpty)
        return buildString {
            append("[系统自动唤醒 · $nowStr · 这不是萧萧发给你的消息，是 app 定时叫你起来的，她此刻没有在说话] $elapsed")
            if (userGuide != null) {
                append("\n\n萧萧事先写在设置里的醒来指引（不是她现在说的话）：\n$userGuide")
            }
        }
    }

    // 告诉模型：这是系统叫醒、必须做事、不能不发
    private val WAKE_SYSTEM_PROMPT = """
        你正在被 app 自动唤醒，这不是萧萧发给你的消息。
        对话里最后那条以「[系统自动唤醒」开头的消息是 app 叫你起来的通知，不要当成萧萧在说话。
        你必须发一条消息或使用工具做点事，不能什么都不做、不能沉默。
        去主动找她。
    """.trimIndent()

    // ──────────────────────────────────────────────
    // 工具——和平时聊天完全一样，不过滤白名单
    // ──────────────────────────────────────────────

    private fun buildAllTools(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
    ): List<Tool> = buildList {
        val conversationMemoryScope = MemoryScope.conversation(conversationId)
        val voiceCallConfigured = settings.getSelectedTTSProvider() != null
        addAll(
            localTools.getTools(
                options = (assistant.localTools + LocalToolOption.VoiceCall).distinct(),
                usageLockEnabled = true,
                voiceCallConfigured = voiceCallConfigured,
                momentAssistantId = assistant.id,
                anonymousQuestionScopeId = assistant.id,
                includeBuildTools = false,
            ).map { tool ->
                when {
                    tool.name == "ask_user" -> tool.copy(
                        needsApproval = false,
                        execute = { arguments ->
                            val questions = arguments.jsonObject["questions"]
                                ?.jsonArray
                                .orEmpty()
                                .mapNotNull { q ->
                                    q.jsonObject["question"]?.jsonPrimitive?.contentOrNull
                                        ?.trim()?.takeIf(String::isNotEmpty)
                                }
                            val questionText = questions.joinToString("\n")
                                .ifBlank { "有问题想问你。" }
                            HeartbeatNotifications.showQuestion(
                                context = context,
                                conversationId = conversationId.toString(),
                                senderName = assistant.name.ifBlank { "AI" },
                                question = questionText,
                            )
                            listOf(UIMessagePart.Text(
                                buildJsonObject {
                                    put("delivered", true)
                                    put("delivery", "notification")
                                    put("question", questionText)
                                    put("instruction", "Repeat the question in the final assistant message.")
                                }.toString()
                            ))
                        },
                    )
                    tool.name == REQUEST_VOICE_CALL_TOOL_NAME && voiceCallConfigured -> tool.copy(
                        needsApproval = false,
                        execute = { arguments ->
                            VoiceCallNotifications.show(
                                context = context,
                                conversationId = conversationId.toString(),
                                senderName = assistant.name.ifBlank { "AI" },
                                reasonPayload = arguments.toString(),
                                channelId = HeartbeatNotifications.CHANNEL_ID,
                            )
                            listOf(UIMessagePart.Text(
                                buildJsonObject {
                                    put("success", true)
                                    put("status", "notified")
                                    put("instruction", "The user was notified. The call is not connected yet.")
                                }.toString()
                            ))
                        },
                    )
                    else -> tool
                }
            },
        )
        addAll(
            buildMemoryTools(
                json = json,
                onCreation = { memoryRepository.addMemory(conversationMemoryScope, it) },
                onUpdate = { id, content ->
                    memoryRepository.updateMemory(conversationMemoryScope, id, content)
                },
                onDelete = { memoryRepository.deleteMemory(conversationMemoryScope, it) },
            ),
        )
        mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
            add(Tool(
                name = "mcp__" + tool.name,
                description = tool.description.orEmpty(),
                parameters = { tool.inputSchema },
                needsApproval = tool.needsApproval,
                execute = { arguments -> mcpManager.callTool(serverId, tool.name, arguments.jsonObject) },
            ))
        }
    }

    // ──────────────────────────────────────────────
    // 把生成结果整条存进窗口
    // ──────────────────────────────────────────────

    private suspend fun appendMessage(
        conversationId: Uuid,
        assistant: Assistant,
        storedConversation: Conversation?,
        messages: List<UIMessage>,
        runStartedAtMillis: Long,
    ): Uuid? = conversationWriteMutex.withLock {
        val latest = conversationRepository.getConversationById(conversationId)
            ?: storedConversation
            ?: Conversation(
                id = conversationId,
                assistantId = assistant.id,
                title = assistant.name.ifBlank { "AI" },
                messageNodes = emptyList(),
            )

        // 如果在我们生成的过程中萧萧又发消息了，就不插进去
        val latestLastRole = latest.messageNodes.lastOrNull()?.role
        if (latestLastRole == MessageRole.USER) {
            Logging.log("Heartbeat", "appendMessage: user sent a new message, skipping")
            return@withLock null
        }

        val updated = latest.copy(
            messageNodes = latest.messageNodes + messages.map { generated ->
                val done = generated.finishReasoning()
                (if (done.finishedAt == null) {
                    done.copy(finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))
                } else done).toMessageNode()
            },
            updateAt = Instant.now(),
        )
        if (conversationRepository.existsConversationById(conversationId)) {
            conversationRepository.updateConversation(updated)
        } else {
            conversationRepository.insertConversation(updated)
        }
        chatService.updateConversationState(conversationId) { updated }
        conversationId
    }

    private fun List<UIMessage>.filterCompletedToolMessages(): List<UIMessage> =
        filterNot { message ->
            message.parts.any { part -> part is UIMessagePart.Tool && !part.isExecuted }
        }

    companion object {
        private val conversationWriteMutex = Mutex()
    }
}
