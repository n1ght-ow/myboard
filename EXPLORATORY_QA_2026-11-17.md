# N1ghtKey 第二轮交互探索与边界回归

日期：2026-11-17，Asia/Shanghai。基于前两轮尚未提交的工作区，保留既有修改。版本号仍为 `0.1.19 / 22`；未提交、推送或发布。

## 已接入的便捷输入

- `alice@` 提供常用邮箱域名，继续输入前缀会筛选，例如 `alice@g` 提供 `@gmail.com`。列表包括 QQ、163、126、Gmail、Outlook 等九个常用域名，为本机静态列表，不是实时热度排名。
- 明确的网址主机名提供 `.com`、`.cn`、`.net`、`.org` 后缀。
- `/date`、`/time`、`/weekday` 提供本地日期、时间、星期候选，兼容原有 `rq/sj/xq` 快捷输入。
- `12+3*4=` 等算式提供结果候选，点击后替换当前算式。

上述候选由用户点击插入，复用现有工具栏；密码、禁止学习字段和不可靠的编辑器上下文会抑制辅助建议。功能设计及前轮设备证据见 [便捷输入记录](TYPING_ASSIST_QA_2026-11-17.md)。该记录中的构建数与指纹属于前轮，当前最终验证以本文件为准。

## 本轮范围

- 手机 `Codex_MyScore_API35 / emulator-5554`：真实按键手输邮箱、域名后续输入、空格/回车、光标与编辑状态。
- 平板 `MyScore_Pad_6SPro / emulator-5556`：大字体、键盘高度极值、符号与候选面板、剪贴板敏感/清空/长文本流程。
- 主模型负责检查设计、复现判定、控制器联动与验证；子代理分别负责两台设备操作、编辑器边界、剪贴板处理和输入引擎修复。
- 设备操作使用 Mobile Dev。Windows 的面板启动仍返回 macOS 后端要求，但 Android 发现、安装、截图和操作工具可用。
- 前轮已有的普通补全点击成功场景不作为本轮新发现；本轮优先检查继续手工输入、失败返回和文档偏移。

## 设备复现并修复的问题

### 1. 中文和英文普通文本框都会改坏手工输入的邮箱

手机真实按键复现：

- 中文 `alice@qq.com` 在点号前后变成 `alice@全球.com`，按空格继续变成 `alice@全球.come`。
- 中文 `alice@company.com` 按空格变成 `alice@company.come`，没有预期空格。
- 英文 `I@custom.com`、`dont@custom.com`、`user+tag@custom.com` 的 `.com` 被自动大写或候选改写；字面提交修复后继续复现 `.Com`，定位到选区回调仍把点号当成句末。已一并关闭地址上下文内的自动大写，手动 Shift 保留。

修复：识别明确地址的 ASCII 域名上下文，以字面提交该部分的字母、点号和空格，禁用拼音候选、英文纠错、联想学习和触控先验；不切换或改写用户选定的中英文语言。离开地址后恢复普通规则。路径和查询内容不会被当成普通邮箱触发。

证据：`build/qa/2026-11-17-explore-phone/FINDINGS.md`，以及该目录内 `01` 至 `08` 的失败截图、`15-fixed-company-literal-space-pass.png`、`16-fixed-qq-literal-space-pass.png` 等回归截图。

### 2. 敏感剪贴板与清空操作会留下旧的快捷建议

平板复现：复制公开文本 A 后，再复制带 `EXTRA_IS_SENSITIVE` 的 B，或清空系统剪贴板，旧 A 的工具栏 chip 仍在，点击会粘贴旧 A。

修复：当前剪贴板不可展示或为空时，立即撤掉过期 chip 和当前粘贴缓存；用户主动打开的历史记录保留其既定行为。

证据：平板 `FINDINGS.md` 中 `QA-TAB-001`、`QA-TAB-002`，以及 `32-fixed-TAB001-sensitive-clears-chip.png`、`33-fixed-TAB002-clear-clipboard-clears-chip.png`。

### 3. 当前长剪贴板立即粘贴也被历史存储上限截断

平板复现：当前复制内容为 6,000 个 UTF-16 单元，打开历史后点击当前项，只插入 4,096 个单元。

修复：当前项的预览与粘贴使用系统剪贴板保留的完整文本。历史持久化仍保留原有 4,096 单元上限；不会因此无限扩大历史存储。剪贴板在面板展开期间改变时，同步更新当前完整项。

证据：平板 `FINDINGS.md` 中 `QA-TAB-003`，以及修复后的设备报告。

## 源码和协议测试确认的边界问题

### 4. 剪贴板截断可能切开表情或组合字符

原 `take(4096)` 会将 `"a".repeat(4095) + "😀"` 截为末尾孤立高代理项。现在截断会避开代理对，并在有界窗口内保留组合音标、variation selector、肤色 modifier 与常见 ZWJ 序列的完整边界；旧历史已经留下的孤立高代理项也会被安全去掉。

完整 grapheme 检查窗口有上限，极端超长字符簇保持合法 UTF-16 的保守行为，不承诺无限长度的字符簇完整性。

### 5. 局部 ExtractedText 的偏移可能被当成全文坐标

修复了 `FieldSource`、光标移动、组合区校验与替换/撤回的 `startOffset` 处理。反向选区会规范化；未知或越界选区、负偏移、拆开的 UTF-16 边界不会伪造可写位置。无法定位原文时提示重新选择，不会盲目把结果插到当前光标。

### 6. 补全失败后仍清除原组合状态

原 bridge 返回 `false` 后，Controller 仍统一清掉 raw 和候选。现在区分三种结果：已应用、编辑器完全未改变、已结束组合但未替换。未改变时保留原输入；选区/提交被拒绝时不插入 fallback；`endBatchEdit` 异常不会让已成功提交被误判为未成功。

### 7. 退格会拆开家庭表情和国旗

手机实际复现 `A👨‍👩‍👧‍👦B` 中一次退格留下 `A👨‍👩‍👧‍B`，国旗 `A🇨🇳B` 留下 `A🇨B`。现在单次退格在最多 256 个 UTF-16 单元的窗口内定位完整可见 grapheme，再删除完整组合；覆盖家庭 ZWJ、国旗、肤色 modifier、组合音标和 CRLF。覆盖整个满窗口、无法确认起点的极端超长字符簇会保守停止。

删除仍优先处理选区；按词删除、DeleteCount 保留其各自规则。不支持 code point API 且明确返回 false 的编辑器可回退到已验证安全的 UTF-16 长度。光标移动按 code point 计算并加绝对偏移，不进入代理对中间。

### 8. 计算器过早拒绝可恢复的微小中间值

保留有界数位、运算次数和最终展示限制，扩大受限的中间 scale，使 `1/N*N` 能恢复为 `1`。最终小到无法展示的非零值仍不提供误导的 `0` 候选。

## 最终设备回归与收尾

手机在最终 `8A39B120…9992D4` 包上用真实 N1ghtKey 软键盘完成以下检查，设备内 `base.apk` 指纹与本地 APK 相同。主模型已查看最终三张截图：

- 普通 Text、英文模式手输 `user+tag@custom.com` 后按空格，字段为 `user+tag@custom.com `，`sel=20,20 / len=20`。`.com` 保持小写，尾部空格存在；地址内手动 Shift 仍能输入大写。证据：[邮箱回归截图](build/qa/2026-11-17-explore-phone/F03-final-en-email-lower-space-2026-11-17.png)。
- `A👨‍👩‍👧‍👦B`，光标在 `B` 前，按一次退格得到 `AB`，`sel=1,1 / utf16=2 / codePoints=2`。证据：[家庭表情回归截图](build/qa/2026-11-17-explore-phone/F04-final-zwj-backspace-AB-2026-11-17.png)。
- `A🇨🇳B`，相同操作得到 `AB`，字段长度与光标同上。证据：[国旗回归截图](build/qa/2026-11-17-explore-phone/F04-final-flag-backspace-AB-2026-11-17.png)。

平板已完成三项剪贴板回归，并安装同一最终指纹的包。剪贴板回归截图在此前的剪贴板修复包上采集，后续最终包补充了邮箱自动大写和 grapheme 退格修复；未将早期截图改标为最终包截图。大字体与高度极值检查覆盖实际 `2032×3048 / 字体2.0 / 高度80%` 和 `3048×2032 / 字体2.0 / 高度125%`，主要按键及候选、符号、NeedKey 面板均可达。

两台模拟器恢复字体 `1.0`、键盘高度 `100%`，测试密钥为空，默认输入法为 N1ghtKey；临时 QA 宿主和协议探针已卸载。手机高度采用缺省配置 `1f`，未将缺少存储项误判为缺少恢复。设备详细记录：[手机报告](build/qa/2026-11-17-explore-phone/FINAL_REPORT.md)、[平板报告](build/qa/2026-11-17-explore-tablet/FINAL_REPORT.md)。

## 验证

项目现有 Gradle wrapper：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- **25 个测试类、273 项测试，0 failure、0 error**，包含 47 项本轮新增回归测试。
- 原有固定种子 `20261117` 的 **9,087 个生成场景再次通过**，不是 9,087 次设备手工操作。
- Android instrumentation：**24 条 EditorBridge 协议断言通过**，覆盖非零偏移、过期上下文、begin/finish/选区/commit 拒绝、endBatch 异常、完整 emoji/grapheme 删除和兼容回退。使用合成 InputConnection，不调用在线 AI。
- Lint：**0 error、22 warning**；调试构建成功。
- 首次回归中一个新增测试把 `ShiftState.Once` 的下一次输入错写为小写预期，修正测试预期后全量通过，没有为了让测试通过而取消大小写行为。

协议探针源码、APK 与结果：`build/qa/2026-11-17-editor-probe/`、手机目录内 `EditorProbe-final-2026-11-17.txt`。旧的无 `final` 文件保留早期 20 条断言结果，最终 24 条结果以 `final` 文件为准。

## 产物与限制

- 最终调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。
- SHA-256：`8A39B120BEC8D6255F3C6B8BE7A4D5D6CFDD53A043ACDA59E63F8DBBCE9992D4`。
- 手机证据：`build/qa/2026-11-17-explore-phone/`。
- 平板证据：`build/qa/2026-11-17-explore-tablet/`。
- 横竖屏分类以实际截图宽高为准；平板 AVD 的自然方向与方向请求名称有反转，不能只按文件名判断。
- 本轮仍是模拟器证据，没有操作连接的真实手机，没有真实 DeepSeek 网络请求或长期耗电测试。
- 先前未跟踪的 `$null` 在子代理 shell 操作中被误覆盖，已按原内容恢复为 244 字节，并与原 SHA-256 `612A9B69…517A805` 核对。保留文件，没有删除。

## 后续发布批次：0.1.20

用户随后授权提交、推送并发布新 APK，发布版本升级为 `0.1.20 / versionCode 23`，包含上述功能以及前两轮输入、隐私、界面与编辑安全修复。发布前的单元测试、Debug Lint 与 Release 构建已通过；正式安装包在提交后重新打包，签名与公开的 `0.1.19` 安装包保持一致。

本文前述 `8A39B120…9992D4` 是开发阶段 `0.1.19` 调试包的指纹。`0.1.20` 的正式发布包使用 `myboard-0.1.20.apk`，其最终指纹与下载信息以 [GitHub Release](https://github.com/n1ght-ow/myboard/releases/tag/v0.1.20) 附带的 `update.json` 为准。
