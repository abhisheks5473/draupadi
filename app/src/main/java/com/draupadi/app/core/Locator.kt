package com.draupadi.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

/**
 * A second pair of eyes on where the phone is.
 *
 * Google's fused provider is better when it works, but it is part of Play
 * Services: it can be throttled, restricted or simply absent, and when it
 * stops delivering it does so silently. For an app whose whole job is telling
 * people where you are, one provider is one too few — so this asks the
 * Android framework's own GPS and network providers directly, in parallel.
 *
 * Whichever source produces the better fix wins; see [isBetterFix].
 */
@SuppressLint("MissingPermission")
class Locator(
    private val context: Context,
    private val onFix: (Location) -> Unit
) : LocationListener {

    private var manager: LocationManager? = null
    private var running = false

    fun start(fast: Boolean) {
        stop()
        val m = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = m
        val everyMs = if (fast) 2_000L else 20_000L
        val everyM = if (fast) 0f else 8f

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!m.isProviderEnabled(provider)) continue
                m.requestLocationUpdates(provider, everyMs, everyM, this, Looper.getMainLooper())
                // seed immediately rather than waiting for the first update
                m.getLastKnownLocation(provider)?.let { onFix(it) }
                running = true
            } catch (t: Throwable) {
                Log.w(TAG, "provider $provider unavailable: ${t.message}")
            }
        }
        if (!running) Log.w(TAG, "no location provider is switched on")
    }

    fun stop() {
        if (!running) return
        try {
            manager?.removeUpdates(this)
        } catch (_: Throwable) {
        }
        running = false
    }

    /** True when the phone has location switched on at all. */
    fun anyProviderEnabled(): Boolean = try {
        val m = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        m != null && (m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
    } catch (_: Throwable) {
        false
    }

    override fun onLocationChanged(location: Location) = onFix(location)

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Still abstract below API 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    companion object {
        private const val TAG = "Draupadi/Loc"

        /**
         * Fixes arrive from several providers out of order. Take a much newer
         * one outright; otherwise keep whichever is more accurate.
         */
        fun isBetterFix(candidate: Location, current: Location?): Boolean {
            if (current == null) return true
            val newerBy = candidate.time - current.time
            if (newerBy > 40_000L) return true
            if (newerBy < -40_000L) return false
            if (candidate.accuracy <= 0f) return newerBy > 0
            if (current.accuracy <= 0f) return true
            return candidate.accuracy <= current.accuracy + 2f
        }

        fun describe(l: Location?): String {
            if (l == null) return ""
            val acc = if (l.accuracy > 0f) " · ±${l.accuracy.toInt()} m" else ""
            return String.format("%.5f, %.5f", l.latitude, l.longitude) + acc
        }
    }
}
