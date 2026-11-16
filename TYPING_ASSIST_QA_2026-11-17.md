# 本地便捷输入与坏例检查

日期：2026-11-17（Asia/Shanghai）。基于 `0.1.19`，保留前一轮全部未提交修复。没有新增生产依赖，没有提交或推送。

## 功能与交互

- **邮箱后缀补全**：`alice@` 显示常用邮箱域名；`alice@g` 筛到 `@gmail.com`。覆盖 `qq.com`、`163.com`、`126.com`、`gmail.com`、`outlook.com`、`hotmail.com`、`icloud.com`、`foxmail.com`、`yeah.net`。这是静态常用列表，不宣称实时热度排名。
- **网址后缀补全**：网址输入框、明确的 `http://` / `https://` / `www.` 主机名提供 `.com`、`.cn`、`.net`、`.org`。输入 `https://example.` 点击 `.com` 得到 `https://example.com`，不会留下两个点。
- **日期时间快捷输入扩展**：新增 `/date`、`/time`、`/weekday` 显式候选；普通文本和英文输入也能使用 `rq/sj/xq`。原拼音引擎已支持 `rq/riqi`、`sj/shijian`、`xq/xingqi`，继续保留，并按实际插入值去重。日期时间来自设备本地时钟。
- **行内计算**：例如 `12+3*4=` 提供 `24`，`(2+3)*4=` 提供 `20`。支持加减乘除、小数、括号和一元正负号。点击后替换整个当前算式，其他文字不动。计算使用 BigDecimal，最多显示十位小数；不会把非零微小值显示成精确 `0`。

全部功能只显示候选，由用户点击插入；不会自动替换正文，也不会改变空格选择原拼音候选的行为。候选复用现有工具栏，不增加独立的一行。

## 安全边界

- 密码、禁止个性化学习的字段、有选区时不显示辅助候选。
- 不补全聊天中的裸 `@`、重复 `@@`、URL 用户信息或查询参数；不改写完整邮箱和企业自定义域名。
- 光标处于已有域名中间时不拼接尾缀；已经完整的网址、路径、查询、IP 不显示域名后缀。
- 日期触发词必须有明确边界；URL 或文件路径中的 `rq`、`/date` 不触发。
- 计算必须以 `=` 结束，不将电话号码、三段日期、URL 查询作为算式；不跨换行替换。
- 点击前重新读取文本和绝对光标位置，比对当初的上下文；宿主不提供可靠位置、文本已变或编辑器拒绝替换时，保守放弃。
- Android 12 及以上优先读取有范围限制的 SurroundingText，旧系统使用 ExtractedText，并正确处理窗口偏移。
- ASCII `@` 会先按字面提交未上屏用户名，避免拼音转换、英文大小写特殊规则或缩写纠正改坏邮箱用户名。
- 辅助建议均在本机计算，不把邮箱、网址或算式发送到网络，不新增输入记录或学习持久化。

## 实现分工

- 子代理：邮箱/网址/日期规则与边界测试；BigDecimal 计算器及批量坏例生成；候选栏界面；手机和平板 Mobile Dev 实际操作；`@` 提交状态修复。
- 主模型：功能取舍、接口和交互设计、编辑器与控制器接入、上下文/光标校验、源代码审查、坏例判定及统一构建验证。

## 本轮发现并修复

1. 普通文本输入 `@` 前可能经过拼音、英文缩写或大小写转换，现按字面保留用户名。
2. 日期候选可能出现在 URL/文件路径内，已收紧触发边界。
3. 完整网址尾点可能继续追加第二个后缀，现保守抑制。
4. 日期过滤可能误伤 `11-17=`、`2026/2=` 等两段运算，已保留这些明确的计算输入。
5. 算式扫描可能跨行，现将 CR/LF 作为硬边界。
6. 极小非零结果经十位小数舍入可能显示 `0`，现不提供这种误导结果。
7. 新日期候选与原拼音快捷候选重复，现按插入值去重，保留原首选拼音。
8. 模拟器实际发现：系统默认已是 N1ghtKey，设置页仍显示“待切换”。Android 14 及以上改用公开的 `InputMethodManager.currentInputMethodInfo` 查询当前输入法，旧系统保留原回退路径。

## 自动验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- **20 个测试类，226 项测试，0 failure / 0 error**。覆盖原有拼音/英文恢复、触控、剪贴板与前轮编辑安全测试。
- 批量坏例固定种子 `20261117`，**9,087 个场景，0 个未解决失败**。包含 5,406 个返回空建议的场景，所有场景累计检查 4,969 条建议；场景数与建议条数是不同统计量。
- Lint：**0 error、22 warning**，未引入新的依赖或资源升级工作。
- 上述全量测试对应完整输入功能；设备收尾发现的设置状态查询修复另执行 `:app:assembleDebug :app:lintDebug` 并进行默认输入法状态实测。
- 首轮批量测试有一项测试假设被修正：中文上下文后的 ASCII 数字可以是合法邮箱用户名，不能将 `联系12345@` 统一判为无效。修正后保留数字邮箱补全，并继续拒绝真正没有 ASCII localpart 的坏例。

主要新增测试：

- `TypingAssistTest.kt`：邮箱、网址、日期与算式的正反例和替换范围。
- `InlineCalculatorTest.kt`：优先级、十进制精度、除零、无效输入和资源上限。
- `TypingAssistAddressEngineTest.kt`：`@` 两个入口、中文/英文、partial、隐私与原标点回归。
- `TypingAssistEditingTest.kt`：绝对偏移、文本窗口、过期候选、选区和非法替换范围。
- `TypingAssistBadCaseMiningTest.kt`：域名前缀遍历、随机邮箱、敏感字段、Unicode、独立整数参考结果和批量范围校验。

## 设备证据与产物

- Mobile Dev 手机：`Codex_MyScore_API35 / emulator-5554`。
- Mobile Dev 平板：`MyScore_Pad_6SPro / emulator-5556`。
- 临时原生测试宿主、截图和设备报告目录：`build/qa/2026-11-17-assist/`。
- 设备报告：`build/qa/2026-11-17-assist/DEVICE_REPORT.md`。
- 实测通过邮箱后缀及精确替换、实际按键输入 `qsr@`、网址后缀点击、计算结果点击、原拼音空格提交、显式日期快捷输入、选区/中间光标抑制、密码及 noLearn 屏蔽。
- 手机 1.5 倍字体横屏与平板 1.8 倍字体横屏已检查，键区和候选显示完整。
- 最终状态修复包已覆盖安装到两台模拟器，`device_is` 均确认“正在使用”和“已选中”；证据为 `final-settings-ime-status-fixed.png`。
- 临时测试宿主已从两台模拟器卸载，字体恢复 1.0，保留 N1ghtKey 为默认输入法。
- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 本轮最终构建 SHA-256：`DAEA5816A9470A4DF454F24B5DA0504CEDC4830979F719C4DA79BAD5FA1BF5A5`。
- 输入功能全量测试构建 SHA-256：`3119D184F281854CBE35BEA75FCA8F4246D85FEAD1FE751A1508B5D4F5CBEB8D`。最终构建追加的变更仅为设置页当前输入法状态查询。

## 参考与限制

功能取舍参考了 [HeliBoard 的开源功能说明](https://github.com/HeliBorg/HeliBoard#features)，结合项目已经具备的剪贴板、数字页、成对符号和日期快捷候选，优先补齐上下文辅助能力。编辑器偏移处理核对了 [AOSP ExtractedText 定义](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/inputmethod/ExtractedText.java)。实现为本项目独立代码，没有引入其他键盘的运行依赖。

本轮设备证据来自模拟器，不代表 HyperOS 真机触控、长期耗电或所有第三方编辑器。无法提供可靠文本及光标位置的编辑器会跳过补全。没有使用真实密钥，没有调用在线 AI，也没有操作连接的真实手机。
