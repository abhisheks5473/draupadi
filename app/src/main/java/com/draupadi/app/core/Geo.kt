package com.draupadi.app.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coarse grid cells instead of a geohash library.
 *
 * Every user document carries one `cell` string. To find everyone inside a
 * radius we ask for the handful of cells that cover it (a single `whereIn`,
 * so Firestore needs no composite index) and then measure exact distances
 * on the phone. Cheap, index-free, and accurate.
 */
object Geo {

    /** ~5 km on a side near the equator; a little narrower further north. */
    private const val CELL = 0.045

    fun cell(lat: Double, lng: Double): String =
        "${floor(lat / CELL).toInt()}_${floor(lng / CELL).toInt()}"

    /** Every cell touching a circle of [radiusKm] around the point. */
    fun cellsCovering(lat: Double, lng: Double, radiusKm: Double): List<String> {
        val latSpan = radiusKm / 111.0
        val lngSpan = radiusKm / (111.0 * max(0.15, cos(Math.toRadians(lat))))
        val steps = { span: Double -> max(1, Math.ceil(span / CELL).toInt()) }
        val li = steps(latSpan)
        val gi = steps(lngSpan)
        val out = mutableListOf<String>()
        for (a in -li..li) {
            for (b in -gi..gi) {
                out += "${floor(lat / CELL).toInt() + a}_${floor(lng / CELL).toInt() + b}"
            }
        }
        return out.distinct()
    }

    /** Metres between two points. */
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /**
     * The blurred position a stranger sees before enough people have accepted.
     * Snapped to a ~600 m grid, so it points at a neighbourhood, not a person.
     */
    fun blur(lat: Double, lng: Double): Pair<Double, Double> {
        val g = 0.0055
        return Pair(floor(lat / g) * g + g / 2, floor(lng / g) * g + g / 2)
    }

    fun pretty(metres: Double): String =
        if (metres >= 1000) String.format("%.1f km", metres / 1000) else "${metres.toInt()} m"

    fun mapsLink(lat: Double, lng: Double): String =
        "https://maps.google.com/?q=%.6f,%.6f".format(lat, lng)

    fun moved(a: Pair<Double, Double>?, lat: Double, lng: Double, minMetres: Double): Boolean {
        if (a == null) return true
        return distance(a.first, a.second, lat, lng) > minMetres
    }
}
