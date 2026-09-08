package com.peerlink.app.godmode

internal object PrimeForegroundParser {
    fun isForeground(output: String, packageName: String): Boolean? {
        val activities = output.lineSequence()
            .filter { it.contains("ResumedActivity", ignoreCase = true) }
            .mapNotNull { Regex("(?:^|\\s)([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").find(it)?.groupValues?.get(1) }
            .toList()
        // Missing OEM fields / errors are unknown, never evidence of a forfeit.
        return if (activities.isEmpty()) null else packageName in activities
    }
}
