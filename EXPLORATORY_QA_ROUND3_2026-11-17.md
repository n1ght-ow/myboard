# N1ghtKey 第三轮交互探索与修复

日期：2026-11-17（Asia/Shanghai）。基线为已发布的 `0.1.20 / versionCode 23`，源码提交 `a13b705f6134f01bd17ad825f087e77814107225`。本轮不更改版本号，不提交、推送或发布，修改保留在工作区。

## 分工与方法

- 主模型负责检查范围、控制器调用链、修复取舍、代码审阅、统一构建及证据核对。
- 手机子代理通过 Mobile Dev 操作真实 N1ghtKey 软键盘，检查选区、组合输入、字段配置及恢复流程。
- 平板子代理检查剪贴板与后续输入、符号/候选/未配置密钥面板等交互。
- 编码子代理分别处理粘贴引擎与 EditorBridge，不交叉修改控制器；Gradle 由主模型串行执行。
- 所有测试使用合成文本，不操作连接的真实手机，不配置真实密钥、不请求在线 AI。

Mobile Dev 面板在 Windows 仍返回 Apple Silicon/Xcode 后端错误；Android 发现、启动、安装与 `device_*` 控制工具正常。本轮使用这些工具，`testIme:false`，不替换实际输入法。

## 设备确认的问题

### R3-01：粘贴共用智能标点，改动原文或光标

在公开的 `0.1.20` 包上，平板先把正文设为 `1。`，复制 `2` 后点击 N1ghtKey 剪贴板项，得到 `1.2`。智能数字标点规则改动了复制内容之前的正文。空框粘贴 `()` 后，实际光标进入括号中间；继续退格、输入 `a` 得到 `a)`，不是在粘贴内容末尾编辑。

合成 Android Controller 探针独立复现了引号被智能闭合、地址中粘贴的 `。` 被改成 `.`、选区粘贴括号后光标落在中间等同源问题。基线探针共 8 条断言，1 条通过、7 条失败；其中另两条失败属于下面的 noLearn 问题。

修复增加独立 `EngineAct.Paste`：复制内容保持原值，不触发删除前文、英文自动空格、地址标点归一化或成对符号光标回退。已有组合内容沿用原先确认规则，隐私及地址上下文按字面确认 raw。粘贴后的候选和组合状态清理，用户语言、页面与 Shift/Caps 偏好保留。普通按键的智能标点继续使用原路径。

基线证据：`build/qa/2026-11-17-round3/tablet/02-paste-probe1-clip2-actual-1.2.png`、`05-paste-probe2-after-delete-and-a-actual-a-paren.png`；协议结果：`phone/ControllerProbe-baseline.txt`。

注意：旧测试宿主的状态文字没有在纯光标回调后立即刷新，因此括号截图中的旧 `sel=2` 不是实际光标的可靠证据。可见光标、后续真实退格结果及独立协议探针共同确认该问题。

### R3-02：禁止个性化学习字段仍会改坏手输邮箱

手机在普通 `Private` 字段确认 `imeOptions=0x11000004`，包含 `IME_FLAG_NO_PERSONALIZED_LEARNING`。预置 `alice@` 后用中文键盘真实输入域名和空格，得到 `alice@全球。com`，预期是 `alice@qq.com `。

旧控制器把 noLearn 字段排除在字面地址保护之外。现在此类字段也能保留用户明确输入的邮箱，辅助邮箱建议仍隐藏，个性化学习和触控校准学习仍禁用。

普通词典候选在 noLearn 字段出现本身是允许的；本次问题是已经明确输入的邮箱域名被继续当作拼音。证据见手机 `FINDINGS.md` 与 `screenshots/private-noLearn-final.png`。

## 源码和合成协议覆盖的编辑器边界

### R3-03：宿主拒绝操作后仍继续修改或错误判断成功

- 智能标点先删除旧句号，若 `deleteSurroundingText` 返回 false，旧代码仍提交改写后的 `.14`，会使 `3。` 变成 `3。.14`。现在删除被拒绝时提交原输入 `14`，得到 `3。14`；删除抛异常则停止该次后续编辑。
- 成对符号 `commitText` 失败后，旧代码仍执行光标回退。现在只有成功提交后才回退。
- 光标位置已正确算出，但 `setSelection` 被宿主拒绝时，旧代码直接返回而不移动。现在使用方向键兼容路径。
- 读取原文、删除前文和选区等局部调用增加异常隔离；不声称这替代了所有第三方编辑器兼容测试。

未知选区读取的原有退格规则保持不变。审阅时撤回了“未知选区一律停止删除”的尝试，以免不提供 ExtractedText 的宿主不能退格；合成协议另覆盖此兼容路径。

### R3-04：长选区正文和可写范围不一致

选区超过 4,000 UTF-16 单元时，旧 `FieldSource` 只保留前 4,000 个单元，但绝对替换范围仍覆盖整个选区。Controller 的原文比较会拒绝这个不匹配目标，形成不可替换的伪目标。现在不产生该可写范围，交给已有安全回退；没有可靠位置的内容保持不可定位，不盲目替换。

## 自动验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- 27 个测试类、282 项测试，0 failure、0 error；本轮增加 9 项测试方法，覆盖多组剪贴板值与编辑边界。
- 固定种子 `20261117` 的 9,087 个生成场景再次通过，0 个未解决失败。这是自动生成场景数，不是手工设备操作次数。
- Lint：0 error、22 warning。Debug APK 构建成功。
- 调试修复包 SHA-256：`4488EB75CE7CDE6CCCE85BA062126126356484ABAA55A85D9349AA081EAF0B6F`。
- 合成 Android 协议共 51 条断言通过：Controller 8 条、本轮 EditorBridge 19 条、原有 EditorBridge 24 条。Controller 从基线 1 通过/7 失败变为 8 通过/0 失败。

精确协议结果：`phone/ControllerProbe-final.txt`、`phone/EditorRound3Probe-final.txt`、`phone/EditorProbe-previous24-final.txt`。这些断言使用合成 InputConnection；真实软键盘复测单独记录在设备报告中。

## 最终真实键盘回归

主模型查看了以下最终截图，并与字段状态核对：

- 手机 Private/noLearn：`alice@qq.com `，`sel=13,13 / len=13`，保留尾部空格，辅助邮箱建议仍隐藏。截图：`phone/screenshots/final-noLearn-alice-qq-com-space-pass.png`。
- 平板 `1。` 后粘贴 `2`：结果 `1。2`，`sel=3,3 / len=3`，前文标点保留。截图：`tablet/regression-01-fixed-paste-1-chinese-period-clip2.png`。
- 平板空框粘贴 `()` 后再用真实键盘输入 `a`：结果 `()a`，`sel=3,3 / len=3`，验证粘贴结束时光标在末尾。截图：`tablet/regression-02-fixed-paren-paste-then-a-is-paren-paren-a.png`。
- 英文候选上屏后已有 `hello `，再粘贴 `,`：结果 `hello ,`，`sel=7,7 / len=7`，不移除原空格、不额外追加空格。截图：`tablet/regression-03-fixed-hello-space-paste-comma.png`。

手机另检查了组合光标左右移动、正反向选区替换、全选粘贴、按词删除、长按退格、英文 Shift/Caps 和中文分词 Shift。平板检查了符号锁定与最近项、类别插入、剪贴板展开后换字段、旋转恢复与后续粘贴、未配置密钥的面板入口。精确范围见两台设备的 `FINDINGS.md`、`FINAL_REPORT.md`，未执行的后台恢复和全部第三方宿主场景不计入通过清单。

两台模拟器均保留修复调试包，恢复字体 `1.0`、键盘高度 `100%` 和默认输入法 N1ghtKey。临时 QA 宿主已卸载，手机的三套协议探针已卸载，产品数据未清除。手机无 height 偏好时按默认 `1f` 判断为 100%，不伪称读取到存储项。密钥配置未变更，未使用真实密钥。

平板曾观察到通过外部 `ime set` 切换后，设置页标签短暂未同步。未稳定复现用户正常切换流程，暂记为未确认观察，未列为本轮修复项。

## 产物与范围

- 调试修复包：`app/build/outputs/apk/debug/app-debug.apk`。
- 原公开 Release 包保持不变：`build/release/v0.1.20/myboard-0.1.20.apk`，指纹 `605D729F…3CD10BE`。
- 本轮手机、平板、协议探针和构建记录：`build/qa/2026-11-17-round3/`。
- [手机最终报告](build/qa/2026-11-17-round3/phone/FINAL_REPORT.md)、[平板最终报告](build/qa/2026-11-17-round3/tablet/FINAL_REPORT.md)。
- 没有新增生产依赖，没有操作真实手机，没有在线 AI、长期耗电或真实游戏输入框证据。合成 InputConnection 协议检查与真实软键盘操作分别记录。

## 后续发布批次：0.1.21

用户随后授权提交、推送并发布上述修复，版本升级为 `0.1.21 / versionCode 24`。本文件前面的 `0.1.20` 版本号及 `4488EB75…0B6F` 指纹属于探索阶段调试修复包；正式发布使用 `myboard-0.1.21.apk`，最终指纹以 [GitHub Release](https://github.com/n1ght-ow/myboard/releases/tag/v0.1.21) 附带的 `update.json` 为准。
