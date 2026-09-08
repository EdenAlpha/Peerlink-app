package com.peerlink.app.godmode

internal object PrimePerformanceModeParser {
    fun currentMode(output: String): Int? {
        val mode = Regex("(?:current|selected)(?: game)? mode\\s*:\\s*([a-z0-9]+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)?.lowercase() ?: return null
        return when (mode) { "standard", "1" -> 1; "performance", "2" -> 2; "battery", "3" -> 3; "custom", "4" -> 4; else -> null }
    }

    fun hasPerformanceMode(output: String): Boolean {
        // AOSP prints numeric modes: "available game modes: [1, 2, 3]".
        // The current mode alone, or a help/error mentioning performance, is not support.
        val available = Regex("available(?: game)? modes\\s*:\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1) ?: return false
        return available.split(',').any {
            it.trim().equals("performance", true) || it.trim() == "2"
        }
    }
}
