package com.kotarov.autoclock.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Passive fixes never start GPS. The fallback is a cancellable one-shot
 * coarse network/fused fix; no background permission or wake lock is added. */
object WeatherLocation {
    private const val FIX_TIMEOUT_MS = 8_000L
    private const val MAX_LAST_FIX_AGE_MS = 60_000L

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun fresh(location: Location?, maxAge: Long = MAX_LAST_FIX_AGE_MS): Boolean {
        if (location == null || location.latitude !in -90.0..90.0 ||
            location.longitude !in -180.0..180.0) return false
        val age = SystemClock.elapsedRealtimeNanos() / 1_000_000L -
            location.elapsedRealtimeNanos / 1_000_000L
        return age in 0..maxAge
    }

    private fun manager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun enabled(context: Context): Boolean {
        val m = manager(context) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
            else m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                m.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
    }

    private fun activeProviders(m: LocationManager): List<String> =
        (listOf(LocationManager.NETWORK_PROVIDER) +
            if (Build.VERSION.SDK_INT >= 31) listOf(LocationManager.FUSED_PROVIDER) else emptyList())
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }

    @Suppress("DEPRECATION")
    fun passive(context: Context, done: (Location) -> Unit): (() -> Unit)? {
        if (!permitted(context)) return null
        val m = manager(context) ?: return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (fresh(location)) done(location)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        return try {
            m.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 30_000L, 0f,
                listener, Looper.getMainLooper())
            val cancel: () -> Unit = { runCatching { m.removeUpdates(listener) }; Unit }
            cancel
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    fun requestOnce(context: Context, done: (Location?) -> Unit): () -> Unit {
        if (!permitted(context)) { done(null); return {} }
        val m = manager(context) ?: run { done(null); return {} }
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        val providers = activeProviders(m)
        var nextProvider = 0
        lateinit var listener: LocationListener
        lateinit var timeout: Runnable
        fun finish(location: Location?, notify: Boolean = true) {
            if (completed) return
            completed = true
            handler.removeCallbacks(timeout)
            runCatching { m.removeUpdates(listener) }
            if (notify) done(location?.takeIf { fresh(it) })
        }
        fun tryNext() {
            if (completed) return
            handler.removeCallbacks(timeout)
            runCatching { m.removeUpdates(listener) }
            while (nextProvider < providers.size) {
                val provider = providers[nextProvider++]
                try {
                    m.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    if (!completed) handler.postDelayed(timeout, FIX_TIMEOUT_MS)
                    return
                } catch (_: Exception) {
                    // Try the other coarse provider, if available.
                }
            }
            finish(null)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (fresh(location)) finish(location) else tryNext()
            }
            override fun onProviderDisabled(provider: String) = tryNext()
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        timeout = Runnable { tryNext() }
        val last = (listOf(LocationManager.PASSIVE_PROVIDER) + providers)
            .mapNotNull { provider -> runCatching { m.getLastKnownLocation(provider) }.getOrNull() }
            .filter { fresh(it) }.maxByOrNull { it.elapsedRealtimeNanos }
        if (last != null) finish(last) else tryNext()
        return { finish(null, false) }
    }
}
