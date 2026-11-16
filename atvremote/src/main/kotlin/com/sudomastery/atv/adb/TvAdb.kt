package com.sudomastery.atv.adb

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class TvMemory(val totalKb: Long, val availableKb: Long)

/** What a clean-up did: the apps it closed and free memory before and after. */
data class CleanupResult(val closed: List<String>, val before: TvMemory?, val after: TvMemory?) {
    val freedKb: Long get() = if (before != null && after != null) after.availableKb - before.availableKb else 0
}

/**
 * App management over network ADB (port 5555), for things the remote protocol can't do:
 * closing apps and freeing memory. Needs "Network debugging" enabled on the TV, and the TV asks
 * once to allow this device ("Always allow").
 *
 * [keyDir] holds this device's ADB key pair, created on first use.
 */
class TvAdb(private val host: String, private val keyDir: File, private val port: Int = 5555) {

    suspend fun memory(): TvMemory? = run { it.readMemory() }

    /** Force-stops the app on screen, which returns the TV to its launcher. */
    suspend fun closeCurrent(): CleanupResult = run { dadb ->
        val state = dadb.readState()
        val target = state.foreground?.takeIf { it.closeable(state) }
        dadb.cleanup(listOfNotNull(target), killBackground = false)
    }

    /**
     * Closes every running app except the one on screen, then drops cached background processes.
     * The field naming the foreground activity differs by Android version (mResumedActivity on
     * 11, topResumedActivity on newer ones), so both are accepted.
     */
    suspend fun closeOthers(): CleanupResult = run { dadb ->
        val state = dadb.readState()
        // Without knowing what's on screen, "others" would include it. Refuse instead.
        val foreground = state.foreground ?: throw IllegalStateException("Couldn't tell which app is on screen")
        dadb.cleanup(state.runningApps().filter { it != foreground }, killBackground = true)
    }

    /** Closes every running app, including the one on screen. */
    suspend fun closeAll(): CleanupResult = run { dadb ->
        val state = dadb.readState()
        dadb.cleanup(state.runningApps(), killBackground = true)
    }

    suspend fun reboot() {
        run { it.shell("reboot") }
    }

    private suspend fun <T> run(block: (Dadb) -> T): T = withContext(Dispatchers.IO) {
        // The socket timeout also bounds how long we wait for the user to accept the
        // "Allow debugging?" prompt on the TV the first time.
        Dadb.create(host, port, keyPair(), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS).use(block)
    }

    private fun keyPair(): AdbKeyPair {
        keyDir.mkdirs()
        val private = File(keyDir, "adbkey")
        val public = File(keyDir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }

    private class State(
        val foreground: String?,
        val home: String?,
        val keyboard: String?,
        val launchable: Set<String>,
        val running: Set<String>,
    )

    /** User-facing apps that are running and safe to stop: never the launcher, keyboard or system pieces. */
    private fun State.runningApps(): List<String> = launchable.filter { it in running && it.closeable(this) }

    private fun Dadb.readState(): State {
        val out = shell(
            """
            echo "FG=$(dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity:')"
            echo "HOME=$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)"
            echo "IME=$(settings get secure default_input_method)"
            cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep / | sed 's/^ */APP=/'
            cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep / | sed 's/^ */APP=/'
            ps -A -o NAME | sed 's/^/PS=/'
            """.trimIndent(),
        ).allOutput.lines().map { it.trim() }

        fun value(prefix: String) = out.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        return State(
            foreground = value("FG=")?.let { Regex("""u\d+ ([\w.]+)/""").find(it)?.groupValues?.get(1) },
            home = value("HOME=")?.substringBefore('/')?.takeIf { '.' in it },
            keyboard = value("IME=")?.substringBefore('/')?.takeIf { '.' in it },
            launchable = out.filter { it.startsWith("APP=") }.map { it.removePrefix("APP=").substringBefore('/') }.toSet(),
            // "com.foo:service" is a process of package com.foo
            running = out.filter { it.startsWith("PS=") }.map { it.removePrefix("PS=").substringBefore(':') }.toSet(),
        )
    }

    private fun String.closeable(state: State): Boolean =
        this != state.home && this != state.keyboard && PROTECTED_PREFIXES.none { startsWith(it) } && PACKAGE.matches(this)

    private fun Dadb.cleanup(packages: List<String>, killBackground: Boolean): CleanupResult {
        val before = readMemory()
        if (packages.isNotEmpty() || killBackground) {
            val script = buildString {
                packages.forEach { append("am force-stop ").append(it).append("; ") }
                if (killBackground) append("am kill-all; ")
                append("sleep 1")
            }
            shell(script)
        }
        return CleanupResult(packages, before, readMemory())
    }

    private fun Dadb.readMemory(): TvMemory? {
        val out = shell("cat /proc/meminfo").allOutput
        fun kb(name: String) = Regex("""$name:\s+(\d+)""").find(out)?.groupValues?.get(1)?.toLongOrNull()
        return TvMemory(kb("MemTotal") ?: return null, kb("MemAvailable") ?: return null)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val SOCKET_TIMEOUT_MS = 30_000

        /** Vendor and platform components: stopping these can break TV input, settings or the remote. */
        val PROTECTED_PREFIXES = listOf("com.tcl.", "com.android.", "com.google.android.", "android", "com.mediatek.", "com.mstar.")

        /** Package names go straight into a shell command, so accept nothing else. */
        val PACKAGE = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
    }
}
