package com.muyuchat.feature.chat

/**
 * Hard upper bound for editing one already-sent user message.
 *
 * It is intentionally much larger than a typical composer line but still bounded so an accidental
 * paste cannot create a turn that no backend could admit.
 */
const val CHAT_MESSAGE_EDIT_MAX_CHARS: Int = 20_000
