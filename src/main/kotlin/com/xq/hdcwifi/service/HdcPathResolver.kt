package com.xq.hdcwifi.service

import com.xq.hdcwifi.settings.HdcSettings
import java.io.File

/**
 * Resolves the hdc binary. Priority: user-configured path -> SDK env vars ->
 * known SDK locations (DevEco Studio / OpenHarmony / command-line tools / ~/sdk) -> PATH.
 */
object HdcPathResolver {

    data class Candidate(val path: String, val source: String)

    fun isExecutableFile(path: String): Boolean {
        val file = File(path)
        return file.isFile && file.canExecute()
    }

    /** Effective path: configured path if valid, otherwise first detected candidate. */
    fun resolve(): String? {
        val configured = HdcSettings.instance.hdcPath
        if (configured.isNotBlank() && isExecutableFile(configured)) {
            return configured
        }
        return detect().firstOrNull()?.path
    }

    fun resolveOrThrow(): String {
        return resolve() ?: throw HdcToolNotFoundException(
            "HDC tool not found. Install the HarmonyOS/OpenHarmony toolchain, " +
                "or set the hdc path in Settings > Tools > HDC Wi-Fi."
        )
    }

    /** All existing, executable hdc candidates, best match first. */
    fun detect(): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()

        fun accept(path: String, source: String) {
            if (isExecutableFile(path)) {
                found.putIfAbsent(File(path).absolutePath, Candidate(File(path).absolutePath, source))
            }
        }

        val configured = HdcSettings.instance.hdcPath
        if (configured.isNotBlank()) {
            accept(configured, "Settings")
        }

        for (envName in listOf("OHOS_BASE_SDK_HOME", "DEVECO_SDK_HOME", "HOS_SDK_HOME")) {
            val base = System.getenv(envName)
            if (!base.isNullOrEmpty()) {
                scanSdkBase(base, "env:$envName", ::accept)
            }
        }

        val userHome = System.getProperty("user.home")
        val knownBases = listOf(
            "$userHome/Library/OpenHarmony/Sdk",
            "$userHome/Library/Huawei/Sdk",
            "$userHome/command-line-tools/sdk",
            "/Applications/DevEco-Studio.app/Contents/sdk",
            "$userHome/sdk"
        )
        for (base in knownBases) {
            if (File(base).isDirectory) {
                scanSdkBase(base, "scan", ::accept)
            }
        }

        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) {
                accept("$dir${File.separator}hdc", "PATH")
            }
        }

        return found.values.toList()
    }

    private fun scanSdkBase(base: String, source: String, accept: (String, String) -> Unit) {
        val root = File(base)
        val direct = listOf(
            "$base/toolchains/hdc",
            "$base/default/openharmony/toolchains/hdc",
            "$base/default/hms/toolchains/hdc"
        )
        direct.forEach { accept(it, source) }

        // SDK bases keep per-version or per-platform subdirectories; prefer higher versions.
        val subDirs = root.listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.name } ?: emptyList()
        for (sub in subDirs) {
            accept("${sub.path}/toolchains/hdc", source)
            accept("${sub.path}/openharmony/toolchains/hdc", source)
            accept("${sub.path}/default/openharmony/toolchains/hdc", source)
            val subSubDirs = sub.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.name } ?: emptyList()
            for (sub2 in subSubDirs) {
                accept("${sub2.path}/toolchains/hdc", source)
                accept("${sub2.path}/openharmony/toolchains/hdc", source)
            }
        }
    }
}

class HdcToolNotFoundException(message: String) : RuntimeException(message)
