package com.kilombino.kilowallet.desktop

import androidx.compose.runtime.mutableStateOf

/**
 * Keeps the computer awake while this wallet's Lightning node holds a Roblosats payment (a bond or
 * an escrow locked as a hold invoice: an HTLC out until the trade ends). Asleep, the node can't
 * settle or cancel it in time and the channel may end up closed on chain.
 *
 * - Linux: systemd-inhibit (logind) blocks sleep, idle sleep and the lid switch.
 * - Windows: SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED), held by a hidden
 *   PowerShell (no native library in the app for one call).
 * - macOS: caffeinate (an IOPMAssertion that prevents idle and system sleep).
 *
 * The helper ends by itself when this app's process does (even killed), so a crash never leaves
 * the computer unable to sleep; it is taken again when the app starts with a payment still held.
 * Released as soon as no payment is held.
 *
 * It also notices when the computer woke up anyway (a lid closed on battery, a forced sleep): the
 * wall clock jumped ahead while the app's loop was not running.
 */
object SleepGuard {
    /** True while the inhibitor is held (the close guard says so). */
    val holding = mutableStateOf(false)
    /** Why it could not be held here (no systemd-inhibit…), for the close guard; null when fine. */
    val unavailable = mutableStateOf<String?>(null)

    @Volatile private var proc: Process? = null

    /** The helper that holds the inhibitor for [pid] on [os]; null when this system has none. */
    fun command(os: Platform.Os, pid: Long, why: String): List<String>? = when (os) {
        Platform.Os.LINUX -> Platform.onPath("systemd-inhibit")?.let {
            listOf(it.path, "--what=sleep:idle:handle-lid-switch", "--who=Kilowallet", "--why=$why", "--mode=block",
                "tail", "--pid=$pid", "-f", "/dev/null")
        }
        Platform.Os.WINDOWS -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            "\$t = Add-Type -Name P -Namespace KW -PassThru -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);'; " +
                "[void]\$t::SetThreadExecutionState([uint32]2147483649); " +
                "while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 5 }")
        Platform.Os.MAC -> listOf("/usr/bin/caffeinate", "-i", "-m", "-s", "-w", pid.toString())
    }

    /** Held from now on (no-op when it already is). */
    @Synchronized fun hold(why: String) {
        if (proc?.isAlive == true) return
        val cmd = command(Platform.os, ProcessHandle.current().pid(), why)
        if (cmd == null) { unavailable.value = "systemd-inhibit is not installed"; holding.value = false; return }
        val p = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
            .getOrElse { unavailable.value = it.message ?: "could not start"; holding.value = false; return }
        // A refused inhibitor (no logind, policy) ends at once: not held.
        if (p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            unavailable.value = "the system refused it (exit ${p.exitValue()})"; holding.value = false; return
        }
        proc = p; holding.value = true; unavailable.value = null
    }

    /** Let go: the computer may sleep again. */
    @Synchronized fun release() {
        proc?.let { p -> p.descendants().forEach { runCatching { it.destroy() } }; p.destroy() }
        proc = null; holding.value = false
    }

    /**
     * Every few seconds: holds or releases as [heldPayment] says (an order and its sats, or null),
     * and calls [onWake] when the computer was asleep since the last look.
     */
    suspend fun watch(heldPayment: () -> String?, onWake: (Long) -> Unit) {
        var wall = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(TICK_MS)
            val now = System.currentTimeMillis()
            val gap = now - wall - TICK_MS
            wall = now
            if (woke(gap)) runCatching { onWake(gap) }
            val why = runCatching { heldPayment() }.getOrNull()
            if (why != null) hold(why) else if (proc != null) release()
        }
    }

    const val TICK_MS = 5_000L

    /** The loop's tick came [gapMs] late: asleep (or frozen) for that long. */
    fun woke(gapMs: Long) = gapMs > 30_000L
}
