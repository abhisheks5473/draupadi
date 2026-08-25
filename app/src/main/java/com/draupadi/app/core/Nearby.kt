package com.draupadi.app.core

/** A phone we are about to buzz. */
data class NearbyUser(val uid: String, val lat: Double, val lng: Double, val distanceM: Double)
