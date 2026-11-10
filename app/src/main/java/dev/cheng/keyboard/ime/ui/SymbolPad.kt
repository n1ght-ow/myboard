package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cheng.keyboard.ime.KeyboardController
import dev.cheng.keyboard.ime.UiState
import dev.cheng.keyboard.ime.symbol.symbolPages

@Composable
internal fun SymbolPad(
    state: UiState,
    palette: Palette,
    controller: KeyboardController,
    keyHeight: Dp,
) {
    val page = symbolPages[state.symbolGroup.coerceIn(0, symbolPages.lastIndex)]
    val category = page[state.symbolIndex.coerceIn(0, page.lastIndex)]
    val symbols = if (state.symbolRecent) state.recentSymbols else category.symbols
    Column(
        Modifier
            .fillMaxWidth()
            .height(keyHeight * 4 + 36.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.width(84.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                page.forEachIndexed { index, item ->
                    CategoryButton(
                        item.name,
                        selected = !state.symbolRecent && index == state.symbolIndex.coerceIn(0, page.lastIndex),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { controller.selectSymbolCategory(index) },
                    )
                }
                CategoryButton(
                    label = null,
                    selected = false,
                    palette = palette,
                    modifier = Modifier.weight(0.9f),
                    onClick = controller::toggleSymbolLock,
                ) {
                    LockIcon(
                        if (state.symbolLocked) palette.accent else palette.icon,
                        Modifier.height(28.dp).width(28.dp),
                        locked = state.symbolLocked,
                    )
                }
                CategoryButton(
                    "返回",
                    selected = false,
                    palette = palette,
                    modifier = Modifier.weight(0.9f),
                    onClick = controller::closeSymbols,
                )
            }
            SymbolGrid(symbols, palette, controller, Modifier.weight(1f).fillMaxHeight())
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable(onClick = controller::cycleSymbolGroups).padding(6.dp)) {
                CycleIcon(palette.icon, Modifier.height(26.dp).width(26.dp))
            }
            Box(Modifier.weight(1f))
            Box(Modifier.clickable(onClick = controller::toggleSymbolRecent).padding(8.dp)) {
                StackIcon(if (state.symbolRecent) palette.accent else palette.icon, Modifier.height(22.dp).width(22.dp))
            }
        }
    }
}

@Composable
private fun CategoryButton(
    label: String?,
    selected: Boolean,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF2F78E6) else palette.key)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) icon()
        else Text(label.orEmpty(), color = if (selected) Color.White else palette.label, fontSize = 16.sp)
    }
}

@Composable
private fun SymbolGrid(
    symbols: List<String>,
    palette: Palette,
    controller: KeyboardController,
    modifier: Modifier,
) {
    val rows = symbols.chunked(4)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.key)
            .verticalScroll(rememberScrollState()),
    ) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有用过的符号", color = palette.subtle, fontSize = 15.sp)
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.board))
            Row(Modifier.fillMaxWidth().height(52.dp)) {
                row.forEachIndexed { column, symbol ->
                    if (column > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(palette.board))
                    Text(
                        symbol,
                        color = palette.label,
                        fontSize = if (symbol.length > 2) 14.sp else 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { controller.insertSymbol(symbol) }
                            .padding(vertical = 12.dp),
                    )
                }
                repeat(4 - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}
