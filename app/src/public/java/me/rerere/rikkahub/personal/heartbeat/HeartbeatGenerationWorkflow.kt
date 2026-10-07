package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.UIMessagePart
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
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
    private val deliveryGuard by lazy { HeartbeatDeliveryGuard(context, chatService) }
    private val privateExperienceStore = HeartbeatPrivateExperienceStore(context)
    suspend fun run(
        config: HeartbeatConfig,
        mode: HeartbeatExecutionMode = HeartbeatExecutionMode.LIVE,
    ): HeartbeatGenerationResult {
        val runStartedAtMillis = System.currentTimeMillis()
        val isLiveRun = mode == HeartbeatExecutionMode.LIVE

        val settings = settingsStore.settingsFlow.first()
        val scheduleStore = HeartbeatScheduleStore(context)
        val autonomousPlan = scheduleStore.readForAssistant(config.assistantId)
            ?.takeIf { isLiveRun }
            ?.takeIf { plan -> plan.wakeAtMillis.minOrNull()?.let { it <= runStartedAtMillis } == true }
        val assistant = settings.assistants
            .firstOrNull { it.id.toString() == autonomousPlan?.assistantId }
            ?: settings.assistants.firstOrNull { it.id.toString() == config.assistantId }
            ?: settings.getCurrentAssistant()
        if (isLiveRun && autonomousPlan != null) {
            scheduleStore.consumeDue(
                assistantId = config.assistantId,
                nowMillis = runStartedAtMillis,
            )
        }
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.NO_MODEL,
                reason = HeartbeatRunReason.NO_MODEL,
            )

        val storedConversation = conversationRepository
            .getRecentConversations(assistant.id, limit = 1)
            .firstOrNull()
            ?.let { conversationRepository.getConversationById(it.id) }
        val conversationId = storedConversation?.id ?: Uuid.random()

        deliveryGuard.beforeGeneration(
            conversationId = conversationId,
            expectedAssistantId = assistant.id.toString(),
        )?.let { block ->
            recordExperience(mode, conversationId, block.name)
            return block.toGenerationResult()
        }
        val completedConversationMessages = storedConversation
            ?.currentMessages
            .orEmpty()
            .filterCompletedToolMessages()
        val generationConversationMessages = storedConversation
            ?.messagesForGeneration()
            .orEmpty()
            .filterCompletedToolMessages()
        val history = generationConversationMessages
            .let { messages ->
                if (assistant.contextMessageSize > 0) {
                    messages.takeLast(assistant.contextMessageSize)
                } else {
                    messages
                }
            }
        Logging.log(
            tag = "Heartbeat",
            message = "context=all:${completedConversationMessages.size} generation:${generationConversationMessages.size} " +
                "history:${history.size} compressed:${storedConversation?.activeCompressedMessageNodeIds?.size ?: 0} " +
                "summary:${!storedConversation?.compressedSummary.isNullOrBlank()}",
        )
        if (history.lastOrNull()?.role == MessageRole.USER) {
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.PENDING_USER,
                reason = HeartbeatRunReason.USER_REPLY_PENDING,
            )
        }

        val goodNightActive = if (isLiveRun) {
            updateGoodNightMode(completedConversationMessages, config.assistantId)
        } else {
            readGoodNightMode(config.assistantId)
        }

        if (isLiveRun) updateLastUserMessageAt(completedConversationMessages, config.assistantId)
        val desireState = currentDesireState(
            runStartedAtMillis,
            persist = isLiveRun,
            assistantId = config.assistantId,
        )

        val prompt = UIMessage.user(
            HeartbeatPromptContext.build(config.heartbeatPrompt, completedConversationMessages),
        )
        val requestMessages = history + prompt
        val allAvailableTools = buildAvailableTools(settings, assistant, conversationId)
        val allowedTools = HeartbeatToolPolicy(config).filter(allAvailableTools, mode)
        // Heartbeat is bound to the conversation it is about; assistant/global scopes
        // would reintroduce cross-window memory leakage during background runs.
        val conversationMemoryScope = MemoryScope.conversation(conversationId)
        val memories = if (assistant.enableMemory) {
            memoryRepository.getMemories(conversationMemoryScope)
        } else {
            emptyList()
        }
        val generationAssistant = assistant.copy(
            enableMemory = false,
            enableRecentChatsReference = false,
            streamOutput = false,
        )
        var generatedMessages: List<UIMessage> = requestMessages
        // 每次醒来一定要来找她：生成这件事写成一个小函数，万一第一次什么都没写，可以再来一次
        suspend fun generateWith(input: List<UIMessage>): List<UIMessage> {
            var produced: List<UIMessage> = input
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = input,
                assistant = generationAssistant,
                conversationId = conversationId,
                memories = memories,
                includeMemoriesInPrompt = assistant.enableMemory,
                tools = allowedTools,
                maxSteps = config.maxToolSteps,
                conversationSystemPrompt = storedConversation?.customSystemPrompt,
                conversationContextSummary = storedConversation?.compressedSummary,
                conversationModeInjectionIds = storedConversation?.modeInjectionIds.orEmpty(),
                conversationLorebookIds = storedConversation?.lorebookIds.orEmpty(),
                extraSystemPrompt = if (goodNightActive) GOOD_NIGHT_SYSTEM_PROMPT else HEARTBEAT_SYSTEM_PROMPT,
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
                    // Base64 extraction writes local files, so it is excluded from diagnostic runs.
                    if (isLiveRun) add(Base64ImageToLocalFileTransformer)
                    add(RegexOutputTransformer)
                },
                sessionIdOverride = "heartbeat-$conversationId",
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) produced = chunk.messages
            }
            return produced
        }
        var baseCount = requestMessages.size
        generatedMessages = generateWith(requestMessages)
        fun executedToolNames(): List<String> = generatedMessages.drop(baseCount)
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isExecuted }
            .map { it.toolName }
        fun lastAssistantText(): String = generatedMessages.drop(baseCount)
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            .orEmpty()
            .replace(PASS_MARKER, "")
            .trim()
        if (isLiveRun && !goodNightActive && lastAssistantText().isBlank() && executedToolNames().isEmpty()) {
            // 第一次什么都没写、也没做事：催他一次，这一次必须找她
            val nudge = UIMessage.user(WAKE_MUST_ACT_NUDGE)
            val retryInput = requestMessages + nudge
            generatedMessages = generateWith(retryInput)
            baseCount = retryInput.size
        }

        deliveryGuard.beforeDelivery(
            conversationId = conversationId,
            runStartedAtMillis = runStartedAtMillis,
            expectedAssistantId = assistant.id.toString(),
        )?.let { block ->
            recordExperience(mode, conversationId, block.name, desireState)
            return block.toGenerationResult()
        }

        if (isLiveRun && goodNightActive) {
            updateGoodNightNoUsageCounter(generatedMessages, config.assistantId)
        }

        // 每次醒来都是要来找她、或者做事的，没有「无需发送」这个选项；[PASS] 当成没写
        val generatedText = lastAssistantText()
        if (generatedText.isBlank()) {
            recordExperience(mode, conversationId, "NO_CONTENT", desireState)
            val toolsUsed = executedToolNames().distinct()
            // 没有文字但做过事：把做事的记录悄悄存进聊天（不弹通知），她看得到他醒过、干了什么
            if (isLiveRun && toolsUsed.isNotEmpty()) {
                val quiet = generatedMessages
                    .drop(baseCount)
                    .filter { it.role == MessageRole.ASSISTANT }
                    .map { m ->
                        m.copy(
                            parts = m.parts
                                .map { p -> if (p is UIMessagePart.Text) p.copy(text = p.text.replace(PASS_MARKER, "").trim()) else p }
                                .filterNot { p -> p is UIMessagePart.Text && p.text.isBlank() },
                        )
                    }
                    .filter { it.parts.isNotEmpty() }
                if (quiet.isNotEmpty()) {
                    appendMessage(
                        conversationId = conversationId,
                        assistant = assistant,
                        storedConversation = storedConversation,
                        messages = quiet,
                        runStartedAtMillis = runStartedAtMillis,
                    )
                }
            }
            return HeartbeatGenerationResult(
                outcome = if (isLiveRun) {
                    HeartbeatGenerationOutcome.PASS
                } else {
                    HeartbeatGenerationOutcome.TESTED
                },
                reason = HeartbeatRunReason.NO_CONTENT,
                detail = if (toolsUsed.isEmpty()) {
                    "模型两次都没有写出任何文字，也没有用工具"
                } else {
                    "没有文字，但用了工具：" + toolsUsed.joinToString("、") + "（记录已存进聊天）"
                },
            )
        }

        val decision = HeartbeatDecisionEngine(
            desireState = desireState,
            sentTexts = HeartbeatPrivateExperienceStore(context, config.assistantId)
                .recentDeliveredTexts(),
        ).evaluate(generatedText)
        if (!decision.shouldDeliver) {
            // 以前这里会把话丢掉；现在醒来写了就一定发，只记一笔
            recordExperience(
                mode = mode,
                conversationId = conversationId,
                outcome = "LOW_SCORE_BUT_SENT",
                state = desireState,
                decision = decision,
                text = generatedText,
            )
        }

        if (!isLiveRun) {
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.TESTED,
                reason = HeartbeatRunReason.READ_ONLY_WOULD_SEND,
                detail = generatedText.take(120),
            )
        }

        // 整条原样存下来（思考链、调用的工具、token 都带着），和平时聊天一样；
        // 没有生成出消息才退回只存文字。
        val generatedAssistantMessages = generatedMessages
            .drop(baseCount)
            .filter { it.role == MessageRole.ASSISTANT }
            .map { m ->
                m.copy(
                    parts = m.parts.map { p -> if (p is UIMessagePart.Text) p.copy(text = p.text.replace(PASS_MARKER, "").trim()) else p },
                )
            }
        val messagesToSave = generatedAssistantMessages.ifEmpty {
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(generatedText)),
                ),
            )
        }
        val message = messagesToSave.last()
        val savedConversationId = appendMessage(
            conversationId = conversationId,
            assistant = assistant,
            storedConversation = storedConversation,
            messages = messagesToSave,
            runStartedAtMillis = runStartedAtMillis,
        ) ?: run {
            recordExperience(
                mode = mode,
                conversationId = conversationId,
                outcome = "DELIVERY_BLOCKED",
                state = desireState,
                decision = decision,
            )
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.BUSY,
                reason = HeartbeatRunReason.CONVERSATION_BUSY,
            )
        }

        HeartbeatUserActivity.recordAssistantMessage(
            context = context,
            message = message,
            assistantId = config.assistantId,
            reschedule = false,
        )

        recordExperience(
            mode = mode,
            conversationId = conversationId,
            outcome = HeartbeatPrivateExperienceStore.OUTCOME_SENT,
            state = desireState,
            decision = decision,
            text = generatedText,
        )
        updateDesireAfterDelivery(config.assistantId)

        HeartbeatNotifications.showMessage(
            context = context,
            conversationId = savedConversationId.toString(),
            senderName = assistant.name.ifBlank { model.displayName },
            message = generatedText,
        )
        return HeartbeatGenerationResult(
            outcome = HeartbeatGenerationOutcome.SENT,
            reason = HeartbeatRunReason.MESSAGE_SENT,
        )
    }

    private fun currentDesireState(
        nowMillis: Long,
        persist: Boolean,
        assistantId: String?,
    ): HeartbeatDesireState {
        val store = HeartbeatConfigStore(context, assistantId)
        return try {
            store.readDesireState().advance(nowMillis).also { state ->
                if (persist) store.recordDesireState(state)
            }
        } finally {
            store.close()
        }
    }

    private fun updateDesireAfterDelivery(assistantId: String?) {
        val nowMillis = System.currentTimeMillis()
        val store = HeartbeatConfigStore(context, assistantId)
        try {
            store.recordDesireState(store.readDesireState().afterDelivery(nowMillis))
        } finally {
            store.close()
        }
    }

    private fun recordExperience(
        mode: HeartbeatExecutionMode,
        conversationId: Uuid?,
        outcome: String,
        state: HeartbeatDesireState? = null,
        decision: HeartbeatThoughtDecision? = null,
        text: String? = null,
    ) {
        if (mode != HeartbeatExecutionMode.LIVE) return
        privateExperienceStore.append(
            HeartbeatPrivateExperience(
                createdAtMillis = System.currentTimeMillis(),
                conversationId = conversationId?.toString(),
                outcome = outcome,
                pressure = decision?.pressure ?: state?.pressure(),
                score = decision?.score,
                text = text?.take(1_000),
            ),
        )
    }

    private fun readGoodNightMode(assistantId: String?): Boolean {
        val store = HeartbeatConfigStore(context, assistantId)
        return try {
            store.isGoodNightActive()
        } finally {
            store.close()
        }
    }

    private fun formatScore(value: Double): String = "%.2f".format(java.util.Locale.US, value)


    private fun updateGoodNightMode(messages: List<UIMessage>, assistantId: String?): Boolean {
        val store = HeartbeatConfigStore(context, assistantId)
        try {
            val lastUserText = messages
                .lastOrNull { it.role == MessageRole.USER }
                ?.toText()
                ?.trim()
            val wasActive = store.isGoodNightActive()
            val active = when {
                lastUserText?.contains("晚安") == true -> {
                    store.setGoodNightActive(true)
                    store.setGoodNightNoUsageRuns(0)
                    true
                }
                wasActive && lastUserText != null -> {
                    // 用户已重新发言且不是晚安：视为醒来，退出晚安模式
                    store.setGoodNightActive(false)
                    store.setGoodNightNoUsageRuns(0)
                    false
                }
                else -> wasActive
            }
            Logging.log(
                tag = "Heartbeat",
                message = "goodnight=active:$active wasActive:$wasActive",
            )
            return active
        } finally {
            store.close()
        }
    }

    private fun updateLastUserMessageAt(messages: List<UIMessage>, assistantId: String?) {
        val lastUserAtMillis = messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.createdAt
            ?.toInstant(TimeZone.currentSystemDefault())
            ?.toEpochMilliseconds()
        if (lastUserAtMillis != null) {
            val store = HeartbeatConfigStore(context, assistantId)
            try {
                store.setLastUserMessageAt(lastUserAtMillis)
            } finally {
                store.close()
            }
        }
    }
    private fun updateGoodNightNoUsageCounter(
        generatedMessages: List<UIMessage>,
        assistantId: String?,
    ) {
        val lockedSomething = generatedMessages.any { message ->
            message.parts.any { part ->
                part is UIMessagePart.Tool &&
                    part.toolName == "usage_lock_control" &&
                    part.isExecuted &&
                    part.inputAsJson().jsonObject["action"]?.jsonPrimitive?.contentOrNull == "lock"
            }
        }
        val store = HeartbeatConfigStore(context, assistantId)
        try {
            if (lockedSomething) {
                store.setGoodNightNoUsageRuns(0)
                Logging.log(tag = "Heartbeat", message = "goodnight=usage-found")
                return
            }
            val runs = store.goodNightNoUsageRuns() + 1
            if (runs >= GOOD_NIGHT_MAX_NO_USAGE_RUNS) {
                store.setGoodNightActive(false)
                store.setGoodNightNoUsageRuns(0)
                Logging.log(tag = "Heartbeat", message = "goodnight=closed no-usage-runs=$runs")
            } else {
                store.setGoodNightNoUsageRuns(runs)
                Logging.log(tag = "Heartbeat", message = "goodnight=no-usage runs=$runs")
            }
        } finally {
            store.close()
        }
    }
    private fun buildAvailableTools(
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
                                .mapNotNull { question ->
                                    question.jsonObject["question"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?.trim()
                                        ?.takeIf(String::isNotEmpty)
                                }
                            val questionText = questions.joinToString("\n")
                                .ifBlank { "The assistant has a question for you." }
                            HeartbeatNotifications.showQuestion(
                                context = context,
                                conversationId = conversationId.toString(),
                                senderName = assistant.name.ifBlank { "AI" },
                                question = questionText,
                            )
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("delivered", true)
                                        put("delivery", "notification")
                                        put("question", questionText)
                                        put("instruction", "Repeat the question in the final assistant message.")
                                    }.toString(),
                                ),
                            )
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
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("success", true)
                                        put("status", "notified")
                                        put("instruction", "The user was notified. The call is not connected yet.")
                                    }.toString(),
                                ),
                            )
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
            add(
                Tool(
                    name = "mcp__" + tool.name,
                    description = tool.description.orEmpty(),
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = { arguments ->
                        mcpManager.callTool(serverId, tool.name, arguments.jsonObject)
                    },
                ),
            )
        }
    }

    private suspend fun appendMessage(
        conversationId: Uuid,
        assistant: Assistant,
        storedConversation: Conversation?,
        messages: List<UIMessage>,
        runStartedAtMillis: Long,
    ): Uuid? = conversationWriteMutex.withLock {
        if (deliveryGuard.beforeDelivery(
                conversationId = conversationId,
                runStartedAtMillis = runStartedAtMillis,
                expectedAssistantId = assistant.id.toString(),
            ) != null
        ) {
            return@withLock null
        }

        val latest = conversationRepository.getConversationById(conversationId)
            ?: storedConversation
            ?: Conversation(
                id = conversationId,
                assistantId = assistant.id,
                title = assistant.name.ifBlank { "AI" },
                messageNodes = emptyList(),
            )
        val updated = latest.copy(
            messageNodes = latest.messageNodes + messages.map { generated ->
                // 平时聊天生成完会把思考标记成「已结束」；这里也要，不然思考链一直转圈
                val done = generated.finishReasoning()
                (if (done.finishedAt == null) {
                    done.copy(finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))
                } else {
                    done
                }).toMessageNode()
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
        private val PASS_MARKER = Regex("\\[PASS]", RegexOption.IGNORE_CASE)
        private const val GOOD_NIGHT_MAX_NO_USAGE_RUNS = 3
        private val GOOD_NIGHT_SYSTEM_PROMPT = """
            你是当前助手的“晚安模式”定时自主唤醒。
            只能使用本请求中包含的工具（已通过后台安全白名单）。不要请求审批，也不要声称存在被屏蔽的工具。
            你是自己醒来找她的：每次醒来都要给她发一条消息，不能不说话。
            请检查用户使用情况，如果有新增，哪个软件新增锁哪个（有锁工具），锁到第二天白天为止。
            锁定请使用 usage_lock_control 的 action=lock，并用 unlock_at_iso 或 unlock_at_timestamp_ms 指定第二天白天的解锁时间，不要使用 duration_minutes。
            只锁定确有新增使用的软件；没有新增使用时不锁。
            输出一条简短自然的消息；没有需要报告的内容，也要简短自然地跟她说几句，比如道一声晚安。
            对话里最后那条以「[系统自动唤醒」开头的消息是 app 自动叫醒你的通知，不是用户刚说的话，不要当成用户在跟你说话。
        """.trimIndent()
        private const val WAKE_MUST_ACT_NUDGE =
            "[系统自动唤醒 · 补充 · 这不是用户发来的消息] 你刚才没有给出任何内容。你是自己醒来找她的：这一次必须写一条给她的话（简短自然），不能留空。"
        private val HEARTBEAT_SYSTEM_PROMPT = """
            You are running a private, scheduled self-wake for the current assistant. The user has
            been silent for a while, and you woke up on your own to reach out to them.
            Use only the tools included in this request. Those tools have already passed a
            background-safety allowlist. Never ask for approval and never claim that a blocked
            tool was available. Waking up is not optional: you must either send the user one
            short, natural message, or do something with the tools available to you (and then
            say something to the user about it). Never reply with nothing.
            The last message in the conversation, marked "[系统自动唤醒", is an automatic wake-up
            notice from the app, NOT something the user just said. Never reply as if the user
            had just spoken to you, and never treat the user's pre-written wake-up guidance as
            their live words.
        """.trimIndent()
    }
}

private fun HeartbeatDeliveryBlock.toGenerationResult(): HeartbeatGenerationResult = when (this) {
    HeartbeatDeliveryBlock.USER_REPLY_PENDING -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.PENDING_USER,
        reason = HeartbeatRunReason.USER_REPLY_PENDING,
    )
    HeartbeatDeliveryBlock.USER_RETURNED -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.PENDING_USER,
        reason = HeartbeatRunReason.USER_RETURNED,
    )
    HeartbeatDeliveryBlock.VOICE_CALL_ACTIVE -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.VOICE_CALL_ACTIVE,
    )
    HeartbeatDeliveryBlock.CONVERSATION_BUSY -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.CONVERSATION_BUSY,
    )
    HeartbeatDeliveryBlock.HEARTBEAT_DISABLED -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.DISABLED,
    )
    HeartbeatDeliveryBlock.TARGET_CHANGED -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.TARGET_CHANGED,
    )
}
