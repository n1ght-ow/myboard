package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val groupIndex = state.symbolGroup.coerceIn(0, symbolPages.lastIndex)
    val page = symbolPages[groupIndex]
    val activeCategoryIndex = state.symbolIndex.coerceIn(0, page.lastIndex)
    val category = page[activeCategoryIndex]
    val symbols = if (state.symbolRecent) state.recentSymbols else category.symbols
    val railShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(keyHeight * 4 + 30.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            Modifier
                .width(74.dp)
                .fillMaxHeight()
                .clip(railShape)
                .background(palette.sheet)
                .border(0.6.dp, palette.divider, railShape)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            page.forEachIndexed { index, item ->
                CategoryPill(
                    label = item.name,
                    selected = !state.symbolRecent && index == activeCategoryIndex,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { controller.selectSymbolCategory(index) },
                )
            }
            val cycleShape = RoundedCornerShape(9.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(cycleShape)
                    .background(palette.funcKey.copy(alpha = 0.72f))
                    .clickable(onClick = controller::cycleSymbolGroups)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                CycleIcon(palette.accent, Modifier.size(14.dp))
                Text(
                    "${groupIndex + 1}/${symbolPages.size}",
                    color = palette.subtle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SymbolGrid(
                symbols = symbols,
                scrollKey = Triple(groupIndex, activeCategoryIndex, state.symbolRecent),
                palette = palette,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SymbolToolChip(
                    label = "最近",
                    active = state.symbolRecent,
                    palette = palette,
                    onClick = controller::toggleSymbolRecent,
                ) {
                    StackIcon(
                        if (state.symbolRecent) palette.accent else palette.icon,
                        Modifier.size(15.dp),
                    )
                }
                SymbolToolChip(
                    label = if (state.symbolLocked) "已锁定" else "锁定",
                    active = state.symbolLocked,
                    palette = palette,
                    onClick = controller::toggleSymbolLock,
                ) {
                    LockIcon(
                        if (state.symbolLocked) palette.accent else palette.icon,
                        Modifier.size(15.dp),
                        locked = state.symbolLocked,
                    )
                }
                Spacer(Modifier.weight(1f))
                val backShape = RoundedCornerShape(9.dp)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(backShape)
                        .background(palette.keyShadow)
                        .padding(bottom = 1.dp)
                        .clip(backShape)
                        .background(palette.funcKey)
                        .border(0.5.dp, palette.keyBorder, backShape)
                        .clickable(onClick = controller::closeSymbols)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "返回",
                        color = palette.label,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    palette: Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) palette.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.onAccent else palette.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SymbolToolChip(
    label: String,
    active: Boolean,
    palette: Palette,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (active) palette.accentSoft else palette.funcKey)
            .border(
                width = 0.6.dp,
                color = if (active) palette.accent.copy(alpha = 0.4f) else palette.keyBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon()
        Text(
            label,
            color = if (active) palette.accent else palette.label,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
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
        val emptyShape = RoundedCornerShape(12.dp)
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
    val rows = symbols.chunked(4)
    Column(
        modifier.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(43.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row.forEach { symbol ->
                    val capShape = RoundedCornerShape(9.dp)
                    Box(
                        Modifier
                            .weight(1f)
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
                                symbol.length > 4 -> 12.sp
                                symbol.length > 2 -> 14.sp
                                else -> 20.sp
                            },
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(4 - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}
