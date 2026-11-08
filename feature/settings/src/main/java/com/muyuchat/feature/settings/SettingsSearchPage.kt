package com.muyuchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.feature.settings.search.SettingsSearchEntry
import com.muyuchat.feature.settings.search.SettingsSearchHit
import com.muyuchat.feature.settings.search.searchSettings

/**
 * One result group: the heading rendered above the rows that share [group].
 *
 * The heading is the index's own group string (the page heading a row lives under), so the list can
 * never invent a heading the user cannot find on the page itself.
 */
internal data class SettingsSearchGroup(
    val group: String,
    val hits: List<SettingsSearchHit>
)

/**
 * Rows rendered for [query], strongest match first.
 *
 * A blank query deliberately returns **nothing** rather than the whole index: the dashboard already
 * shows every section as a card, so a full dump would only repeat it as 54 unranked rows and invite
 * taps that land on a section instead of the row the user picked. The page renders a hint instead.
 *
 * Pure and synchronous: no network, no coroutine, no I/O, so the field can filter on every keystroke.
 */
internal fun settingsSearchResults(query: String): List<SettingsSearchHit> =
    if (query.isBlank()) emptyList() else searchSettings(query)

/**
 * Groups [hits] by their index group, preserving the matcher's ordering inside each group and the
 * first-appearance order of the groups themselves, so the rendered list is reproducible for an
 * unchanged query and [hits] is never reordered or dropped (every hit appears exactly once).
 */
internal fun settingsSearchGroups(hits: List<SettingsSearchHit>): List<SettingsSearchGroup> =
    hits.groupBy { it.entry.group }
        .map { (group, groupHits) -> SettingsSearchGroup(group, groupHits) }

/**
 * The dashboard's search affordance.
 *
 * Built from the shared [McaTextField] so it carries the exact field metrics, placeholder and icon
 * slot every other field in the app uses; no bespoke search-bar styling is introduced here.
 *
 * The field is read-only and a transparent tap layer sits on top: a read-only Material field still
 * claims its own pointer input to take focus, which would swallow the first tap. Typing happens on
 * [SettingsSearchPage], where the field is editable.
 */
@Composable
internal fun SettingsSearchEntryField(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth()) {
        McaTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            showClearButton = false,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(SEARCH_TITLE) },
            leadingIcon = { Icon(McaIcons.Search, contentDescription = null) }
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    role = Role.Button,
                    onClickLabel = "打开$SEARCH_TITLE",
                    onClick = onClick
                )
        )
    }
}

/**
 * Settings search results.
 *
 * **Jump semantics are row-level where the owning page supports it.** Pressing a result reports the
 * whole [SettingsSearchEntry]; the host opens `entry.section` and asks that page to reveal
 * `entry.spotId` (scroll + brief ring, see `SettingsReveal.kt`). Rows listed in
 * `SettingsRevealDegradation` still resolve to their section page only - each one is named there with
 * the reason, so a partial jump is never presented as a full one.
 */
@Composable
internal fun SettingsSearchPage(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onOpenSection: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberMcaHaptics()
    val groups = remember(query) { settingsSearchGroups(settingsSearchResults(query)) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            McaIconButton(contentDescription = "返回设置中心", onClick = onClose) {
                Icon(McaIcons.Back, contentDescription = null)
            }
            Text(
                text = SEARCH_TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
        }
        McaTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("输入关键词，例如 模型、缓存、外观") },
            leadingIcon = { Icon(McaIcons.Search, contentDescription = null) }
        )
        Spacer(Modifier.height(10.dp))
        when {
            query.isBlank() -> SearchNotice("输入关键词搜索设置项。")
            groups.isEmpty() -> SearchNotice("没有找到匹配的设置项，换个关键词试试。")
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { group ->
                    item(key = "group-${group.group}") {
                        Text(
                            text = group.group,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(group.hits, key = { it.entry.spotId }) { hit ->
                        SettingsSearchHitRow(
                            hit = hit,
                            onClick = {
                                haptics.tick()
                                onOpenSection(hit.entry)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** A quiet, full-width line for the two non-list states (blank query, no match). */
@Composable
private fun SearchNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * One result row: the row title plus the section it belongs to, because a hit's group heading is the
 * page and the section name is the card the user came from. Same 8dp card language, hairline border
 * and chevron as the other settings list rows; no new visual vocabulary.
 */
@Composable
private fun SettingsSearchHitRow(
    hit: SettingsSearchHit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(
                role = Role.Button,
                onClickLabel = "打开${hit.entry.section.title}",
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = hit.entry.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = hit.entry.section.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = McaIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Page title and dashboard placeholder label: one string so the two entry points cannot drift. */
private const val SEARCH_TITLE = "搜索设置"
