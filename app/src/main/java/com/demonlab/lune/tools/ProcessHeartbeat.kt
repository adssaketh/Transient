package com.demonlab.lune.tools

import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * Diagnostics for "the song stops for ~2 seconds while another app is starting".
 *
 * A tiny thread wakes every 100 ms. If it is woken much later than planned, the whole app was frozen or starved of CPU
 * (not just the audio code). Each late wake-up is written to the playback trace together with:
 *  - the thread's scheduler counters before/after (waiting time grows = starved of CPU, unchanged = frozen/blocked),
 *  - the app's cgroup (which resource group Android had put it in) and its oom_score_adj (how important Android thinks it is).
 * Changes to the cgroup / oom_score_adj are noted too.
 */
object ProcessHeartbeat {
    @Volatile private var thread: Thread? = null

    fun start() {
        if (!DiagnosticLogger.isEnabled()) return // logging switched off in Settings
        if (thread?.isAlive == true) return
        val t = Thread({ loop() }, "transient-heartbeat").apply { isDaemon = true }
        thread = t
        t.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun read(path: String): String =
        try { File(path).readText().trim().replace('\n', ' ') } catch (_: Throwable) { "n/a" }

    private fun cgroup(): String = read("/proc/self/cgroup").take(220)

    /** Page-fault counters of this process: [minor, major]. Major faults mean memory had to be read back from storage or swap. */
    private fun faults(): LongArray {
        return try {
            val line = File("/proc/self/stat").readText()
            val f = line.substring(line.lastIndexOf(')') + 2).trim().split(' ')
            longArrayOf(f[7].toLong(), f[9].toLong())
        } catch (_: Throwable) { longArrayOf(-1, -1) }
    }

    /** Memory facts worth having next to a stall: how much of this app sits in swap, and how tight memory and storage are. */
    private fun memoryFacts(): String {
        val status = read("/proc/self/status")
        fun field(name: String) = Regex("$name:\\s*(\\d+) kB").find(status)?.groupValues?.get(1) ?: "?"
        val meminfo = read("/proc/meminfo")
        fun mem(name: String) = Regex("$name:\\s*(\\d+) kB").find(meminfo)?.groupValues?.get(1) ?: "?"
        val psiMem = read("/proc/pressure/memory").take(110)
        val psiIo = read("/proc/pressure/io").take(110)
        return "VmRSS=${field("VmRSS")}kB VmSwap=${field("VmSwap")}kB | MemAvailable=${mem("MemAvailable")}kB SwapFree=${mem("SwapFree")}kB | psi memory=[$psiMem] io=[$psiIo]"
    }

    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var lastCgroup = cgroup()
        var lastAdj = read("/proc/self/oom_score_adj")
        var lastSched = read("/proc/thread-self/schedstat")
        var lastFaults = faults()
        DiagnosticLogger.note("HEARTBEAT started | cgroup=$lastCgroup oom_adj=$lastAdj schedstat=[$lastSched]")
        var ticks = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (++ticks % 20 == 0 && !DiagnosticLogger.isEnabled()) break // switched off while running
                val t0 = SystemClock.uptimeMillis()
                Thread.sleep(100)
                val late = SystemClock.uptimeMillis() - t0 - 100
                val sched = read("/proc/thread-self/schedstat")
                val cg = cgroup()
                val adj = read("/proc/self/oom_score_adj")
                val nowFaults = faults()
                if (late > 400) {
                    DiagnosticLogger.note("HEARTBEAT late by $late ms | schedstat before=[$lastSched] after=[$sched] | cgroup=$cg oom_adj=$adj")
                    DiagnosticLogger.note(
                        "  at that moment: page faults +${nowFaults[0] - lastFaults[0]} minor, +${nowFaults[1] - lastFaults[1]} major | " + memoryFacts()
                    )
                }
                lastFaults = nowFaults
                if (cg != lastCgroup || adj != lastAdj) {
                    DiagnosticLogger.note("PROCESS state changed | cgroup=$cg oom_adj=$adj (was cgroup=$lastCgroup oom_adj=$lastAdj)")
                    lastCgroup = cg
                    lastAdj = adj
                }
                lastSched = sched
            }
        } catch (_: InterruptedException) {
        }
    }
}
