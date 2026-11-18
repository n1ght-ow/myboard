package dev.cheng.keyboard.ime.assist

private val literalUrlStart = Regex("(?i)https?://|www\\.")

/** Protect an explicitly started address without changing the user's language setting. */
fun isLiteralAddressContext(before: String): Boolean {
    if (before.isEmpty()) return false
    val url = literalUrlStart.findAll(before).lastOrNull { match ->
        match.range.first == 0 || before[match.range.first - 1].isAddressBoundary()
    }
    if (url != null) {
        val address = before.substring(url.range.last + 1)
        val suffixStart = address.indexOfAny(charArrayOf('/', '?', '#'))
        val host = if (suffixStart < 0) address else address.substring(0, suffixStart)
        if (host.length <= 253 && host.all { it.isAddressLetterOrDigit() || it in ".-:[]" } &&
            (suffixStart < 0 || host.isNotEmpty() && address.substring(suffixStart).all(Char::isUrlSuffixChar))
        ) return true
    }

    val at = before.lastIndexOf('@')
    if (at <= 0) return false
    var localStart = at
    while (localStart > 0 && before[localStart - 1].isEmailLocal()) localStart--
    val local = before.substring(localStart, at)
    if (local.isEmpty() || local.length > 64 || local.startsWith('.') || local.endsWith('.') || ".." in local) return false
    if (localStart > 0 && !before[localStart - 1].isAddressBoundary()) return false
    val domain = before.substring(at + 1)
    return domain.length <= 253 && domain.all { it.isAddressLetterOrDigit() || it == '.' || it == '-' }
}

private fun Char.isAddressLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isUrlSuffixChar(): Boolean = isAddressLetterOrDigit() || this in "-._~:/?#[]@!$&'()*+,;=%"

private fun Char.isEmailLocal(): Boolean = isAddressLetterOrDigit() || this in "._+-%"

private fun Char.isAddressBoundary(): Boolean = isWhitespace() ||
    this in "，。！？；：、（）【】《》「」『』〈〉〔〕“”‘’…—～·()<>\"" ||
    code in 0x3400..0x9FFF
