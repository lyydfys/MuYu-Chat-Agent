package com.muyuchat.feature.video.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.core.video.contract.VideoDeleteResult
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoImportPort
import com.muyuchat.core.video.contract.VideoImportPreview
import com.muyuchat.core.video.contract.VideoImportSaveResult
import com.muyuchat.core.video.contract.VideoInputBinding
import com.muyuchat.core.video.contract.VideoLocalEngine
import com.muyuchat.core.video.contract.VideoLoraBinding
import com.muyuchat.core.video.contract.VideoMediaAssetId
import com.muyuchat.core.video.contract.VideoMediaProjection
import com.muyuchat.core.video.contract.VideoModelAssetPort
import com.muyuchat.core.video.contract.VideoModelAssetState
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoPortKind
import com.muyuchat.core.video.contract.VideoRemoteCapabilities
import com.muyuchat.core.video.contract.VideoRemoteEndpointBinding
import com.muyuchat.core.video.contract.VideoRemoteProbePort
import com.muyuchat.core.video.contract.VideoRemoteProbeResult
import com.muyuchat.core.video.contract.VideoRevisionId
import com.muyuchat.core.video.contract.VideoRevisionResult
import com.muyuchat.core.video.contract.VideoWorkflowCanon
import com.muyuchat.core.video.contract.VideoRunId
import com.muyuchat.core.video.contract.VideoRunProjection
import com.muyuchat.core.video.contract.VideoSubmitCommand
import com.muyuchat.core.video.contract.VideoSubmitResult
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowPort
import com.muyuchat.feature.video.workspace.editor.VideoDraftCodec
import com.muyuchat.feature.video.workspace.editor.VideoGraphEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Page-state holder of the video workspace (proposal §9). It owns editing and view state plus the
 * read-only projections the page renders: the draft graph (via [VideoGraphEditorState]), the
 * selected node, the run list/active run, the published media library and the ComfyUI import
 * session. Execution is never started here — [port] and [importPort] are the single typed
 * entries (null = not wired yet, reported honestly) and every intent is forwarded verbatim.
 *
 * The port is the process-level owner: this VM only collects its flows. Leaving the page cancels
 * nothing owned by the port; re-entering reuses the same owner and gets the same projections.
 * The draft survives rotation/process death through [SavedStateHandle] (compact codec).
 */
class VideoWorkspaceViewModel(
    private val savedState: SavedStateHandle,
    private val port: VideoWorkflowPort?,
    private val importPort: VideoImportPort?,
    private val remotePort: VideoRemoteProbePort?,
    private val modelAssetPort: VideoModelAssetPort? = null,
    /**
     * Persisted experimental-backend switch. `null` means the composition root did not wire a
     * preference store: the page still works and the switch stays off (the default), it just cannot
     * remember a user choice.
     */
    private val enginePreference: VideoExperimentalEnginePreference? = null,
    /**
     * Resolves the installed Turbo distillation LoRA for the external engine. `null` (or an empty
     * result) means the asset is not installed: the run then executes without it rather than
     * inventing a recipe the device cannot back.
     */
    private val turboLoraPathProvider: (() -> String?)? = null,
    /**
     * Durable draft backing store. `null` keeps the old behaviour (saved-state only), so callers
     * that do not wire it still compile and work.
     */
    private val draftStore: VideoDraftStore? = null,
) : ViewModel() {

    enum class TemplateMode { TEXT_TO_VIDEO, IMAGE_TO_VIDEO }

    enum class ViewMode { SIMPLIFIED, ADVANCED }

    data class EditorSnapshot(
        val documentJson: String,
        val canUndo: Boolean,
        val canRedo: Boolean,
    )

    private val editor = VideoGraphEditorState(
        initialDocument = restoreDraft() ?: VideoWorkflowTemplates.textToVideoTemplate(),
    )

    private val _mode = MutableStateFlow(TemplateMode.TEXT_TO_VIDEO)
    val mode: StateFlow<TemplateMode> = _mode.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.ADVANCED)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    private val _lastConnectRejection = MutableStateFlow<String?>(null)
    val lastConnectRejection: StateFlow<String?> = _lastConnectRejection.asStateFlow()

    /** User-selected local image for image-to-video; a content URI, never a raw file path. */
    private val _inputImageUri = MutableStateFlow(savedState.get<String>(KEY_INPUT_IMAGE))
    val inputImageUri: StateFlow<String?> = _inputImageUri.asStateFlow()

    /** Read-only run projections, newest first, exactly as the owner publishes them. */
    private val _runs = MutableStateFlow<List<VideoRunProjection>>(emptyList())
    val runs: StateFlow<List<VideoRunProjection>> = _runs.asStateFlow()

    private val _runsError = MutableStateFlow<String?>(null)
    val runsError: StateFlow<String?> = _runsError.asStateFlow()

    /** Verbatim rejection of an explicit retry intent; null while no retry is pending. */
    private val _retryNotice = MutableStateFlow<String?>(null)
    val retryNotice: StateFlow<String?> = _retryNotice.asStateFlow()

    private val _lastSubmittedRunId = MutableStateFlow<VideoRunId?>(null)

    /**
     * The run the page highlights: the most recent submit while it is still visible, otherwise
     * the newest projection. Null until the owner publishes a run.
     */
    private val _activeRun = MutableStateFlow<VideoRunProjection?>(null)
    val activeRun: StateFlow<VideoRunProjection?> = _activeRun.asStateFlow()

    /** Published media library, owned by the port; deletion never edits this list locally. */
    private val _media = MutableStateFlow<List<VideoMediaProjection>>(emptyList())
    val media: StateFlow<List<VideoMediaProjection>> = _media.asStateFlow()

    private val _mediaLoaded = MutableStateFlow(false)
    val mediaLoaded: StateFlow<Boolean> = _mediaLoaded.asStateFlow()

    private val _mediaError = MutableStateFlow<String?>(null)
    val mediaError: StateFlow<String?> = _mediaError.asStateFlow()

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _remoteState = MutableStateFlow<RemoteUiState>(RemoteUiState.Idle)
    val remoteState: StateFlow<RemoteUiState> = _remoteState.asStateFlow()

    /** Process-owned model asset state; null until the port publishes or when unwired. */
    private val _modelAssets = MutableStateFlow<VideoModelAssetState?>(null)
    val modelAssets: StateFlow<VideoModelAssetState?> = _modelAssets.asStateFlow()

    /**
     * External-engine switch. Defaults to ON; only an explicit user opt-out persists false and
     * falls back to the built-in engine. Every submit records the choice per run.
     */
    private val _externalEngineEnabled =
        MutableStateFlow(enginePreference?.isExternalEngineEnabled() ?: true)
    val externalEngineEnabled: StateFlow<Boolean> = _externalEngineEnabled.asStateFlow()

    /** Full Wan VAE decode (~30-40 min per 480p clip) instead of the TAE head. Default on. */
    private val _fullVaeDecode =
        MutableStateFlow(enginePreference?.isFullVaeEnabled() ?: true)
    val fullVaeDecode: StateFlow<Boolean> = _fullVaeDecode.asStateFlow()

    val remoteWired: Boolean get() = remotePort != null

    val modelAssetsWired: Boolean get() = modelAssetPort != null

    private var runsJob: Job? = null
    private var mediaJob: Job? = null
    private var importJob: Job? = null

    val executionWired: Boolean get() = port != null

    val importWired: Boolean get() = importPort != null

    init {
        persistDraft()
        if (port == null) {
            _mediaLoaded.value = true
            _mediaError.value = "视频媒体库尚未接入：当前没有可读取的媒体端口"
        } else {
            startRunsObservation()
            startMediaObservation()
        }
        if (modelAssetPort != null) {
            viewModelScope.launch {
                modelAssetPort.observe().collect { _modelAssets.value = it }
            }
            modelAssetPort.refresh()
        }
    }

    fun refreshModelAssets() {
        modelAssetPort?.refresh()
    }

    fun downloadModelAssets() {
        modelAssetPort?.download()
    }

    fun cancelModelAssetDownload() {
        modelAssetPort?.cancel()
    }

    fun document() = editor.document

    fun issues() = editor.issues

    fun hasIssues(): Boolean = editor.issues.isNotEmpty()

    fun editorSnapshot(): EditorSnapshot = EditorSnapshot(
        documentJson = VideoDraftCodec.encode(editor.document),
        canUndo = editor.canUndo,
        canRedo = editor.canRedo,
    )

    fun switchTemplate(mode: TemplateMode) {
        val current = editor.document
        val preservedPrompt = (current.node("n_input")?.params?.get("prompt") as? VideoNodeValue.TextValue)?.text.orEmpty()
        val preservedNegative =
            (current.node("n_input")?.params?.get("negative_prompt") as? VideoNodeValue.TextValue)?.text.orEmpty()
        val preservedSeed = (current.node("n_cond")?.params?.get("seed") as? VideoNodeValue.LongValue)?.value
        val template = when (mode) {
            TemplateMode.TEXT_TO_VIDEO -> VideoWorkflowTemplates.textToVideoTemplate(
                prompt = preservedPrompt,
                negativePrompt = preservedNegative,
                seed = preservedSeed ?: 0L,
            )

            TemplateMode.IMAGE_TO_VIDEO -> VideoWorkflowTemplates.imageToVideoTemplate(
                prompt = preservedPrompt,
                negativePrompt = preservedNegative,
                seed = preservedSeed ?: 0L,
                imageAssetId = _inputImageUri.value,
            )
        }
        editor.resetTo(template)
        _mode.value = mode
        _selectedNodeId.value = null
        _lastConnectRejection.value = null
        persistDraft()
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
    }

    fun selectNode(nodeId: String?) {
        _selectedNodeId.value = nodeId
    }

    /** Persist the picked content URI and materialize it into the image-to-video draft node. */
    fun setInputImage(uri: String?) {
        _inputImageUri.value = uri
        savedState[KEY_INPUT_IMAGE] = uri
        if (editor.document.node("n_image") != null) {
            if (uri != null) {
                editor.setParam("n_image", "image", VideoNodeValue.ImageAssetRefValue(uri))
            }
            persistDraft()
        }
    }

    /** Persists and applies the backend switch; the value is read only at submit. */
    fun setExternalEngineEnabled(enabled: Boolean) {
        _externalEngineEnabled.value = enabled
        enginePreference?.setExternalEngineEnabled(enabled)
    }

    /** Persists the decode-quality switch; the worker reads it when it builds the payload. */
    fun setFullVaeDecode(enabled: Boolean) {
        _fullVaeDecode.value = enabled
        enginePreference?.setFullVaeEnabled(enabled)
    }

    /** The engine a submit started now would freeze; [VideoLocalEngine.DEFAULT] when the switch is off. */
    fun selectedEngine(): String =
        if (_externalEngineEnabled.value) {
            VideoLocalEngine.SDCPP_EXTERNAL
        } else {
            VideoLocalEngine.DEFAULT
        }

    /**
     * Product recipe is 4-step Turbo, so a local run on the external engine applies the installed
     * distillation LoRA. The binding is frozen into the snapshot: a retry replays the same weights
     * even if the asset was removed afterwards. No asset means no binding — a missing LoRA never
     * silently turns into a different recipe claim.
     */
    private fun turboLoraSpecs(engine: String): List<VideoLoraBinding> {
        if (engine != VideoLocalEngine.SDCPP_EXTERNAL) return emptyList()
        val path = turboLoraPathProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(VideoLoraBinding(path = path))
    }

    /**
     * Distilled (Turbo/Lightning) LoRAs run without classifier-free guidance: on device the same
     * seed/prompt produced a featureless blob at CFG 6.0 and a coherent image at guidance 1.0
     * (2026-11-02 A/B). Only the submitted revision carries the override; the editor draft keeps
     * whatever the user set.
     */
    private fun distilledLoraDocument(
        document: VideoWorkflowDocument,
        loraSpecs: List<VideoLoraBinding>,
    ): VideoWorkflowDocument {
        if (loraSpecs.isEmpty()) return document
        return document.copy(
            nodes = document.nodes.map { node ->
                if (node.typeId == VideoNodeTypeId.WAN_SAMPLER.typeId) {
                    node.copy(
                        params = node.params + ("guidance" to VideoNodeValue.DecimalValue(10L, 1)),
                    )
                } else {
                    node
                }
            },
        )
    }

    fun setParam(nodeId: String, name: String, value: VideoNodeValue) {
        if (editor.setParam(nodeId, name, value)) persistDraft()
    }

    fun moveNode(nodeId: String, x: Float, y: Float) {
        val node = editor.document.node(nodeId) ?: return
        editor.setNodeUi(nodeId, node.ui.copy(positionX = x, positionY = y))
        persistDraft()
    }

    fun addNode(type: VideoNodeTypeId): String? {
        val id = editor.addNode(type)
        if (id != null) persistDraft()
        return id
    }

    fun deleteImpact(nodeId: String) = editor.deleteImpact(nodeId)

    fun deleteNode(nodeId: String) {
        if (editor.deleteNode(nodeId)) {
            if (_selectedNodeId.value == nodeId) _selectedNodeId.value = null
            persistDraft()
        }
    }

    fun connect(fromNodeId: String, fromPort: String, toNodeId: String, toPort: String) {
        _lastConnectRejection.value = when (val decision = editor.connect(fromNodeId, fromPort, toNodeId, toPort)) {
            is VideoGraphEditorState.ConnectDecision.Connected -> {
                persistDraft()
                null
            }

            is VideoGraphEditorState.ConnectDecision.Rejected -> decision.reason
        }
    }

    fun disconnect(fromNodeId: String, fromPort: String, toNodeId: String, toPort: String) {
        val edge = editor.document.edges.firstOrNull {
            it.fromNodeId == fromNodeId && it.fromPort == fromPort && it.toNodeId == toNodeId && it.toPort == toPort
        } ?: return
        if (editor.disconnect(edge)) persistDraft()
    }

    fun undo() {
        if (editor.undo()) persistDraft()
    }

    fun redo() {
        if (editor.redo()) persistDraft()
    }

    /** Submit intent: freeze the draft as an immutable revision, then forward to the owner. */
    fun submit(onResult: (VideoSubmitResult) -> Unit) {
        val target = port ?: run {
            onResult(VideoSubmitResult.Rejected(VideoErrorCode.BACKEND_UNAVAILABLE, "视频执行链尚未接入"))
            return
        }
        viewModelScope.launch {
            val engine = selectedEngine()
            val loraSpecs = turboLoraSpecs(engine)
            val draft = revisionBoundDraft(distilledLoraDocument(editor.document, loraSpecs))
            val draftFrames = draft.nodes
                .firstOrNull { it.typeId == VideoNodeTypeId.WAN_CONDITIONING.typeId }
                ?.params?.get("frames")
            android.util.Log.i("McaVideoWorkspace", "submit draft frames=$draftFrames")
            when (val frozen = target.saveRevision(draft)) {
                is VideoRevisionResult.Saved -> {
                    val bindings = if (_mode.value == TemplateMode.IMAGE_TO_VIDEO) {
                        _inputImageUri.value?.let { reference ->
                            mapOf(
                                "n_image" to VideoInputBinding(
                                    nodeId = "n_image",
                                    kind = VideoPortKind.IMAGE_REF,
                                    reference = reference,
                                ),
                            )
                        }.orEmpty()
                    } else {
                        emptyMap()
                    }
                    android.util.Log.i(
                        "McaVideoWorkspace",
                        "submit engine=$engine loras=${loraSpecs.size} " +
                            "provider=${turboLoraPathProvider != null}",
                    )
                    val result = target.submit(
                        VideoSubmitCommand(
                            revisionId = frozen.revisionId,
                            inputBindings = bindings,
                            // Frozen per run: the experimental switch only affects runs submitted
                            // after it was turned on, never history and never a retry.
                            engine = engine,
                            loraSpecs = loraSpecs,
                        ),
                    )
                    when (result) {
                        is VideoSubmitResult.Admitted ->
                            android.util.Log.i("McaVideoWorkspace", "submit admitted run=${result.runId.value}")

                        is VideoSubmitResult.Rejected ->
                            android.util.Log.w(
                                "McaVideoWorkspace",
                                "submit rejected code=${result.code.name} detail=${result.detail}",
                            )
                    }
                    if (result is VideoSubmitResult.Admitted) {
                        _lastSubmittedRunId.value = result.runId
                        refreshActiveRun()
                    }
                    onResult(result)
                }

                is VideoRevisionResult.Conflict -> {
                    onResult(
                        VideoSubmitResult.Rejected(
                            VideoErrorCode.WORKFLOW_INVALID,
                            "草稿与既有修订 ${frozen.revisionId.value} 内容不一致，请先保存为新修订",
                        ),
                    )
                }
            }
        }
    }

    fun requestCancel(runId: VideoRunId) {
        val target = port ?: return
        viewModelScope.launch { target.requestCancel(runId) }
    }

    /**
     * Explicit retry intent (V1 requirement: re-run/cancel/retry from the production page).
     *
     * The owner mints a NEW attempt bound to this terminal run's frozen snapshot and recorded
     * seed; the terminal run itself is never mutated. Rejections are surfaced verbatim instead
     * of being swallowed or replaced by a fabricated new run.
     */
    fun retry(runId: VideoRunId) {
        val target = port ?: run {
            _retryNotice.value = "视频执行链尚未接入，无法重试"
            return
        }
        viewModelScope.launch {
            _retryNotice.value = null
            val result = runCatching { target.retry(runId) }.getOrElse {
                VideoSubmitResult.Rejected(
                    VideoErrorCode.NATIVE_EXECUTION_FAILED,
                    "重试请求失败：${it.message ?: it.javaClass.simpleName}",
                )
            }
            when (result) {
                is VideoSubmitResult.Admitted -> {
                    _lastSubmittedRunId.value = result.runId
                    refreshActiveRun()
                }

                is VideoSubmitResult.Rejected ->
                    _retryNotice.value = "重试被拒绝：${result.code.name} — ${result.detail}"
            }
        }
    }

    /** Probe an explicitly configured remote ComfyUI endpoint; never submits a task. */
    fun probeRemote(baseUrl: String, apiKey: String?, allowTargetedCancel: Boolean) {
        val remote = remotePort ?: run {
            _remoteState.value = RemoteUiState.Failed("BACKEND_UNAVAILABLE", "远程探测端口未装配")
            return
        }
        if (baseUrl.isBlank()) {
            _remoteState.value = RemoteUiState.Failed("INPUT_INVALID", "请填写 ComfyUI endpoint")
            return
        }
        viewModelScope.launch {
            _remoteState.value = RemoteUiState.Probing
            val binding = VideoRemoteEndpointBinding(
                endpointId = remoteEndpointId(baseUrl),
                baseUrl = baseUrl.trim(),
                apiKey = apiKey?.takeIf { it.isNotBlank() },
                allowTargetedCancel = allowTargetedCancel,
            )
            _remoteState.value = when (val result = remote.probe(binding)) {
                is VideoRemoteProbeResult.Ready -> RemoteUiState.Ready(result.capabilities)
                VideoRemoteProbeResult.AuthFailed -> RemoteUiState.AuthFailed
                is VideoRemoteProbeResult.Failed -> RemoteUiState.Failed(result.code.name, result.detail)
            }
        }
    }

    /**
     * Explicit remote submit. Local failure never reaches this path automatically; the caller must
     * press the remote action with a configured endpoint. Only an imported ComfyUI revision has
     * the raw API graph the remote executor needs, so the owner rejects native-only revisions.
     */
    fun submitRemote(
        baseUrl: String,
        apiKey: String?,
        allowTargetedCancel: Boolean,
        onResult: (VideoSubmitResult) -> Unit,
    ) {
        val target = port ?: run {
            onResult(VideoSubmitResult.Rejected(VideoErrorCode.BACKEND_UNAVAILABLE, "视频执行链尚未接入"))
            return
        }
        if (baseUrl.isBlank()) {
            onResult(VideoSubmitResult.Rejected(VideoErrorCode.INPUT_INVALID, "请填写 ComfyUI endpoint"))
            return
        }
        viewModelScope.launch {
            val draft = revisionBoundDraft(editor.document)
            when (val frozen = target.saveRevision(draft)) {
                is VideoRevisionResult.Saved -> {
                    val binding = VideoRemoteEndpointBinding(
                        endpointId = remoteEndpointId(baseUrl),
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey?.takeIf { it.isNotBlank() },
                        allowTargetedCancel = allowTargetedCancel,
                    )
                    val result = target.submit(
                        VideoSubmitCommand(
                            revisionId = frozen.revisionId,
                            backend = com.muyuchat.core.video.contract.VideoBackendKind.REMOTE_COMFY,
                            remoteBinding = binding,
                        ),
                    )
                    if (result is VideoSubmitResult.Admitted) {
                        _lastSubmittedRunId.value = result.runId
                        refreshActiveRun()
                    }
                    onResult(result)
                }

                is VideoRevisionResult.Conflict -> onResult(
                    VideoSubmitResult.Rejected(
                        VideoErrorCode.WORKFLOW_INVALID,
                        "草稿与既有修订 ${frozen.revisionId.value} 内容不一致，请先保存为新修订",
                    ),
                )
            }
        }
    }

    /**
     * Mint a content-addressed revision id for the current draft. Editing a saved draft must not
     * reuse its revision id: revisions are immutable, so the store would reject the changed graph.
     * The id is stable for identical executable content and excludes UI positions/raw source.
     */
    private fun revisionBoundDraft(document: VideoWorkflowDocument): VideoWorkflowDocument {
        val digest = VideoWorkflowCanon.canonicalExecutableSha256(document)
        return document.copy(revisionId = VideoRevisionId("rev-" + digest.take(16)))
    }

    private fun remoteEndpointId(baseUrl: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(baseUrl.trim().toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "remote-$digest"
    }

    /** Re-subscribes the run projection after a transport failure (owner stays untouched). */
    fun refreshRuns() {
        startRunsObservation()
    }

    /** Re-subscribes the media projection after a transport failure (owner stays untouched). */
    fun refreshMedia() {
        startMediaObservation()
    }

    /**
     * Deletes the MCA-owned copy through the port. The callback receives the port's real result;
     * the list itself is only changed by the next media projection emission, so a local "deleted"
     * state can never hide a failed deletion.
     */
    fun deleteMedia(mediaId: VideoMediaAssetId, onResult: (VideoDeleteResult) -> Unit) {
        val target = port ?: run {
            onResult(
                VideoDeleteResult.Failed(
                    mediaId = mediaId,
                    code = VideoErrorCode.BACKEND_UNAVAILABLE,
                    detail = "视频媒体库尚未接入",
                ),
            )
            return
        }
        viewModelScope.launch {
            val result = try {
                target.deleteMedia(mediaId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                VideoDeleteResult.Failed(
                    mediaId = mediaId,
                    code = VideoErrorCode.PUBLISH_FAILED,
                    detail = "删除时发生异常：${failure.message ?: failure.javaClass.simpleName}",
                )
            }
            onResult(result)
        }
    }

    /** Loads a previewed/saved import into the editor and persists the new draft. */
    fun loadImportedDocument(document: VideoWorkflowDocument) {
        editor.resetTo(document)
        _selectedNodeId.value = null
        _lastConnectRejection.value = null
        persistDraft()
    }

    /** Reads/previews a ComfyUI API-format document through the import port. */
    fun previewComfyApi(json: String, fileName: String?) {
        val target = importPort
        if (target == null) {
            _importState.value = ImportUiState.Rejected(
                fileName = fileName,
                code = VideoErrorCode.BACKEND_UNAVAILABLE,
                diagnostics = emptyList(),
                detail = "ComfyUI 导入端口尚未接入",
            )
            return
        }
        if (json.isBlank()) {
            _importState.value = ImportUiState.Rejected(
                fileName = fileName,
                code = null,
                diagnostics = emptyList(),
                detail = "所选文件内容为空",
            )
            return
        }
        importJob?.cancel()
        _importState.value = ImportUiState.Previewing(fileName)
        importJob = viewModelScope.launch {
            val preview = try {
                target.previewComfyApi(json, fileName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _importState.value = ImportUiState.Rejected(
                    fileName = fileName,
                    code = null,
                    diagnostics = emptyList(),
                    detail = "导入预览失败：${failure.message ?: failure.javaClass.simpleName}",
                )
                return@launch
            }
            _importState.value = when (preview) {
                is VideoImportPreview.Ready -> ImportUiState.Preview(
                    fileName = fileName,
                    json = json,
                    document = preview.document,
                    runnable = preview.runnable,
                    diagnostics = preview.diagnostics,
                )

                is VideoImportPreview.Rejected -> ImportUiState.Rejected(
                    fileName = fileName,
                    code = preview.code,
                    diagnostics = preview.diagnostics,
                )
            }
        }
    }

    /** Saves the currently previewed import as an immutable revision through the port. */
    fun saveImportedRevision() {
        val preview = _importState.value as? ImportUiState.Preview ?: return
        val target = importPort
        if (target == null) {
            _importState.value = ImportUiState.Rejected(
                fileName = preview.fileName,
                code = VideoErrorCode.BACKEND_UNAVAILABLE,
                diagnostics = emptyList(),
                detail = "ComfyUI 导入端口尚未接入",
            )
            return
        }
        importJob?.cancel()
        _importState.value = ImportUiState.Saving(
            fileName = preview.fileName,
            json = preview.json,
            document = preview.document,
            runnable = preview.runnable,
            diagnostics = preview.diagnostics,
        )
        importJob = viewModelScope.launch {
            val saved = try {
                target.saveComfyApiRevision(preview.json, preview.fileName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _importState.value = ImportUiState.Rejected(
                    fileName = preview.fileName,
                    code = null,
                    diagnostics = emptyList(),
                    detail = "保存修订失败：${failure.message ?: failure.javaClass.simpleName}",
                )
                return@launch
            }
            when (saved) {
                is VideoImportSaveResult.Saved -> {
                    loadImportedDocument(saved.document)
                    _importState.value = ImportUiState.Saved(
                        fileName = preview.fileName,
                        revisionId = saved.revisionId,
                        runnable = saved.runnable,
                        diagnostics = saved.diagnostics,
                    )
                }

                is VideoImportSaveResult.Rejected -> {
                    _importState.value = ImportUiState.Rejected(
                        fileName = preview.fileName,
                        code = saved.code,
                        diagnostics = saved.diagnostics,
                    )
                }
            }
        }
    }

    /** Local read/encoding failure before the port is called; never reported as a success. */
    fun failImportedFile(fileName: String?, detail: String) {
        importJob?.cancel()
        _importState.value = ImportUiState.Rejected(
            fileName = fileName,
            code = null,
            diagnostics = emptyList(),
            detail = detail,
        )
    }

    fun dismissImport() {
        if (_importState.value is ImportUiState.Saving) return
        importJob?.cancel()
        _importState.value = ImportUiState.Idle
    }

    private fun startRunsObservation() {
        val target = port ?: return
        runsJob?.cancel()
        runsJob = viewModelScope.launch {
            try {
                target.observeRuns().collect { projections ->
                    _runs.value = projections
                    _runsError.value = null
                    refreshActiveRun()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _runsError.value = failure.message ?: "运行记录读取失败"
            }
        }
    }

    private fun startMediaObservation() {
        val target = port ?: return
        mediaJob?.cancel()
        _mediaLoaded.value = false
        _mediaError.value = null
        mediaJob = viewModelScope.launch {
            try {
                target.observeMedia().collect { projections ->
                    _media.value = projections
                    _mediaLoaded.value = true
                    _mediaError.value = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _mediaLoaded.value = true
                _mediaError.value = failure.message ?: "作品读取失败"
            }
        }
    }

    private fun refreshActiveRun() {
        val projections = _runs.value
        val submittedRunId = _lastSubmittedRunId.value
        _activeRun.value = projections.firstOrNull { it.runId == submittedRunId }
            ?: projections.firstOrNull()
    }

    private fun restoreDraft(): VideoWorkflowDocument? {
        val saved = savedState.get<String>(KEY_DRAFT)
        if (saved != null) return VideoDraftCodec.decode(saved)
        val durable = runCatching { draftStore?.load() }.getOrNull() ?: return null
        return VideoDraftCodec.decode(durable)
    }

    private fun persistDraft() {
        val encoded = VideoDraftCodec.encode(editor.document)
        savedState[KEY_DRAFT] = encoded
        runCatching { draftStore?.save(encoded) }
    }

    companion object {
        private const val KEY_DRAFT = "video.workspace.draft"
        private const val KEY_INPUT_IMAGE = "video.workspace.input_image"
    }
}
