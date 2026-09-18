package com.peerlink.app.godmode.shizuku

import android.content.Context
import com.peerlink.app.godmode.PrimeAuth
import java.io.File

object PrimeShizukuStarter {

    fun userCommand(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libpeerlinkstarter.so").absolutePath

    fun internalCommand(context: Context): String {
        val starterFile = File(context.applicationInfo.nativeLibraryDir, "libpeerlinkstarter.so")
        check(starterFile.isFile) { "Prime starter is missing from this APK" }
        return "${starterFile.absolutePath} --apk=${context.applicationInfo.sourceDir} --token=${PrimeAuth.token(context)}"
    }
}
