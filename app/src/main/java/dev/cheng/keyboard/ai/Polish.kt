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

private val WorkPackages = setOf(
    "com.tencent.wework",
    "com.ss.android.lark",
    "com.bytedance.ee.lark",
    "com.alibaba.android.rimet",
    "com.microsoft.teams",
    "com.google.android.apps.meetings",
)

private val ImPackages = setOf(
    "com.tencent.mm",
    "com.tencent.mobileqq",
    "org.telegram.messenger",
    "com.whatsapp",
    "jp.naver.line.android",
)

private val MailDocPackages = setOf(
    "com.tencent.androidqqmail",
    "com.netease.mobimail",
    "com.google.android.gm",
    "com.microsoft.office.outlook",
    "cn.wps.moffice_eng",
    "com.youdao.note",
)

private val SocialPackages = setOf(
    "com.xingin.xhs",
    "com.sina.weibo",
    "com.ss.android.ugc.aweme",
    "tv.danmaku.bili",
    "com.zhihu.android",
    "com.coolapk.market",
)

fun appCategoryOf(packageName: String?): HostCategory {
    val pkg = packageName?.trim().orEmpty()
    if (pkg.isEmpty()) return HostCategory.General
    return when {
        pkg in MailDocPackages -> HostCategory.MailDoc
        pkg in WorkPackages || pkg.startsWith("com.slack", ignoreCase = true) -> HostCategory.Work
        pkg in ImPackages -> HostCategory.Im
        pkg in SocialPackages -> HostCategory.Social
        else -> HostCategory.General
    }
}

fun hostSceneOf(packageName: String?, hint: String?, enter: EnterKind): HostScene {
    val category = appCategoryOf(packageName)
    return HostScene(
        category = category,
        hint = hint.orEmpty().trim().take(60),
        chatBubble = enter == EnterKind.Send || category == HostCategory.Im,
    )
}

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

fun buildPolishUserPrompt(
    original: String,
    contextBefore: String,
    contextAfter: String,
    scene: HostScene,
    tone: PolishTone,
): String = buildString {
    val form = if (scene.chatBubble) {
        "即时聊天气泡（短句，禁止书信格式）"
    } else {
        "普通文本（可多行）"
    }
    val cleanHint = scene.hint.trim()
    append("【指定方向】").append(tone.promptLabel).append('\n')
    append("【输入形态】").append(form).append("；应用类型：").append(scene.category.label)
    if (cleanHint.isNotEmpty()) {
        append("；输入框提示：").append(cleanHint)
    }
    append('\n')
    val before = contextBefore.trim()
    if (before.isNotEmpty()) {
        append("【前文参考（仅用于判断对象，勿润色、勿重复）】\n")
        append(before).append('\n')
    }
    val after = contextAfter.trim()
    if (after.isNotEmpty()) {
        append("【后文参考（勿润色、勿重复）】\n")
        append(after).append('\n')
    }
    append("【待润色原文】\n")
    append(original)
}

private val PolishTag = Regex("^【([^】]{1,24})】\\s*(.*)$")

fun parsePolishStream(raw: String): List<PolishCandidate> {
    if (raw.isBlank()) return emptyList()
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = ArrayList<PolishCandidate>()
    val body = StringBuilder()
    val fallback = StringBuilder()
    var tag: String? = null
    var sawTag = false

    fun clean(value: String): String = stripWrapped(stripWrapped(value)).trim()

    fun flush() {
        val text = clean(body.toString())
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
            tag = match.groupValues[1].trim().ifEmpty { "智能润色" }
            sawTag = true
            val rest = match.groupValues[2]
            if (rest.isNotEmpty()) body.append(rest).append('\n')
            continue
        }
        if (!sawTag) {
            fallback.append(rawLine).append('\n')
            continue
        }
        body.append(rawLine).append('\n')
    }
    if (!sawTag) {
        val whole = clean(fallback.toString())
        return if (whole.isEmpty()) emptyList() else listOf(PolishCandidate("智能润色", whole))
    }
    if (body.isNotEmpty()) flush()
    return out
}
