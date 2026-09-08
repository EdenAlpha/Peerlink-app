package com.peerlink.app.service

internal object FinalScoreEvidence {
    fun isNonFinal(text: String): Boolean = Regex(
        "\\b(half[\\s-]*time|kick[\\s-]*off|resume match)\\b", RegexOption.IGNORE_CASE
    ).containsMatchIn(text)

    fun isFinal(text: String): Boolean = Regex(
        "\\b(full[\\s-]*time|match ended|final result)\\b", RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
}
