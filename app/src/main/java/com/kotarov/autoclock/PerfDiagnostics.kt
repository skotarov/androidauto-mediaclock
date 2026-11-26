package com.kotarov.autoclock

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Explicit opt-in; no sampler, timer, service or persistent data. */
internal object PerfDiagnostics {
    @Volatile var enabled = false
        private set
    @Volatile var audio = "Awaiting next playback event"
    private val art = AtomicLong()
    private val meta = AtomicLong()
    private val state = AtomicLong()
    private val http = AtomicLong()
    private val geo = AtomicLong()
    private val loc = AtomicLong()
    private var startWall = 0L
    private var startCpu = 0L
    fun artwork() { if (enabled) art.incrementAndGet() }
    fun metadata() { if (enabled) meta.incrementAndGet() }
    fun playback() { if (enabled) state.incrementAndGet() }
    fun network() { if (enabled) http.incrementAndGet() }
    fun geocode() { if (enabled) geo.incrementAndGet() }
    fun location() { if (enabled) loc.incrementAndGet() }
    fun start() {
        enabled = false
        listOf(art, meta, state, http, geo, loc).forEach { it.set(0) }
        audio = "Awaiting next playback event"
        startWall = SystemClock.elapsedRealtime()
        startCpu = Process.getElapsedCpuTime()
        enabled = true
    }
    fun stop() { enabled = false }
    fun snapshot(): String {
        val wall = (SystemClock.elapsedRealtime() - startWall).coerceAtLeast(1L)
        val cpu = (Process.getElapsedCpuTime() - startCpu).coerceAtLeast(0L)
        val cpuPct = 100.0 * cpu / wall
        val runtime = Runtime.getRuntime()
        val pss = Debug.getPss() / 1024.0
        val heap = (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0
        return String.format(Locale.US,
            "Measured: %.1f min\nProcess CPU: %.1f%% (one core = 100%%)\nRAM PSS: %.1f MB\nJava heap: %.1f MB\n\nRendered images: %d\nMetadata updates: %d\nPlayback state updates: %d\nWeather HTTP attempts: %d\nGeocoder calls: %d\nLocation requests: %d\n\nAudio: %s\n\nMonitoring is event-driven. Close this dialog to continue measuring, or Stop to disable. External Android Auto/TTS processes are not included.",
            wall / 60000.0, cpuPct, pss, heap, art.get(), meta.get(), state.get(),
            http.get(), geo.get(), loc.get(), audio)
    }
}
