package com.example.yt3chardirect

object HandleScoring {
    private val common = setOf(
        "aaa","abc","app","api","art","ask","bot","box","boy","car","cat","com","dev","dog","fun",
        "god","hub","lol","man","max","new","one","pro","sky","sun","the","top","usa","vip","web",
        "win","www","xyz","you","qwe","asd","zxc","777","888","999","123","321","000","111","222",
        "333","444","555","666"
    )

    fun validate(handle: String): String? {
        if (handle.codePointCount(0, handle.length) != 3) return "не 3 символа"
        if (!Regex("^[A-Za-z0-9._\\-·]+$").matches(handle)) return "недопустимые символы"
        if (Regex("^[._\\-·]|[._\\-·]$").containsMatchIn(handle)) return "разделитель на краю"
        return null
    }

    fun score(raw: String): Int {
        val h = raw.lowercase()
        var s = 50
        val chars = h.toCharArray()
        val letters = h.count { it in 'a'..'z' }
        val digits = h.count { it.isDigit() }
        val separators = h.count { it == '.' || it == '_' || it == '-' || it == '·' }

        if (letters > 0 && digits > 0) s += 22
        if (letters == 3) s -= 7
        if (digits == 3) s -= 20
        if (separators == 1) s += 8
        if (separators > 1) s -= 7
        if (chars.toSet().size == 1) s -= 25
        if (chars.size == 3 && chars[0] == chars[2]) s -= 10
        if (chars.size == 3 && (chars[0] == chars[1] || chars[1] == chars[2])) s -= 7
        if (h in common) s -= 20
        if (Regex("[qxzvj]").containsMatchIn(h) && digits > 0) s += 10
        if (Regex("[qxz]").containsMatchIn(h) && digits > 0) s += 4
        return s.coerceIn(0, 100)
    }
}
