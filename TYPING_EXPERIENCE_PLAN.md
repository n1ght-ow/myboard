# 打字触控体验优化实施任务书

> 仓库：`D:\keyboard`（Android 输入法，Kotlin + Compose，包名 `dev.cheng.keyboard`）
> 性质：实施方案，不是产品文档；执行者在新对话中按本文件落地。
> 行号基于 2026-11-12 工作区；代码漂移时用符号名和 `rg` 定位。

## 0. 启动指令（新对话直接粘贴）

```text
项目根目录：D:\keyboard（Android 输入法，Kotlin + Compose，包名 dev.cheng.keyboard）
请完整阅读仓库根目录 TYPING_EXPERIENCE_PLAN.md，按其中契约实现“打字触控体验优化”。
要求：
1. 先 git status。当前工作区已有未提交的 AI 润色相关改动（POLISH_PLAN.md、ai/Polish.kt 及若干修改文件），禁止 revert、覆盖或顺手重构无关内容。
2. 本文件模块全部实现，不按优先级删减；可按依赖顺序分阶段，但最终必须全部完成。
3. 不新增生产依赖，不改包名，不引入 AccessibilityService、MediaProjection 或截屏权限。
4. 可测试逻辑优先纯 Kotlin；新增逻辑不得依赖 Android Context（持久化除外）。
5. 密码框、IME_FLAG_NO_PERSONALIZED_LEARNING、URI/邮箱模式必须遵守第 1 节边界。
6. 完成后执行 ./gradlew.bat testDebugUnitTest 和 ./gradlew.bat assembleDebug，并报告结果。
7. 不要 commit、不要 push，除非用户另行明确要求。
```

## 1. 不变量与安全边界

1. **零新增生产依赖**：只用现有 Compose、DataStore、Android SDK、JUnit4。
2. **不改已有功能**：`ask()`、`translate()`、`polish()`、`insertResult()`、`copyResult()`、剪贴板、符号面板行为不变。
3. **密码 / `IME_FLAG_NO_PERSONALIZED_LEARNING`**：不计算先验、不启用动态热区、不记录校准样本；保留零死区、核心保护盒、2-Key Rollover（纯几何，不依赖内容）。
4. **URI/邮箱模式**：不启用语言先验和语言纠错；保留零死区、核心保护盒、Rollover。
5. **不改键帽视觉几何**：`placeKeys` 的 `x/y/w/h` 不因热区而变。
6. **不破坏现有测试**：`KeyboardLogicTest` 的拼音、英文、手势、`nearbyLetters` 断言继续通过；允许更新因 API 扩展需要的新参数。
7. **性能**：`hitKey` 每次 O(键数)；`nextLetterPriors` 不访问 SQLite/网络/磁盘；`onMove` 不做解码或大对象分配。
8. **隐私**：校准只持久化三个浮点偏移，不保存文字、轨迹、按键序列。

## 2. 目标

- 修复双拇指高速交替输入的吞字。
- 消除键缝、左右留白、边缘死区。
- 视觉键帽不变，隐形热区按下一字母概率扩张。
- 连续二维高斯空间似然替代“半径内全算邻键”的二值纠错。
- 静态保守基线 + 在线自校准解决拇指落点偏下，兼容模拟器。
- 收紧低频上下滑，增加短按放大气泡和分级触感。

非目标：不改布局、按键数量、键帽尺寸、主题、AI 请求与流式解析逻辑。

## 3. 当前代码锚点

当前工作区有未提交改动，开工先 `git status`，不要回滚。

| 主题 | 文件:符号 | 现状 |
| --- | --- | --- |
| 多指手势 | `app/src/main/java/dev/cheng/keyboard/ime/ui/KeyboardScreen.kt:600` `KeyArea`，`:622` `pointerInput`，`:624` `awaitFirstDown` | 只跟踪第一根手指；`awaitEachGesture` 等所有手指抬起才重启；第二指按下被忽略。 |
| 命中 | `app/src/main/java/dev/cheng/keyboard/ime/layout/KeyboardLayout.kt:73` `hitKey` | 精确矩形，否则 `max(w,h)*0.65` 半径欧氏最近邻，超出返回 null。 |
| 邻键 | 同文件 `:90` `nearbyLetters` | `max(w,h)*1.35` 半径，最多 3 个字母，无权重；半径通常大于键节距，点键中心也会把邻键算作候选。 |
| 布局 | 同文件 `:32` `placeKeys` | 第二排 `letterInset` 内缩，`A/L` 外侧有留白。 |
| 手势 | `app/src/main/java/dev/cheng/keyboard/ime/gesture/KeyGesture.kt:27` 类，`:51` `down`，`:77` `move`，`:148` 跨键滑移，`:160` `up` | 跨键只判断 `slopPx`；换键未传新样本，`downNeighbors` 被清空；上下滑用 `swipePx` 和 1.15 角度比。 |
| 引擎 | `app/src/main/java/dev/cheng/keyboard/engine/InputEngine.kt:11` `EngineState`，`:28` `touchHints`，`:46` `EngineAct` | `touchHints: List<String>`，每字母一个无权重邻键串。 |
| 英文 | `app/src/main/java/dev/cheng/keyboard/engine/Latin.kt:47` `suggest`，`:247` `fuzzyMatches`，`:289` 空间替换 | 空间命中固定 `subCost=0.78`，无法区分靶心与压线。 |
| 拼音 | `app/src/main/java/dev/cheng/keyboard/engine/Pinyin.kt:14` `SylTrie`，`:333` `decode`，`:960` `typos`，`:1384` `correctedSegments`，`:1465` `editCost` | 空间命中是布尔；`correctedSegments` 只返回 `cost>0`，合法擦边音节不会被软纠错。 |
| 控制器 | `app/src/main/java/dev/cheng/keyboard/ime/KeyboardController.kt:136` `configure`，`:292` `onDown`，`:298` `onMove`，`:836` `applyFrame` | 手势阈值在 `configure`；`onDown` 接收邻键串；`applyFrame` 只更新 `pressedId/popup`。 |
| 触感 | `app/src/main/java/dev/cheng/keyboard/ime/KeyboardService.kt:127` | 只用 `LONG_PRESS/CLOCK_TICK/KEYBOARD_TAP`。 |
| 状态 | `app/src/main/java/dev/cheng/keyboard/ime/UiState.kt:19`，`:26` `pressedId`，`:27` `popup` | 无短按气泡状态。 |
| 设置 | `app/src/main/java/dev/cheng/keyboard/data/Stores.kt:23` `PrefKeys` | 已有 `floatPreferencesKey`。 |
| 工程 | `app/build.gradle.kts` | minSdk 26，targetSdk 36，compileSdk 37，Kotlin 2.2.10，Compose BOM 2026.09.00；Manifest 已有 `VIBRATE`。 |

## 4. 数据模型

### 4.1 `model/Types.kt`

```kotlin
data class KeyLikelihood(val ch: Char, val penalty: Float) // penalty [0,1]

data class TouchSample(
    val primary: Char? = null,
    val alternatives: List<KeyLikelihood> = emptyList(),
    val residualX: Float = 0f,
    val residualY: Float = 0f,
    val leftSide: Boolean = false,
) {
    val neighborText: String get() = alternatives.joinToString("") { it.ch.toString() }
    fun penaltyOf(ch: Char): Float? = when {
        ch == primary -> 0f
        else -> alternatives.firstOrNull { it.ch == ch }?.penalty
    }
}

data class TouchBias(
    val leftDxRatio: Float = 0f,
    val rightDxRatio: Float = 0f,
    val dyRatio: Float = 0f, // 正值 = 手指偏下，命中时 y 减去它
)
```

### 4.2 `KeyboardLayout.kt`

```kotlin
val NoPriors: List<Float> = List(26) { 0f }

data class HitContext(
    val priors: List<Float> = NoPriors,
    val composing: Boolean = false,
    val hasCandidates: Boolean = false,
    val password: Boolean = false,
    val noLearn: Boolean = false,
    val bias: TouchBias = TouchBias(),
    val boardWidthPx: Float = 0f,
    val refKeyWidthPx: Float = 0f,
    val refKeyHeightPx: Float = 0f,
)

private const val CoreInsetX = 0.22f
private const val CoreInsetY = 0.20f
private const val PriorLambda = 2.0f
private const val MaxNormalizedDistance = 2.5f
private const val GaussianSigmaX = 0.42f
private const val GaussianSigmaY = 0.45f
private const val EnergyRange = 3.2f
private const val TouchPenaltyCutoff = 0.85f
```

### 4.3 `KeyGesture.kt`

```kotlin
data class TapPreview(val keyId: String, val label: String)

data class GestureFrame(
    val pressedId: String?,
    val popup: PopupPreview?,
    val actions: List<EngineAct> = emptyList(),
    val haptic: HapticKind? = null,
    val tapPreview: TapPreview? = null,
)
```

### 4.4 `InputEngine.kt`

```kotlin
data class EngineState(
    // ...原有字段...
    val touchHints: List<String> = emptyList(),
    val touchSamples: List<TouchSample?> = emptyList(),
)

sealed interface EngineAct {
    data class Letter(
        val ch: Char,
        val force: Choice?,
        val symbol: String,
        val neighbors: String = "",
        val sample: TouchSample? = null,
    ) : EngineAct
}
```

`sample` 必须是第 5 个参数，保留旧 `EngineAct.Letter(ch, null, "")` 调用。

## 5. 模块 A：2-Key Rollover

### 5.1 `KeyGesture`

```kotlin
fun canRollover(): Boolean {
    val current = key ?: return false
    if (!tracking || armed || flickChoice != null || spaceSliding || repeating || swiped) return false
    return current.kind in setOf(
        KeyKind.Letter, KeyKind.Char, KeyKind.Space,
        KeyKind.Backspace, KeyKind.Enter, KeyKind.Shift,
    )
}

fun rollover(
    target: KeyModel, x: Float, y: Float, time: Long,
    touchNeighbors: String = "", touchSample: TouchSample? = null,
): List<GestureFrame> {
    if (!canRollover()) return emptyList()
    val first = up()
    val second = down(target, x, y, time, touchNeighbors, touchSample)
    return listOf(first, second)
}
```

排除 `Language` 和 `Page*`；`Shift` 允许（先结算 Shift 再按字母得到大写）；`Backspace` 未连删时允许。

### 5.2 `KeyArea` 多指循环

把 `awaitEachGesture + awaitFirstDown` 替换为单循环 `awaitPointerEventScope`：

```kotlin
awaitPointerEventScope {
    var activeId: PointerId? = null
    var pendingAdopt = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val keysNow = placementState.value.keys
        val ctxNow = controller.hitContext(widthState.value, refWidthState.value, heightState.value)
        val downs = event.changes.filter { it.changedToDown() }
        if (activeId == null) {
            val down = downs.firstOrNull()
                ?: if (pendingAdopt) event.changes.firstOrNull { it.pressed } else null
                ?: continue
            pendingAdopt = false
            val hit = hitKey(keysNow, down.position.x, down.position.y, ctxNow) ?: continue
            controller.onDown(hit.model, down.position.x, down.position.y,
                SystemClock.uptimeMillis(),
                touchSampleOf(keysNow, down.position.x, down.position.y, hit.model, ctxNow))
            down.consume()
            activeId = down.id
            continue
        }
        val second = downs.firstOrNull { it.id != activeId && it.pressed }
        if (second != null) {
            val hit = hitKey(keysNow, second.position.x, second.position.y, ctxNow)
            if (hit != null) {
                val switched = controller.onRollover(hit.model, second.position.x, second.position.y,
                    SystemClock.uptimeMillis(),
                    touchSampleOf(keysNow, second.position.x, second.position.y, hit.model, ctxNow))
                if (switched) activeId = second.id
            }
            second.consume()
        }
        val active = event.changes.firstOrNull { it.id == activeId } ?: continue
        if (!active.pressed) {
            controller.onUp(SystemClock.uptimeMillis())
            activeId = null
            pendingAdopt = true
            active.consume()
            continue
        }
        val current = hitKey(keysNow, active.position.x, active.position.y, ctxNow)
        controller.onMove(active.position.x, active.position.y,
            SystemClock.uptimeMillis(), current?.model,
            current?.let { touchSampleOf(keysNow, active.position.x, active.position.y, it.model, ctxNow) })
        active.consume()
    }
}
```

导入：删除 `awaitEachGesture`、`awaitFirstDown`；新增 `awaitPointerEventScope`、`awaitPointerEvent`、`PointerEventPass`、`changedToDown`、`PointerId`。

`KeyboardController.onRollover`：

```kotlin
fun onRollover(key: KeyModel, x: Float, y: Float, time: Long, sample: TouchSample? = null): Boolean {
    val frames = gesture.rollover(key, x, y, time, sample?.neighborText.orEmpty(), sample)
    if (frames.isEmpty()) return false
    if (key.kind == KeyKind.Letter && !engineState.password && !engineState.noLearn) {
        sample?.let(calibrator::record)
    }
    frames.forEach(::applyFrame)
    return true
}
```

验收：`down('n') -> rollover('i') -> up()` 依次产生 `Letter('n')`、`Letter('i')`，不丢不乱序。

## 6. 模块 B：零死区 + 核心保护盒 + 动态热区

### 6.1 纠偏

`hitKey` 先把触点夹到面板内，再减偏移：

```text
xCorr = x - (x < boardWidth/2 ? leftDxRatio : rightDxRatio) * refKeyWidth
yCorr = y - dyRatio * refKeyHeight
```

密码/`noLearn` 只用静态 `BASE_DY = 0.07f`，不用在线学习值。

### 6.2 核心保护盒

```text
x ∈ [key.x + 0.22*w, key.x + 0.78*w]
y ∈ [key.y + 0.20*h, key.y + 0.80*h]
```

落在盒内无条件命中该键；多个重叠取中心最近者。这保证刻意点低频键中心时不被高概率邻键抢走。

### 6.3 加权 Voronoi

核心盒外：

```text
nx = (xCorr - centerX) / (w/2)
ny = (yCorr - centerY) / (h/2)
score = nx*nx + ny*ny - PriorLambda * prior
```

取最小 score。`prior`：

| 键 | prior |
| --- | --- |
| 字母 | `priors[ch-'a']`，夹 `[-0.35, 1.0]` |
| `backspace` 且 `composing` | `1.20`，夹到 `1.4` |
| `space` 且 `hasCandidates` | `0.90`，夹到 `1.4` |
| `shift` 且 `composing` | `0.50` |
| `to-symbols`/`to-number`/`lang` 且 `composing` | `0.60` |
| 其他 | `0` |

`PriorLambda=2.0`：键缝中心归一化距离约 1.0，最大先验可跨缝并吃进邻键边缘约 5%~13%；核心盒从 22% 开始，留约 9% 缓冲。不要调到 3 以上。

### 6.4 零死区

- 只考虑 `abs(nx)<=2.5 && abs(ny)<=2.5` 的键。
- 没有候选时返回全局中心距离最近键。
- 面板内 `hitKey` 永不返回 null；`A/L` 外侧留白归属最近字母。

### 6.5 API 兼容

```kotlin
fun hitKey(keys: List<PlacedKey>, x: Float, y: Float): PlacedKey? =
    hitKey(keys, x, y, HitContext())

fun hitKey(keys: List<PlacedKey>, x: Float, y: Float, context: HitContext): PlacedKey?
```

验收：输入 `tim` 后 `E` 先验 1、`R` 先验 0，`E/R` 缝隙中点命中 `E`；`R` 核心盒左边界内命中 `R`；密码模式同一点恢复 `R`。

## 7. 模块 C：下一字母先验

### 7.1 英文

`LatinDecoder.nextLetterPriors(prefix, prevWord): FloatArray`：

1. `prefix` 非空：`walk(prefix.lowercase())`，对子节点取 `ln(child.best+1)`，按最大归一化到 `[0,1]`；不存在的子节点 `-0.20f`；`walk` 为 null 返回全 0。
2. `prefix` 为空且有 `prevWord`：从 `bigrams[prevWord]` 统计后继词首字母，归一化最大 `0.55f`；无 bigram 返回全 0。
3. 词首无 `prevWord` 返回全 0。
4. 密码/`noLearn`/`uriOrEmail` 由调用方使用 `NoPriors`。

### 7.2 拼音

`SylTrie` 新增：

```kotlin
fun continuationLetters(prefix: String): Set<Char> {
    if (prefix.isEmpty()) return emptySet()
    var node = root
    for (ch in prefix) {
        if (ch !in 'a'..'z') return emptySet()
        node = node.next[ch - 'a'] ?: return emptySet()
    }
    return buildSet {
        for (i in 0..25) if (node.next[i] != null) add('a' + i)
    }
}

fun initialLetters(): Set<Char> = buildSet {
    for (i in 0..25) {
        if (root.next[i] != null) {
            val ch = 'a' + i
            if (ch !in "iuv") add(ch)
        }
    }
}
```

`PinyinDecoder.nextLetterPriors(raw, context): FloatArray`：

1. `raw` 空：默认 0；`i/u/v=-0.30`；`a/e/o=0.10`；常见声母 `b p m f d t n l g k h j q x z c s r y w=0.20`。
2. `raw` 非空：取最后一个 `'` 后片段 `chunk`；从末尾找 1~6 字符中最长的 `sub`，满足 `trie.hasPrefix(sub,0) || trie.canonicalOf(sub)!=null`。
3. 全部默认 `-0.30`；`trie.continuationLetters(sub)` 的字母 `0.85`；若 `sub` 是完整音节，`trie.initialLetters()` 至少 `0.30`。
4. 不调用 `associate()`/SQLite；密码/`noLearn`/`uriOrEmail` 用 `NoPriors`。

验收：`zh` 后 `a/e/i/u/o` 高、`v` 低；`q` 后 `i/u` 高、`a` 低。

### 7.3 控制器按需计算

```kotlin
fun hitContext(widthPx: Float, refKeyWidthPx: Float, refKeyHeightPx: Float): HitContext {
    val isPrivate = engineState.password || engineState.noLearn
    val priors = when {
        isPrivate || engineState.uriOrEmail -> NoPriors
        engineState.language == Language.English ->
            latin.nextLetterPriors(engineState.raw, engineState.lastEnglishWord).toList()
        else ->
            pinyin.nextLetterPriors(engineState.raw, engineState.lastChineseContext).toList()
    }
    return HitContext(
        priors = priors,
        composing = !isPrivate && (engineState.raw.isNotEmpty() || engineState.candidates.isNotEmpty()),
        hasCandidates = !isPrivate && engineState.candidates.isNotEmpty(),
        password = isPrivate,
        noLearn = engineState.noLearn,
        bias = calibrator.bias(isPrivate),
        boardWidthPx = widthPx,
        refKeyWidthPx = refKeyWidthPx,
        refKeyHeightPx = refKeyHeightPx,
    )
}
```

## 8. 模块 D：模拟器边界与在线校准

### 8.1 能/不能

- **能**：验证零死区、核心盒、动态热区边界、短按/长按气泡、布局帧率、密码模式、API 31+ 触感不崩溃；用 JVM `rollover` 测时序。
- **不能**：校准人类拇指偏下常数。模拟器鼠标是理想像素点，没有指腹弧度、握持视差和指肚滚动；统计会趋近 0，回真机无效。

### 8.2 策略

- 静态 `BASE_DY=0.07f`（约 7% 键高）；初始学习值 0。
- 模拟器鼠标点键帽中心（0.50h）纠偏后 0.43h，仍在核心盒内，不影响正常点击。
- 真机通过“干净上屏”样本自动收敛。

### 8.3 `TouchCalibrator`（新文件 `ime/touch/TouchCalibrator.kt`，纯 Kotlin）

```kotlin
class TouchCalibrator {
    private var learnedLeft = 0f
    private var learnedRight = 0f
    private var learnedDy = 0f
    private val samples = ArrayList<TouchSample>(16)
    private var dirty = false
    private var commitsSinceSave = 0

    companion object {
        const val BASE_DY = 0.07f
        private const val ALPHA = 0.12f
        private const val MIN_SAMPLES = 3
        private const val MAX_RESIDUAL = 0.35f
        private const val PERSIST_EVERY = 20
    }

    fun load(left: Float, right: Float, dy: Float) {
        learnedLeft = left.coerceIn(-0.06f, 0.06f)
        learnedRight = right.coerceIn(-0.06f, 0.06f)
        learnedDy = dy.coerceIn(-0.09f, 0.08f)
    }

    fun bias(isPrivate: Boolean): TouchBias = if (isPrivate) {
        TouchBias(dyRatio = BASE_DY)
    } else {
        TouchBias(learnedLeft, learnedRight, (BASE_DY + learnedDy).coerceIn(-0.02f, 0.15f))
    }

    fun record(sample: TouchSample) {
        if (dirty) {
            if (samples.isNotEmpty()) return
            dirty = false
        }
        if (kotlin.math.abs(sample.residualX) > MAX_RESIDUAL) return
        if (kotlin.math.abs(sample.residualY) > MAX_RESIDUAL) return
        samples += sample
    }

    fun markDirty() { dirty = true; samples.clear() }

    fun commitIfClean(): Boolean {
        if (dirty || samples.size < MIN_SAMPLES) {
            samples.clear(); dirty = false; return false
        }
        val left = samples.filter { it.leftSide }.map { it.residualX }
        val right = samples.filterNot { it.leftSide }.map { it.residualX }
        val dy = samples.map { it.residualY }
        if (left.isNotEmpty()) learnedLeft = ema(learnedLeft, median(left), ALPHA)
        if (right.isNotEmpty()) learnedRight = ema(learnedRight, median(right), ALPHA)
        learnedDy = ema(learnedDy, median(dy) - BASE_DY, ALPHA)
        learnedLeft = learnedLeft.coerceIn(-0.06f, 0.06f)
        learnedRight = learnedRight.coerceIn(-0.06f, 0.06f)
        learnedDy = learnedDy.coerceIn(-0.09f, 0.08f)
        samples.clear(); dirty = false; commitsSinceSave++
        return true
    }

    fun shouldPersist(): Boolean = commitsSinceSave >= PERSIST_EVERY
    fun snapshot(): Triple<Float, Float, Float> = Triple(learnedLeft, learnedRight, learnedDy)
    fun reset() {
        learnedLeft = 0f; learnedRight = 0f; learnedDy = 0f
        samples.clear(); dirty = false; commitsSinceSave = 0
    }

    private fun ema(previous: Float, target: Float, alpha: Float): Float =
        previous * (1f - alpha) + target * alpha

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }
}
```

### 8.4 干净上屏判定

- `onDown` 字母键且 `!password && !noLearn`：`sample?.let(calibrator::record)`。
- `Backspace`、`BackspaceWord`、`Direct`、`MoveCursor`、`Language`、`Page`：`markDirty()`。
- 中文 `Shift` 且有 `raw`：`markDirty()`。
- `Space`、`Pick`、`Enter` 且非私密：`commitIfClean()`。
- 下一字母落指时 `samples` 为空且 `dirty` 为 true，`record` 自动开始新词。

### 8.5 持久化

`PrefKeys` 新增：

```kotlin
val calibLeft = floatPreferencesKey("calib_left")
val calibRight = floatPreferencesKey("calib_right")
val calibDy = floatPreferencesKey("calib_dy")
val calibEpoch = longPreferencesKey("calib_epoch")
```

- 启动或 `calibEpoch` 变化时 `calibrator.load(...)`。
- `commitIfClean() && shouldPersist()` 写三个浮点值；不递增 epoch。
- `resetTouchCalibration()`：`reset()`，写三个 0，`calibEpoch + 1`。
- `SettingsActivity.AppearanceAndInputSection` 增加“重置触控校准”一行，写 0 并递增 epoch，键盘服务运行中也能即时清空。

## 9. 模块 E：连续高斯空间软纠错

### 9.1 `touchSampleOf`

```kotlin
fun touchSampleOf(
    keys: List<PlacedKey>, x: Float, y: Float,
    primary: KeyModel, context: HitContext = HitContext(),
): TouchSample?
```

1. 非字母主键返回 null。
2. 用与 `hitKey` 相同的 bias 纠偏得到 `px/py`。
3. `sx=0.42*primary.w`，`sy=0.45*primary.h`，最小 1f。
4. 对每个字母键：`energy=0.5*(((px-cx)/sx)^2+((py-cy)/sy)^2)`。
5. 以主键能量 `primaryEnergy` 为基准：`penalty=((energy-primaryEnergy)/3.2).coerceIn(0f,1f)`。
6. 排除主键，保留 `penalty<=0.85`，按升序取前 4。
7. `residualX/Y` 用原始 x/y 相对主键中心除以主键宽高；`leftSide=x<boardWidth/2`。

注意：`penaltyOf(primary)` 固定为 0；判断压线歧义必须看 `alternatives` 是否有低惩罚项。

### 9.2 `nearbyLetters` 兼容

```kotlin
fun nearbyLetters(keys: List<PlacedKey>, x: Float, y: Float, primary: KeyModel): String =
    touchSampleOf(keys, x, y, primary)
        ?.alternatives?.sortedBy { it.penalty }?.take(3)
        ?.joinToString("") { it.ch.toString() }.orEmpty()
```

现有 `nearLeftEdgeOfP` 若失败，把 `TouchPenaltyCutoff` 调到 `0.95f`，不要恢复 `1.35*max(w,h)`。

### 9.3 英文

```kotlin
fun suggest(raw, verbatimFirst, prevWord, touchHints, touchSamples): List<Candidate>

internal fun spatialSubstitutionCost(
    candidate: Char, sample: TouchSample?, legacySpatial: Boolean,
): Float? {
    val penalty = sample?.penaltyOf(candidate)
    return when {
        penalty != null -> 0.35f + 0.50f * penalty.coerceIn(0f, 1f)
        legacySpatial -> 0.78f
        else -> null
    }
}
```

`fuzzyMatches` 有样本且候选在 `alternatives` 用连续代价；无样本但旧 hint 命中保留 0.78；否则走 `isQwertyNeighbor` 和原有分支。点键中心时邻键不再被大半径误判。

### 9.4 拼音

```kotlin
fun decode(raw, fuzzy, context, touchHints, touchSamples): Decode
```

1. `Edge.spatial: Boolean` 改 `spatialPenalty: Float`。
2. `editCost` 返回 `Pair<Int, Float>?`：替换惩罚来自 `sample.penaltyOf(target[diff])`；旧 hint 命中为 0.5；都没有为 1。
3. `Node` 增加 `spatialPenalty`，路径取已发生编辑的最小值；`Node.score` 改 `Double`，加 `2.0*(1.0-edge.spatialPenalty)`。
4. `syllableEdges` 排序：cost 升序、spatialPenalty 升序、音节长度降序。
5. `Book.typos` 直接惩罚：`if (cost==1) 18.0 - 6.0*(1.0-spatialPenalty) else 36.0*cost`；惩罚 0→12，1→18，与旧行为一致。

### 9.5 合法音节擦边（`bihao`→`nihao`）

在 `Book` 新增只处理首字母的软变体：

```kotlin
fun softFirstLetter(letters: String, samples: List<TouchSample?>): List<Scored> {
    if (letters.length < 2) return emptyList()
    val sample = samples.getOrNull(0) ?: return emptyList()
    val alt = sample.alternatives.filter { it.penalty <= 0.35f }
        .minByOrNull { it.penalty } ?: return emptyList()
    if (alt.ch !in 'a'..'z') return emptyList()
    val ambiguous = (sample.primary == letters[0]) ||
        ((sample.penaltyOf(letters[0]) ?: 1f) >= 0.55f)
    if (!ambiguous) return emptyList()
    val variant = alt.ch + letters.substring(1)
    val items = ArrayList<Scored>()
    for (seg in segmentPinyin(variant, trie).take(2)) {
        val penalty = 8.0 * (0.35f - alt.penalty)
        items += candidates(seg).take(4).map { it.copy(score = it.score - penalty) }
        items += prefixes(seg).take(2).map { it.copy(score = it.score - penalty) }
    }
    return rank(items)
}
```

`decode` 计算完 `body`、做 `single` 前：

```kotlin
if (touchSamples.isNotEmpty() && letters.length >= 2) {
    val soft = book.softFirstLetter(letters, touchSamples)
    if (soft.isNotEmpty()) body = if (body.isEmpty()) soft else book.rank(body + soft)
}
```

只处理首字母、最多 2 个变体；只有明显压线才触发；仍按词频排序。

### 9.6 引擎接线

所有 `touchHints` 变更同步 `touchSamples`：

- `chineseLetter`：`touchHints + act.neighbors`，`touchSamples + act.sample`。
- `appendEnglish`：新增 sample 参数，`nextSamples`，传给 `latin.suggest(..., touchSamples=nextSamples)`。
- `backspace`：`droppedChar=='\''` 时两者都不 drop；否则同步 `dropLast(1)`。
- 所有 `touchHints=emptyList()` 同步 `touchSamples=emptyList()`。
- `pick`/`chooseChinese`/`leftover` 的 `takeLast(remainLetters)` 同步。
- `startInput`/`onUpdateSelection` 重置同步。
- `refreshChinese`/`requestPinyin` 传 `engineState.touchSamples`。

用 `rg -n "touchHints" app/src/main/java/dev/cheng/keyboard/engine/InputEngine.kt` 逐项检查。

验收：F 中心样本不含 G；F/G 缝隙两者惩罚接近 0；`bihao` 压线样本首选“你好”。

## 10. 模块 F：快速轻敲锁键 + 收紧上下滑

### 10.1 快速轻敲

```kotlin
private val fastTapLockMs: Long = 70L
```

`move` 跨键分支：

```kotlin
val moved = abs(dx) > slopPx || abs(dy) > slopPx
val withinFastTapLock = time - downAt < fastTapLockMs
if (moved && hit != null && hit.kind == KeyKind.Letter &&
    hit.id != current.id && !withinFastTapLock) {
    return down(hit, x, y, time, touchNeighbors, touchSample)
}
```

70ms 内微滑不换键；超过 70ms 仍允许按住滑到正确键。锁只作用于跨键滑移，不作用于上下滑。换键时同步 `downNeighbors` 和 `downSample`，修复当前丢样本问题。

### 10.2 上下滑

```kotlin
class KeyGesture(
    var slopPx: Float,
    var caseSlopPx: Float,
    var swipePx: Float,
    var verticalFlickPx: Float = 46f,
    private val longMs: Long = 350,
    private val repeatMs: Long = 40,
)
```

`configure`：

```kotlin
gesture.slopPx = 16f * density
gesture.caseSlopPx = 34f * density
gesture.swipePx = 36f * density
gesture.verticalFlickPx = 46f * density
```

`move` 上下滑：

- 位移用 `verticalFlickPx`（约 46dp）。
- 角度比 `abs(dy) > abs(dx) * 1.75f`（约 60°）。
- 时长 `time - downAt >= 45L`。
- 取消阈值 `verticalFlickPx * 0.6f`。
- `swipePx` 继续用于退格左滑删词和空格左右移光标。

验收：60.8°、50px、100ms 触发；51° 或 30ms 不触发；退格/空格横滑旧测试通过。

## 11. 模块 G：短按气泡 + 分级触感

### 11.1 短按气泡

`KeyGesture.down` 对字母返回 `TapPreview(target.id,target.label)`，其他键 null。`UiState` 加 `val tapPreview: TapPreview? = null`。

`KeyboardController`：

- `onDown` 取消旧清除任务、清空旧气泡，再 `applyFrame`。
- `onUp`/`onCancel` 记录当前气泡，应用手势后延迟 40ms 清除；40ms 内新键按下则取消任务并替换。
- `startInput`/`onHidden` 清空。
- `applyFrame` 只在 `frame.tapPreview != null` 时写入，移动帧为 null 不清除。

`KeyArea` 渲染：

- 找到 `keyId` 对应 `PlacedKey`；`state.popup != null` 或密码模式不画。
- 宽 48dp、高 56dp，水平居中，垂直在键上方 4dp，顶部夹 0；圆角 12dp，背景 `palette.sheetElevated`，边框 `0.8dp palette.keyBorder`，文字 24sp。

### 11.2 触感

`KeyboardService` 新增：

```kotlin
private val vibrator: Vibrator? by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }
}
```

`performKeyboardHaptic`：

- API 31+ 且 `areAllPrimitivesSupported`：
  - Down `PRIMITIVE_LOW_TICK` 0.55
  - Delete `PRIMITIVE_LOW_TICK` 0.40
  - Step `PRIMITIVE_TICK` 0.40
  - Arm `PRIMITIVE_CLICK` 0.65
  - `VibrationEffect.startComposition().addPrimitive(...).compose()`，外层 `runCatching`。
- 否则回退：Down API 33+ `KEYBOARD_PRESS`，否则 `KEYBOARD_TAP`；Delete `KEYBOARD_TAP`；Step `CLOCK_TICK`；Arm `LONG_PRESS`；保留 `FLAG_IGNORE_VIEW_SETTING`。

`state.haptic` 仍是总开关。

## 12. 文件清单

| 文件 | 改动 |
| --- | --- |
| `model/Types.kt` | `KeyLikelihood`、`TouchSample`、`TouchBias` |
| `ime/layout/KeyboardLayout.kt` | `HitContext`、常量、`hitKey` 重载、`touchSampleOf`、`nearbyLetters` 包装、`priorOf` |
| `ime/gesture/KeyGesture.kt` | `TapPreview`、`GestureFrame.tapPreview`、`verticalFlickPx`、`fastTapLockMs`、`canRollover/rollover`、样本参数、上下滑收紧 |
| `engine/InputEngine.kt` | `touchSamples`、`Letter.sample`、所有清理/裁剪、`latin.suggest` 样本 |
| `engine/Latin.kt` | `nextLetterPriors`、样本参数、`spatialSubstitutionCost` |
| `engine/Pinyin.kt` | `SylTrie` 新方法、`nextLetterPriors`、样本、连续 `spatialPenalty`、`softFirstLetter` |
| `ime/touch/TouchCalibrator.kt` | 新增纯 Kotlin 校准器 |
| `ime/KeyboardController.kt` | `hitContext`、`onRollover`、`onDown/onMove/onUp`、气泡任务、校准生命周期、样本透传 |
| `ime/UiState.kt` | `tapPreview` |
| `ime/ui/KeyboardScreen.kt` | 多指循环、命中/样本接线、`TapBubble` |
| `ime/KeyboardService.kt` | API 31+ 触感与回退 |
| `data/Stores.kt` | `calibLeft/Right/Dy/Epoch` |
| `settings/SettingsActivity.kt` | 重置触控校准 |
| `KeyboardLogicTest.kt` | 适配新参数，保留原断言 |
| `TouchTypingTest.kt` | 新增几何、先验、Rollover、校准、软纠错测试 |

不要改 `ai/Polish.kt`、`POLISH_PLAN.md` 或 AI 润色相关文件。

## 13. 测试计划

### 13.1 命令

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug
./gradlew.bat testDebugUnitTest --tests dev.cheng.keyboard.TouchTypingTest
```

### 13.2 新增 JVM 测试

用 `placeKeys(KeyboardPage.Letters, Language.English, 1080f, 144f, 18f, 30f, 12f)` 生成几何：

1. `hitTestNeverDeadAcrossLetterPanel`：4px 网格遍历，`hitKey` 永远非空。
2. `coreSafeBoxProtectsEveryKey`：每键中心/核心盒四角，其他键先验 1，仍命中本键。
3. `dynamicPriorExpandsIntoGap`：E 先验 1、R 先验 0，E/R 缝隙中点命中 E。
4. `coreBoxStopsDynamicExpansion`：R 核心盒左边界内命中 R。
5. `passwordDisablesDynamicPrior`：同位置 `password=true` 命中 R。
6. `englishNextLetterPriorsAfterTim`：高频 `time` 词库，`e` 明显高于 `w/r`。
7. `pinyinNextLetterPriorsAfterZhAndQ`：`zh` 后元音高、`v` 低；`q` 后 `i/u` 高、`a` 低。
8. `rolloverCommitsBothLetters`：`down('n')->rollover('i')->up()` 依次出 n、i。
9. `fastTapLockPreventsMicroSlideSwitch`：40ms 横移 20px 不换键；90ms 换键。
10. `verticalFlickRequiresSteepLongGesture`：60.8°/50px/100ms 触发；51°/30ms 不触发。
11. `touchSampleAtCenterHasNoNeighbors`：F 中心 alternatives 空或惩罚 >0.85。
12. `touchSampleAtGapIncludesBothKeys`：F/G 缝隙包含 G 且惩罚低。
13. `pinyinSpatialRecoveryUsesSamples`：`nihap` 用样本恢复“你好”。
14. `pinyinSoftFirstLetterRecoversBihao`：B/N 压线样本，`bihao` 首选“你好”。
15. `calibratorConvergesAndClamps`：20 次干净样本，dy 上升不超 0.15；reset 回 0.07。
16. `noLearnProducesNoPriors`：password/noLearn 的 `effectivePriors` 全零。

### 13.3 现有测试

- `KeyGesture` 旧构造可用（`verticalFlickPx` 默认 46）。
- `nearbyLetters` 旧测试继续走包装；失败时调 `TouchPenaltyCutoff`，不要恢复大半径。
- 所有旧 `EngineAct.Letter(ch,null,"")`、`suggest`、`decode` 调用因默认参数继续编译。

### 13.4 模拟器手工验证

```powershell
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable dev.cheng.keyboard/.ime.KeyboardService
adb shell ime set dev.cheng.keyboard/.ime.KeyboardService
```

验证：键缝/A/L 留白/边缘都有输入；`tim` 后 E/R 缝隙优先 e、R 中心仍 r；短按气泡/长按三格；上下滑更费力；密码框无动态热区；API 31+ 触感不崩溃。真机验证手感与校准，模拟器只验证逻辑和几何。

## 14. 性能、隐私、兼容

- `hitKey` O(约 40 键)，无数据库/磁盘。
- `touchSampleOf` 只在按下和换键时调用，最多 4 个候选。
- `nextLetterPriors` 纯内存 O(26) 或 O(6×26)。
- `TouchCalibrator` 每词样本很少，每 20 次干净上屏才写 DataStore。
- `onMove` 不调用解码、`runBlocking` 或大分配。
- 密码/noLearn 不学习、不计算先验、不记录样本；校准只存三个浮点。
- minSdk 26：Composition/`areAllPrimitivesSupported` API 31 守卫，`KEYBOARD_PRESS` API 33 守卫；`VIBRATE` 已有。
- 不依赖 `BuildConfig`，不新增权限/依赖/包名。

## 15. 实施顺序（依赖顺序，不是优先级）

1. `Types.kt` + `hitKey/touchSampleOf` + 几何单测。
2. `Latin/Pinyin` 先验 + 单测。
3. `KeyGesture` Rollover/锁键/上下滑 + 单测。
4. `InputEngine` 样本全链路 + 连续空间惩罚 + 解码单测。
5. `TouchCalibrator` + 控制器 + `UiState/KeyboardScreen` 多指与气泡。
6. `KeyboardService` 触感 + `Stores/SettingsActivity` 校准重置。
7. 全量测试、assemble、模拟器手工验证。

## 16. 验收清单

- [ ] 双拇指快速交替不吞字；rollover 单测通过。
- [ ] 面板任意点 `hitKey` 非空；A/L 外侧可用。
- [ ] `tim` 后 E 热区跨缝扩张；R 核心盒仍稳定。
- [ ] 密码/noLearn 下先验、动态扩张、校准样本全关。
- [ ] 英文/拼音使用连续空间惩罚；点键中心不误判邻键。
- [ ] `bihao` 压线样本可纠回“你好”。
- [ ] 上下滑 46dp/60°/45ms；退格/空格横滑不变。
- [ ] 70ms 快速轻敲锁键生效。
- [ ] 短按气泡 40ms 消失；长按切换三格气泡。
- [ ] API 31+ 触感有回退；API 26~30 不崩溃。
- [ ] `testDebugUnitTest` 通过。
- [ ] `assembleDebug` 通过。
- [ ] `git status` 只有预期文件；没有 commit/push。

## 17. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 动态热区让按键“会跑” | 核心盒 56%×60%；单键扩张约 5%~13%；密码/noLearn 全关；常量集中可回调。 |
| Rollover 同键双字母 | 只允许普通键/空格/退格/回车/Shift；长按、选符、空格移光标、连删期间禁用。 |
| 拼音软变体误纠 | 只处理首字母；要求压线或替代惩罚 <=0.35；按词频排序；最多 2 个变体。 |
| 模拟器无法验证真机偏移 | 静态基线 + 真机在线自校准；模拟器只验证逻辑和几何。 |
| 触感设备差异 | API 31+ 原语失败回退；外层 runCatching。 |
| 工作区已有未提交改动 | 开工 `git status`；只改清单文件；禁止 `git checkout -- .`、`git reset --hard`。 |

回滚：按文件清单逐个还原本任务内容；不要动 AI 润色相关未提交文件。
