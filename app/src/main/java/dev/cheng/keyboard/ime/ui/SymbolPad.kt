package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cheng.keyboard.ime.KeyboardController
import dev.cheng.keyboard.ime.UiState
import dev.cheng.keyboard.ime.symbol.symbolCategories
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SymbolPad(
    state: UiState,
    palette: Palette,
    controller: KeyboardController,
    keyHeight: Dp,
) {
    val activeCategoryIndex = state.symbolIndex.coerceIn(0, symbolCategories.lastIndex)
    val category = symbolCategories[activeCategoryIndex]
    val symbols = if (state.symbolRecent) state.recentSymbols else category.symbols
    val railShape = RoundedCornerShape(11.dp)
    val railScroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .height(keyHeight * 4 + 30.dp)
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                Modifier
                    .width(68.dp)
                    .fillMaxHeight()
                    .clip(railShape)
                    .background(palette.sheet)
                    .border(0.6.dp, palette.divider, railShape)
                    .padding(3.dp)
                    .verticalScroll(railScroll),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                CategoryPill(
                    label = symbolCategories[0].name,
                    selected = !state.symbolRecent && activeCategoryIndex == 0,
                    palette = palette,
                    onClick = { controller.selectSymbolCategory(0) },
                )
                CategoryPill(
                    label = "最近",
                    selected = state.symbolRecent,
                    palette = palette,
                    onClick = controller::selectRecentSymbols,
                )
                for (index in 1..symbolCategories.lastIndex) {
                    CategoryPill(
                        label = symbolCategories[index].name,
                        selected = !state.symbolRecent && activeCategoryIndex == index,
                        palette = palette,
                        onClick = { controller.selectSymbolCategory(index) },
                    )
                }
            }
            SymbolGrid(
                symbols = symbols,
                scrollKey = activeCategoryIndex to state.symbolRecent,
                palette = palette,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(
            Modifier.fillMaxWidth().height(keyHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SymbolActionKey(
                label = "返回",
                palette = palette,
                modifier = Modifier.weight(1.35f),
                onClick = controller::closeSymbols,
            )
            SymbolActionKey(
                label = "123",
                palette = palette,
                modifier = Modifier.weight(1.35f),
                onClick = controller::openNumberPage,
            )
            SymbolLockKey(
                locked = state.symbolLocked,
                palette = palette,
                modifier = Modifier.weight(1.65f),
                onClick = controller::toggleSymbolLock,
            )
            SymbolSpaceKey(
                palette = palette,
                modifier = Modifier.weight(2.75f),
                onClick = controller::spaceFromSymbols,
            )
            SymbolRepeatBackspaceKey(
                palette = palette,
                modifier = Modifier.weight(1.50f),
                onTrigger = controller::backspaceFromSymbols,
            )
        }
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    palette: Palette,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.5.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(33.dp)
            .clip(shape)
            .background(if (selected) palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.onAccent else palette.label,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SymbolActionKey(
    label: String,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.5.dp)
    Box(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(palette.keyShadow)
            .padding(bottom = 1.dp)
            .clip(shape)
            .background(palette.funcKey)
            .border(0.5.dp, palette.keyBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = palette.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SymbolLockKey(
    locked: Boolean,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.5.dp)
    Row(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(palette.keyShadow)
            .padding(bottom = 1.dp)
            .clip(shape)
            .background(if (locked) palette.accentSoft else palette.funcKey)
            .border(
                width = 0.5.dp,
                color = if (locked) palette.accent.copy(alpha = 0.45f) else palette.keyBorder,
                shape = shape,
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        LockIcon(
            if (locked) palette.accent else palette.icon,
            Modifier.size(14.5.dp),
            locked = locked,
        )
        Text(
            "锁定",
            color = if (locked) palette.accent else palette.label,
            fontSize = 12.5.sp,
            fontWeight = if (locked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SymbolSpaceKey(
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.5.dp)
    Box(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(palette.keyShadow)
            .padding(bottom = 1.dp)
            .clip(shape)
            .background(palette.key)
            .border(0.5.dp, palette.keyBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "空格",
            color = palette.subtle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .width(24.dp)
                .height(2.dp)
                .clip(CircleShape)
                .background(palette.divider),
        )
    }
}

@Composable
private fun SymbolRepeatBackspaceKey(
    palette: Palette,
    modifier: Modifier,
    onTrigger: () -> Unit,
) {
    val shape = RoundedCornerShape(8.5.dp)
    val currentOnTrigger by rememberUpdatedState(onTrigger)
    var pressed by remember { mutableStateOf(false) }
    val fill = if (pressed) palette.funcPressed else palette.funcKey
    Box(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (pressed) fill else palette.keyShadow)
            .padding(bottom = if (pressed) 0.dp else 1.dp)
            .clip(shape)
            .background(fill)
            .border(0.5.dp, palette.keyBorder, shape)
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        currentOnTrigger()
                        val repeatJob = launch {
                            delay(380L)
                            while (true) {
                                currentOnTrigger()
                                delay(55L)
                            }
                        }
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            repeatJob.cancel()
                            pressed = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BackspaceIcon(palette.icon, Modifier.size(21.dp))
    }
}

private data class PackedSymbol(val text: String, val span: Int)

private fun packSymbolRows(symbols: List<String>, columns: Int = 5): List<List<PackedSymbol>> {
    val rows = ArrayList<List<PackedSymbol>>()
    var current = ArrayList<PackedSymbol>(columns)
    var used = 0
    for (symbol in symbols) {
        val span = if (symbol.length >= 4) 2 else 1
        if (used + span > columns && current.isNotEmpty()) {
            rows += current
            current = ArrayList(columns)
            used = 0
        }
        current += PackedSymbol(symbol, span)
        used += span
        if (used == columns) {
            rows += current
            current = ArrayList(columns)
            used = 0
        }
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

@Composable
private fun SymbolGrid(
    symbols: List<String>,
    scrollKey: Any,
    palette: Palette,
    controller: KeyboardController,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
    if (symbols.isEmpty()) {
        val emptyShape = RoundedCornerShape(11.dp)
        Box(
            modifier
                .clip(emptyShape)
                .background(palette.sheet)
                .border(0.6.dp, palette.divider, emptyShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StackIcon(palette.subtle, Modifier.size(22.dp))
                Text("还没有用过的符号", color = palette.subtle, fontSize = 13.5.sp)
            }
        }
        return
    }
    val columns = 5
    val rows = remember(symbols) { packSymbolRows(symbols, columns) }
    Column(
        modifier.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        rows.forEach { row ->
            val usedSpan = row.sumOf { it.span }
            Row(
                Modifier.fillMaxWidth().height(39.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row.forEach { cell ->
                    val symbol = cell.text
                    val capShape = RoundedCornerShape(8.5.dp)
                    Box(
                        Modifier
                            .weight(cell.span.toFloat())
                            .fillMaxHeight()
                            .clip(capShape)
                            .background(palette.keyShadow)
                            .padding(bottom = 1.dp)
                            .clip(capShape)
                            .background(palette.key)
                            .border(0.5.dp, palette.keyBorder, capShape)
                            .clickable { controller.insertSymbol(symbol) }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            symbol,
                            color = palette.label,
                            fontSize = when {
                                symbol.length >= 6 -> 13.sp
                                symbol.length >= 3 -> 14.sp
                                symbol.length == 2 -> 16.sp
                                else -> 19.sp
                            },
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (usedSpan < columns) {
                    Box(Modifier.weight((columns - usedSpan).toFloat()))
                }
            }
        }
    }
}
