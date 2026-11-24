package com.kotarov.autoclock.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * One-shot approximate position, only when Auto weather is selected.
 * No ongoing location subscription, background GPS tracking or wake locks.
 * Never transmit the device's coordinates to the observation-station API.
 */
object WeatherLocation {
    private const val MAX_LAST_FIX_AGE_MS = 60L * 1000
    private const val FIX_TIMEOUT_MS = 8000L

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun requestOnce(context: Context, done: (Location?) -> Unit) {
        val app = context.applicationContext
        if (!permitted(app)) {
            done(null)
            return
        }
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            done(null)
            return
        }
        val provider = LocationManager.NETWORK_PROVIDER
        try {
            if (!manager.isProviderEnabled(provider)) {
                done(null)
                return
            }
            val last = manager.getLastKnownLocation(provider)
            if (last != null &&
                last.latitude in -90.0..90.0 && last.longitude in -180.0..180.0 &&
                System.currentTimeMillis() - last.time in 0..MAX_LAST_FIX_AGE_MS
            ) {
                done(last)
                return
            }
            val handler = Handler(Looper.getMainLooper())
            var completed = false
            lateinit var listener: LocationListener
            val timeout = Runnable {
                if (!completed) {
                    completed = true
                    manager.removeUpdates(listener)
                    done(null)
                }
            }
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!completed) {
                        completed = true
                        handler.removeCallbacks(timeout)
                        manager.removeUpdates(this)
                        done(location)
                    }
                }
                override fun onProviderDisabled(provider: String) {
                    if (!completed) {
                        completed = true
                        handler.removeCallbacks(timeout)
                        manager.removeUpdates(this)
                        done(null)
                    }
                }
            }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            handler.postDelayed(timeout, FIX_TIMEOUT_MS)
        } catch (_: SecurityException) {
            done(null)
        } catch (_: IllegalArgumentException) {
            done(null)
        }
    }
}
