# 实施任务书：语境感知自适应 AI 润色

> 本文件是给编码代理（LLM）执行的任务书，不是产品文档。
> 阅读顺序：第 1 节不变量 → 第 2 节锚点 → 第 3 节数据流 → 第 4 节新增文件 → 5~8 节逐文件改造 → 9 节测试 → 10 节验收。
> 只实现本文件描述的内容，不要顺手重构、不要改包名、不要引入新依赖。

---

## 0. 启动指令（供新对话直接粘贴）

```text
项目根目录：D:\keyboard（Android 输入法，Kotlin + Compose，包名 dev.cheng.keyboard）
请完整阅读仓库根目录的 POLISH_PLAN.md，按其中的契约与文件锚点实现"语境感知自适应润色"。
要求：先实现 ai/Polish.kt 与单元测试，再改 Editor.kt / UiState.kt / KeyboardController.kt / KeyboardScreen.kt。
完成后必须执行 ./gradlew.bat testDebugUnitTest 和 ./gradlew.bat assembleDebug，并报告结果。
不要修改 translate() 与 ask() 的现有行为，不引入 AccessibilityService、截屏权限或任何新依赖。
```

---

## 1. 不变量（违反即视为实现失败）

1. **零新增敏感权限**：不引入 `AccessibilityService`、`MediaProjection`、截屏或读取通知的权限。只使用 `EditorInfo` 已有的元数据与输入框内文本。
2. **不改动既有功能**：`ask()`（询问）、`translate()`（翻译）、`insertResult()`、`copyResult()`、剪贴板菜单、符号面板的行为保持不变。
3. **`polish()` 无参重载必须保留**：`app/src/main/java/dev/cheng/keyboard/ime/ui/KeyboardScreen.kt:314` 使用 `onClick = controller::polish`。Kotlin 的方法引用不会自动适配默认参数，因此**必须同时存在** `fun polish()` 与 `fun polish(tone: PolishTone)`，否则编译失败。
4. **密码框与 `IME_FLAG_NO_PERSONALIZED_LEARNING` 字段**：不得发送正文、前后文或 `hintText`。沿用现有 `engineState.password` 守卫。
5. **纯 Kotlin 优先**：所有可测试逻辑放进 `ai/Polish.kt`，该文件不得 import 任何 `android.*`。`EditorInfo` 的读取留在 `ime/Editor.kt`。
6. **无新依赖**：只使用现有 OkHttp、org.json、JUnit4。
7. **流式优先**：沿用 `DeepSeekClient.stream()`；禁止为了多候选改回非流式。
8. **替换区间不可扩大**：`replaceResult()` 只能替换 `[FieldSource.start, FieldSource.end)`，前后文仅作为提示词参考，绝不写入输入框。

---

## 2. 现状锚点（已核对，实现前先确认未漂移）

| 位置 | 内容 |
| --- | --- |
| `app/src/main/java/dev/cheng/keyboard/ai/Sse.kt:80` | 现有 `POLISH_PROMPT`（单结果、纯文字编辑） |
| `app/src/main/java/dev/cheng/keyboard/ai/Sse.kt:71` | `MODEL_FLASH = "deepseek-flash"` |
| `app/src/main/java/dev/cheng/keyboard/ai/DeepSeekClient.kt:47` | `stream(...)`，带 `onDelta(reasoning, content)` 回调 |
| `app/src/main/java/dev/cheng/keyboard/ime/Editor.kt:93` | `data class FieldSource(text, selection, start, end)` |
| `app/src/main/java/dev/cheng/keyboard/ime/Editor.kt:100` | `readField(connection): FieldSource?` |
| `app/src/main/java/dev/cheng/keyboard/ime/Editor.kt:126` | `paragraphSource(all, cursor)`，只取光标所在自然段 |
| `app/src/main/java/dev/cheng/keyboard/ime/Editor.kt:26` | `fieldTraitsOf(info): FieldTraits` |
| `app/src/main/java/dev/cheng/keyboard/ime/UiState.kt:8` | `enum class ResultKind { Translate, Polish }` |
| `app/src/main/java/dev/cheng/keyboard/ime/UiState.kt:10` | `sealed interface Overlay`，含 `Result(kind)` |
| `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:81` | `private var activeInfo: EditorInfo?` |
| `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:92` | `private var source: FieldSource?` |
| `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:474` | `fun polish() = transform(...)` |
| `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:601` | `private fun transform(kind, prompt, temperature)` |
| `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:647` | `private fun launchAi(..., onStream, onOk)` |
| `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:682` | `private fun currentOutput(): String?` |
| `app/src/main/java/dev/cheng/keyboard/ime/ui/KeyboardScreen.kt:148` | `ResultPanel(overlay.kind, state, palette, controller)` |
| `app/src/main/java/dev/cheng/keyboard/ime/ui/KeyboardScreen.kt:276` | 工具栏中间提示位（`state.note` / `freshClip`） |
| `app/src/main/java/dev/cheng/keyboard/ime/ui/KeyboardScreen.kt:734` | `private fun ResultPanel(...)` |
| `app/src/test/java/dev/cheng/keyboard/KeyboardLogicTest.kt` | 现有 JVM 单元测试入口 |

---

## 3. 目标数据流

```text
点击润色 / 切换语气胶囊
  → prepareTransform(Polish) 读取 FieldSource（含 contextBefore / contextAfter）
  → hostSceneOf(packageName, hintText, enterKind) 得到宿主场景
  → buildPolishUserPrompt(text, ctxBefore, ctxAfter, scene, tone)
  → DeepSeekClient.stream(..., ADAPTIVE_POLISH_SYSTEM_PROMPT)
  → parsePolishStream(delta) 逐帧产出 1~3 个 PolishCandidate
  → ResultPanel 渲染"诊断标签 + 正文"卡片，选中项同步到 resultText
  → 替换时校验原文未变 → 原位替换 → 记录 PolishUndo → 工具栏显示"撤回"
```

---

## 4. 新增文件 `app/src/main/java/dev/cheng/keyboard/ai/Polish.kt`

纯 Kotlin，不 import `android.*`。允许 import `dev.cheng.keyboard.engine.stripWrapped` 与 `dev.cheng.keyboard.model.EnterKind`。

### 4.1 类型定义

```kotlin
package dev.cheng.keyboard.ai

import dev.cheng.keyboard.engine.stripWrapped
import dev.cheng.keyboard.model.EnterKind

enum class PolishTone(val label: String) {
    Auto("自动"),
    Elder("长辈"),
    Teacher("老师"),
    Work("职场"),
    Friend("朋友"),
    Concise("精简"),
    ;

    val promptLabel: String get() = if (this == Auto) "自动判断" else label
}

data class PolishCandidate(
    val tag: String,
    val text: String,
)

enum class HostCategory(val label: String) {
    Work("办公协作"),
    Im("即时通讯"),
    MailDoc("邮件/长文档"),
    Social("社区评论"),
    General("通用"),
}

data class HostScene(
    val category: HostCategory,
    val hint: String = "",
    val chatBubble: Boolean = false,
)

data class PolishUndo(
    val start: Int,
    val end: Int,
    val original: String,
    val replaced: String,
)
```

### 4.2 宿主包名 → 场景分类

必须实现为纯函数，便于单测：

```kotlin
fun appCategoryOf(packageName: String?): HostCategory

fun hostSceneOf(packageName: String?, hint: String?, enter: EnterKind): HostScene
```

分类规则（`packageName` 为空或未知返回 `General`）：

- `Work`：`com.tencent.wework`、`com.ss.android.lark`、`com.bytedance.ee.lark`、`com.alibaba.android.rimet`、`com.microsoft.teams`、`com.google.android.apps.meetings`、`com.slack` 前缀 `com.Slack`、`com.microsoft.office.outlook` 以外的企业协同类可归入。
- `Im`：`com.tencent.mm`、`com.tencent.mobileqq`、`org.telegram.messenger`、`com.whatsapp`、`jp.naver.line.android`。
- `MailDoc`：`com.tencent.androidqqmail`、`com.netease.mobimail`、`com.google.android.gm`、`com.microsoft.office.outlook`、`cn.wps.moffice_eng`、`com.youdao.note`。
- `Social`：`com.xingin.xhs`、`com.sina.weibo`、`com.ss.android.ugc.aweme`、`tv.danmaku.bili`、`com.zhihu.android`、`com.coolapk.market`。
- 其余 → `General`。

`hostSceneOf` 规则：

```kotlin
val category = appCategoryOf(packageName)
HostScene(
    category = category,
    hint = hint.orEmpty().trim().take(60),
    chatBubble = enter == EnterKind.Send || category == HostCategory.Im,
)
```

### 4.3 系统提示词（逐字使用，不要改写措辞）

```kotlin
const val ADAPTIVE_POLISH_SYSTEM_PROMPT =
    "你是中文与英文输入法里的语境润色专家。请根据【场景线索】、【前文参考】、【指定方向】和【待润色原文】，先在内部判断沟通对象与言语意图（请假、致歉、求助、拒绝、汇报、催促、问候、评论等），再输出可直接发送的改写结果。\n" +
        "\n" +
        "一、分对象润色准则\n" +
        "1. 长辈/亲属：重在温度、体贴与礼貌。去公文腔（忌用“知悉、打扰、烦请”），去生硬网络黑话，语气尊敬但亲近自然。\n" +
        "2. 老师/导师/前辈：重在谦逊、尊重与条理。恰当使用“您/老师/请教/麻烦”，按“事由+诉求/补救方案”理顺逻辑，把质问或祈使句改为礼貌请教。\n" +
        "3. 上级/客户/职场协作：重在专业、结论先行、去情绪化。把抱怨或推脱改为客观进展、原因与可执行方案，简洁得体。\n" +
        "4. 朋友/同辈日常：重在自然松弛、消除歧义。严禁过度敬语化（不要把朋友聊天改成公文或客服腔），仅顺畅语序或柔化易引发误会的生硬语气。\n" +
        "\n" +
        "二、硬性红线\n" +
        "- 严禁篡改原文核心事实、时间、数字、人名与原语言（中文保持简体中文，英文保持英文）。\n" +
        "- 若【前文参考】中已经写过称呼/问候，待润色正文中严禁重复称呼。\n" +
        "- 若【输入形态】为“即时聊天气泡”，必须保持日常消息的短句篇幅，严禁添加书信抬头、“此致敬礼”或小作文式扩写。\n" +
        "\n" +
        "三、输出数量与严格格式\n" +
        "- 若【指定方向】不为“自动判断”，仅针对该方向输出 2 个不同详略度的高质量版本。\n" +
        "- 若【指定方向】为“自动判断”且原文/线索已能明确判断对象（如含称呼或明显职场/学术语境），输出 1 个最佳匹配版本 + 1 个更简短版本（共 2 个）。\n" +
        "- 若【指定方向】为“自动判断”且原文完全无法判断对象（如“明天有事去不了”），必须覆盖不同关系输出 3 个版本（如：得体委婉、温和亲切、自然口语）。\n" +
        "- 严格按以下格式输出，不要任何开场白、解释或引号包裹：\n" +
        "【对象·意图标签】\n" +
        "润色后的正文\n"
```

### 4.4 用户消息构造

```kotlin
fun buildPolishUserPrompt(
    original: String,
    contextBefore: String,
    contextAfter: String,
    scene: HostScene,
    tone: PolishTone,
): String
```

输出顺序固定，空段落整体省略（不要留空标题）：

```text
【指定方向】自动判断
【输入形态】即时聊天气泡（短句，禁止书信格式）；应用类型：即时通讯；输入框提示：回复 @张老师
【前文参考（仅用于判断对象，勿润色、勿重复）】
王老师您好：
【后文参考（勿润色、勿重复）】
谢谢老师
【待润色原文】
明天早八我有课去不了了
```

实现要求：

- `chatBubble` 为 true → `即时聊天气泡（短句，禁止书信格式）`；否则 `普通文本（可多行）`。
- `hint` 为空时不输出 `；输入框提示：`。
- `contextBefore` / `contextAfter` 为空白时整段不输出。
- `original` 始终输出。

### 4.5 流式块解析

```kotlin
fun parsePolishStream(raw: String): List<PolishCandidate>
```

规则，按顺序执行：

1. 空输入返回 `emptyList()`。
2. 统一换行：`\r\n` 与 `\r` 归一为 `\n`，按行切分。
3. 标签正则：`^【([^】]{1,24})】\s*(.*)$`（对 `trim()` 后的行匹配）。
4. 匹配到标签 → 先 flush 上一条（正文 `trim()` 后非空才产出，`tag` 为标签文本），再开启新块；标签行 `】` 之后的剩余内容作为正文首行。
5. 未匹配标签且尚未遇到任何标签 → 丢弃（模型开场白）。
6. 未匹配标签且已开启块 → 追加到当前正文。
7. **半截标签保护**：若某行以 `【` 开头、不含 `】`、且长度 ≤ 26，视为流式未完成的标签，直接跳过，不写入正文（避免上一条正文被污染）。
8. 全部行处理完后，若出现过标签 → flush 收尾并返回；若从未出现标签 → 整体降级：
   `listOf(PolishCandidate("智能润色", stripWrapped(raw).trim()))`，`raw` 全空则返回空列表。
9. 每条候选正文都要经过 `stripWrapped`，去掉首尾引号与 `"润色："`、`"Polished:"` 之类前缀。
10. 函数必须是纯函数：不修改入参、不访问 Android API、对流式半成品输入不抛异常。

参考实现（可直接采用）：

```kotlin
private val PolishTag = Regex("^【([^】]{1,24})】\\s*(.*)$")

fun parsePolishStream(raw: String): List<PolishCandidate> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = ArrayList<PolishCandidate>()
    val body = StringBuilder()
    var tag: String? = null
    var sawTag = false

    fun flush() {
        val text = stripWrapped(body.toString()).trim()
        val name = tag
        if (name != null && text.isNotEmpty()) out += PolishCandidate(name, text)
        body.setLength(0)
    }

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.startsWith("【") && !line.contains("】") && line.length <= 26) continue
        val match = PolishTag.find(line)
        if (match != null) {
            flush()
            tag = match.groupValues[1].trim()
            sawTag = true
            val rest = match.groupValues[2]
            if (rest.isNotEmpty()) body.append(rest).append('\n')
            continue
        }
        if (!sawTag) continue
        body.append(rawLine).append('\n')
    }
    if (!sawTag) {
        val whole = stripWrapped(raw).trim()
        return if (whole.isEmpty()) emptyList() else listOf(PolishCandidate("智能润色", whole))
    }
    if (body.isNotEmpty()) flush()
    return out
}
```

注意：`flush()` 内部已经清除 `body`，但 `tag` 保持到下一次赋值，因此连续空块不会产出空卡片。

---

## 5. 改造 `app/src/main/java/dev/cheng/keyboard/ime/Editor.kt`

### 5.1 扩展 `FieldSource`（第 93 行）

新增两个带默认值的字段，保证三处构造点向后兼容：

```kotlin
data class FieldSource(
    val text: String,
    val selection: Boolean,
    val start: Int,
    val end: Int,
    val contextBefore: String = "",
    val contextAfter: String = "",
)
```

### 5.2 填充前后文（`readField`，第 100~119 行）

新增私有纯函数：

```kotlin
private fun contextBeforeOf(all: String, start: Int, limit: Int = 200): String =
    if (start <= 0) "" else all.substring(maxOf(0, start - limit), start)

private fun contextAfterOf(all: String, end: Int, limit: Int = 120): String =
    if (end < 0 || end >= all.length) "" else all.substring(end, minOf(all.length, end + limit))
```

三处返回点：

- **选区分支**（第 109 行）：
  `FieldSource(all.substring(from, to).take(4000), true, from, to, contextBeforeOf(all, from), contextAfterOf(all, to))`
- **`paragraphSource` 分支**（第 111 行）：在函数内部用最终 `start` / `end` 填充上面两个字段。
- **`getSelectedText` 兜底**（第 114 行）：偏移为 `-1`，前后文留空（`""`）。
- **`getTextBeforeCursor` 兜底**（第 118 行）：`contextBefore` 取 `before` 去掉当前段头后的尾部 200 字，`contextAfter` 取 `after` 头部 120 字，实现方式与 `paragraphHead` 长度对齐即可。

关键约束：`text` 仍是**要被替换的目标内容**，前后文绝不能合并进 `text`，否则 `replaceResult()` 会误删。

### 5.3 新增 `EditorInfo` 场景提取（文件末尾）

```kotlin
fun extractHostScene(info: EditorInfo?, enter: EnterKind): HostScene {
    val traits = info?.let(::fieldTraitsOf)
    val hint = if (info == null || traits?.password == true || traits?.noLearn == true) {
        null
    } else {
        info.hintText?.toString()
    }
    return hostSceneOf(info?.packageName, hint, enter)
}
```

需要 import `dev.cheng.keyboard.ai.HostScene`、`dev.cheng.keyboard.ai.hostSceneOf`。

---

## 6. 改造 `app/src/main/java/dev/cheng/keyboard/ime/UiState.kt`

在 `UiState` 末尾新增 4 个字段（保持默认值，避免初始化处全部改动）：

```kotlin
val polishTone: PolishTone = PolishTone.Auto,
val polishCandidates: List<PolishCandidate> = emptyList(),
val polishSelected: Int = 0,
val canUndoPolish: Boolean = false,
```

import：`dev.cheng.keyboard.ai.PolishCandidate`、`dev.cheng.keyboard.ai.PolishTone`。

`PolishUndo` 建议只保存在 Controller 私有字段，不进入 `UiState`（含原文，避免 Compose 重组持有大字符串）。

---

## 7. 改造 `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt`

### 7.1 新增私有字段（第 92 行附近）

```kotlin
private var lastPolishUndo: PolishUndo? = null
```

### 7.2 拆分 `transform` 的守卫逻辑（第 601 行）

抽出，`translate()` 的行为保持不变：

```kotlin
private fun prepareTransform(kind: ResultKind): FieldSource? {
    if (engineState.password) return null
    val key = secrets.get()
    if (key.isNullOrBlank()) {
        update { it.copy(overlay = Overlay.NeedKey, menu = false, keyDraft = "") }
        return null
    }
    val field = connection()?.let(::readField)
    if (field == null) {
        update { it.copy(overlay = Overlay.Result(kind), resultText = "", note = "读不到输入框内容", busy = false, menu = false) }
        return null
    }
    if (field.text.isBlank()) {
        update { it.copy(overlay = Overlay.Result(kind), resultText = "", note = "没有可处理的内容", busy = false, menu = false) }
        return null
    }
    return field
}
```

`transform(kind, prompt, temperature)` 改为调用它，并在开始时追加
`update { it.copy(polishCandidates = emptyList(), polishSelected = 0, canUndoPolish = false) }`
（保证翻译面板不显示上一次的润色候选）。

### 7.3 替换 `polish()`（第 474 行）

```kotlin
fun polish() = polish(PolishTone.Auto)

fun polish(tone: PolishTone) {
    val open = state.overlay as? Overlay.Result
    val reuse = open?.kind == ResultKind.Polish && source != null
    val field = if (reuse) source!! else prepareTransform(ResultKind.Polish)?.also { source = it } ?: return
    val key = secrets.get().orEmpty()
    if (key.isBlank()) return
    val scene = extractHostScene(activeInfo, enter)
    val user = buildPolishUserPrompt(field.text, field.contextBefore, field.contextAfter, scene, tone)
    update {
        it.copy(
            overlay = Overlay.Result(ResultKind.Polish),
            polishTone = tone,
            polishCandidates = emptyList(),
            polishSelected = 0,
            resultText = "",
            resultSource = field.text.take(80),
            note = null,
            busy = true,
            menu = false,
            expanded = false,
            canUndoPolish = false,
        )
    }
    launchAi(
        key = key,
        model = MODEL_FLASH,
        system = ADAPTIVE_POLISH_SYSTEM_PROMPT,
        user = user,
        temperature = 0.35,
        maxTokens = 2048,
        thinking = false,
        onStream = { _, content ->
            val list = parsePolishStream(content)
            if (list.isNotEmpty()) {
                update { prev ->
                    val index = prev.polishSelected.coerceIn(0, list.lastIndex)
                    prev.copy(
                        polishCandidates = list,
                        polishSelected = index,
                        resultText = list[index].text,
                    )
                }
            }
        },
        onOk = { text ->
            val list = parsePolishStream(text)
            update { prev ->
                val index = prev.polishSelected.coerceIn(0, maxOf(0, list.lastIndex))
                prev.copy(
                    busy = false,
                    polishCandidates = list,
                    polishSelected = index,
                    resultText = list.getOrNull(index)?.text.orEmpty(),
                    note = if (list.isEmpty()) "没有返回正文" else null,
                )
            }
        },
    )
}

fun selectPolishCandidate(index: Int) {
    val list = state.polishCandidates
    if (index !in list.indices) return
    update { it.copy(polishSelected = index, resultText = list[index].text) }
}
```

说明：

- 复用 `source` 实现"切语气胶囊不重新读输入框"，避免切换时丢失用户已选区间。
- `launchAi` 内部已做 35ms 节流与 `aiToken` 竞态保护，不要重复实现。
- `resultText` 必须始终等于当前选中候选，这样 `insertResult()` / `copyResult()` / `replaceResult()` 无需改动。

### 7.4 `replaceResult()` 记录撤回（第 562 行）

仅在润色替换成功分支（原文校验通过、`commitText` 之后）：

```kotlin
if ((state.overlay as? Overlay.Result)?.kind == ResultKind.Polish) {
    lastPolishUndo = PolishUndo(target.start, target.start + text.length, target.text, text)
    finishOverlay("已替换")
    update { it.copy(canUndoPolish = true) }
    return
}
```

其余分支（`target == null`、原文已变）保持现有"插入 + 提示"逻辑不变。

### 7.5 新增 `undoPolish()`

```kotlin
fun undoPolish() {
    val undo = lastPolishUndo ?: return
    val input = connection() ?: return
    val current = input.getExtractedText(
        android.view.inputmethod.ExtractedTextRequest().apply {
            hintMaxChars = 10000
            hintMaxLines = 200
        },
        0,
    )?.text?.toString() ?: return
    if (undo.end > current.length || current.substring(undo.start, undo.end) != undo.replaced) {
        lastPolishUndo = null
        update { it.copy(canUndoPolish = false, note = "原文已变化，无法撤回") }
        return
    }
    input.setSelection(undo.start, undo.end)
    input.commitText(undo.original, 1)
    lastPolishUndo = null
    update { it.copy(canUndoPolish = false, note = "已撤回") }
}
```

### 7.6 清理时机

- `finishOverlay()`：追加 `polishCandidates = emptyList(), polishSelected = 0, polishTone = PolishTone.Auto`。
- `startInput()`：追加 `canUndoPolish = false` 并清空 `lastPolishUndo`（输入框切换后区间失效）。
- `polish()` 与 `transform()` 开始：`canUndoPolish = false`。
- 撤回失败时（区间校验不过）清空快照，避免反复失败。

---

## 8. 改造 `app/src/main/java/dev/cheng/keyboard/ime/ui/KeyboardScreen.kt`

### 8.1 工具栏撤回入口（第 272~278 行）

优先级：`canUndoPolish` > `note` > `freshClip`。

```kotlin
if (state.canUndoPolish) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.sheet)
            .clickable { controller.undoPolish() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已替换", color = palette.subtle, fontSize = 12.sp)
        Text(" 撤回", color = palette.accent, fontSize = 12.sp)
    }
} else if (note != null) { ... 原逻辑 ... } else if (freshClip != null) { ... 原逻辑 ... }
```

### 8.2 重写 `ResultPanel`（第 734 行）

要求：`ResultKind.Translate` 走原单文本框分支；`ResultKind.Polish` 走新分支。

```kotlin
@Composable
private fun ResultPanel(kind: ResultKind, state: UiState, palette: Palette, controller: KeyboardController) {
    if (kind == ResultKind.Translate) {
        TranslatePanel(state, palette, controller)
        return
    }
    Column(Modifier.sheet(palette)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("润色", color = palette.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PolishTone.entries.forEach { tone ->
                    val on = state.polishTone == tone
                    Text(
                        tone.label,
                        color = if (on) palette.label else palette.subtle,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) palette.pressed else Color.Transparent)
                            .clickable(enabled = !state.busy) { controller.polish(tone) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (state.resultSource.isNotBlank()) {
            Text("原文：${state.resultSource}", color = palette.subtle, fontSize = 12.sp, maxLines = 1)
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
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) palette.key else Color.Transparent)
                            .clickable { controller.selectPolishCandidate(index) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(candidate.tag, color = palette.accent, fontSize = 11.sp, maxLines = 1)
                        Text(candidate.text, color = palette.label, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            state.busy -> Text("正在识别语境并润色…", color = palette.subtle, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
            else -> state.note?.let { Text(it, color = palette.subtle, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionText("替换", palette, controller::replaceResult)
            ActionText("插入", palette, controller::insertResult)
            ActionText("复制", palette, controller::copyResult)
            ActionText("关闭", palette, controller::closeOverlay)
        }
    }
}
```

同时把原 `ResultPanel` 的单文本框部分抽成 `TranslatePanel(state, palette, controller)`，逻辑与改造前完全一致，不要改动文案。

需要新增 import：`androidx.compose.foundation.horizontalScroll`、`dev.cheng.keyboard.ai.PolishTone`。

约束：

- 候选区最大高度 `132.dp`，超出滚动，不允许撑高键盘。
- `state.busy` 为 true 时语气胶囊不可点击（已在 `clickable(enabled = !state.busy)` 处理）。
- 不显示原始 `【标签】` 文本，标签只用于小号徽章。

---

## 9. 测试（追加到 `app/src/test/java/dev/cheng/keyboard/KeyboardLogicTest.kt`）

全部为 JVM 纯函数测试，不构造 `EditorInfo`、不调用 `readField`。

1. `polishStreamSplitsMultipleCandidates`
   - 输入含 2 个 `【…】` 块的多行文本，断言返回 2 条、标签与正文正确、正文内换行保留。
2. `polishStreamHidesHalfWrittenTag`
   - 输入 `"【对老师·请假】\n王老师您好\n【简短"`，断言只有 1 条候选且正文不含 `【简短`。
3. `polishStreamFallsBackToPlainText`
   - 输入 `"润色：今天去开会路上堵车，稍晚到。"`，断言 1 条、标签 `智能润色`、正文不含 `润色：` 与引号。
4. `polishStreamDropsPreamble`
   - 输入 `"好的，以下是润色结果：\n【长辈·温和】\n妈，我这周末回不去啦"`，断言只有 1 条且标签为 `长辈·温和`。
5. `polishPromptIncludesContextOnlyWhenPresent`
   - 前后文为空时，断言结果不含 `【前文参考` 与 `【后文参考`；非空时包含且原文位于最后一段。
6. `polishSceneClassifiesPackages`
   - 断言 `com.tencent.mm` → `Im` 且 `chatBubble = true`；`com.tencent.wework` → `Work`；`com.tencent.androidqqmail` → `MailDoc`；`com.xingin.xhs` → `Social`；`null` → `General`。
7. `polishToneAutoUsesAutoLabel`
   - `PolishTone.Auto.promptLabel == "自动判断"`，`PolishTone.Teacher.promptLabel == "老师"`。

---

## 10. 验收标准

```powershell
cd D:\keyboard
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

- 两条命令均成功，无新增编译警告级错误。
- 现有测试（拼音、英文、手势、自更新解析）全部保持通过。
- 手工回归（真机或模拟器）：
  1. 微信/短信输入框输入 `"明天有事去不了"` → 润色 → 出现多候选，标签能区分关系；点击卡片切换，`替换` 只替换该段。
  2. 多行文本首行写 `"王老师您好："`，光标停第二行正文 → 润色结果不重复称呼。
  3. 选中一段文字 → 润色 → `替换` 只影响选区。
  4. 替换后工具栏出现 `已替换 撤回`，点击能恢复原文；若期间手动改过该段，撤回提示 `原文已变化，无法撤回` 且不破坏文本。
  5. 翻译面板行为与改造前一致；密码框点击润色无任何内容外发。

---

## 11. 已知坑位（实现时逐条核对）

1. `controller::polish` 方法引用不接受默认参数 → 两个重载都要写。
2. `FieldSource` 有三个构造点（选区、段落、兜底），漏改会编译失败。
3. `paragraphSource` 里 `useAll` 分支的 `start = 0`、`end = all.length`，此时前后文必须为空，否则会把整篇文本重复发给模型。
4. `parsePolishStream` 的 `flush()` 若在标签刚出现时执行，正文为空必须丢弃，否则面板会出现空卡片。
5. 不要在 `onStream` 里对整段 `content` 调用 `stripWrapped` 后再解析——`stripWrapped` 只作用于单条候选正文。
6. `finishOverlay()` 会清空 `resultText` 与 `source`，撤回快照必须在调用前捕获。
7. `undoPolish()` 必须重新读取输入框并校验 `[start, end)`，不能直接 `deleteSurroundingText`。
8. 语气切换复用 `source` 时，不要重新执行 `prepareTransform`，否则会重置面板状态并闪屏。
9. 新增 Compose 代码需引入 `horizontalScroll`，否则编译报未解析引用。
10. `Polish.kt` 里不要出现 `android.util.Log`、`Context`、`EditorInfo`，否则 JVM 单测会因 "not mocked" 失败。

---

## 12. 建议执行顺序

1. 新建 `ai/Polish.kt`（类型 + 分类 + 提示词 + 拼装 + 解析）。
2. 在 `KeyboardLogicTest.kt` 补齐第 9 节 7 个测试，先跑 `testDebugUnitTest` 让纯逻辑变绿。
3. 改 `Editor.kt`（`FieldSource` 扩展、前后文、`extractHostScene`）。
4. 改 `UiState.kt` 增加 4 个字段。
5. 改 `KeyboardController.kt`（`prepareTransform`、`polish` 双重载、选中、撤回、清理）。
6. 改 `KeyboardScreen.kt`（工具栏撤回、`ResultPanel` 分流、`TranslatePanel` 抽取）。
7. 跑完整 `testDebugUnitTest` 与 `assembleDebug`，再按第 10 节做手工回归。
