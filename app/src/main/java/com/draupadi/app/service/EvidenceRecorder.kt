package com.draupadi.app.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Records video with sound straight into MediaStore.
 *
 * That last part matters: because the output target is MediaStore rather than
 * a private file, the moment the recording finalises the clip is a normal
 * entry in the phone's Gallery. Nothing to export, nothing to remember to
 * save — it is simply there, next to her photos.
 *
 * While it records it also grabs a still every few seconds and hands it to the
 * uploader, so evidence exists in the cloud even if the phone never survives
 * long enough to finish the video.
 */
class EvidenceRecorder(
    private val context: Context,
    private val owner: LifecycleOwner
) {

    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null

    @Volatile var isRecording: Boolean = false
        private set

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /**
     * @param onSaved called with the Gallery uri once the file is finalised
     */
    fun start(useFrontCamera: Boolean, onSaved: (Uri?) -> Unit) {
        if (isRecording) return
        if (!granted(Manifest.permission.CAMERA)) {
            Log.w(TAG, "no camera permission")
            onSaved(null)
            return
        }
        // ProcessCameraProvider.getInstance() is declared as returning a Guava
        // ListenableFuture. Guava reaches the finished APK through Firebase, but
        // Gradle resolves the standalone artifact to the deliberately empty
        // "9999.0-empty-to-avoid-conflict-with-guava" stub, so the type is not
        // usable at compile time. At runtime it is an ordinary
        // java.util.concurrent.Future, which is all this needs — so we take it
        // as one and keep Guava out of the build entirely.
        exec.execute {
            val ready: ProcessCameraProvider? = try {
                val getInstance = ProcessCameraProvider::class.java
                    .getMethod("getInstance", Context::class.java)
                val future = getInstance.invoke(null, context) as Future<*>
                future.get(8, TimeUnit.SECONDS) as ProcessCameraProvider
            } catch (t: Throwable) {
                Log.e(TAG, "camera unavailable: ${t.message}")
                null
            }

            // binding must happen on the main thread
            ContextCompat.getMainExecutor(context).execute {
                if (ready == null) {
                    onSaved(null)
                } else {
                    try {
                        provider = ready
                        bindAndRecord(ready, useFrontCamera, onSaved)
                    } catch (t: Throwable) {
                        Log.e(TAG, "could not start the camera: ${t.message}")
                        onSaved(null)
                    }
                }
            }
        }
    }

    private fun bindAndRecord(
        p: ProcessCameraProvider,
        useFrontCamera: Boolean,
        onSaved: (Uri?) -> Unit
    ) {
        val quality = QualitySelector.fromOrderedList(
            listOf(Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder().setQualitySelector(quality).build()
        val vc = VideoCapture.withOutput(recorder)
        val ic = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector =
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

        p.unbindAll()
        // Some phones cannot hold video and stills at the same time. Video is
        // the thing that matters, so it is bound first and alone if need be.
        try {
            p.bindToLifecycle(owner, selector, vc, ic)
            imageCapture = ic
        } catch (t: Throwable) {
            Log.w(TAG, "stills unavailable, video only: ${t.message}")
            imageCapture = null
            p.bindToLifecycle(owner, selector, vc)
        }
        videoCapture = vc

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Draupadi-$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Draupadi")
            }
        }
        val output = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .setFileSizeLimit(MAX_BYTES)   // a long alert must not fill her phone
            .build()

        var pending = vc.output.prepareRecording(context, output)
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> isRecording = true
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    val uri = if (event.hasError()) {
                        Log.e(TAG, "recording error ${event.error}")
                        null
                    } else {
                        event.outputResults.outputUri
                    }
                    onSaved(uri)
                }
            }
        }
    }

    /** A single JPEG, small enough to reach the cloud in a second or two. */
    fun snapshot(onBytes: (ByteArray) -> Unit) {
        val ic = imageCapture ?: return
        try {
            ic.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        onBytes(bytes)
                    } catch (t: Throwable) {
                        Log.w(TAG, "snapshot read failed: ${t.message}")
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "snapshot failed: ${exception.message}")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot threw: ${t.message}")
        }
    }

    fun stop() {
        try {
            recording?.stop()
        } catch (_: Throwable) {
        }
        recording = null
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        videoCapture = null
        imageCapture = null
        isRecording = false
    }

    fun release() {
        stop()
        try {
            exec.shutdown()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "Draupadi/Rec"
        const val MAX_BYTES = 600L * 1024 * 1024
    }
}
