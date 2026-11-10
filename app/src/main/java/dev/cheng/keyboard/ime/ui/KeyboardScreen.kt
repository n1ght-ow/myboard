package dev.cheng.keyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalContext
import dev.cheng.keyboard.engine.applyCase
import dev.cheng.keyboard.ime.KeyboardController
import dev.cheng.keyboard.ime.Overlay
import dev.cheng.keyboard.ime.ResultKind
import dev.cheng.keyboard.ime.UiState
import dev.cheng.keyboard.ime.gesture.PopupPreview
import dev.cheng.keyboard.ime.layout.PlacedKey
import dev.cheng.keyboard.ime.layout.hitKey
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.ime.layout.popupBox
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.model.ThemeMode

internal data class Palette(
    val board: Color,
    val key: Color,
    val pressed: Color,
    val label: Color,
    val hint: Color,
    val accent: Color,
    val icon: Color,
    val subtle: Color,
    val sheet: Color,
)

private val DarkPalette = Palette(
    board = Color(0xFF17181E),
    key = Color(0xFF3A3C44),
    pressed = Color(0xFF4C4E58),
    label = Color(0xFFF3F5FA),
    hint = Color(0x73F3F5FA),
    accent = Color(0xFF5B9AF2),
    icon = Color(0xFFE7E9F2),
    subtle = Color(0xFFA7B0C4),
    sheet = Color(0xFF242733),
)

private val LightPalette = Palette(
    board = Color(0xFFE4E7EF),
    key = Color.White,
    pressed = Color(0xFFD5D9E4),
    label = Color(0xFF17181E),
    hint = Color(0x7317181E),
    accent = Color(0xFF2F7FE0),
    icon = Color(0xFF1C2030),
    subtle = Color(0xFF5C6578),
    sheet = Color(0xFFF7F8FB),
)

@Composable
fun KeyboardRoot(controller: KeyboardController) {
    val state = controller.state
    val density = LocalDensity.current
    LaunchedEffect(density.density) { controller.configure(density.density) }
    val dark = when (state.theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette
    val configuration = LocalConfiguration.current
    val keyHeight = keyHeight(configuration.screenWidthDp, configuration.smallestScreenWidthDp, configuration.orientation, state.heightScale)
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val bottomPad = if (navPad > 0.dp) navPad else displayBottomPad(context, density)
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.board)
            .padding(bottom = bottomPad + 4.dp),
    ) {
        Toolbar(state, palette, controller)
        if (state.menu) MenuPanel(state, palette, controller)
        when (val overlay = state.overlay) {
            Overlay.None -> KeyArea(state, palette, controller, keyHeight)
            Overlay.Ask -> {
                AskPanel(state, palette, controller)
                KeyArea(state, palette, controller, keyHeight)
            }
            Overlay.NeedKey -> {
                NeedKeyPanel(state, palette, controller)
                KeyArea(state, palette, controller, keyHeight)
            }
            is Overlay.Result -> {
                ResultPanel(overlay.kind, state, palette, controller)
                KeyArea(state, palette, controller, keyHeight)
            }
        }
    }
}

private fun displayBottomPad(context: android.content.Context, density: androidx.compose.ui.unit.Density): Dp {
    if (android.os.Build.VERSION.SDK_INT < 30) return 24.dp
    val px = runCatching {
        val manager = context.getSystemService(android.view.WindowManager::class.java)
        val insets = manager.currentWindowMetrics.windowInsets
        val navigation = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
        val gesture = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom
        maxOf(navigation, gesture)
    }.getOrDefault(0)
    return if (px > 0) with(density) { px.toDp() } else 24.dp
}

private fun keyHeight(widthDp: Int, smallestDp: Int, orientation: Int, scale: Float): Dp {
    val tablet = smallestDp >= 600
    val landscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val base = when {
        tablet -> 52f
        landscape -> 42f
        else -> 48f
    }
    return (base * scale).dp
}

@Composable
private fun Toolbar(state: UiState, palette: Palette, controller: KeyboardController) {
    val composing = state.engine.raw.isNotEmpty() || state.engine.candidates.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!composing) {
            ToolButton(palette, onClick = controller::toggleMenu) { GridIcon(palette.icon, Modifier.size(22.dp)) }
            Box(Modifier.weight(1f))
        } else {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val preedit = state.engine.preedit.ifBlank { state.engine.raw }
                if (preedit.isNotEmpty()) {
                    Text(preedit, color = palette.accent, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                }
                state.engine.candidates.forEachIndexed { index, candidate ->
                    val shown = if (state.engine.language == Language.English && !state.engine.associating) {
                        applyCase(candidate.text, state.engine.raw)
                    } else {
                        candidate.text
                    }
                    Text(
                        shown,
                        color = palette.label,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { controller.pick(index) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            Text(
                if (state.expanded) "收起" else "展开",
                color = palette.subtle,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = controller::toggleExpanded).padding(horizontal = 6.dp),
            )
        }
        val iconSize = if (composing) 18.dp else 20.dp
        val enabled = !state.engine.password
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(palette.sheet)
                .alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(palette, enabled = enabled, onClick = controller::ask) { AskIcon(palette.icon, Modifier.size(iconSize)) }
            ToolButton(palette, enabled = enabled, onClick = controller::translate) { TranslateIcon(palette.icon, Modifier.size(iconSize)) }
            ToolButton(palette, enabled = enabled, onClick = controller::polish) { PolishIcon(palette.icon, Modifier.size(iconSize)) }
        }
        ToolButton(palette, onClick = controller::dismiss) { DismissIcon(palette.icon, Modifier.size(iconSize)) }
    }
}

@Composable
private fun ToolButton(
    palette: Palette,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier
            .height(44.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.then(if (enabled) Modifier else Modifier.background(Color.Transparent))) { icon() }
    }
}

@Composable
private fun MenuPanel(state: UiState, palette: Palette, controller: KeyboardController) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val clip = state.clip
        if (clip != null) {
            Text(
                "粘贴  ${clip.take(24)}",
                color = palette.label,
                fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = controller::paste).padding(vertical = 6.dp),
            )
        }
        Text("键盘高度", color = palette.subtle, fontSize = 12.sp)
        Slider(
            value = state.heightScale,
            onValueChange = controller::setHeight,
            valueRange = 0.8f..1.25f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                val selected = state.theme == mode
                Text(
                    when (mode) {
                        ThemeMode.System -> "跟随系统"
                        ThemeMode.Dark -> "深色"
                        ThemeMode.Light -> "浅色"
                    },
                    color = if (selected) palette.accent else palette.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) palette.key else Color.Transparent)
                        .clickable { controller.setTheme(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            "更多设置",
            color = palette.accent,
            modifier = Modifier.clickable(onClick = controller::openSettings).padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun KeyArea(state: UiState, palette: Palette, controller: KeyboardController, keyHeight: Dp) {
    if (state.engine.page == KeyboardPage.Symbols) {
        SymbolPad(state, palette, controller, keyHeight)
        return
    }
    if (state.expanded) {
        CandidateGrid(state, palette, controller, keyHeight)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { keyHeight.toPx() }
        val gapX = with(density) { 6.dp.toPx() }
        val gapY = with(density) { 10.dp.toPx() }
        val side = with(density) { 4.dp.toPx() }
        val placement = placeKeys(state.engine.page, state.engine.language, widthPx, heightPx, gapX, gapY, side)
        val placementState = rememberUpdatedState(placement)
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { placement.height.toDp() })
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val hit = hitKey(placementState.value.keys, down.position.x, down.position.y)
                        if (hit != null) {
                            controller.onDown(hit.model, down.position.x, down.position.y, SystemClock.uptimeMillis())
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (!change.pressed) {
                                if (hit != null) controller.onUp(SystemClock.uptimeMillis())
                                break
                            }
                            val current = hitKey(placementState.value.keys, change.position.x, change.position.y)
                            controller.onMove(change.position.x, change.position.y, SystemClock.uptimeMillis(), current?.model)
                            change.consume()
                        }
                    }
                },
        ) {
            placement.keys.forEach { key ->
                KeyCap(key, state, palette, keyHeight)
            }
            val popup = state.popup
            val anchor = placement.keys.firstOrNull { it.model.id == popup?.keyId }
            if (popup != null && anchor != null) {
                PopupKeys(popup, anchor, widthPx, side, gapX, palette, density)
            }
        }
    }
}

@Composable
private fun KeyCap(key: PlacedKey, state: UiState, palette: Palette, keyHeight: Dp) {
    val density = LocalDensity.current
    val pressed = state.pressedId == key.model.id
    val fill = when {
        key.model.accent -> palette.accent
        pressed -> palette.pressed
        else -> palette.key
    }
    val showUpper = state.engine.language == Language.English &&
        state.engine.shift != ShiftState.Off &&
        key.model.kind == KeyKind.Letter
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(key.x.toInt(), key.y.toInt()) }
            .width(with(density) { key.w.toDp() })
            .height(with(density) { key.h.toDp() })
            .clip(RoundedCornerShape(8.dp))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        when (key.model.kind) {
            KeyKind.Letter -> {
                if (key.model.hint != null) {
                    Text(
                        key.model.hint,
                        color = palette.hint,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp),
                    )
                }
                Text(
                    if (showUpper) key.model.label.uppercase() else key.model.label,
                    color = palette.label,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.offset(y = 3.dp),
                )
            }
            KeyKind.Shift -> {
                val separating = state.engine.language == Language.Chinese &&
                    state.engine.raw.isNotEmpty() &&
                    !state.engine.password
                if (separating) {
                    Text("分词", color = palette.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                } else {
                    ShiftIcon(palette.icon, state.engine.shift, Modifier.size(22.dp))
                }
            }
            KeyKind.Backspace -> BackspaceIcon(palette.icon, Modifier.size(22.dp))
            KeyKind.Enter -> {
                val label = enterLabel(state.enter)
                if (label == null) EnterIcon(Color.White, Modifier.size(26.dp))
                else Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            KeyKind.Space -> Text(
                if (state.engine.language == Language.Chinese) "中文" else "英文",
                color = palette.subtle,
                fontSize = 13.sp,
            )
            KeyKind.Language -> LanguageMark(state.engine.language, palette)
            else -> Text(
                key.model.label,
                color = if (key.model.accent) Color.White else palette.label,
                fontSize = if (key.model.label.length > 1) 15.sp else 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LanguageMark(language: Language, palette: Palette) {
    val top = if (language == Language.Chinese) "中" else "英"
    val bottom = if (language == Language.Chinese) "英" else "中"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(top, color = palette.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
        Text(bottom, color = palette.subtle, fontSize = 11.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun PopupKeys(
    popup: PopupPreview,
    anchor: PlacedKey,
    boardWidth: Float,
    side: Float,
    gapX: Float,
    palette: Palette,
    density: androidx.compose.ui.unit.Density,
) {
    val cell = 52.dp
    val gap = 2.dp
    val pad = 6.dp
    val barWidth = pad * 2 + cell * 3 + gap * 2
    val barHeight = 64.dp
    val barWidthPx = with(density) { barWidth.toPx() }
    val barHeightPx = with(density) { barHeight.toPx() }
    val maxX = (boardWidth - side - barWidthPx).coerceAtLeast(side)
    val x = (anchor.x + anchor.w / 2f - barWidthPx / 2f).coerceIn(side, maxX)
    val y = (anchor.y - barHeightPx + with(density) { 10.dp.toPx() }).coerceAtLeast(0f)
    val cells = listOf(Choice.Upper to popup.upper, Choice.Symbol to popup.symbol, Choice.Lower to popup.lower)
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
            .size(barWidth, barHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.pressed)
            .padding(pad),
    ) {
        cells.forEachIndexed { index, (choice, label) ->
            val selected = popup.choice == choice
            Box(
                Modifier
                    .offset(x = (cell + gap) * index)
                    .size(cell)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) palette.accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else palette.label,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CandidateGrid(state: UiState, palette: Palette, controller: KeyboardController, keyHeight: Dp) {
    val rows = state.engine.candidates.chunked(4)
    Column(
        Modifier.fillMaxWidth().height(keyHeight * 4 + 30.dp).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.take(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { candidate ->
                    val index = state.engine.candidates.indexOf(candidate)
                    Text(
                        candidate.text,
                        color = palette.label,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.key)
                            .clickable { controller.pick(index) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AskPanel(state: UiState, palette: Palette, controller: KeyboardController) {
    Column(Modifier.sheet(palette)) {
        val pending = if (state.engine.raw.isEmpty()) "" else state.engine.preedit.ifBlank { state.engine.raw }
        if (state.askDraft.isEmpty() && pending.isEmpty()) {
            Text("输入问题，回车发送", color = palette.subtle, fontSize = 16.sp)
        }
        if (state.askDraft.isNotEmpty()) Text(state.askDraft, color = palette.label, fontSize = 16.sp)
        if (pending.isNotEmpty()) Text(pending, color = palette.accent, fontSize = 16.sp)
        val attached = state.askAttached
        if (attached != null) {
            Text(
                "附带：${attached.take(40)}  ×",
                color = palette.subtle,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = controller::clearAttach).padding(top = 4.dp),
            )
        }
        if (state.askReason.isNotBlank()) {
            Text(state.askReason, color = palette.subtle, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (state.askAnswer.isNotBlank()) {
            Text(state.askAnswer, color = palette.label, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
        state.note?.let { Text(it, color = palette.accent, fontSize = 12.sp) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionText(if (state.busy) "生成中" else "发送", palette, controller::sendAsk)
            ActionText("粘贴", palette, controller::pasteIntoDraft)
            ActionText("插入", palette, controller::insertResult)
            ActionText("复制", palette, controller::copyResult)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}

@Composable
private fun NeedKeyPanel(state: UiState, palette: Palette, controller: KeyboardController) {
    Column(Modifier.sheet(palette)) {
        Text("DeepSeek 密钥", color = palette.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            state.keyDraft.ifBlank { "用键盘输入 sk-…" },
            color = if (state.keyDraft.isBlank()) palette.subtle else palette.label,
            fontSize = 15.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        state.note?.let { Text(it, color = palette.accent, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionText("粘贴", palette, controller::pasteIntoDraft)
            ActionText("保存", palette, controller::saveKey)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}

@Composable
private fun ResultPanel(kind: ResultKind, state: UiState, palette: Palette, controller: KeyboardController) {
    Column(Modifier.sheet(palette)) {
        Text(if (kind == ResultKind.Translate) "翻译" else "润色", color = palette.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (state.resultSource.isNotBlank()) {
            Text("原文：${state.resultSource}", color = palette.subtle, fontSize = 12.sp, maxLines = 1)
        }
        Text(
            when {
                state.resultText.isNotBlank() -> state.resultText
                state.busy -> "正在生成…"
                else -> state.note ?: ""
            },
            color = palette.label,
            fontSize = 16.sp,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .heightIn(max = 72.dp)
                .verticalScroll(rememberScrollState()),
        )
        if (state.resultText.isNotBlank()) {
            state.note?.let { Text(it, color = palette.accent, fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionText("替换", palette, controller::replaceResult)
            ActionText("插入", palette, controller::insertResult)
            ActionText("复制", palette, controller::copyResult)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}

private fun Modifier.sheet(palette: Palette): Modifier = this
    .padding(horizontal = 8.dp, vertical = 6.dp)
    .clip(RoundedCornerShape(16.dp))
    .background(palette.sheet)
    .padding(horizontal = 14.dp, vertical = 12.dp)

@Composable
private fun ActionText(label: String, palette: Palette, onClick: () -> Unit) {
    Text(
        label,
        color = palette.accent,
        fontSize = 15.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

private fun enterLabel(kind: EnterKind): String? = when (kind) {
    EnterKind.Newline -> null
    EnterKind.Go -> "前往"
    EnterKind.Search -> "搜索"
    EnterKind.Send -> "发送"
    EnterKind.Done -> "完成"
    EnterKind.Next -> "下一项"
}
