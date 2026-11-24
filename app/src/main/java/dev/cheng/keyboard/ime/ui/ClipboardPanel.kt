package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cheng.keyboard.data.ClipboardEntry
import dev.cheng.keyboard.data.MAX_RECENT_CLIPS
import dev.cheng.keyboard.data.clipboardDisplayEntries
import dev.cheng.keyboard.data.clipboardPreviewText
import dev.cheng.keyboard.ime.KeyboardController
import dev.cheng.keyboard.ime.UiState

private val ClipboardTileHeight = 40.dp
private val ClipboardMinimumPanelHeight = 140.dp
private val ClipboardMinimumContentHeight = ClipboardMinimumPanelHeight - 8.dp - 14.dp
private val ClipboardEmptyContentHeight = 48.dp
private val ClipboardTopPadding = 8.dp
private val ClipboardBottomPadding = 14.dp

@Composable
internal fun ClipboardPanel(
    state: UiState,
    palette: Palette,
    controller: KeyboardController,
) {
    val privateInput = state.engine.password || state.engine.noLearn
    val entries = if (privateInput) {
        emptyList()
    } else {
        clipboardDisplayEntries(state.clip, state.recentClips, state.pinnedClips, MAX_RECENT_CLIPS)
    }
    var preview by remember(state.menu, state.engine.password, state.engine.noLearn) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(
        state.menu,
        state.engine.password,
        state.engine.noLearn,
        state.engine.page,
        state.overlay,
        state.engine.raw,
    ) {
        preview = null
    }
    LaunchedEffect(entries, preview) {
        if (preview != null && entries.none { it.text == preview }) preview = null
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(palette.board)
            .padding(horizontal = 4.dp, vertical = 0.dp)
            .padding(top = ClipboardTopPadding, bottom = ClipboardBottomPadding),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val minimumCellWidth = 104.dp * fontScale.coerceAtLeast(1f)
        val columns = ((maxWidth.value + 6f) / (minimumCellWidth.value + 6f))
            .toInt()
            .coerceAtLeast(1)
        val visibleRows = if (entries.isEmpty()) {
            0
        } else {
            ((entries.size + columns - 1) / columns).coerceAtMost(3)
        }
        val gridHeight = when (visibleRows) {
            0 -> ClipboardEmptyContentHeight
            1 -> ClipboardTileHeight
            2 -> ClipboardTileHeight * 2 + 6.dp
            else -> ClipboardTileHeight * 3 + 12.dp
        }
        val headerHeight = if (fontScale >= 1.35f) {
            maxOf(24.dp, 18.dp * fontScale)
        } else {
            24.dp
        }
        val contentHeight = maxOf(
            headerHeight + 8.dp + gridHeight,
            ClipboardMinimumContentHeight,
        )
        val selectedEntry = preview?.let { text -> entries.firstOrNull { it.text == text } }
        Box(Modifier.fillMaxWidth().height(contentHeight)) {
            if (selectedEntry == null) {
                Column(Modifier.fillMaxSize()) {
                    ClipboardHeader(
                        count = entries.size,
                        privateInput = privateInput,
                        canClearRecent = !privateInput && (state.recentClips.isNotEmpty() || state.clip != null),
                        palette = palette,
                        height = headerHeight,
                        fontScale = fontScale,
                        onClearRecent = controller::clearRecentClips,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        privateInput || entries.isEmpty() -> Box(
                            Modifier.fillMaxWidth().height(gridHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (privateInput) "当前输入不显示剪贴板" else "复制文字后，会显示在这里",
                                color = palette.subtle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> ClipboardGrid(
                            entries = entries,
                            palette = palette,
                            columns = columns,
                            height = gridHeight,
                            onPaste = controller::pasteText,
                            onPreview = { preview = it },
                        )
                    }
                }
            } else {
                ClipboardPreview(
                    entry = selectedEntry,
                    palette = palette,
                    height = contentHeight,
                    onClose = { preview = null },
                    onPaste = { controller.pasteText(selectedEntry.text) },
                    onTogglePin = { controller.togglePinClip(selectedEntry.text) },
                    onDelete = {
                        controller.deleteClip(selectedEntry.text)
                        preview = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ClipboardHeader(
    count: Int,
    privateInput: Boolean,
    canClearRecent: Boolean,
    palette: Palette,
    height: androidx.compose.ui.unit.Dp,
    fontScale: Float,
    onClearRecent: () -> Unit,
) {
    val countLabel = when {
        privateInput -> "隐藏"
        else -> "${count}条"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "最近剪贴板",
            color = palette.subtle,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (fontScale >= 1.35f) {
                countLabel
            } else {
                "$countLabel · 长按管理"
            },
            color = palette.subtle,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            maxLines = 1,
        )
        if (canClearRecent) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .semantics {
                        contentDescription = "清空未置顶剪贴板"
                        role = Role.Button
                    }
                    .clickable(onClick = onClearRecent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "清空",
                    color = palette.accent,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipboardGrid(
    entries: List<ClipboardEntry>,
    palette: Palette,
    columns: Int,
    height: androidx.compose.ui.unit.Dp,
    onPaste: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { "${if (it.pinned) "p" else "r"}:${it.text}" }) { entry ->
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ClipboardTileHeight)
                    .clip(shape)
                    .background(palette.key)
                    .then(
                        if (entry.pinned) {
                            Modifier.border(0.7.dp, palette.accent.copy(alpha = 0.45f), shape)
                        } else {
                            Modifier
                        },
                    )
                    .combinedClickable(
                        onClick = { onPaste(entry.text) },
                        onLongClick = { onPreview(entry.text) },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (entry.pinned) {
                    PinIcon(palette.accent, Modifier.size(12.dp), pinned = true)
                }
                Text(
                    clipboardPreviewText(entry.text, maxCharacters = 5),
                    color = palette.label,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ClipboardPreview(
    entry: ClipboardEntry,
    palette: Palette,
    height: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
    onPaste: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entry.pinned) "完整内容 · 已置顶" else "完整内容",
                color = palette.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .semantics {
                        contentDescription = "关闭完整内容"
                        role = Role.Button
                    }
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                CloseIcon(palette.subtle, Modifier.size(16.dp))
            }
        }
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            Text(
                entry.text,
                color = palette.label,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (entry.pinned) palette.accentSoft else palette.funcKey)
                    .semantics {
                        contentDescription = if (entry.pinned) "取消置顶" else "置顶"
                        role = Role.Button
                    }
                    .clickable(onClick = onTogglePin)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PinIcon(
                        if (entry.pinned) palette.accent else palette.icon,
                        Modifier.size(14.dp),
                        pinned = entry.pinned,
                    )
                    Text(
                        if (entry.pinned) "已置顶" else "置顶",
                        color = if (entry.pinned) palette.accent else palette.label,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Box(
                Modifier
                    .size(width = 40.dp, height = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.funcKey)
                    .semantics {
                        contentDescription = "删除"
                        role = Role.Button
                    }
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                TrashIcon(palette.subtle, Modifier.size(16.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.accent)
                    .clickable(onClick = onPaste),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "粘贴",
                    color = palette.onAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
