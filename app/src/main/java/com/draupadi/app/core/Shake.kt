package com.draupadi.app.core

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shake-to-trigger.
 *
 * The naive version — "is total acceleration above X" — fails, because the
 * reading always carries gravity, so the bar ends up either impossible to reach
 * or tripped by walking. This measures acceleration *away from* gravity and
 * asks for a sustained burst: several strong samples inside about a second.
 * A deliberate shake clears it easily; a stumble, a pocket, or a bus does not.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    /** 0..1, for the live meter on the self-test screen. */
    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    var armed: Boolean = true

    private val hits = ArrayDeque<Long>()
    private var lastFire = 0L

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val force = abs(sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH)

        // decay so the meter falls back instead of sticking at its peak
        level = maxOf((force / 18.0).coerceIn(0.0, 1.0).toFloat(), level * 0.82f)

        if (!armed) return

        val now = System.currentTimeMillis()
        if (now - lastFire < COOLDOWN_MS) return
        if (force < THRESHOLD) return

        hits.addLast(now)
        while (hits.isNotEmpty() && now - hits.first() > WINDOW_MS) hits.removeFirst()

        if (hits.size >= NEEDED) {
            hits.clear()
            lastFire = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun reset() {
        hits.clear()
        level = 0f
    }

    private companion object {
        const val THRESHOLD = 10.5      // m/s² beyond gravity
        const val NEEDED = 6            // strong samples…
        const val WINDOW_MS = 1100L     // …inside this window
        const val COOLDOWN_MS = 6000L
    }
}
