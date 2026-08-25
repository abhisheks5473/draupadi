package com.draupadi.app.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.draupadi.app.core.AppState
import com.draupadi.app.core.Geo
import com.draupadi.app.core.NearbyUser
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

/**
 * The only part of Draupadi that needs a server: reaching phones that do not
 * belong to you.
 *
 * Everything here degrades quietly. If no Firebase project has been wired up,
 * `enabled` is false and every call is a no-op — the SMS, the recording, the
 * gallery save and the siren still work exactly the same. The app is never
 * less useful than a phone with no signal.
 */
object Cloud {

    private const val TAG = "Draupadi/Cloud"
    private const val PLACEHOLDER = "draupadi-replace-me"

    private var db: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null

    @Volatile var enabled: Boolean = false
        private set

    @Volatile var uid: String? = null
        private set

    fun init(context: Context) {
        try {
            val app = FirebaseApp.initializeApp(context) ?: FirebaseApp.getInstance()
            val project = app.options.projectId ?: ""
            if (project.isBlank() || project.contains(PLACEHOLDER, ignoreCase = true)) {
                enabled = false
                AppState.cloudStatus.value = "Offline mode — no Firebase project connected"
                return
            }
            db = FirebaseFirestore.getInstance()
            auth = FirebaseAuth.getInstance()
            enabled = true
            AppState.cloudStatus.value = "Connected"
        } catch (t: Throwable) {
            Log.w(TAG, "firebase unavailable: ${t.message}")
            enabled = false
            AppState.cloudStatus.value = "Offline mode"
        }
    }

    suspend fun ensureSignedIn(): String? {
        if (!enabled) return null
        return try {
            val a = auth ?: return null
            val current = a.currentUser
            val user = current ?: a.signInAnonymously().await().user
            uid = user?.uid
            uid
        } catch (t: Throwable) {
            Log.w(TAG, "sign-in failed: ${t.message}")
            null
        }
    }

    /** Keeps this phone on the map so it can be reached by someone else's alert. */
    suspend fun heartbeat(lat: Double, lng: Double, name: String) {
        val id = uid ?: ensureSignedIn() ?: return
        val d = db ?: return
        try {
            d.collection("users").document(id).set(
                mapOf(
                    "cell" to Geo.cell(lat, lng),
                    "lat" to lat,
                    "lng" to lng,
                    "name" to name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (t: Throwable) {
            Log.w(TAG, "heartbeat failed: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- alerts

    suspend fun createAlert(name: String, lat: Double, lng: Double, trigger: String): String? {
        val id = uid ?: ensureSignedIn() ?: return null
        val d = db ?: return null
        return try {
            val (bLat, bLng) = Geo.blur(lat, lng)
            val doc = d.collection("alerts").document()
            doc.set(
                mapOf(
                    "ownerUid" to id,
                    "name" to name,
                    "trigger" to trigger,
                    "status" to "active",
                    "radiusKm" to 1,
                    "reached" to 0,
                    "acceptedCount" to 0,
                    "approxLat" to bLat,
                    "approxLng" to bLng,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            doc.id
        } catch (t: Throwable) {
            Log.w(TAG, "createAlert failed: ${t.message}")
            null
        }
    }

    /**
     * The exact position lives in its own document. Security rules refuse to
     * serve it to anyone until enough verified people have accepted — the
     * consent gate is enforced by the server, not by the app being polite.
     */
    suspend fun pushPrecise(alertId: String, lat: Double, lng: Double, accuracy: Float) {
        val d = db ?: return
        if (alertId.isBlank()) return
        try {
            d.collection("alerts").document(alertId)
                .collection("precise").document("live")
                .set(
                    mapOf(
                        "lat" to lat,
                        "lng" to lng,
                        "accuracy" to accuracy,
                        "at" to FieldValue.serverTimestamp()
                    )
                ).await()
            val (bLat, bLng) = Geo.blur(lat, lng)
            d.collection("alerts").document(alertId)
                .update(mapOf("approxLat" to bLat, "approxLng" to bLng)).await()
        } catch (t: Throwable) {
            Log.w(TAG, "pushPrecise failed: ${t.message}")
        }
    }

    suspend fun setRadius(alertId: String, km: Int, reached: Int) {
        val d = db ?: return
        if (alertId.isBlank()) return
        try {
            d.collection("alerts").document(alertId)
                .update(mapOf("radiusKm" to km, "reached" to reached)).await()
        } catch (_: Throwable) {
        }
    }

    suspend fun closeAlert(alertId: String) {
        val d = db ?: return
        if (alertId.isBlank()) return
        try {
            d.collection("alerts").document(alertId)
                .update(mapOf("status" to "closed", "closedAt" to FieldValue.serverTimestamp()))
                .await()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- reaching

    /**
     * Find every Draupadi phone inside the radius and drop an alert in its
     * inbox. Written from the client so the whole thing runs on Firebase's
     * free tier — no Cloud Functions, no billing account.
     */
    suspend fun fanOut(
        alertId: String,
        lat: Double,
        lng: Double,
        radiusKm: Double,
        fromName: String
    ): Int {
        val me = uid ?: return 0
        val d = db ?: return 0
        return try {
            val cells = Geo.cellsCovering(lat, lng, radiusKm)
            val found = mutableListOf<NearbyUser>()
            cells.chunked(10).forEach { chunk ->
                val snap = d.collection("users").whereIn("cell", chunk).get().await()
                for (doc: DocumentSnapshot in snap.documents) {
                    if (doc.id == me) continue
                    val uLat = doc.getDouble("lat") ?: continue
                    val uLng = doc.getDouble("lng") ?: continue
                    val dist = Geo.distance(lat, lng, uLat, uLng)
                    if (dist <= radiusKm * 1000) {
                        found += NearbyUser(doc.id, uLat, uLng, dist)
                    }
                }
            }
            val (bLat, bLng) = Geo.blur(lat, lng)
            found.chunked(400).forEach { group ->
                val batch = d.batch()
                group.forEach { u ->
                    val ref = d.collection("users").document(u.uid)
                        .collection("inbox").document(alertId)
                    batch.set(
                        ref,
                        mapOf(
                            "alertId" to alertId,
                            "fromName" to fromName,
                            "approxLat" to bLat,
                            "approxLng" to bLng,
                            "distanceM" to u.distanceM.toInt(),
                            "at" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
            found.size
        } catch (t: Throwable) {
            Log.w(TAG, "fanOut failed: ${t.message}")
            0
        }
    }

    suspend fun accept(alertId: String) {
        val me = uid ?: ensureSignedIn() ?: return
        val d = db ?: return
        try {
            d.collection("alerts").document(alertId)
                .collection("responders").document(me)
                .set(mapOf("at" to FieldValue.serverTimestamp())).await()
            d.collection("alerts").document(alertId)
                .update("acceptedCount", FieldValue.increment(1)).await()
        } catch (t: Throwable) {
            Log.w(TAG, "accept failed: ${t.message}")
        }
    }

    suspend fun clearInbox(alertId: String) {
        val me = uid ?: return
        val d = db ?: return
        try {
            d.collection("users").document(me)
                .collection("inbox").document(alertId).delete().await()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ listeners

    fun watchInbox(onAlert: (id: String, fromName: String, distanceM: Int, lat: Double, lng: Double) -> Unit): ListenerRegistration? {
        val me = uid ?: return null
        val d = db ?: return null
        return try {
            d.collection("users").document(me).collection("inbox")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) return@addSnapshotListener
                    for (change in snap.documentChanges) {
                        val doc = change.document
                        onAlert(
                            doc.getString("alertId") ?: doc.id,
                            doc.getString("fromName") ?: "Someone",
                            (doc.getLong("distanceM") ?: 0L).toInt(),
                            doc.getDouble("approxLat") ?: 0.0,
                            doc.getDouble("approxLng") ?: 0.0
                        )
                    }
                }
        } catch (_: Throwable) {
            null
        }
    }

    fun watchAlert(alertId: String, cb: (accepted: Int, status: String) -> Unit): ListenerRegistration? {
        val d = db ?: return null
        return try {
            d.collection("alerts").document(alertId).addSnapshotListener { doc, err ->
                if (err != null || doc == null || !doc.exists()) return@addSnapshotListener
                cb(
                    (doc.getLong("acceptedCount") ?: 0L).toInt(),
                    doc.getString("status") ?: "active"
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Fires only once the gate has opened; before that the rules deny it. */
    fun watchPrecise(alertId: String, cb: (lat: Double, lng: Double) -> Unit): ListenerRegistration? {
        val d = db ?: return null
        return try {
            d.collection("alerts").document(alertId)
                .collection("precise").document("live")
                .addSnapshotListener { doc, err ->
                    if (err != null || doc == null || !doc.exists()) return@addSnapshotListener
                    val lat = doc.getDouble("lat") ?: return@addSnapshotListener
                    val lng = doc.getDouble("lng") ?: return@addSnapshotListener
                    cb(lat, lng)
                }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------- evidence

    suspend fun uploadSnapshot(alertId: String, index: Int, bytes: ByteArray): Boolean {
        if (!enabled || alertId.isBlank()) return false
        return try {
            FirebaseStorage.getInstance().reference
                .child("alerts/$alertId/frame_${index.toString().padStart(4, '0')}.jpg")
                .putBytes(bytes).await()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot upload failed: ${t.message}")
            false
        }
    }

    suspend fun uploadVideo(alertId: String, uri: Uri): String? {
        if (!enabled || alertId.isBlank()) return null
        return try {
            val ref = FirebaseStorage.getInstance().reference
                .child("alerts/$alertId/evidence.mp4")
            ref.putFile(uri).await()
            ref.downloadUrl.await().toString()
        } catch (t: Throwable) {
            Log.w(TAG, "video upload failed: ${t.message}")
            null
        }
    }
}
