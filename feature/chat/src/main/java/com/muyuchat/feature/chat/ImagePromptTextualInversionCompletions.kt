package com.muyuchat.feature.chat

import android.os.SystemClock
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll




import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ImagePromptTextualInversionCompletion(
    val id: String,
    val name: String,
    val trigger: String,
    val selected: Boolean,
)

internal fun imagePromptTextualInversionSuggestions(
    query: String,
    completions: List<ImagePromptTextualInversionCompletion>,
    limit: Int,
): List<ImagePromptTagSuggestion> {
    if (limit <= 0) return emptyList()
    val normalizedQuery = normalizeTextualInversionCompletionTerm(query)
    if (normalizedQuery.isEmpty()) return emptyList()

    return completions
        .distinctBy(ImagePromptTextualInversionCompletion::id)
        .mapIndexedNotNull { ordinal, completion ->
            val normalizedTrigger = normalizeTextualInversionCompletionTerm(completion.trigger)
            val normalizedName = normalizeTextualInversionCompletionTerm(completion.name)
            val triggerIndex = normalizedTrigger.indexOf(normalizedQuery)
            val nameIndex = normalizedName.indexOf(normalizedQuery)
            if (triggerIndex < 0 && nameIndex < 0) return@mapIndexedNotNull null
            val matchedByTrigger = triggerIndex >= 0 &&
                (nameIndex < 0 || triggerIndex <= nameIndex)
            RankedTextualInversionCompletion(
                prefixRank = if (triggerIndex == 0 || nameIndex == 0) 0 else 1,
                selectedRank = if (completion.selected) 0 else 1,
                matchOffset = when {
                    triggerIndex < 0 -> nameIndex
                    nameIndex < 0 -> triggerIndex
                    else -> minOf(triggerIndex, nameIndex)
                },
                normalizedTrigger = normalizedTrigger,
                ordinal = ordinal,
                suggestion = ImagePromptTagSuggestion(
                    replacementTag = completion.trigger,
                    category = 0,
                    postCount = 0L,
                    matchKind = if (matchedByTrigger) {
                        ImagePromptTagMatchKind.TAG_PREFIX
                    } else {
                        ImagePromptTagMatchKind.ALIAS_PREFIX
                    },
                    matchedText = if (matchedByTrigger) completion.trigger else completion.name,
                    translation = completion.name.takeUnless {
                        it.equals(completion.trigger, ignoreCase = true)
                    },
                    textualInversionId = completion.id,
                    textualInversionSelected = completion.selected,
                ),
            )
        }
        .sortedWith(
            compareBy<RankedTextualInversionCompletion>(
                RankedTextualInversionCompletion::prefixRank,
                RankedTextualInversionCompletion::selectedRank,
                RankedTextualInversionCompletion::matchOffset,
                RankedTextualInversionCompletion::normalizedTrigger,
                RankedTextualInversionCompletion::ordinal,
            )
        )
        .take(minOf(limit, TEXTUAL_INVERSION_SUGGESTION_LIMIT))
        .map(RankedTextualInversionCompletion::suggestion)
}

private data class RankedTextualInversionCompletion(
    val prefixRank: Int,
    val selectedRank: Int,
    val matchOffset: Int,
    val normalizedTrigger: String,
    val ordinal: Int,
    val suggestion: ImagePromptTagSuggestion,
)

internal fun normalizeTextualInversionCompletionTerm(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(' ', '_')
    .replace('-', '_')

private const val TEXTUAL_INVERSION_SUGGESTION_LIMIT: Int = 5
