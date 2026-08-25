package com.draupadi.app.core

import kotlinx.coroutines.flow.MutableStateFlow

/** What the alert looks like from the inside — her phone. */
data class AlertUi(
    val active: Boolean = false,
    val id: String = "",
    val seconds: Int = 0,
    val trigger: String = "",
    val silent: Boolean = false,
    val radiusKm: Int = 1,
    val reached: Int = 0,
    val accepted: Int = 0,
    val unlocked: Boolean = false,
    val recording: Boolean = false,
    val smsSent: Int = 0,
    val cloudOk: Boolean = false,
    val locationFixed: Boolean = false,
    val savedVideo: String? = null,
    val note: String = ""
)

/** What it looks like from the outside — a neighbour's phone. */
data class IncomingUi(
    val active: Boolean = false,
    val id: String = "",
    val fromName: String = "",
    val distanceM: Int = 0,
    val accepted: Boolean = false,
    val acceptedCount: Int = 0,
    val unlocked: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val closed: Boolean = false
)

/**
 * The single place the service and the UI meet. No database, no event bus —
 * two flows the service writes and the screens read.
 */
object AppState {
    val alert = MutableStateFlow(AlertUi())
    val incoming = MutableStateFlow(IncomingUi())
    val guardianRunning = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val heard = MutableStateFlow("")
    val cloudStatus = MutableStateFlow("")

    const val UNLOCK_AT = 3
}
