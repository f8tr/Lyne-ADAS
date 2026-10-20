package com.lyne.adas.l1.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * GPS speed straight from the platform LocationManager - works on ROMs without Google Play Services.
 * Needs ACCESS_FINE_LOCATION (already requested by Lyne).
 *
 *   speed.start()          // after the permission is granted
 *   speed.speedKmh()       // km/h, or null if there is no fresh fix (treat null as "unknown")
 *   speed.stop()
 */
class PlatformSpeedProvider(context: Context) : LocationListener {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var speedMs = 0f
    @Volatile private var lastFixAt = 0L
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val granted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper()
            )
            running = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        if (!running) return
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        running = false
        speedMs = 0f
        lastFixAt = 0L
    }

    /** Speed in km/h, or null when there is no recent GPS fix with a speed value. */
    fun speedKmh(): Float? {
        val at = lastFixAt
        if (at == 0L) return null
        if (SystemClock.elapsedRealtime() - at > STALE_MS) return null
        return speedMs * 3.6f
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        speedMs = location.speed
        lastFixAt = SystemClock.elapsedRealtime()
    }

    // Implemented explicitly: on API < 30 these are abstract in LocationListener.
    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private companion object {
        const val UPDATE_INTERVAL_MS = 500L
        const val STALE_MS = 4000L
    }
}
