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
 * reading always carries gravity, so the bar ends up either impossible to
 * reach or tripped by walking. This measures acceleration *away from* gravity
 * and asks for a sustained burst: several strong samples inside about a
 * second.
 *
 * Sensitivity is a setting rather than a constant, because what counts as a
 * deliberate shake depends on the phone, the case and the person holding it.
 */
enum class ShakeSensitivity(
    val label: String,
    val threshold: Double,   // m/s² beyond gravity
    val needed: Int,         // strong samples…
    val windowMs: Long       // …inside this window
) {
    /** Hardest to set off. A hard, deliberate shake for about a second. */
    LOW("Firm shake", 17.0, 11, 1500L),

    /** Default. A clear shake, but not a wrestle. */
    MEDIUM("Normal", 13.5, 8, 1300L),

    /** Easiest. Use only if the other two will not fire on your phone. */
    HIGH("Light shake", 10.5, 6, 1100L);

    companion object {
        fun of(index: Int): ShakeSensitivity = when (index) {
            0 -> LOW
            2 -> HIGH
            else -> MEDIUM
        }
    }
}

class ShakeDetector(
    private var sensitivity: ShakeSensitivity = ShakeSensitivity.LOW,
    private val onShake: () -> Unit
) : SensorEventListener {

    /** 0..1, for the live meter on the self-test screen. */
    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    var armed: Boolean = true

    private val hits = ArrayDeque<Long>()
    private var lastFire = 0L

    fun setSensitivity(s: ShakeSensitivity) {
        sensitivity = s
        hits.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val force = abs(sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH)

        // the meter is scaled against the current threshold, so "full bar"
        // always means "this would count", whatever the setting
        val scaled = force / (sensitivity.threshold * 1.35)
        level = maxOf(scaled.coerceIn(0.0, 1.0).toFloat(), level * 0.82f)

        if (!armed) return

        val now = System.currentTimeMillis()
        if (now - lastFire < COOLDOWN_MS) return
        if (force < sensitivity.threshold) return

        hits.addLast(now)
        while (hits.isNotEmpty() && now - hits.first() > sensitivity.windowMs) hits.removeFirst()

        if (hits.size >= sensitivity.needed) {
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
        const val COOLDOWN_MS = 8000L
    }
}
