package com.peerlink.app.godmode

internal object PrimeForegroundParser {
    fun resumedPackages(output: String): List<String>? {
        val activities = output.lineSequence()
            .filter { it.contains("ResumedActivity", ignoreCase = true) }
            .mapNotNull { Regex("(?:^|\\s)([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").find(it)?.groupValues?.get(1) }
            .toList()
        return if (activities.isEmpty()) null else activities
    }

    fun isForeground(output: String, packageName: String): Boolean? {
        val activities = resumedPackages(output) ?: return null
        return packageName in activities
    }
}
