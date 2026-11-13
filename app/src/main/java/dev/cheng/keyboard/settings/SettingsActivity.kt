package dev.cheng.keyboard.settings

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.cheng.keyboard.R
import dev.cheng.keyboard.ai.MODEL_FLASH
import dev.cheng.keyboard.ai.MODEL_PRO
import dev.cheng.keyboard.data.PrefKeys
import dev.cheng.keyboard.data.Secrets
import dev.cheng.keyboard.data.SqliteLexicon
import dev.cheng.keyboard.data.UserStore
import dev.cheng.keyboard.data.installLexicon
import dev.cheng.keyboard.data.settingsStore
import dev.cheng.keyboard.data.themeMode
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.ime.ui.CheckIcon
import dev.cheng.keyboard.ime.ui.CycleIcon
import dev.cheng.keyboard.ime.ui.GridIcon
import dev.cheng.keyboard.ime.ui.KeyIcon
import dev.cheng.keyboard.ime.ui.LockIcon
import dev.cheng.keyboard.ime.ui.PolishIcon
import dev.cheng.keyboard.ime.ui.SettingsIcon
import dev.cheng.keyboard.ime.ui.TrashIcon
import dev.cheng.keyboard.model.ThemeMode
import dev.cheng.keyboard.update.InstallCoordinator
import dev.cheng.keyboard.update.UpdateClient
import dev.cheng.keyboard.update.UpdateDownload
import dev.cheng.keyboard.update.UpdatePhase
import dev.cheng.keyboard.update.checkForUpdate
import dev.cheng.keyboard.update.findDownloadedNewerApk
import dev.cheng.keyboard.update.installedVersionCode
import dev.cheng.keyboard.update.installedVersionName
import dev.cheng.keyboard.update.markDownloaded
import dev.cheng.keyboard.update.openInstallPermission
import dev.cheng.keyboard.update.prepareInstall
import dev.cheng.keyboard.update.updateApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class SettingsColors(
    val bg: Color,
    val card: Color,
    val cardBorder: Color,
    val field: Color,
    val fieldBorder: Color,
    val text: Color,
    val subtle: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val divider: Color,
)

private val DarkSettingsColors = SettingsColors(
    bg = Color(0xFF11131A),
    card = Color(0xFF1A1E29),
    cardBorder = Color(0x18FFFFFF),
    field = Color(0xFF131620),
    fieldBorder = Color(0x1FFFFFFF),
    text = Color(0xFFF2F4F8),
    subtle = Color(0xFF949EB4),
    accent = Color(0xFF4D8DF7),
    accentSoft = Color(0x264D8DF7),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF4ADE80),
    successSoft = Color(0x244ADE80),
    danger = Color(0xFFF87171),
    dangerSoft = Color(0x24F87171),
    divider = Color(0x14FFFFFF),
)

private val LightSettingsColors = SettingsColors(
    bg = Color(0xFFF3F5F9),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0x12141C2E),
    field = Color(0xFFF1F3F8),
    fieldBorder = Color(0x16141C2E),
    text = Color(0xFF151822),
    subtle = Color(0xFF5E677A),
    accent = Color(0xFF2B6DE8),
    accentSoft = Color(0x182B6DE8),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF16A34A),
    successSoft = Color(0x1A16A34A),
    danger = Color(0xFFDC2626),
    dangerSoft = Color(0x16DC2626),
    divider = Color(0x12141C2E),
)

private data class ImeStatus(val enabled: Boolean, val selected: Boolean)

private fun queryImeStatus(context: Context): ImeStatus {
    val enabled = runCatching {
        val manager = context.getSystemService(InputMethodManager::class.java)
        manager?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    }.getOrDefault(false)
    val selected = runCatching {
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        current?.startsWith("${context.packageName}/") == true
    }.getOrDefault(false)
    return ImeStatus(enabled = enabled, selected = selected)
}

class SettingsActivity : ComponentActivity() {
    private val focusTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent { SettingsRoot(focusTick.intValue) }
    }

    override fun onStart() {
        super.onStart()
        InstallCoordinator.attachActivity(this)
    }

    override fun onStop() {
        InstallCoordinator.detachActivity(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        focusTick.intValue++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusTick.intValue++
    }
}

@Composable
private fun SettingsRoot(refreshTick: Int) {
    val context = LocalContext.current
    val prefs by context.settingsStore.data.collectAsState(initial = null)
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs?.themeMode() ?: ThemeMode.System) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }
    val target = if (dark) DarkSettingsColors else LightSettingsColors
    val bgColor by animateColorAsState(target.bg, label = "settingsBg")
    val colors = target.copy(bg = bgColor)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? ComponentActivity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val secrets = remember { Secrets(context) }
    val key = remember { mutableStateOf(secrets.get().orEmpty()) }
    val imeStatus = remember(refreshTick) { queryImeStatus(context) }
    val version = remember { installedVersionName(context) }
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 800

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 28.dp else 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandHeader(version = version, imeStatus = imeStatus, colors = colors)
            if (wide && prefs != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SetupCard(context, imeStatus, colors)
                        TryFieldCard(colors)
                        LexiconAndUpdateSection(context, version, refreshTick, colors, scope)
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AppearanceAndInputSection(context, prefs, scope, colors)
                        AiConfigCard(context, prefs, scope, secrets, key, colors)
                        FooterNotice(colors)
                    }
                }
            } else {
                SetupCard(context, imeStatus, colors)
                TryFieldCard(colors)
                prefs?.let { current ->
                    AppearanceAndInputSection(context, current, scope, colors)
                    AiConfigCard(context, current, scope, secrets, key, colors)
                }
                LexiconAndUpdateSection(context, version, refreshTick, colors, scope)
                FooterNotice(colors)
            }
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        val drawable = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_brand_badge))
        val pixels = (96 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        drawable.toBitmap(width = pixels, height = pixels).asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
}

@Composable
private fun BrandHeader(version: String, imeStatus: ImeStatus, colors: SettingsColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandMark(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "N1ghtKey",
                    color = colors.text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(
                    label = "v$version",
                    bg = colors.accentSoft,
                    fg = colors.accent,
                )
            }
            Text(
                "轻量、纯净的本地拼音英文与 AI 增强键盘",
                color = colors.subtle,
                fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val ready = imeStatus.enabled && imeStatus.selected
        StatusBadge(
            label = if (ready) "正在使用" else if (imeStatus.enabled) "待切换" else "未启用",
            bg = if (ready) colors.successSoft else colors.accentSoft,
            fg = if (ready) colors.success else colors.accent,
        )
    }
}

@Composable
private fun SetupCard(context: Context, imeStatus: ImeStatus, colors: SettingsColors) {
    SettingsCard(colors) {
        CardHeader(
            title = "启用与切换",
            subtitle = "先在系统设置里启用 N1ghtKey，再将其选为当前输入法。小米路径：设置 → 更多设置 → 语言与输入法 → 输入法管理。",
            colors = colors,
        ) {
            SettingsIcon(colors.accent, Modifier.size(18.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepStatusPill(
                step = "1",
                title = "系统启用",
                done = imeStatus.enabled,
                doneLabel = "已启用",
                todoLabel = "去开启",
                colors = colors,
                modifier = Modifier.weight(1f),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            StepStatusPill(
                step = "2",
                title = "当前键盘",
                done = imeStatus.selected,
                doneLabel = "已选中",
                todoLabel = "去选择",
                colors = colors,
                modifier = Modifier.weight(1f),
                onClick = {
                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActionButton(
                label = "打开输入法设置",
                primary = !imeStatus.enabled,
                colors = colors,
                modifier = Modifier.weight(1f),
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            ActionButton(
                label = "选择当前输入法",
                primary = imeStatus.enabled && !imeStatus.selected,
                colors = colors,
                modifier = Modifier.weight(1f),
            ) {
                context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        }
    }
}

@Composable
private fun StepStatusPill(
    step: String,
    title: String,
    done: Boolean,
    doneLabel: String,
    todoLabel: String,
    colors: SettingsColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(colors.field)
            .border(0.8.dp, if (done) colors.success.copy(alpha = 0.35f) else colors.fieldBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) colors.successSoft else colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                CheckIcon(colors.success, Modifier.size(13.dp))
            } else {
                Text(step, color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                if (done) doneLabel else todoLabel,
                color = if (done) colors.success else colors.subtle,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun TryFieldCard(colors: SettingsColors) {
    val scratch = remember { mutableStateOf("") }
    SettingsCard(colors) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("试打字", color = colors.text, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "测试拼音、英文，以及 AI 询问、翻译与润色效果",
                    color = colors.subtle,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (scratch.value.isNotEmpty()) {
                SmallChipButton("清空", colors) { scratch.value = "" }
            }
        }
        val fieldShape = RoundedCornerShape(13.dp)
        BasicTextField(
            value = scratch.value,
            onValueChange = { scratch.value = it },
            textStyle = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 23.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = 96.dp)
                .clip(fieldShape)
                .background(colors.field)
                .border(0.8.dp, colors.fieldBorder, fieldShape)
                .padding(14.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (scratch.value.isEmpty()) {
                        Text("点这里唤起键盘体验输入…", color = colors.subtle, fontSize = 15.5.sp)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun AppearanceAndInputSection(
    context: Context,
    current: Preferences?,
    scope: CoroutineScope,
    colors: SettingsColors,
) {
    if (current == null) return
    var calibResetNotice by remember { mutableStateOf(false) }
    SettingsCard(colors) {
        CardHeader(
            title = "外观与手感",
            subtitle = "自定义深浅色主题、键盘高度比例与输入辅助偏好",
            colors = colors,
        ) {
            GridIcon(colors.accent, Modifier.size(18.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("外观主题", color = colors.subtle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Segment(
            labels = listOf("跟随系统", "深色", "浅色"),
            selected = ThemeMode.entries.indexOf(current.themeMode()).coerceAtLeast(0),
            colors = colors,
        ) { index ->
            val mode = ThemeMode.entries[index]
            scope.launch { context.settingsStore.edit { it[PrefKeys.theme] = mode.name } }
        }

        val heightScale = (current[PrefKeys.height] ?: 1f).coerceIn(0.8f, 1.25f)
        val percent = (heightScale * 100).toInt()
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("键盘高度", color = colors.subtle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (percent != 100) {
                Text(
                    "重置",
                    color = colors.subtle,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            scope.launch { context.settingsStore.edit { it[PrefKeys.height] = 1f } }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            StatusBadge("$percent%", bg = colors.accentSoft, fg = colors.accent)
        }
        Slider(
            value = heightScale,
            onValueChange = { value ->
                scope.launch { context.settingsStore.edit { it[PrefKeys.height] = value } }
            },
            valueRange = 0.8f..1.25f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.field,
            ),
        )

        CardDivider(colors)
        ToggleRow(
            label = "按键振动",
            description = "按下按键、长按选符与滑动光标时提供轻巧触感反馈",
            checked = current[PrefKeys.haptic] != false,
            colors = colors,
        ) { value ->
            scope.launch { context.settingsStore.edit { it[PrefKeys.haptic] = value } }
        }
        CardDivider(colors)
        ToggleRow(
            label = "拼音模糊音",
            description = "兼容 z/zh、c/ch、s/sh、n/ng 等常见近似音输入",
            checked = current[PrefKeys.fuzzy] == true,
            colors = colors,
        ) { value ->
            scope.launch { context.settingsStore.edit { it[PrefKeys.fuzzy] = value } }
        }
        CardDivider(colors)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("触控校准", color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (calibResetNotice) {
                        "已恢复默认触控落点偏移基线"
                    } else {
                        "自适应修正双拇指落点偏移，更换握姿后可重置"
                    },
                    color = if (calibResetNotice) colors.accent else colors.subtle,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            SmallChipButton("重置触控校准", colors) {
                calibResetNotice = true
                scope.launch {
                    context.settingsStore.edit { prefs ->
                        prefs[PrefKeys.calibLeft] = 0f
                        prefs[PrefKeys.calibRight] = 0f
                        prefs[PrefKeys.calibDy] = 0f
                        prefs[PrefKeys.calibEpoch] = (prefs[PrefKeys.calibEpoch] ?: 0L) + 1L
                    }
                }
            }
        }
    }
}

@Composable
private fun AiConfigCard(
    context: Context,
    current: Preferences?,
    scope: CoroutineScope,
    secrets: Secrets,
    key: MutableState<String>,
    colors: SettingsColors,
) {
    if (current == null) return
    var revealKey by remember { mutableStateOf(false) }
    var savedState by remember { mutableStateOf(!secrets.get().isNullOrBlank()) }
    var feedback by remember { mutableStateOf<String?>(null) }

    SettingsCard(colors) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                PolishIcon(colors.accent, Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("DeepSeek AI", color = colors.text, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                Text("密钥仅加密存储在本机，用于询问、翻译与润色", color = colors.subtle, fontSize = 12.5.sp)
            }
            StatusBadge(
                label = if (savedState) "已配置" else "未配置",
                bg = if (savedState) colors.successSoft else colors.field,
                fg = if (savedState) colors.success else colors.subtle,
            )
        }

        Spacer(Modifier.height(12.dp))
        val inputShape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(inputShape)
                .background(colors.field)
                .border(0.8.dp, colors.fieldBorder, inputShape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyIcon(colors.subtle, Modifier.size(17.dp))
            BasicTextField(
                value = key.value,
                onValueChange = {
                    key.value = it
                    feedback = null
                },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(color = colors.text, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (key.value.isEmpty()) {
                            Text("输入 DeepSeek API Key (sk-…)", color = colors.subtle, fontSize = 14.5.sp)
                        }
                        inner()
                    }
                },
            )
            if (key.value.isNotEmpty()) {
                Text(
                    if (revealKey) "隐藏" else "显示",
                    color = colors.accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { revealKey = !revealKey }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        feedback?.let { msg ->
            Text(
                msg,
                color = colors.accent,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("保存密钥", primary = true, colors = colors) {
                secrets.set(key.value)
                savedState = key.value.trim().isNotEmpty()
                feedback = if (savedState) "密钥已安全保存" else "已清除保存的密钥"
            }
            if (key.value.isNotEmpty() || savedState) {
                ActionButton("清除", primary = false, colors = colors) {
                    key.value = ""
                    secrets.set("")
                    savedState = false
                    feedback = "密钥已清除"
                }
            }
        }

        CardDivider(colors)
        val model = current[PrefKeys.askModel] ?: MODEL_FLASH
        Text("AI 询问模型", color = colors.subtle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Segment(
            labels = listOf("Flash · 极速响应", "Pro · 深度推理"),
            selected = if (model == MODEL_PRO) 1 else 0,
            colors = colors,
        ) { index ->
            val next = if (index == 1) MODEL_PRO else MODEL_FLASH
            scope.launch { context.settingsStore.edit { it[PrefKeys.askModel] = next } }
        }
    }
}

@Composable
private fun LexiconAndUpdateSection(
    context: Context,
    version: String,
    refreshTick: Int,
    colors: SettingsColors,
    scope: CoroutineScope,
) {
    var clearedNotice by remember { mutableStateOf(false) }
    SettingsCard(colors) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                LockIcon(colors.accent, Modifier.size(17.dp), locked = true)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("本机词库", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (clearedNotice) "已清空本机自学习词频与联想记录" else "清除输入过程中自动记忆的中英文个人词频",
                    color = if (clearedNotice) colors.accent else colors.subtle,
                    fontSize = 12.5.sp,
                )
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.field)
                    .border(0.8.dp, colors.fieldBorder, RoundedCornerShape(10.dp))
                    .clickable {
                        UserStore(context).clear()
                        clearedNotice = true
                        scope.launch {
                            context.settingsStore.edit { prefs ->
                                prefs[PrefKeys.userEpoch] = (prefs[PrefKeys.userEpoch] ?: 0L) + 1L
                            }
                        }
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TrashIcon(colors.danger, Modifier.size(14.dp))
                Text("清空用户词", color = colors.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        CardDivider(colors)
        UpdateSection(context, version, refreshTick, colors)
    }
}

@Composable
private fun EngineProbeNote(context: Context, colors: SettingsColors) {
    var note by remember { mutableStateOf("引擎自检读取中…") }
    LaunchedEffect(Unit) {
        note = withContext(Dispatchers.IO) { engineProbe(context) }
    }
    Text(note, color = colors.subtle, fontSize = 11.5.sp, lineHeight = 15.sp)
}

@Composable
private fun UpdateSection(
    context: Context,
    version: String,
    refreshTick: Int,
    colors: SettingsColors,
) {
    val client = remember(context) { UpdateClient(context) }
    val versionCode = remember { installedVersionCode(context) }
    val ticket = remember { intArrayOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Idle) }
    DisposableEffect(client) {
        val listener: (UpdatePhase) -> Unit = { next -> phase = next }
        InstallCoordinator.statusListener = listener
        onDispose {
            if (InstallCoordinator.statusListener === listener) {
                InstallCoordinator.statusListener = null
            }
            client.cancel()
            main.removeCallbacksAndMessages(null)
        }
    }

    fun publish(current: Int, next: UpdatePhase) {
        if (ticket[0] == current) phase = next
    }

    fun run(block: suspend (Int) -> Unit) {
        val current = ++ticket[0]
        client.cancel()
        scope.launch { block(current) }
    }

    fun check() {
        run { current ->
            publish(current, UpdatePhase.Checking)
            val next = withContext(Dispatchers.IO) {
                val checked = markDownloaded(context, checkForUpdate(client, version, versionCode))
                if (checked is UpdatePhase.Failed) {
                    val cached = findDownloadedNewerApk(context)
                    if (cached != null) {
                        UpdatePhase.Ready(cached.first, "已缓存新版本 v${cached.second} 安装包，可直接安装。")
                    } else {
                        checked
                    }
                } else {
                    checked
                }
            }
            publish(current, next)
        }
    }

    LaunchedEffect(Unit) {
        check()
    }
    LaunchedEffect(refreshTick) {
        val current = phase
        if (current is UpdatePhase.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            phase = prepareInstall(context, current.file)
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            CycleIcon(colors.accent, Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("版本与更新", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("当前安装版本 v$version", color = colors.subtle, fontSize = 12.5.sp)
            EngineProbeNote(context, colors)
        }
        if (phase is UpdatePhase.UpToDate) {
            StatusBadge("已是最新", bg = colors.successSoft, fg = colors.success)
        } else if (phase is UpdatePhase.Available || phase is UpdatePhase.Ready || phase is UpdatePhase.NeedsPermission) {
            StatusBadge("有新版本", bg = colors.accentSoft, fg = colors.accent)
        }
    }

    when (val current = phase) {
        UpdatePhase.Idle -> Unit
        UpdatePhase.Checking -> StatusNote("正在通过官方与加速通道检查新版本…", colors.subtle)
        UpdatePhase.UpToDate -> StatusNote("当前已是最新正式版本。", colors.subtle)
        is UpdatePhase.Available -> {
            val noteShape = RoundedCornerShape(12.dp)
            val sizeLabel = if (current.release.size > 0L) {
                String.format(Locale.ROOT, " · %.1f MB", current.release.size / (1024.0 * 1024.0))
            } else {
                ""
            }
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(noteShape)
                    .background(colors.field)
                    .border(0.8.dp, colors.fieldBorder, noteShape)
                    .padding(12.dp),
            ) {
                Text(
                    "新版本 v${current.release.version} 可供安装$sizeLabel",
                    color = colors.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (current.release.notes.isNotBlank()) {
                    Text(
                        current.release.notes.take(280),
                        color = colors.subtle,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        is UpdatePhase.Downloading -> {
            val routeHint = if (current.routeLabel.isNotBlank()) "（${current.routeLabel}）" else ""
            Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("正在下载更新包$routeHint…", color = colors.text, fontSize = 13.5.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${current.percent}%", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(colors.field),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((current.percent.coerceIn(0, 100)) / 100f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                }
            }
        }
        is UpdatePhase.NeedsPermission -> StatusNote("请允许 N1ghtKey 安装未知应用权限，返回后将自动继续安装。", colors.text)
        is UpdatePhase.Ready -> StatusNote(current.message, colors.subtle)
        is UpdatePhase.Failed -> StatusNote(current.message, colors.danger)
    }

    Spacer(Modifier.height(10.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val current = phase) {
            UpdatePhase.Checking -> Unit
            is UpdatePhase.Downloading -> {
                SmallChipButton("取消下载", colors) {
                    ticket[0]++
                    client.cancel()
                    phase = UpdatePhase.Idle
                }
            }
            is UpdatePhase.Available -> {
                ActionButton(
                    label = if (current.downloaded) "立即安装" else "下载并安装",
                    primary = true,
                    colors = colors,
                ) {
                    if (current.downloaded) {
                        phase = prepareInstall(context, updateApk(context, current.release.version))
                    } else {
                        val release = current.release
                        run { ticketId ->
                            publish(ticketId, UpdatePhase.Downloading(0))
                            val file = updateApk(context, release.version)
                            val result = withContext(Dispatchers.IO) {
                                client.download(release, file) { percent, routeLabel ->
                                    main.post { publish(ticketId, UpdatePhase.Downloading(percent, routeLabel)) }
                                }
                            }
                            if (ticket[0] != ticketId) return@run
                            val next = when (result) {
                                is UpdateDownload.Ok -> prepareInstall(context, result.file)
                                is UpdateDownload.Failed -> UpdatePhase.Failed(result.message)
                            }
                            publish(ticketId, next)
                        }
                    }
                }
                SmallChipButton("重新检查", colors) { check() }
            }
            is UpdatePhase.NeedsPermission -> {
                ActionButton("去允许权限", primary = true, colors = colors) { openInstallPermission(context) }
                ActionButton("继续安装", primary = false, colors = colors) {
                    phase = prepareInstall(context, current.file)
                }
            }
            is UpdatePhase.Ready -> {
                ActionButton("立即安装", primary = true, colors = colors) {
                    phase = prepareInstall(context, current.file)
                }
                SmallChipButton("重新检查", colors) { check() }
            }
            else -> {
                ActionButton(
                    label = "检查更新",
                    primary = phase is UpdatePhase.Failed,
                    colors = colors,
                ) {
                    check()
                }
            }
        }
    }
}

@Composable
private fun FooterNotice(colors: SettingsColors) {
    Text(
        "按键、词库与个人词频仅保存在本机设备。使用 AI 询问、翻译与润色时，仅会将当前待处理文本发送至 DeepSeek 接口。应用与中文词库遵循 GPL-3.0 开源许可。",
        color = colors.subtle,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsCard(
    colors: SettingsColors,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(0.8.dp, colors.cardBorder, shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun CardHeader(
    title: String,
    subtitle: String,
    colors: SettingsColors,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = colors.subtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun CardDivider(colors: SettingsColors) {
    Box(
        Modifier
            .padding(vertical = 14.dp)
            .fillMaxWidth()
            .height(0.8.dp)
            .background(colors.divider),
    )
}

@Composable
private fun StatusBadge(label: String, bg: Color, fg: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(label, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusNote(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ActionButton(
    label: String,
    primary: Boolean,
    colors: SettingsColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) colors.accent else colors.field)
            .then(if (primary) Modifier else Modifier.border(0.8.dp, colors.fieldBorder, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) colors.onAccent else colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SmallChipButton(label: String, colors: SettingsColors, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .clip(shape)
            .background(colors.field)
            .border(0.8.dp, colors.fieldBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.subtle, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    colors: SettingsColors,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
            Text(
                description,
                color = colors.subtle,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.subtle,
                uncheckedTrackColor = colors.field,
                uncheckedBorderColor = colors.fieldBorder,
            ),
        )
    }
}

@Composable
private fun Segment(
    labels: List<String>,
    selected: Int,
    colors: SettingsColors,
    onPick: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.field)
            .border(0.8.dp, colors.fieldBorder, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val itemShape = RoundedCornerShape(9.dp)
            Text(
                label,
                color = if (on) colors.accent else colors.subtle,
                fontSize = 13.5.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(if (on) colors.card else Color.Transparent)
                    .then(
                        if (on) Modifier.border(0.6.dp, colors.cardBorder, itemShape)
                        else Modifier,
                    )
                    .clickable { onPick(index) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// Mirrors the input engine's own syllable table so a stale or mangled install shows up on screen:
// if this line reads "shang→shan'g" the device's assets are not the ones the build shipped.
private fun engineProbe(context: Context): String {
    val syllables = runCatching {
        context.assets.open("dict/syllables.txt").bufferedReader().use { reader ->
            reader.readLines()
                .map { line -> line.replace("\uFEFF", "").trim().lowercase() }
                .filter { line -> line.isNotEmpty() && line.all { it in 'a'..'z' } }
                .toSet()
        }
    }.getOrDefault(emptySet())
    val build = "build ${installedVersionCode(context)}"
    if (syllables.isEmpty()) return "$build · 音节表读取失败"
    val decoder = PinyinDecoder(syllables)
    val samples = listOf("shang", "jian", "nian", "maofan")
    val lexFile = installLexicon(context)
    val probe = if (lexFile != null && lexFile.isFile) {
        runCatching {
            SQLiteDatabase.openDatabase(lexFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                decoder.lexicon = SqliteLexicon(db)
                samples.joinToString(" ") { input ->
                    val decoded = decoder.decode(input, fuzzy = false)
                    val top = decoded.candidates.firstOrNull()?.text.orEmpty()
                    if (top.isNotEmpty()) "$input→$top(${decoded.preedit})" else "$input→${decoded.preedit}"
                }
            }
        }.getOrElse {
            samples.joinToString(" ") { input -> "$input→" + decoder.decode(input, fuzzy = false).preedit }
        }
    } else {
        samples.joinToString(" ") { input -> "$input→" + decoder.decode(input, fuzzy = false).preedit }
    }
    return "$build · 音节 ${syllables.size} · $probe"
}
