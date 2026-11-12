// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.feature.chat.*
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.provider.SecretRedactor
import runtime.mobileagent.skills.ToolCall
import java.net.URI

/**
 * Trusted onApprove boundary, not an event/Inspector projection. Keep the exact original bytes
 * beside the executor-owned invocation identity; never reconstruct arguments from redacted events.
 * A missing route is an error, not permission to manufacture a different approval identity.
 */
internal fun persistChatApprovalInvocation(
    runs: runtime.mobileagent.data.RunRepository,
    runId: String,
    runtimeInvocationId: String?,
    call: ToolCall,
    knownSecrets: List<String> = emptyList(),
): ToolInvocation {
    val id = requireNotNull(runtimeInvocationId?.takeIf { it.isNotBlank() }) {
        "Pending tool invocation identity is unavailable"
    }
    fun safe(value: String): Boolean = SecretRedactor.redact(value, knownSecrets) == value
    fun safeJson(value: JsonElement): Boolean = when (value) {
        // Re-serialization restores decoded field names beside their values, so field-based
        // redaction rules cannot be bypassed by JSON Unicode escapes in a key.
        is JsonObject -> safe(value.toString()) && value.all { (key, item) -> safe(key) && safeJson(item) }
        is JsonArray -> value.all(::safeJson)
        is JsonPrimitive -> !value.isString || safe(value.content)
    }
    require(safe(call.argumentsJson) && safeJson(Json.parseToJsonElement(call.argumentsJson))) {
        "Sensitive tool arguments cannot be persisted for approval"
    }
    val invocation = ToolInvocation(id, runId, call.callId, call.name, call.argumentsJson,
        state = "WAITING_APPROVAL", createdAt = Utc.nowIso())
    runs.recordInvocation(invocation)
    return invocation
}

internal class ChatInputBudgetExceeded(
    val estimated: Long,
    val limit: Long,
    val contextLimit: Int,
    val outputReserve: Long,
    val imageCount: Int,
    val imageBudget: Int,
    val protocolUnits: Long,
    val messageTextUnits: Long,
    val toolCallUnits: Long,
    val toolSchemaUnits: Long,
    val imageUnits: Long,
) : IllegalArgumentException("CONTEXT_OVERFLOW") {
    /** [context] resolves host copy; the budget numbers and the ASCII code stay unchanged. */
    fun userMessage(context: Context): String = buildString {
        append(context.hostChatText(R.string.chat_budget_exceeded, estimated, limit, contextLimit,
            outputReserve, protocolUnits, messageTextUnits, toolCallUnits, toolSchemaUnits, imageUnits,
            imageCount, imageBudget))
    }

    fun diagnosticUnits(): Map<String, Long> = mapOf(
        "configuredContextLimit" to contextLimit.toLong(),
        "outputReserve" to outputReserve,
        "inputLimit" to limit,
        "estimatedUnits" to estimated,
        "imageCount" to imageCount.toLong(),
        "imageBudget" to imageBudget.toLong(),
        "protocolUnits" to protocolUnits,
        "messageTextUnits" to messageTextUnits,
        "toolCallUnits" to toolCallUnits,
        "toolSchemaUnits" to toolSchemaUnits,
        "imageUnits" to imageUnits,
    )
}

/** UI state projects durable conversations, immutable bindings, and checkpointed partial answers. */
class ChatViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    execution: ChatRunExecution,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    constructor(application: Application, savedStateHandle: SavedStateHandle) :
        this(application, savedStateHandle, ChatRunExecution())

    private val container get() = (getApplication<Application>() as MobileAgentApp).container
    val state = mutableStateOf(ChatUiState())
    val locator = mutableStateOf<EvidenceLocator?>(null)
    val unknownRetry = mutableStateOf<String?>(null)
    private val citations = linkedMapOf<String, Pair<Citation, String>>()
    private var selectedImage: Pair<String, ByteArray>? = null
    private val imageDraft = ChatImageDraft(state)
    private val requestPreviews = ChatRequestPreviews()
    private var readGeneration = 0L
    private var inputRevision = 0L
    private var readJob: Job? = null
    private var earlierJob: Job? = null
    private var sessionPageJob: Job? = null
    private var creationJob: Job? = null
    private var activeSessionCursor: runtime.mobileagent.data.ConversationCursor? = null
    private var archivedSessionCursor: runtime.mobileagent.data.ConversationCursor? = null
    private var messageCursor: String? = null
    private val draftCache = object : java.util.LinkedHashMap<runtime.mobileagent.data.ChatDraftOwner, String>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<runtime.mobileagent.data.ChatDraftOwner, String>?) = size > 4
    }
    private fun draftOwner() = chatDraftOwner(state.value.selectedSessionId, state.value.selectedAgentId)
    private fun draftFailure(owner: runtime.mobileagent.data.ChatDraftOwner, failure: Exception) {
        viewModelScope.launch { if (draftOwner() == owner) fail(failure) }
    }
    private fun saveDraft() {
        val owner = draftOwner()
        val body = state.value.input
        draftCache[owner] = body
        container.chatDraftPersistence.stage(owner, body) { draftFailure(owner, it) }
    }
    private fun peekDraft(sessionId: String?, agentId: String?) = draftCache[chatDraftOwner(sessionId, agentId)]
        ?: savedStateHandle.get<String>("chat.draft.${sessionId ?: "new:$agentId"}").orEmpty()
    private fun invalidateRead() {
        readGeneration++
        readJob?.cancel()
        earlierJob?.cancel()
        sessionPageJob?.cancel()
    }
    private fun legacyDrafts(): List<Triple<String, runtime.mobileagent.data.ChatDraftOwner, String>> =
        savedStateHandle.keys().filter { it.startsWith("chat.draft.") }.mapNotNull { key ->
            val suffix = key.removePrefix("chat.draft.")
            val owner = runCatching {
                if (suffix.startsWith("new:")) chatDraftOwner(null, suffix.removePrefix("new:").takeUnless { it == "null" })
                else chatDraftOwner(suffix, null)
            }.getOrNull() ?: return@mapNotNull null
            Triple(key, owner, savedStateHandle.get<String>(key).orEmpty())
        }
    private fun blockedSessionChange(): Boolean {
        if (!state.value.streaming) return false
        state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_session_running_switch))
        return true
    }
    private val runs by lazy {
        ChatExecutionController(getApplication(), container, viewModelScope, execution, object : ChatExecutionUi {
            override val state get() = this@ChatViewModel.state
            override val unknownRetry get() = this@ChatViewModel.unknownRetry
            override val inputRevision get() = this@ChatViewModel.inputRevision
            override val citations get() = this@ChatViewModel.citations
            override val imageDraft get() = this@ChatViewModel.imageDraft
            override val requestPreviews get() = this@ChatViewModel.requestPreviews
            override fun canProjectRun(owner: ChatRunOwner) = this@ChatViewModel.canProjectRun(owner)
            override fun publishRunState(owner: ChatRunOwner, next: ChatUiState) = this@ChatViewModel.publishRunState(owner, next)
            override fun saveRunDraft(owner: ChatRunOwner) = this@ChatViewModel.saveRunDraft(owner)
            override fun failRun(owner: ChatRunOwner, failure: Exception) = this@ChatViewModel.failRun(owner, failure)
            override fun messageUi(message: Message) = this@ChatViewModel.messageUi(message)
            override fun citationUis(source: Map<String, Pair<Citation, String>>) = this@ChatViewModel.citationUis(source)
            override fun publishRunCitations(owner: ChatRunOwner, source: Map<String, Pair<Citation, String>>) = this@ChatViewModel.publishRunCitations(owner, source)
            override suspend fun refreshMessages(owner: ChatRunOwner) = this@ChatViewModel.refreshMessages(owner)
            override fun compactionUi(record: ContextCompactionRecord) = this@ChatViewModel.compactionUi(record)
            override fun reloadChat(preserveTerminalStatus: Boolean) = this@ChatViewModel.reloadChat(preserveTerminalStatus)
        })
    }
    private val runOwnership get() = runs.runOwnership
    private var runJob: Job?
        get() = runs.runJob
        set(value) { runs.runJob = value }
    private var preflightJob: Job?
        get() = runs.preflightJob
        set(value) { runs.preflightJob = value }
    private var activeRunResources: ChatRunSession?
        get() = runs.activeRunResources
        set(value) { runs.activeRunResources = value }

    init {
        addCloseable(requestPreviews.observe(container.uiPreferences) { state.value = state.value.withInspector() })
        reload()
    }

    fun reload() = reloadChat(preserveTerminalStatus = false)

    private fun reloadChat(preserveTerminalStatus: Boolean) {
        if (state.value.streaming) return
        invalidateRead()
        val generation = readGeneration
        val edit = inputRevision
        val previous = state.value.copy(requestPreview = null, promptLayers = emptyList())
        val savedSession = savedStateHandle.get<String>(SELECTED_SESSION_KEY)
        val savedAgent = savedStateHandle.get<String>(SELECTED_AGENT_KEY)
        val sourceCitations = LinkedHashMap(citations)
        val legacy = legacyDrafts()
        state.value = state.value.copy(loading = true, loadingEarlierMessages = false)
        readJob = viewModelScope.launch {
            try {
                withContext(NonCancellable + storageDispatcher) {
                    legacy.forEach { (_, owner, body) -> container.chatDrafts.migrateLegacy(owner, body) }
                    withContext(Dispatchers.Main) { legacy.forEach { (key, _, _) -> savedStateHandle.remove<String>(key) } }
                }
                val (result, invalidated) = withContext(storageDispatcher) {
                    val closed = runs.closeOrphanedToolApprovals()
                    val app = getApplication<Application>()
                    val historyCopy = ChatHistoryCopy(
                        unconfirmedUserMessageSummary = app.hostChatText(R.string.chat_user_message_saved_unconfirmed),
                        configurationSnapshotLabel = { snapshotPrefix ->
                            app.hostChatText(R.string.chat_configuration_snapshot_label, snapshotPrefix)
                        },
                    )
                    readChatHistory(container, previous, savedSession, savedAgent,
                        sourceCitations, historyCopy, ::messageUi, ::restoreCitations, ::citationUis, ::compactionUi,
                        ::projectWorkspaceAccess) to closed
                }
                if (generation != readGeneration || state.value.streaming) return@launch
                citations.clear()
                citations.putAll(result.citations)
                activeSessionCursor = result.activeCursor
                archivedSessionCursor = result.archivedCursor
                messageCursor = result.messageCursor
                val statusChanged = preserveTerminalStatus || state.value.status != previous.status
                state.value = result.state.copy(input = if (edit == inputRevision) result.state.input else state.value.input,
                    status = if (statusChanged) state.value.status else result.state.status,
                    statusKind = if (statusChanged) state.value.statusKind else result.state.statusKind,
                    inspectorOpen = savedStateHandle.get<Boolean>(INSPECTOR_KEY) ?: false).withInspector()
                draftCache[draftOwner()] = state.value.input
                imageDraft.sync()
                if (invalidated > 0 && !preserveTerminalStatus) state.value = state.value.copy(
                    status = getApplication<Application>().hostChatText(R.string.chat_approval_invalidated_by_restart), statusKind = "")
                else if (!preserveTerminalStatus && result.state.selectedSessionId != null && withContext(storageDispatcher) {
                    container.db.query("SELECT run_id FROM runs WHERE conversation_id=? AND state='UNKNOWN_OUTCOME' LIMIT 1",
                        listOf(result.state.selectedSessionId)).isNotEmpty()
                }) {
                    if (generation == readGeneration && !statusChanged && state.value.status == previous.status) state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_unknown_run_present))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == readGeneration) { state.value = state.value.copy(loading = false); fail(failure) }
            }
        }
    }

    fun loadMoreSessions(archived: Boolean = false) {
        val cursor = (if (archived) archivedSessionCursor else activeSessionCursor) ?: return
        if (sessionPageJob?.isActive == true) return
        val generation = readGeneration
        val names = state.value.agents.associate { it.id to it.label }
        sessionPageJob = viewModelScope.launch {
            try {
                val (rows, next) = withContext(storageDispatcher) { container.readChatSessionPage(archived, cursor, names) }
                if (generation != readGeneration) return@launch
                if (archived) {
                    archivedSessionCursor = next
                    state.value = state.value.copy(archivedSessions = (state.value.archivedSessions + rows).distinctBy { it.id },
                        hasMoreArchivedSessions = next != null)
                } else {
                    activeSessionCursor = next
                    state.value = state.value.copy(sessions = (state.value.sessions + rows).distinctBy { it.id }, hasMoreSessions = next != null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == readGeneration) fail(failure) }
        }
    }

    fun loadEarlierMessages() {
        val conversation = state.value.selectedSessionId ?: return
        val cursor = messageCursor ?: return
        if (earlierJob?.isActive == true) return
        val generation = readGeneration
        state.value = state.value.copy(loadingEarlierMessages = true)
        earlierJob = viewModelScope.launch {
            try {
                val (page, source, references) = withContext(storageDispatcher) {
                    val page = container.conversations.messagePage(conversation, cursor)
                    val source = linkedMapOf<String, Pair<Citation, String>>()
                    page.messages.forEach { restoreCitations(it.metadataJson, source) }
                    Triple(page, source, citationUis(source))
                }
                if (generation != readGeneration || state.value.selectedSessionId != conversation) return@launch
                citations.putAll(source)
                val existing = state.value.messages.map { it.id }.toSet()
                messageCursor = page.nextBeforeMessageId
                state.value = state.value.copy(messages = page.messages.filterNot { it.id in existing }.map(::messageUi) + state.value.messages,
                    citations = (state.value.citations + references).distinctBy { it.id },
                    hasMoreMessages = page.nextBeforeMessageId != null, loadingEarlierMessages = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == readGeneration) { state.value = state.value.copy(loadingEarlierMessages = false); fail(failure) } }
        }
    }

    fun selectAgent(id: String) {
        if (blockedSessionChange()) return
        leaveSelectedSession()
        container.uiPreferences.edit().putString("selected-agent", id).remove("selected-conversation").apply()
        savedStateHandle[SELECTED_AGENT_KEY] = id
        savedStateHandle.remove<String>(SELECTED_SESSION_KEY)
        state.value = state.value.copy(selectedAgentId = id, selectedSessionId = null, input = peekDraft(null, id), messages = emptyList(), citations = emptyList(),
            status = getApplication<Application>().hostChatText(R.string.chat_new_session_freezes_configuration), loading = false)
            .withInspector()
        citations.clear()
        reload()
    }

    /** Create a conversation using the canonical Agent default, if one is valid. */
    fun newSession(): String? = createSession(requestedWorkspaceId = null, useAgentDefault = true)

    /**
     * Create a conversation whose workspace is decided once, at thread creation.  Passing null
     * explicitly means an unbound thread; it never means "use the Agent's current workspace".
     * The overload is reserved for a foreground picker and is validated again by the canonical
     * runtime adapter.
     */
    fun newSession(workspaceId: String?): String? =
        createSession(requestedWorkspaceId = workspaceId, useAgentDefault = false)

    private fun createSession(requestedWorkspaceId: String?, useAgentDefault: Boolean): String? {
        if (blockedSessionChange()) return null
        return try {
            val id = container.creationAgentId(state.value.selectedSessionId, state.value.selectedAgentId)
            val conversation = container.createChatConversation(id, requestedWorkspaceId, useAgentDefault)
            selectCommittedSession(conversation.id, id)
            conversation.id
        } catch (failure: Exception) { fail(failure); null }
    }

    /** UI entry point; the synchronous overloads remain compatible with explicit test/host callers. */
    fun newSessionAsync(workspaceId: String? = null, useAgentDefault: Boolean = true) {
        if (blockedSessionChange() || creationJob?.isActive == true) return
        saveDraft()
        invalidateRead()
        val generation = readGeneration
        val selectedConversation = state.value.selectedSessionId
        val selectedAgent = state.value.selectedAgentId
        val owner = draftOwner()
        state.value = state.value.copy(loading = true)
        creationJob = viewModelScope.launch {
            try {
                val (created, agent) = withContext(storageDispatcher) {
                    container.chatDraftPersistence.flush(owner)
                    val agent = container.creationAgentId(selectedConversation, selectedAgent)
                    container.createChatConversation(agent, workspaceId, useAgentDefault) to agent
                }
                if (generation == readGeneration && !state.value.streaming) selectCommittedSession(created.id, agent)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == readGeneration) fail(failure) }
            finally {
                if (generation == readGeneration) state.value = state.value.copy(loading = false)
                creationJob = null
            }
        }
    }

    fun sessionAction(id: String, action: runtime.mobileagent.feature.chat.SessionAction) {
        when (action) {
            runtime.mobileagent.feature.chat.SessionAction.ARCHIVE -> changeArchive(id, true)
        }
    }

    fun restoreSession(id: String) = changeArchive(id, false)

    private fun changeArchive(id: String, archived: Boolean) {
        if (runOwnership.active?.conversationId == id || (state.value.streaming && state.value.selectedSessionId == id)) {
            state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_archive_blocked_running))
            return
        }
        try {
            if (!container.conversations.setArchived(id, archived)) {
                state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_archive_state_unchanged))
                return
            }
            reload()
        } catch (failure: Exception) { fail(failure) }
    }

    fun selectSession(id: String) {
        if (blockedSessionChange()) return
        selectCommittedSession(id)
    }

    private fun selectCommittedSession(id: String, knownAgentId: String? = null) {
        leaveSelectedSession()
        val agent = knownAgentId ?: (state.value.sessions + state.value.archivedSessions).firstOrNull { it.id == id }?.agentId
        container.uiPreferences.edit().putString("selected-conversation", id).apply()
        savedStateHandle[SELECTED_SESSION_KEY] = id
        state.value = state.value.copy(
            selectedSessionId = id,
            selectedAgentId = agent,
            input = peekDraft(id, agent),
            messages = emptyList(), citations = emptyList(), hasMoreMessages = false,
            status = getApplication<Application>().hostChatText(R.string.chat_session_uses_configuration_snapshot),
        ).withInspector()
        citations.clear()
        messageCursor = null
        reload()
    }
    fun input(value: String) { inputRevision++; state.value = state.value.copy(input = value); saveDraft() }
    fun degrade(value: Boolean) { if (!state.value.streaming) state.value = state.value.copy(textDegradation = value) }
    fun inspector(open: Boolean) {
        savedStateHandle[INSPECTOR_KEY] = open
        state.value = state.value.copy(inspectorOpen = open).withInspector()
    }
    fun closeCitation() { selectedImage = null; state.value = state.value.copy(selectedCitationId = null); locator.value = null }
    fun cancelUnknownRetry() { unknownRetry.value = null }
    fun clearImageDraft() = imageDraft.clear()

    /**
     * Adds picker results to the pending selection. [uploads] were already validated by
     * [ChatImageUpload]; [rejected] are per-file messages from the picker reader. Extras past the
     * per-message limit are dropped with a visible message instead of being sent later.
     */
    fun attachImages(
        uploads: List<Pair<ChatImageUpload, Bitmap?>>,
        rejected: List<String> = emptyList(),
        forSession: String? = state.value.selectedSessionId,
        forAgent: String? = state.value.selectedAgentId,
    ) = imageDraft.attach(uploads, rejected, forSession, forAgent)

    fun removePendingImage(id: String) = imageDraft.remove(id)

    fun acknowledgeUnknown() {
        unknownRetry.value?.let { container.runs.acknowledgeUnknown(it, true) }
        unknownRetry.value = null
        sendWithImages(imageDraft.uploads())
    }

    fun send() = sendWithImages(imageDraft.uploads())

    /** Backend seam for a foreground image picker; no UI/URI handling is required here. */
    fun sendWithImages(uploads: List<ChatImageUpload>) {
        if (state.value.selectedSessionArchived) {
            state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_archived_conversation_read_only))
            return
        }
        val text = state.value.input.trim()
        if (!imageDraft.hasSendableInput(text, uploads)) return
        if (uploads.size > ChatImageUpload.MAX_IMAGES_PER_MESSAGE) {
            state.value = state.value.copy(
                status = getApplication<Application>().hostChatText(
                    R.string.chat_image_attachment_limit, ChatImageUpload.MAX_IMAGES_PER_MESSAGE, "2 MiB",
                ),
                statusKind = "error",
            )
            return
        }
        val inputImages = uploads.toList()
        if (runOwnership.active != null || runJob?.isActive == true || preflightJob?.isActive == true) {
            state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_previous_run_finishing))
            return
        }
        val pendingImages = imageDraft.pending()
        val selectedConversation = state.value.selectedSessionId
        val selectedAgent = state.value.selectedAgentId
        saveDraft()
        invalidateRead()
        val generation = readGeneration
        runJob = null
        val preflight = runs.chatRunPreflight()
        state.value = state.value.copy(streaming = true, loading = false,
            status = getApplication<Application>().hostChatText(R.string.chat_preparing_conversation), statusKind = "")
        preflightJob = viewModelScope.launch {
            var resources: ChatRunSession? = null
            try {
                val conversationId = selectedConversation ?: withContext(storageDispatcher) {
                    container.createChatConversation(selectedAgent ?: error("No Agent selected for a new conversation")).id
                }
                if (generation != readGeneration || state.value.selectedSessionId != selectedConversation || state.value.selectedAgentId != selectedAgent) return@launch
                // Reject an offline Root before selecting the new thread, adopting images, or
                // clearing the composer. prepare() rechecks immediately before USER persistence.
                preflight.rootRefusal(conversationId)?.let { throw it }
                if (selectedConversation == null) selectCommittedSession(conversationId, selectedAgent)
                imageDraft.adopt(conversationId, inputImages, pendingImages)
                val owner = runOwnership.begin(conversationId)
                val admitted = ChatRunSession(owner)
                resources = admitted
                activeRunResources = admitted
                container.chatDraftPersistence.stage(chatDraftOwner(conversationId, selectedAgent), text) {
                    draftFailure(chatDraftOwner(conversationId, selectedAgent), it)
                }
                publishRunState(owner, state.value.copy(input = "", streaming = true, loading = false))
                runs.sendAfterInput(text, owner, admitted, inputImages, preflight)
            } catch (cancelled: CancellationException) {
                resources?.let { publishRunState(it.owner, state.value.copy(streaming = false)) }
                throw cancelled
            } catch (failure: Exception) {
                val admitted = resources
                if (admitted != null) {
                    failRun(admitted.owner, failure)
                    publishRunState(admitted.owner, state.value.copy(streaming = false, input = text))
                    saveRunDraft(admitted.owner)
                } else if (generation == readGeneration) {
                    state.value = state.value.copy(streaming = false)
                    fail(failure)
                }
            } finally {
                if (preflightJob === currentCoroutineContext()[Job]) preflightJob = null
                val admitted = resources
                if (admitted?.job == null) {
                    admitted?.let {
                        runOwnership.finish(it.owner)
                        if (activeRunResources === it) activeRunResources = null
                    }
                    if (admitted == null && generation == readGeneration) state.value = state.value.copy(streaming = false)
                }
            }
        }
    }

    fun approveTool(approved: Boolean) = runs.approveTool(approved)
    fun cancel() = runs.cancel()

    private fun ChatUiState.withInspector() = requestPreviews.project(
        this, container.uiPreferences.requestInspectorEnabled, container.uiPreferences.hadRequestPreview(selectedSessionId),
    )

    fun openCitation(id: String) {
        val entry = citations[id] ?: return
        selectedImage = null
        locator.value = container.knowledge.locateCitation(entry.first)
        state.value = state.value.copy(selectedCitationId = id, citations = citationUis())
        if (entry.first.assetId != null && locator.value?.removed == false) viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { container.knowledge.evidenceBytes(entry.first) }
            if (state.value.selectedCitationId != id) return@launch
            if (image != null && image.first.startsWith("image/") && image.second.size <= 8 * 1024 * 1024) {
                selectedImage = id to image.second
                state.value = state.value.copy(citations = citationUis())
            } else state.value = state.value.copy(status = getApplication<Application>().hostChatText(R.string.chat_image_preview_unavailable))
        }
    }
    private fun canProjectRun(owner: ChatRunOwner): Boolean =
        runOwnership.canProject(owner, state.value.selectedSessionId)

    private fun leaveSelectedSession() {
        invalidateRead()
        inputRevision++
        requestPreviews.leave(state.value.selectedSessionId)
        runOwnership.active?.let(runOwnership::detach)
        imageDraft.detach()
        selectedImage = null
        locator.value = null
        unknownRetry.value = null
        state.value = state.value.copy(selectedCitationId = null, pendingTool = null)
        saveDraft()
    }

    private fun publishRunState(owner: ChatRunOwner, next: ChatUiState) {
        if (canProjectRun(owner)) state.value = next
    }

    private fun saveRunDraft(owner: ChatRunOwner) {
        if (canProjectRun(owner)) saveDraft()
    }

    private fun failRun(owner: ChatRunOwner, failure: Exception) {
        publishRunState(owner, state.value.copy(error = localizedFailureMessage(failure)))
    }

    private fun publishRunCitations(owner: ChatRunOwner, source: Map<String, Pair<Citation, String>>) {
        if (!canProjectRun(owner)) return
        citations.clear()
        citations.putAll(source)
    }

    private suspend fun refreshMessages(owner: ChatRunOwner) {
        val messages = withContext(storageDispatcher) { container.conversations.messagePage(owner.conversationId).messages }
        val ids = messages.map { it.id }.toSet()
        publishRunState(owner, state.value.copy(messages = state.value.messages.filterNot { it.id in ids } + messages.map(::messageUi)))
    }
    private fun messageUi(message: Message): ChatMessageUi {
        val app = getApplication<Application>()
        return projectChatMessage(
            rawMessage = message,
            streaming = state.value.streaming,
            copy = ChatMessageProjectionCopy(
                visualBatchAnalyzed = { count -> app.hostChatText(R.string.chat_visual_batch_analyzed, count.toString()) },
                toolAttachedImages = { count -> app.hostChatText(R.string.chat_tool_attached_images, count.toString()) },
                turnIncomplete = { status -> app.hostChatText(R.string.chat_turn_incomplete_retained, status) },
                partialRetrievalCoverage = app.hostChatText(R.string.chat_retrieval_partial_coverage_fallback),
                visualDegraded = { raw ->
                    toolVisualEvidenceSummary(
                        raw,
                        app.hostChatText(R.string.chat_visual_degraded_prefix),
                        app.hostChatText(R.string.chat_visual_degraded_without_reason),
                    )
                },
            ),
        )
    }

    private fun citationUis(source: Map<String, Pair<Citation, String>> = citations) = source.map { (id, value) ->
        val loc = container.knowledge.locateCitation(value.first)
        ChatCitationUi(id, loc.displayName, value.first.documentId, if (loc.removed) getApplication<Application>().hostChatText(R.string.chat_citation_source_removed) else value.second,
            getApplication<Application>().hostChatText(R.string.chat_citation_page_label, loc.page ?: "—",
                loc.assetId ?: loc.sourceSpan.orEmpty()), !loc.removed,
            imageBytes = selectedImage?.takeIf { it.first == id && !loc.removed }?.second,
            knowledgeBaseId = value.first.knowledgeBaseId,
            documentVersionId = value.first.documentVersionId,
            chunkId = value.first.chunkId,
            assetId = value.first.assetId)
    }
    private fun citationMetadata(bound: List<Citation>, warning: String?, coverage: RetrievalCoverage? = null, source: Map<String, Pair<Citation, String>> = citations) = chatCitationMetadata(bound, warning, coverage, source)
    private fun restoreCitations(raw: String, target: MutableMap<String, Pair<Citation, String>> = citations) = restoreChatCitations(raw, target)

    private fun compactionUi(record: ContextCompactionRecord): ChatCompactionUi = ChatCompactionUi(
        id = record.id, state = record.state.name, sourceMessageIds = record.sourceMessageIds,
        summaryJson = record.summaryJson, modelId = record.modelId, reason = record.reason,
        beforeUnits = record.beforeUnits, afterUnits = record.afterUnits,
        inputTokens = record.inputTokens, outputTokens = record.outputTokens,
        inputHash = record.inputHash, createdAt = record.createdAt,
    )

    private fun projectWorkspaceAccess(
        conversationId: String?,
        agentId: String?,
        agentLabel: String?,
    ): ChatWorkspaceAccessUi {
        val agentName = agentLabel?.takeIf { it.isNotBlank() }.orEmpty()
        val threadPort = (container as? ThreadWorkspacePortProvider)?.threadWorkspacePort
        val binding = conversationId?.let { id ->
            runCatching { threadPort?.conversationWorkspaceBinding(id) }.getOrNull()
        }
        val presentation = binding?.workspaceId?.let { workspaceId ->
            runCatching { container.workspacePresentationPort.workspaceUiPresentation(workspaceId) }.getOrNull()
        }
        val authority = runCatching { container.authorityQueryPort.snapshot() }.getOrNull()
        val storedAgentDefault = agentId?.let { id ->
            runCatching { threadPort?.agentWorkspaceDefault(id) }.getOrNull()
        }
        val resolvedAgentDefaultId = agentId?.let { id ->
            runCatching { threadPort?.resolveNewThreadWorkspace(id) }.getOrNull()
        }
        val agentDefaultPresentation = resolvedAgentDefaultId?.let { workspaceId ->
            runCatching { container.workspacePresentationPort.workspaceUiPresentation(workspaceId) }.getOrNull()
        }
        val systemLabel = when {
            authority == null -> ""
            authority.selectedAuthority == Authority.SHIZUKU -> "Shizuku"
            authority.selectedAuthority == Authority.WIRED_ADB -> "Wired ADB"
            authority.selectedAuthority == Authority.ROOT_DIRECT -> "Root"
            authority.selectedAuthority == Authority.SHIZUKU_ROOT -> "Shizuku Root"
            else -> ""
        }
        // Only user-supplied display metadata belongs in the view model. Fixed
        // workspace status text is localized by the feature at composition time.
        val summary = presentation?.title.orEmpty()
        val threadWorkspaceState = when {
            binding != null -> ChatThreadWorkspaceState.BOUND
            resolvedAgentDefaultId != null -> ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE
            else -> ChatThreadWorkspaceState.UNBOUND_NO_AGENT_DEFAULT
        }
        return ChatWorkspaceAccessUi(
            agentLabel = agentName,
            workspaceSummary = summary,
            systemAccessLabel = systemLabel,
            agentDefaultUnavailable = storedAgentDefault != null && resolvedAgentDefaultId == null,
            threadWorkspaceState = threadWorkspaceState,
            agentDefaultWorkspaceId = resolvedAgentDefaultId,
            agentDefaultWorkspaceLabel = agentDefaultPresentation?.title.orEmpty(),
        )
    }

    private fun localizedFailureMessage(failure: Exception): String {
        val resource = (failure as? AppException)?.error?.let(ChatRootErrors::preflightMessageResource)
        return SecretRedactor.redact(resource?.let { getApplication<Application>().rootChatMessage(it) }
            ?: failure.message ?: getApplication<Application>().hostChatText(R.string.chat_operation_failed))
    }

    private fun fail(failure: Exception) { state.value = state.value.copy(error = localizedFailureMessage(failure)) }
    private companion object {
        const val SELECTED_SESSION_KEY = "chat.selectedSessionId"
        const val SELECTED_AGENT_KEY = "chat.selectedAgentId"
        const val INSPECTOR_KEY = "chat.inspectorOpen"
    }
}
