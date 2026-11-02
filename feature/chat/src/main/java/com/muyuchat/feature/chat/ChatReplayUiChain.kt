package com.muyuchat.feature.chat

/**
 * R10.3: the pure half of the replay -> UI chain.
 *
 * A replayed answer frame is only text. What a user actually sees is that text projected through
 * the message page budget and the Markdown parser, and then the renderer branch the chat surface
 * selects for every parsed block. All of those rules already ship in this module:
 *
 * - [MESSAGE_RENDER_PAGE_CHARS] plus [safePrefix] produce the displayed page;
 * - [markdownProjectionNeedsBackground] decides whether the parse may run on the composition fast
 *   path or has to be produced by [MarkdownProjectionController] off the main thread;
 * - [parseMarkdownBlocks] produces the block list from the displayed page plus the complete body;
 * - [MarkdownProjectionRequest.canDisplay] decides whether the previous projection may stay on
 *   screen while a newer frame is pending.
 *
 * This file composes exactly those shipped rules into one deterministic function, so a fixture can
 * assert "frame in, projection + renderer branch out" without a device and without a Compose
 * runtime: everything here is plain Kotlin over plain data. The Composable renderer's own block
 * dispatch lives in `ChatMarkdownBlockRenderers.kt`; [ChatReplayRenderBranch] and
 * [renderBranchOf] mirror it one-to-one, and `ChatReplayUiChainTest` pins that mirror against the
 * renderer source so the two cannot drift silently.
 *
 * Nothing here draws, and nothing here is a second rendering path: the driver never mutates state,
 * keeps no history and is never called by composition. The only stateful rule it delegates to is
 * [MarkdownProjectionRequest.canDisplay], which is called on the real request objects.
 */

/**
 * The renderer the chat surface selects for one parsed block.
 *
 * The mapping mirrors `AssistantRichText`'s `when (block)` in `ChatMarkdownBlockRenderers.kt`
 * exactly: the renderer family, not a new taxonomy.
 */
public enum class ChatReplayRenderBranch {
    /** `MarkdownBlock.Code` -> `CodeBlock`. */
    CODE,

    /** `MarkdownBlock.Table` -> `TableBlock`. */
    TABLE,

    /** `MarkdownBlock.BulletList` / `MarkdownBlock.NumberedList` -> `ListBlock` / `NumberedListBlock`. */
    LIST,

    /** Paragraph / heading / quote -> the inline rich-text renderers. */
    RICH,

    /** `MarkdownBlock.Divider` -> `HorizontalDivider`. */
    DIVIDER
}

/**
 * One frame of a replayed answer: the complete body the page holds at that point.
 *
 * [visibleCharacters] is the page budget the renderer is currently showing, not the body length.
 * The renderer starts at [MESSAGE_RENDER_PAGE_CHARS] and grows by one page per "show more" tap, so
 * a replay that wants to reproduce a paged body passes the grown budget explicitly.
 */
public data class ChatReplayFrame(
    val content: String,
    val owner: ChatMessageIdentity? = null,
    val visibleCharacters: Int = MESSAGE_RENDER_PAGE_CHARS
) {
    init {
        require(visibleCharacters >= 0) { "visibleCharacters must not be negative: $visibleCharacters" }
    }
}

/**
 * The projection plus renderer selection one [ChatReplayFrame] produces.
 *
 * [request] is the exact [MarkdownProjectionRequest] the renderer would build for this frame, so
 * [ChatReplayUiChain.keepsPreviousProjection] can run the shipped [canDisplay] gate instead of a
 * reimplementation of it. The block list is kept for the module's own tests; the public surface of
 * this class is the summary a fixture can assert on.
 */
public class ChatReplayFrameProjection internal constructor(
    internal val request: MarkdownProjectionRequest,
    internal val blocks: List<MarkdownBlock>,
    /** Renderer branch per parsed block, in renderer order; never empty. */
    public val blockBranches: List<ChatReplayRenderBranch>,
    /** Engineering reason when the parser failed and the renderer had to fall back to source. */
    public val failure: String?
) {
    /** The displayed page: the body cut to the page budget, never splitting a surrogate pair. */
    public val displayContent: String get() = request.displayContent

    /** The complete body, including content past the page budget (the code copy payload source). */
    public val fullContent: String get() = request.fullContent

    /** Length of the complete body in UTF-16 units. */
    public val contentLength: Int get() = request.contentLength

    /** Page budget the frame is displayed under. */
    public val visibleCharacters: Int get() = request.visibleCharacters

    /** Message identity the projection belongs to. */
    public val owner: ChatMessageIdentity? get() = request.owner

    /** Number of parsed blocks; always at least one, so the renderer always selects a branch. */
    public val blockCount: Int get() = blocks.size

    /** The branch of the first block, i.e. the first renderer the page selects. */
    public val primaryBranch: ChatReplayRenderBranch get() = blockBranches.first()

    /** Distinct branches this frame selects, in renderer order. */
    public val branches: Set<ChatReplayRenderBranch> get() = LinkedHashSet(blockBranches)

    /** Whether this frame would be produced by [MarkdownProjectionController] instead of the fast path. */
    public val needsBackgroundProjection: Boolean
        get() = markdownProjectionNeedsBackground(displayContent, fullContent)

    public fun hasBranch(branch: ChatReplayRenderBranch): Boolean = blockBranches.contains(branch)
}

/**
 * Stateless driver behind the controlled-replay UI-chain assertions.
 *
 * The three functions answer, in order: what does one frame hold, what does the renderer select for
 * it, and may the previous projection stay on screen while it is parsed.
 */
public object ChatReplayUiChain {

    /** The synchronous parse budget the renderer uses ([MARKDOWN_SYNC_PARSE_BUDGET_CHARS]). */
    public val syncParseBudgetCharacters: Int = MARKDOWN_SYNC_PARSE_BUDGET_CHARS

    /** The first page the renderer shows ([MESSAGE_RENDER_PAGE_CHARS]). */
    public val initialPageCharacters: Int = MESSAGE_RENDER_PAGE_CHARS

    /** Builds a frame from a replayed body; [owner] and the page budget are optional. */
    public fun frame(
        content: String,
        owner: ChatMessageIdentity? = null,
        visibleCharacters: Int = MESSAGE_RENDER_PAGE_CHARS
    ): ChatReplayFrame = ChatReplayFrame(content = content, owner = owner, visibleCharacters = visibleCharacters)

    /**
     * Projects one frame through the shipped page, budget and parser rules.
     *
     * The result is the same block list the renderer ends up with: the fast path parses inline, and
     * the page above [syncParseBudgetCharacters] is parsed by [MarkdownProjectionController] with
     * this exact request. A parser failure keeps the readable source paragraph and reports
     * [ChatReplayFrameProjection.failure], which is the controller's fallback, not a new one.
     */
    public fun project(frame: ChatReplayFrame): ChatReplayFrameProjection {
        val display = frame.content.safePrefix(frame.visibleCharacters)
        val request = MarkdownProjectionRequest(
            owner = frame.owner,
            visibleCharacters = frame.visibleCharacters,
            contentLength = frame.content.length,
            displayContent = display,
            fullContent = frame.content
        )
        var failure: String? = null
        val blocks: List<MarkdownBlock> = try {
            parseMarkdownBlocks(display, frame.content)
        } catch (error: Exception) {
            failure = error.toString()
            listOf(MarkdownBlock.Paragraph(display))
        }
        return ChatReplayFrameProjection(
            request = request,
            blocks = blocks,
            blockBranches = blocks.map(::renderBranchOf),
            failure = failure
        )
    }

    /**
     * Whether [next] may keep [previous] on screen while it is parsed, per the shipped
     * [MarkdownProjectionRequest.canDisplay] gate.
     *
     * [previous] `null` (first frame of a run) can never be displayed. A replaced body, a shrunk
     * page, a different page budget or a different message identity all invalidate it.
     */
    public fun keepsPreviousProjection(
        previous: ChatReplayFrameProjection?,
        next: ChatReplayFrameProjection
    ): Boolean {
        if (previous == null) return false
        // canDisplay only reads the result's own request; the block list is irrelevant here.
        return next.request.canDisplay(MarkdownProjectionResult(request = previous.request, blocks = previous.blocks))
    }

    /** Mirrors `AssistantRichText`'s block dispatch. */
    internal fun renderBranchOf(block: MarkdownBlock): ChatReplayRenderBranch = when (block) {
        is MarkdownBlock.Code -> ChatReplayRenderBranch.CODE
        is MarkdownBlock.Table -> ChatReplayRenderBranch.TABLE
        is MarkdownBlock.BulletList, is MarkdownBlock.NumberedList -> ChatReplayRenderBranch.LIST
        is MarkdownBlock.Heading, is MarkdownBlock.Paragraph, is MarkdownBlock.Quote -> ChatReplayRenderBranch.RICH
        MarkdownBlock.Divider -> ChatReplayRenderBranch.DIVIDER
    }
}
