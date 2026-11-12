package dev.cheng.keyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cheng.keyboard.ai.PolishTone
import dev.cheng.keyboard.engine.applyCase
import dev.cheng.keyboard.ime.KeyboardController
import dev.cheng.keyboard.ime.Overlay
import dev.cheng.keyboard.ime.ResultKind
import dev.cheng.keyboard.ime.UiState
import dev.cheng.keyboard.ime.gesture.PopupPreview
import dev.cheng.keyboard.ime.layout.PlacedKey
import dev.cheng.keyboard.ime.layout.hitKey
import dev.cheng.keyboard.ime.layout.nearbyLetters
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.model.Candidate
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
    val funcKey: Color,
    val pressed: Color,
    val funcPressed: Color,
    val label: Color,
    val hint: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val icon: Color,
    val subtle: Color,
    val sheet: Color,
    val sheetElevated: Color,
    val keyShadow: Color,
    val keyBorder: Color,
    val divider: Color,
)

private val DarkPalette = Palette(
    board = Color(0xFF161820),
    key = Color(0xFF2B2F3B),
    funcKey = Color(0xFF20242F),
    pressed = Color(0xFF3E4454),
    funcPressed = Color(0xFF333948),
    label = Color(0xFFF2F4F8),
    hint = Color(0xFF7D869C),
    accent = Color(0xFF4D8DF7),
    accentPressed = Color(0xFF3B78DF),
    accentSoft = Color(0x2B4D8DF7),
    onAccent = Color(0xFFFFFFFF),
    icon = Color(0xFFE3E7F2),
    subtle = Color(0xFF96A0B5),
    sheet = Color(0xFF1F232E),
    sheetElevated = Color(0xFF303544),
    keyShadow = Color(0x6606080D),
    keyBorder = Color(0x16FFFFFF),
    divider = Color(0x1FFFFFFF),
)

private val LightPalette = Palette(
    board = Color(0xFFECEEF3),
    key = Color(0xFFFFFFFF),
    funcKey = Color(0xFFD9DEE8),
    pressed = Color(0xFFCDD3E0),
    funcPressed = Color(0xFFC2C9D9),
    label = Color(0xFF161922),
    hint = Color(0xFF8891A5),
    accent = Color(0xFF2B6DE8),
    accentPressed = Color(0xFF1F58C6),
    accentSoft = Color(0x1C2B6DE8),
    onAccent = Color(0xFFFFFFFF),
    icon = Color(0xFF222736),
    subtle = Color(0xFF5D6679),
    sheet = Color(0xFFF7F8FC),
    sheetElevated = Color(0xFFFFFFFF),
    keyShadow = Color(0x29141A29),
    keyBorder = Color(0x12141A29),
    divider = Color(0x17182030),
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
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(palette.divider))
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

private val ToolbarHeight = 46.dp

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
    if (!composing) {
        FunctionRow(state, palette, controller)
        return
    }
    val preedit = state.engine.preedit.ifBlank { state.engine.raw }
    Column(Modifier.fillMaxWidth().height(ToolbarHeight).padding(horizontal = 8.dp)) {
        if (preedit.isNotEmpty()) {
            Text(
                preedit,
                color = palette.accent,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
            )
        }
        val candidateScroll = rememberScrollState()
        LaunchedEffect(state.engine.raw, state.engine.candidates.firstOrNull()?.text) {
            candidateScroll.scrollTo(0)
        }
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(candidateScroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.engine.candidates.forEachIndexed { index, candidate ->
                    val shown = displayCandidateText(state, candidate)
                    val primary = index == 0 && !state.engine.associating
                    val pillShape = RoundedCornerShape(10.dp)
                    Text(
                        shown,
                        color = if (primary) palette.accent else palette.label,
                        fontSize = 16.sp,
                        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(pillShape)
                            .background(if (primary) palette.accentSoft else Color.Transparent)
                            .candidateTap(
                                onClick = { controller.pick(index) },
                                onLongClick = { controller.forgetCandidate(index) },
                            )
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(width = 34.dp, height = 30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (state.expanded) palette.accentSoft else palette.funcKey.copy(alpha = 0.7f))
                    .clickable(onClick = controller::toggleExpanded)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                DismissIcon(
                    if (state.expanded) palette.accent else palette.icon,
                    Modifier.size(16.dp).rotate(if (state.expanded) 180f else 0f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.candidateTap(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

private fun displayCandidateText(state: UiState, candidate: Candidate): String {
    return if (state.engine.language == Language.English && !state.engine.associating) {
        applyCase(candidate.text, state.engine.raw)
    } else if (state.engine.language == Language.English && state.engine.associating) {
        val ref = if (state.engine.shift != ShiftState.Off) {
            candidate.text.replaceFirstChar { it.uppercaseChar() }
        } else {
            candidate.text
        }
        applyCase(candidate.text, ref)
    } else {
        candidate.text
    }
}

@Composable
private fun FunctionRow(state: UiState, palette: Palette, controller: KeyboardController) {
    val enabled = !state.engine.password
    val freshClip = state.freshClip?.takeIf { enabled }
    Row(
        Modifier.fillMaxWidth().height(ToolbarHeight).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(palette, active = state.menu, onClick = controller::toggleMenu) {
            GridIcon(if (state.menu) palette.accent else palette.icon, Modifier.size(20.dp))
        }
        Box(
            Modifier.weight(1f).padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val note = state.note?.takeIf { state.overlay == Overlay.None }
            if (state.canUndoPolish) {
                val undoShape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .clip(undoShape)
                        .background(palette.sheet)
                        .border(0.8.dp, palette.divider, undoShape)
                        .clickable { controller.undoPolish() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已替换", color = palette.subtle, fontSize = 12.sp, maxLines = 1)
                    Text(
                        " 撤回",
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            } else if (note != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.accentSoft)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        note,
                        color = palette.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (freshClip != null) {
                val clipShape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .clip(clipShape)
                        .background(palette.sheet)
                        .border(0.8.dp, palette.divider, clipShape)
                        .padding(start = 9.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f, fill = false)
                            .clickable { controller.pasteText(freshClip) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ClipboardIcon(palette.accent, Modifier.size(14.dp))
                        Text(
                            freshClip.replace('\n', ' ').take(16),
                            color = palette.label,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier
                            .padding(start = 2.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = controller::dismissFreshClip),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloseIcon(palette.subtle, Modifier.size(11.dp))
                    }
                }
            }
        }
        val aiClusterShape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .clip(aiClusterShape)
                .background(palette.sheet)
                .border(0.8.dp, palette.divider, aiClusterShape)
                .alpha(if (enabled) 1f else 0.38f)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val askActive = state.overlay == Overlay.Ask
            val translateActive = (state.overlay as? Overlay.Result)?.kind == ResultKind.Translate
            val polishActive = (state.overlay as? Overlay.Result)?.kind == ResultKind.Polish
            ToolButton(palette, enabled = enabled, active = askActive, compact = true, onClick = controller::ask) {
                AskIcon(if (askActive) palette.accent else palette.icon, Modifier.size(18.dp))
            }
            ToolButton(palette, enabled = enabled, active = translateActive, compact = true, onClick = controller::translate) {
                TranslateIcon(if (translateActive) palette.accent else palette.icon, Modifier.size(18.dp))
            }
            ToolButton(palette, enabled = enabled, active = polishActive, compact = true, onClick = controller::polish) {
                PolishIcon(if (polishActive) palette.accent else palette.icon, Modifier.size(18.dp))
            }
        }
        ToolButton(palette, onClick = controller::dismiss) {
            HideKeyboardIcon(palette.subtle, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ToolButton(
    palette: Palette,
    enabled: Boolean = true,
    active: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier
            .height(if (compact) 32.dp else 36.dp)
            .widthIn(min = if (compact) 36.dp else 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) palette.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
private fun MenuPanel(state: UiState, palette: Palette, controller: KeyboardController) {
    Column(
        Modifier
            .sheet(palette)
            .heightIn(max = 204.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val clips = if (state.recentClips.isNotEmpty()) state.recentClips else listOfNotNull(state.clip)
        if (clips.isNotEmpty() && !state.engine.password) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ClipboardIcon(palette.subtle, Modifier.size(13.dp))
                Text("最近剪贴板", color = palette.subtle, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                clips.forEach { item ->
                    val chipShape = RoundedCornerShape(10.dp)
                    Text(
                        item.replace('\n', ' ').take(20),
                        color = palette.label,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(chipShape)
                            .background(palette.key)
                            .border(0.6.dp, palette.keyBorder, chipShape)
                            .clickable { controller.pasteText(item) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("键盘高度", color = palette.subtle, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "${(state.heightScale * 100).toInt()}%",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = state.heightScale,
            onValueChange = controller::setHeight,
            valueRange = 0.8f..1.25f,
            modifier = Modifier.height(26.dp),
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.funcKey,
            ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.board)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = state.theme == mode
                Text(
                    when (mode) {
                        ThemeMode.System -> "跟随系统"
                        ThemeMode.Dark -> "深色"
                        ThemeMode.Light -> "浅色"
                    },
                    color = if (selected) palette.accent else palette.subtle,
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) palette.key else Color.Transparent)
                        .clickable { controller.setTheme(mode) }
                        .padding(vertical = 5.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickToggleChip(
                "模糊音",
                checked = state.fuzzy,
                palette = palette,
                onClick = { controller.setFuzzy(!state.fuzzy) },
            )
            QuickToggleChip(
                "按键振动",
                checked = state.haptic,
                palette = palette,
                onClick = { controller.setHaptic(!state.haptic) },
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = controller::openSettings)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsIcon(palette.accent, Modifier.size(15.dp))
                Text("更多设置", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun QuickToggleChip(
    label: String,
    checked: Boolean,
    palette: Palette,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (checked) palette.accentSoft else palette.key)
            .border(
                width = 0.8.dp,
                color = if (checked) palette.accent.copy(alpha = 0.4f) else palette.keyBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (checked) palette.accent else palette.label,
            fontSize = 12.5.sp,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
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
                        val keysNow = placementState.value.keys
                        val hit = hitKey(keysNow, down.position.x, down.position.y)
                        if (hit != null) {
                            val neighbors = nearbyLetters(keysNow, down.position.x, down.position.y, hit.model)
                            controller.onDown(hit.model, down.position.x, down.position.y, SystemClock.uptimeMillis(), neighbors)
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
    val separating = key.model.kind == KeyKind.Shift &&
        state.engine.language == Language.Chinese &&
        state.engine.raw.isNotEmpty() &&
        !state.engine.password
    val shiftActive = key.model.kind == KeyKind.Shift &&
        !separating &&
        state.engine.shift != ShiftState.Off
    val isFunction = when (key.model.kind) {
        KeyKind.Shift,
        KeyKind.Backspace,
        KeyKind.Language,
        KeyKind.PageLetters,
        KeyKind.PageNumber,
        KeyKind.PageSymbols,
        KeyKind.PageMore -> true
        else -> false
    }
    val fill = when {
        key.model.accent -> if (pressed) palette.accentPressed else palette.accent
        shiftActive -> if (pressed) palette.pressed else palette.key
        isFunction -> if (pressed) palette.funcPressed else palette.funcKey
        else -> if (pressed) palette.pressed else palette.key
    }
    val showUpper = state.engine.language == Language.English &&
        state.engine.shift != ShiftState.Off &&
        key.model.kind == KeyKind.Letter
    val corner = RoundedCornerShape(if (key.model.kind == KeyKind.Enter) 11.dp else 8.5.dp)
    Box(
        Modifier
            .offset { IntOffset(key.x.toInt(), key.y.toInt()) }
            .width(with(density) { key.w.toDp() })
            .height(with(density) { key.h.toDp() })
            .clip(corner)
            .background(if (pressed) fill else palette.keyShadow)
            .padding(bottom = if (pressed) 0.dp else 1.dp)
            .clip(corner)
            .background(fill)
            .border(
                width = 0.5.dp,
                color = if (shiftActive && state.engine.shift == ShiftState.Caps) palette.accent.copy(alpha = 0.45f) else palette.keyBorder,
                shape = corner,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (key.model.kind) {
            KeyKind.Letter -> {
                if (key.model.hint != null) {
                    Text(
                        key.model.hint,
                        color = palette.hint,
                        fontSize = 9.5.sp,
                        lineHeight = 10.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.5.dp, end = 4.dp),
                    )
                }
                Text(
                    if (showUpper) key.model.label.uppercase() else key.model.label,
                    color = palette.label,
                    fontSize = 21.sp,
                    fontWeight = if (showUpper) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.offset(y = 2.dp),
                )
            }
            KeyKind.Shift -> {
                if (separating) {
                    Text("分词", color = palette.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                } else {
                    val shiftColor = if (state.engine.shift == ShiftState.Caps) palette.accent else palette.icon
                    ShiftIcon(shiftColor, state.engine.shift, Modifier.size(20.dp))
                }
            }
            KeyKind.Backspace -> BackspaceIcon(palette.icon, Modifier.size(21.dp))
            KeyKind.Enter -> {
                val effectiveEnter = when (state.overlay) {
                    Overlay.Ask -> EnterKind.Send
                    Overlay.NeedKey -> EnterKind.Done
                    else -> state.enter
                }
                EnterIcon(palette.onAccent, Modifier.size(21.dp), kind = effectiveEnter)
            }
            KeyKind.Space -> {
                Text(
                    if (state.engine.language == Language.Chinese) "中文" else "English",
                    color = palette.subtle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .width(26.dp)
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(palette.divider),
                )
            }
            KeyKind.Language -> LanguageMark(state.engine.language, palette)
            else -> Text(
                key.model.label,
                color = if (key.model.accent) palette.onAccent else palette.label,
                fontSize = if (key.model.label.length > 1) 13.sp else 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun LanguageMark(language: Language, palette: Palette) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        GlobeIcon(palette.icon, Modifier.size(16.dp))
        Text(
            if (language == Language.Chinese) "中" else "EN",
            color = palette.subtle,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 10.sp,
        )
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
    val cell = 48.dp
    val gap = 3.dp
    val pad = 5.dp
    val barWidth = pad * 2 + cell * 3 + gap * 2
    val barHeight = 58.dp
    val barWidthPx = with(density) { barWidth.toPx() }
    val barHeightPx = with(density) { barHeight.toPx() }
    val maxX = (boardWidth - side - barWidthPx).coerceAtLeast(side)
    val x = (anchor.x + anchor.w / 2f - barWidthPx / 2f).coerceIn(side, maxX)
    val y = (anchor.y - barHeightPx + with(density) { 8.dp.toPx() }).coerceAtLeast(0f)
    val cells = listOf(Choice.Upper to popup.upper, Choice.Symbol to popup.symbol, Choice.Lower to popup.lower)
    val bubbleShape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .offset { IntOffset(x.toInt(), y.toInt()) }
            .size(barWidth, barHeight)
            .shadow(elevation = 6.dp, shape = bubbleShape, clip = false)
            .clip(bubbleShape)
            .background(palette.sheetElevated)
            .border(0.8.dp, palette.divider, bubbleShape)
            .padding(pad),
    ) {
        cells.forEachIndexed { index, (choice, label) ->
            val selected = popup.choice == choice
            Box(
                Modifier
                    .offset(x = (cell + gap) * index)
                    .size(cell)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) palette.accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) palette.onAccent else palette.label,
                    fontSize = if (selected) 22.sp else 19.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CandidateGrid(state: UiState, palette: Palette, controller: KeyboardController, keyHeight: Dp) {
    val rows = packCandidateRows(state)
    Column(
        Modifier
            .fillMaxWidth()
            .height(keyHeight * 4 + 30.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            val usedSpan = row.sumOf { it.span }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell ->
                    val primary = cell.index == 0 && !state.engine.associating
                    val cellShape = RoundedCornerShape(10.dp)
                    Text(
                        cell.shown,
                        color = if (primary) palette.accent else palette.label,
                        fontSize = 16.sp,
                        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(cell.span.toFloat())
                            .clip(cellShape)
                            .background(if (primary) palette.accentSoft else palette.key)
                            .border(
                                width = 0.6.dp,
                                color = if (primary) palette.accent.copy(alpha = 0.35f) else palette.keyBorder,
                                shape = cellShape,
                            )
                            .candidateTap(
                                onClick = { controller.pick(cell.index) },
                                onLongClick = { controller.forgetCandidate(cell.index) },
                            )
                            .padding(horizontal = 6.dp, vertical = 11.dp),
                    )
                }
                if (usedSpan < 4) {
                    Box(Modifier.weight((4 - usedSpan).toFloat()))
                }
            }
        }
    }
}

private data class PackedCandidate(val index: Int, val shown: String, val span: Int)

private fun packCandidateRows(state: UiState): List<List<PackedCandidate>> {
    val rows = ArrayList<List<PackedCandidate>>()
    var current = ArrayList<PackedCandidate>(4)
    var currentSpan = 0
    state.engine.candidates.forEachIndexed { index, candidate ->
        val shown = displayCandidateText(state, candidate)
        val visualWeight = shown.sumOf { ch -> if (ch.code > 127) 2 else 1 }
        val span = when {
            visualWeight >= 15 -> 4
            visualWeight >= 9 -> 2
            else -> 1
        }
        if (currentSpan + span > 4 && current.isNotEmpty()) {
            rows += current
            current = ArrayList(4)
            currentSpan = 0
        }
        current += PackedCandidate(index, shown, span)
        currentSpan += span
        if (currentSpan == 4) {
            rows += current
            current = ArrayList(4)
            currentSpan = 0
        }
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

@Composable
private fun AskPanel(state: UiState, palette: Palette, controller: KeyboardController) {
    Column(Modifier.sheet(palette)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskIcon(palette.accent, Modifier.size(16.dp))
            Text(
                "AI 询问",
                color = palette.label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            val thinkShape = RoundedCornerShape(8.dp)
            Text(
                "深度思考",
                color = if (state.askThinking) palette.accent else palette.subtle,
                fontSize = 11.5.sp,
                fontWeight = if (state.askThinking) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(thinkShape)
                    .background(if (state.askThinking) palette.accentSoft else Color.Transparent)
                    .border(
                        width = 0.6.dp,
                        color = if (state.askThinking) palette.accent.copy(alpha = 0.35f) else palette.divider,
                        shape = thinkShape,
                    )
                    .clickable(onClick = controller::toggleThinking)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = controller::closeOverlay),
                contentAlignment = Alignment.Center,
            ) {
                CloseIcon(palette.subtle, Modifier.size(13.dp))
            }
        }
        val pending = if (state.engine.raw.isEmpty()) "" else state.engine.preedit.ifBlank { state.engine.raw }
        val attached = state.askAttached
        if (attached != null) {
            Row(
                Modifier
                    .padding(top = 5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.board)
                    .clickable(onClick = controller::clearAttach)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "附带：${attached.replace('\n', ' ').take(34)}",
                    color = palette.subtle,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CloseIcon(palette.subtle, Modifier.size(11.dp))
            }
        }
        Column(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .heightIn(min = 34.dp, max = 104.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.board.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.askDraft.isEmpty() && pending.isEmpty()) {
                Text("输入问题，按回车发送…", color = palette.subtle, fontSize = 14.sp)
            }
            if (state.askDraft.isNotEmpty()) {
                Text(state.askDraft, color = palette.label, fontSize = 14.5.sp, lineHeight = 20.sp)
            }
            if (pending.isNotEmpty()) {
                Text(pending, color = palette.accent, fontSize = 14.sp)
            }
            if (state.askReason.isNotBlank()) {
                Text(
                    state.askReason,
                    color = palette.subtle,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            if (state.askAnswer.isNotBlank()) {
                Text(
                    state.askAnswer,
                    color = palette.label,
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        state.note?.let {
            Text(it, color = palette.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(
            Modifier
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionText(if (state.busy) "生成中" else "发送", palette, controller::sendAsk, primary = true)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyIcon(palette.accent, Modifier.size(16.dp))
            Text(
                "DeepSeek 密钥",
                color = palette.label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = controller::closeOverlay),
                contentAlignment = Alignment.Center,
            ) {
                CloseIcon(palette.subtle, Modifier.size(13.dp))
            }
        }
        Box(
            Modifier
                .padding(top = 7.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.board)
                .border(0.8.dp, palette.divider, RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Text(
                state.keyDraft.ifBlank { "用下方键盘输入或粘贴 sk-…" },
                color = if (state.keyDraft.isBlank()) palette.subtle else palette.label,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.note?.let {
            Text(it, color = palette.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionText("保存", palette, controller::saveKey, primary = true)
            ActionText("粘贴", palette, controller::pasteIntoDraft)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}

@Composable
private fun ResultPanel(kind: ResultKind, state: UiState, palette: Palette, controller: KeyboardController) {
    if (kind == ResultKind.Translate) {
        TranslatePanel(state, palette, controller)
        return
    }
    Column(Modifier.sheet(palette)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PolishIcon(palette.accent, Modifier.size(16.dp))
            Text(
                "润色",
                color = palette.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PolishTone.entries.forEach { tone ->
                        val on = state.polishTone == tone
                        val toneShape = RoundedCornerShape(9.dp)
                        Text(
                            tone.label,
                            color = if (on) palette.accent else palette.subtle,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier
                                .alpha(if (state.busy && !on) 0.5f else 1f)
                                .clip(toneShape)
                                .background(if (on) palette.accentSoft else Color.Transparent)
                                .border(
                                    width = 0.6.dp,
                                    color = if (on) palette.accent.copy(alpha = 0.38f) else palette.divider,
                                    shape = toneShape,
                                )
                                .clickable(enabled = !state.busy) { controller.polish(tone) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (state.resultSource.isNotBlank()) {
            Text(
                "原文：${state.resultSource.replace('\n', ' ')}",
                color = palette.subtle,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        when {
            state.polishCandidates.isNotEmpty() -> Column(
                Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 132.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.polishCandidates.forEachIndexed { index, candidate ->
                    val on = index == state.polishSelected
                    val cardShape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(if (on) palette.key else palette.board.copy(alpha = 0.68f))
                            .border(
                                width = if (on) 1.dp else 0.6.dp,
                                color = if (on) palette.accent.copy(alpha = 0.55f) else palette.divider,
                                shape = cardShape,
                            )
                            .clickable { controller.selectPolishCandidate(index) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                candidate.tag,
                                color = palette.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(palette.accentSoft)
                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                            )
                            if (on) {
                                Spacer(Modifier.weight(1f))
                                CheckIcon(palette.accent, Modifier.size(13.dp))
                            }
                        }
                        Text(
                            candidate.text,
                            color = palette.label,
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            state.busy -> Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.board.copy(alpha = 0.68f))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text("正在识别语境并润色…", color = palette.subtle, fontSize = 14.5.sp)
            }
            else -> state.note?.let {
                Text(it, color = palette.subtle, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        if (state.polishCandidates.isNotEmpty()) {
            state.note?.let {
                Text(it, color = palette.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionText("替换", palette, controller::replaceResult, primary = true)
            ActionText("插入", palette, controller::insertResult)
            ActionText("复制", palette, controller::copyResult)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}

@Composable
private fun TranslatePanel(state: UiState, palette: Palette, controller: KeyboardController) {
    Column(Modifier.sheet(palette)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TranslateIcon(palette.accent, Modifier.size(16.dp))
            Text(
                "翻译",
                color = palette.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = controller::closeOverlay),
                contentAlignment = Alignment.Center,
            ) {
                CloseIcon(palette.subtle, Modifier.size(13.dp))
            }
        }
        if (state.resultSource.isNotBlank()) {
            Text(
                "原文：${state.resultSource.replace('\n', ' ')}",
                color = palette.subtle,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .heightIn(min = 36.dp, max = 88.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.board.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                when {
                    state.resultText.isNotBlank() -> state.resultText
                    state.busy -> "正在生成…"
                    else -> state.note ?: ""
                },
                color = if (state.resultText.isNotBlank()) palette.label else palette.subtle,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        }
        if (state.resultText.isNotBlank()) {
            state.note?.let {
                Text(it, color = palette.accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionText("替换", palette, controller::replaceResult, primary = true)
            ActionText("插入", palette, controller::insertResult)
            ActionText("复制", palette, controller::copyResult)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}

private fun Modifier.sheet(palette: Palette): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = 8.dp, vertical = 4.dp)
    .clip(RoundedCornerShape(16.dp))
    .background(palette.sheet)
    .border(0.8.dp, palette.divider, RoundedCornerShape(16.dp))
    .padding(horizontal = 13.dp, vertical = 10.dp)

@Composable
private fun ActionText(
    label: String,
    palette: Palette,
    onClick: () -> Unit,
    primary: Boolean = label == "替换" || label == "发送" || label.startsWith("生成中") || label.startsWith("保存"),
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (primary) palette.accent else palette.key)
            .then(if (primary) Modifier else Modifier.border(0.6.dp, palette.keyBorder, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) palette.onAccent else palette.label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
