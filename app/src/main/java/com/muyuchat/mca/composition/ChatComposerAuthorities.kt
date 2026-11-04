package com.muyuchat.mca.composition

import com.muyuchat.mca.ConversationRegenerationFields
import com.muyuchat.mca.ChatPromptDraftFields
import com.muyuchat.mca.MainViewModel
import com.muyuchat.mca.UiStateWriter

/**
 * Writer authorities for the composer quote and edit-and-resend delegates.
 *
 * The delegates live in the business layer (`com.muyuchat.mca`), and the architecture contract forbids
 * that layer from importing a composition writer factory — only the composition layer may issue
 * writers. These two accessors are the single reviewed seam: the delegate asks for an authority by
 * name, and the factory call stays here.
 */
internal val MainViewModel.chatPromptDraftAuthority: UiStateWriter<ChatPromptDraftFields>
    get() = uiStateStore.chatPromptDraftWriter()

internal val MainViewModel.conversationRegenerationAuthority: UiStateWriter<ConversationRegenerationFields>
    get() = uiStateStore.conversationRegenerationWriter()
