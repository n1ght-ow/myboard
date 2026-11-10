package dev.cheng.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.cheng.keyboard.ai.MODEL_FLASH
import dev.cheng.keyboard.ai.MODEL_PRO
import dev.cheng.keyboard.data.PrefKeys
import dev.cheng.keyboard.data.Secrets
import dev.cheng.keyboard.data.UserStore
import dev.cheng.keyboard.data.settingsStore
import dev.cheng.keyboard.data.themeMode
import dev.cheng.keyboard.model.ThemeMode
import dev.cheng.keyboard.update.UpdateClient
import dev.cheng.keyboard.update.UpdateDownload
import dev.cheng.keyboard.update.UpdatePhase
import dev.cheng.keyboard.update.checkForUpdate
import dev.cheng.keyboard.update.installedVersionName
import dev.cheng.keyboard.update.markDownloaded
import dev.cheng.keyboard.update.openInstallPermission
import dev.cheng.keyboard.update.prepareInstall
import dev.cheng.keyboard.update.updateApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF10131C)
private val Field = Color(0xFF242838)
private val InkText = Color(0xFFF3F5FA)
private val Mute = Color(0xFFA7B0C4)
private val Accent = Color(0xFF5B9AF2)

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsRoot() }
    }
}

@Composable
private fun SettingsRoot() {
    val context = LocalContext.current
    val prefs by context.settingsStore.data.collectAsState(initial = null)
    val secrets = remember { Secrets(context) }
    val key = remember { mutableStateOf(secrets.get().orEmpty()) }
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 800
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        if (wide && prefs != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1.1f)) {
                    Intro(context)
                    TryField()
                }
                Column(Modifier.weight(1f)) {
                    Controls(context, prefs, scope, secrets, key)
                }
            }
        } else {
            Intro(context)
            TryField()
            prefs?.let { Controls(context, it, scope, secrets, key) }
        }
    }
}

@Composable
private fun Intro(context: Context) {
    Text("myboard", color = InkText, fontSize = 32.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Text(
        "在系统设置里启用，再从输入法列表里选它。小米的路径是：设置 → 更多设置 → 语言与输入法 → 输入法管理。",
        color = Mute,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Link("打开输入法设置") {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Link("选择当前输入法") {
            context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }
}

@Composable
private fun TryField() {
    val scratch = remember { mutableStateOf("") }
    Spacer(Modifier.height(22.dp))
    Text("试打字", color = InkText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Text("试拼音、英文，以及询问、翻译、润色。", color = Mute, fontSize = 14.sp)
    BasicTextField(
        value = scratch.value,
        onValueChange = { scratch.value = it },
        textStyle = TextStyle(color = InkText, fontSize = 18.sp),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Field)
            .padding(16.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (scratch.value.isEmpty()) Text("点这里输入", color = Mute, fontSize = 18.sp)
                inner()
            }
        },
    )
    Link("清空") { scratch.value = "" }
}

@Composable
private fun Controls(
    context: Context,
    current: Preferences?,
    scope: CoroutineScope,
    secrets: Secrets,
    key: androidx.compose.runtime.MutableState<String>,
) {
    if (current == null) return
    Section("外观")
    Segment(
        listOf("跟随系统", "深色", "浅色"),
        selected = ThemeMode.entries.indexOf(current.themeMode()).coerceAtLeast(0),
    ) { index ->
        val mode = ThemeMode.entries[index]
        scope.launch { context.settingsStore.edit { it[PrefKeys.theme] = mode.name } }
    }
    Spacer(Modifier.height(16.dp))
    Text("键盘高度", color = Mute, fontSize = 14.sp)
    Slider(
        value = current[PrefKeys.height] ?: 1f,
        onValueChange = { value ->
            scope.launch { context.settingsStore.edit { it[PrefKeys.height] = value } }
        },
        valueRange = 0.8f..1.25f,
        colors = SliderDefaults.colors(
            thumbColor = InkText,
            activeTrackColor = Accent,
            inactiveTrackColor = Field,
        ),
    )
    Toggle("按键振动", current[PrefKeys.haptic] != false) { value ->
        scope.launch { context.settingsStore.edit { it[PrefKeys.haptic] = value } }
    }
    Toggle("模糊音", current[PrefKeys.fuzzy] == true) { value ->
        scope.launch { context.settingsStore.edit { it[PrefKeys.fuzzy] = value } }
    }
    Section("DeepSeek")
    Text("密钥只保存在这台设备上。", color = Mute, fontSize = 14.sp)
    BasicTextField(
        value = key.value,
        onValueChange = { key.value = it },
        textStyle = TextStyle(color = InkText, fontSize = 16.sp),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Field)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
    Link("保存密钥") { secrets.set(key.value) }
    val model = current[PrefKeys.askModel] ?: MODEL_FLASH
    Spacer(Modifier.height(8.dp))
    Text("询问模型", color = Mute, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    Segment(listOf("flash", "pro"), selected = if (model == MODEL_PRO) 1 else 0) { index ->
        val next = if (index == 1) MODEL_PRO else MODEL_FLASH
        scope.launch { context.settingsStore.edit { it[PrefKeys.askModel] = next } }
    }
    Section("本机词")
    Link("清空用户词") {
        UserStore(context).clear()
        scope.launch {
            context.settingsStore.edit { prefs ->
                prefs[PrefKeys.userEpoch] = (prefs[PrefKeys.userEpoch] ?: 0L) + 1L
            }
        }
    }
    UpdateSection(context, secrets)
    Spacer(Modifier.height(18.dp))
    Text(
        "按键、词库和用户词只留在这台设备上。询问、翻译和润色会把这一次的文字发给 DeepSeek。应用与中文词库按 GPL-3.0 授权。",
        color = Mute,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )
}

@Composable
private fun UpdateSection(context: Context, secrets: Secrets) {
    val version = remember { installedVersionName(context) }
    val client = remember { UpdateClient() }
    val ticket = remember { intArrayOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Idle) }
    var showToken by remember { mutableStateOf(secrets.githubToken().isNullOrBlank()) }
    var draft by remember { mutableStateOf("") }
    DisposableEffect(client) {
        onDispose {
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

    fun check(token: String) {
        run { current ->
            publish(current, UpdatePhase.Checking)
            val next = withContext(Dispatchers.IO) {
                markDownloaded(context, checkForUpdate(client, version, token))
            }
            if (ticket[0] == current && (next is UpdatePhase.UpToDate || next is UpdatePhase.Available)) {
                showToken = false
            }
            publish(current, next)
        }
    }

    LaunchedEffect(Unit) {
        val token = secrets.githubToken().orEmpty()
        if (token.isNotEmpty()) check(token)
    }
    LaunchedEffect(phase) {
        if (phase is UpdatePhase.Failed && (phase as UpdatePhase.Failed).askToken) showToken = true
    }

    Section("更新")
    Text("当前版本 $version", color = Mute, fontSize = 14.sp)
    when (val current = phase) {
        UpdatePhase.Idle -> Unit
        UpdatePhase.Checking -> Status("正在检查…", Mute)
        UpdatePhase.UpToDate -> Status("已是最新版本", Mute)
        is UpdatePhase.Available -> {
            Text(
                "${current.release.version} 可以安装",
                color = InkText,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (current.release.notes.isNotBlank()) {
                Text(
                    current.release.notes.take(280),
                    color = Mute,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        is UpdatePhase.Downloading -> Status("正在下载 ${current.percent}%", InkText)
        is UpdatePhase.NeedsPermission -> Status("请允许 myboard 安装未知应用，然后继续安装。", InkText)
        is UpdatePhase.Ready -> Status("如果安装界面没弹出，再点一次安装。", Mute)
        is UpdatePhase.Failed -> Status(current.message, Color(0xFFF2B8B5))
    }
    if (showToken) {
        Text(
            "私有仓库需要一个能读取 myboard 的 GitHub 令牌，只保存在这台设备上。",
            color = Mute,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            textStyle = TextStyle(color = InkText, fontSize = 16.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Field)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (draft.isEmpty()) {
                        Text(
                            if (secrets.githubToken().isNullOrBlank()) "GitHub 令牌" else "已保存，点此更换",
                            color = Mute,
                            fontSize = 16.sp,
                        )
                    }
                    inner()
                }
            },
        )
    }
    when (val current = phase) {
        UpdatePhase.Checking, is UpdatePhase.Downloading -> Unit
        is UpdatePhase.Available -> {
            Link(if (current.downloaded) "安装" else "下载并安装") {
                val token = secrets.githubToken().orEmpty()
                if (current.downloaded) {
                    phase = prepareInstall(context, updateApk(context, current.release.version))
                } else {
                    val release = current.release
                    run { ticketId ->
                        publish(ticketId, UpdatePhase.Downloading(0))
                        val file = updateApk(context, release.version)
                        val result = withContext(Dispatchers.IO) {
                            client.download(token, release, file) { percent ->
                                main.post { publish(ticketId, UpdatePhase.Downloading(percent)) }
                            }
                        }
                        if (ticket[0] != ticketId) return@run
                        val next = when (result) {
                            is UpdateDownload.Ok -> prepareInstall(context, result.file)
                            UpdateDownload.Unauthorized -> UpdatePhase.Failed("令牌无效。", askToken = true)
                            is UpdateDownload.Failed -> UpdatePhase.Failed(result.message, askToken = false)
                        }
                        publish(ticketId, next)
                    }
                }
            }
        }
        is UpdatePhase.NeedsPermission -> {
            Link("去允许") { openInstallPermission(context) }
            Link("继续安装") { phase = prepareInstall(context, current.file) }
        }
        is UpdatePhase.Ready -> Link("安装") { phase = prepareInstall(context, current.file) }
        else -> {
            Link(if (showToken && draft.isNotBlank()) "保存并检查" else "检查更新") {
                if (draft.isNotBlank()) {
                    secrets.setGithubToken(draft)
                    draft = ""
                }
                val token = secrets.githubToken().orEmpty()
                if (token.isBlank()) {
                    showToken = true
                    phase = UpdatePhase.Failed("先填写 GitHub 令牌。", askToken = true)
                } else {
                    check(token)
                }
            }
        }
    }
    if (!showToken && !secrets.githubToken().isNullOrBlank()) {
        Link("更换令牌") { showToken = true }
    }
}

@Composable
private fun Status(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = InkText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 26.dp, bottom = 10.dp),
    )
}

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Accent,
        fontSize = 15.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = InkText, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = InkText,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Mute,
                uncheckedTrackColor = Field,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Segment(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Field)
            .padding(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Text(
                label,
                color = if (on) InkText else Mute,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Color(0xFF31456E) else Color.Transparent)
                    .clickable { onPick(index) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
