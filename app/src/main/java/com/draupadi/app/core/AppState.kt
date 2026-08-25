package com.draupadi.app.core

import kotlinx.coroutines.flow.MutableStateFlow

/** What the alert looks like from the inside — her phone. */
data class AlertUi(
    val active: Boolean = false,
    val id: String = "",
    val seconds: Int = 0,
    val trigger: String = "",
    val silent: Boolean = false,
    val dryRun: Boolean = false,
    val radiusKm: Int = 1,
    val reached: Int = 0,
    val accepted: Int = 0,
    val unlocked: Boolean = false,
    val recording: Boolean = false,
    val smsTotal: Int = 0,
    val smsSent: Int = 0,
    val smsFailed: Int = 0,
    val cloudOk: Boolean = false,
    val locationFixed: Boolean = false,
    val savedVideo: String? = null,
    /** seconds left to call it a false alarm before anyone is disturbed further */
    val cancelSecs: Int = 0,
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

/** The last thing that happened, so the app can say so plainly afterwards. */
data class ResultUi(
    val show: Boolean = false,
    val cancelled: Boolean = false,
    val dryRun: Boolean = false,
    val seconds: Int = 0,
    val smsSent: Int = 0,
    val smsTotal: Int = 0,
    val reached: Int = 0,
    val savedVideo: String? = null
)

/**
 * The single place the service and the UI meet. No database, no event bus —
 * flows the service writes and the screens read.
 */
object AppState {
    val alert = MutableStateFlow(AlertUi())
    val incoming = MutableStateFlow(IncomingUi())
    val result = MutableStateFlow(ResultUi())

    val guardianRunning = MutableStateFlow(false)

    /** True while the keyword loop is alive. Deliberately steady: the
     *  recogniser restarts constantly under the hood and the user should
     *  never see that flicker. */
    val listening = MutableStateFlow(false)

    /** Live microphone loudness, 0..1 — proof that it really is listening. */
    val micLevel = MutableStateFlow(0f)

    /** Live shake force, 0..1, and a timestamp that pulses on detection. */
    val shakeLevel = MutableStateFlow(0f)
    val shakeDetected = MutableStateFlow(0L)

    val heard = MutableStateFlow("")

    /** Where the phone last knew it was, and when — shown in the self-test so
     *  "is location working?" can be answered before it matters. */
    val locationSummary = MutableStateFlow("")
    val locationAt = MutableStateFlow(0L)
    val locationOn = MutableStateFlow(true)
    val cloudStatus = MutableStateFlow("")

    const val UNLOCK_AT = 3
}
