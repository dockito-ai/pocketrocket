package com.pocketrocket.dockito

/**
 * What `dockito login --no-browser` prints before it waits: the sign-in link and the code to check
 * on that page. Pure, so it is unit-tested on sample output.
 */
object DockitoLoginOutput {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val LINK = Regex("""https://\S+""")
    /** "code ABCD-1234", "Code: ABCD-1234", or a bare XXXX-XXXX on its own. */
    private val CODE_AFTER_WORD = Regex("""(?i)\bcode\b[^A-Za-z0-9]*([A-Z0-9]{3,}(?:-[A-Z0-9]{3,})+)""")
    private val BARE_CODE = Regex("""\b([A-Z0-9]{4}-[A-Z0-9]{4})\b""")

    fun clean(line: String) = line.replace(ANSI, "").trim()

    /** The sign-in link in [line], if any, without trailing punctuation. */
    fun link(line: String): String? = LINK.find(clean(line))?.value?.trimEnd('.', ',', ')', '>', '"', '\'')

    /** The code to check in [line], if any. A code inside the link (`?code=`) counts too. */
    fun code(line: String): String? {
        val t = clean(line)
        CODE_AFTER_WORD.find(t)?.let { return it.groupValues[1] }
        val withoutLinks = t.replace(LINK, " ")
        return BARE_CODE.find(withoutLinks)?.groupValues?.get(1)
            ?: LINK.find(t)?.let { Regex("""[?&](?:user_)?code=([A-Za-z0-9-]+)""").find(it.value)?.groupValues?.get(1) }
    }
}
