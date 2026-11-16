package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cheng.keyboard.data.MAX_RECENT_CLIPS
import dev.cheng.keyboard.data.clipboardPreviewText
import dev.cheng.keyboard.ime.KeyboardController
import dev.cheng.keyboard.ime.UiState

private val ClipboardPanelHeight = 196.dp
private val ClipboardTileHeight = 40.dp
private val ClipboardGridHeight = 132.dp

@Composable
internal fun ClipboardPanel(
    state: UiState,
    palette: Palette,
    controller: KeyboardController,
) {
    val privateInput = state.engine.password || state.engine.noLearn
    val clips = if (privateInput) {
        emptyList()
    } else {
        (if (state.recentClips.isNotEmpty()) state.recentClips else listOfNotNull(state.clip))
            .distinct()
            .take(MAX_RECENT_CLIPS)
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
    LaunchedEffect(clips, preview) {
        if (preview != null && preview !in clips) preview = null
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(ClipboardPanelHeight)
            .background(palette.sheet)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val selected = preview
        if (selected == null) {
            Column(Modifier.fillMaxSize()) {
                ClipboardHeader(
                    count = clips.size,
                    privateInput = privateInput,
                    palette = palette,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    privateInput || clips.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(ClipboardGridHeight),
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
                        clips = clips,
                        palette = palette,
                        onPaste = controller::pasteText,
                        onPreview = { preview = it },
                    )
                }
            }
        } else {
            ClipboardPreview(
                text = selected,
                palette = palette,
                onClose = { preview = null },
                onPaste = { controller.pasteText(selected) },
            )
        }
    }
}

@Composable
private fun ClipboardHeader(
    count: Int,
    privateInput: Boolean,
    palette: Palette,
) {
    val fontScale = LocalDensity.current.fontScale
    val hint = when {
        privateInput -> "隐藏"
        count == 0 -> "点按粘贴 · 长按查看"
        else -> "$count 条 · 点按粘贴 · 长按查看"
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
        if (fontScale >= 1.35f) {
            Text(
                when {
                    privateInput -> "最近剪贴板 · 隐藏"
                    count == 0 -> "最近剪贴板"
                    else -> "最近剪贴板 · $count 条"
                },
                color = palette.label,
                fontSize = 14.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        } else if (maxWidth < 360.dp) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    "最近剪贴板",
                    color = palette.label,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    hint,
                    color = palette.subtle,
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近剪贴板",
                    color = palette.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    hint,
                    color = palette.subtle,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipboardGrid(
    clips: List<String>,
    palette: Palette,
    onPaste: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(ClipboardGridHeight)) {
        val minimumCellWidth = 104.dp * fontScale
        val columns = ((maxWidth.value + 6f) / (minimumCellWidth.value + 6f))
            .toInt()
            .coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(clips, key = { it }) { item ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ClipboardTileHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.key)
                        .combinedClickable(
                            onClick = { onPaste(item) },
                            onLongClick = { onPreview(item) },
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        clipboardPreviewText(item, maxCharacters = 5),
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
}

@Composable
private fun ClipboardPreview(
    text: String,
    palette: Palette,
    onClose: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "完整内容",
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
                text,
                color = palette.label,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
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
