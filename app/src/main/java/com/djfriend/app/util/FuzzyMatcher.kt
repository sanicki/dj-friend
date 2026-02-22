package com.djfriend.app.util

object FuzzyMatcher {

    // Normalise "The Cranberries" <-> "Cranberries, The" -> "cranberries"
    // Also strips leading "the " so both forms collapse to the same string
    fun normalizeArtist(input: String): String {
        val s = input.trim()
        // "Cranberries, The" -> "The Cranberries"
        val swapped = Regex("^(.+),\\s*the$", RegexOption.IGNORE_CASE)
            .replace(s) { "The ${it.groupValues[1].trim()}" }
        // Strip leading "the " / "the" entirely so "The Cranberries" -> "cranberries"
        return swapped.replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")
            .lowercase().trim()
    }

    fun normalize(input: String): String =
        input
            .replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "") // strip (...) and [...] and their contents
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
