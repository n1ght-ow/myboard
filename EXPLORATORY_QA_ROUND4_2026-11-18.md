# N1ghtKey 第四轮模拟器探索

日期：2026-11-18（Asia/Shanghai）。基线：`0.1.21 / versionCode 24`，提交 `e217a7f`。修复安装在两台模拟器，版本号不变；未提交、推送或发布。

## 确认并修复

- **编辑英文时误删正文（手机）。** 英文键盘输入 `hello` 并按空格后，将光标移到 `hel|lo `，再按逗号。原结果是 `he, lo `，删除了一个 `l`；最终包得到 `hel,lo `。自动空格现在绑定已提交词与绝对光标位置，不可靠或已移动时不再执行删除。光标未移时的连续两次空格仍产生 `hello. `，此对照在首个修复包实测。
- **网址路径被拼音改写（平板）。** 普通文本框中从 `https://example.com/path` 续打 `du`，再按空格，原结果是 `https://example.com/path度`，最终包保留 `https://example.com/pathdu `。路径、查询和片段沿用字面输入；空格退出后可继续输入中文。查询和片段另由 JVM 测试覆盖。
- **专用邮箱栏切到中文后域名被改写（平板）。** 设置 `alice@`，切到中文，输入 `qq` 后按空格，得到 `alice@全球`。最终包相同模式下输入 `qq.com` 和空格，得到 `alice@qq.com `。显式地址上下文不再排除专用字段，邮箱/网址栏的显式候选选择也不再训练词库。
- **组合区替换没有尊重宿主拒绝（合成 InputConnection）。** 拒绝设置选区时旧代码仍提交候选；拒绝提交时仍按已替换长度移动光标；读取文本抛异常会中断操作。现在仅成功替换才重算光标。独立探针旧版三项失败、修复版四项通过；最终包在真实软键盘上另确认正常路径 `@ni` 选中全文后得到 `@你`，保留映射后的选区 `0,2`。

## 验证和边界

- Mobile Dev 用 Android 控制工具操控 `Codex_MyScore_API35`（`emulator-5554`）和 `MyScore_Pad_6SPro`（`emulator-5556`），使用 `testIme:false` 和已有本地合成表单。Windows 面板后端仍报 Apple Silicon/Xcode 要求；不影响本轮实际 Android 控制。未操作连接的真机或调用在线 AI。
- 额外检查带符号小数 `-3.14`、选区出现后隐藏计算建议、PIN 禁用 AI 且不显示剪贴板内容、普通字段剪贴板面板可显示合成内容。
- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --continue` 通过：29 个类、289 项单测、0 失败；包含固定种子 `20261117` 的 9,087 个自动生成场景。Lint 报告 0 error、22 warning。
- 最终调试包：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `F9C0BF99877DC1A3E740D18BB73065F21F004A315CD903A3CF0A5F88C57DDF8A`。截图在 `build/qa/2026-11-18-round4/`。
- 未合入完整音节的用户词前缀补全：生产词库探针显示 `ceshi` 不补“测试词”，而 `ceshic`、`ceshici` 可见；改变这个行为会新增候选并影响热路径。本轮也未改变普通提交/剪贴板被宿主拒绝时的整体失败反馈；那条路径目前只有合成证据。
