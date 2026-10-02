package com.muyuchat.feature.chat

// The chat surface colour tokens, moved verbatim out of ChatScreen.kt.
//
// These seven values were private to ChatScreen.kt, so nothing outside that file could see them.
// Splitting the image workspace out of that file made them shared: both ChatScreen.kt and
// ImageWorkspaceScreens.kt read them, and a private top-level declaration is visible only within
// its own file. Kotlin has no package-private visibility: top-level private is file-scoped and
// internal is module-scoped, so sharing these across files requires internal. The values,
//
// McaStatusAmber is included even though the image workspace does not use it: it belongs to the
// same block, and splitting the block would have separated a KDoc from the value it documents.

import androidx.compose.ui.graphics.Color
// The only edit in this move is the visibility keyword: private val became internal val.

internal val McaPrimaryBlue = Color(0xFF3F7DE8)

/** Thermal / pressure warning tone for the device-status overlay. */
internal val McaStatusAmber = Color(0xFFD98324)
internal val McaInputShell = Color(0xFFFEFEFF)
internal val McaInputField = Color(0xFFF3F7FF)
internal val McaInputIconSurface = Color(0xFFEAF1FF)
internal val McaInputText = Color(0xFF1F2937)
internal val McaInputPlaceholder = Color(0xFF8A93A3)