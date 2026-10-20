package com.lyne.adas.l1.recording

import android.annotation.SuppressLint
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Continuous dashcam with loop recording.
 *  - 720p (Quality.HD), falls back to lower quality if the camera can't do it
 *  - splits into [segmentMs] files saved under Movies/MyDashcam (MediaStore, no storage permission on API 29+)
 *  - deletes the oldest clips when the folder passes [maxTotalBytes] or free space drops below [minFreeBytes]
 *
 * Usage (see integration notes):
 *   val recorder = LoopRecorder(context)
 *   cameraProvider.bindToLifecycle(owner, selector, preview, analysis, recorder.videoCapture)
 *   recorder.start()
 *   ...
 *   recorder.stop()            // BEFORE unbinding the camera, so the last clip is finalized
 *
 * Note: there is a short gap (usually well under a second) between two segments.
 */
class LoopRecorder(
    context: Context,
    private val segmentMs: Long = 5 * 60_000L,
    private val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    private val minFreeBytes: Long = 1_500L * 1024 * 1024,
    bitrateBps: Int = 4_000_000,
    private val enableAudio: Boolean = false,
    private val onStatus: ((String) -> Unit)? = null,
) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .setTargetVideoEncodingBitRate(bitrateBps)
            .build()
    )

    @Volatile private var wantRecording = false
    private var active: Recording? = null
    private var failures = 0

    val isRecording: Boolean get() = active != null

    /** Match the preview rotation, e.g. recorder.setRotation(previewView.display.rotation). */
    fun setRotation(surfaceRotation: Int) {
        videoCapture.targetRotation = surfaceRotation
    }

    fun start() {
        if (wantRecording) return
        wantRecording = true
        failures = 0
        trimStorage()
        startSegment()
    }

    fun stop() {
        wantRecording = false
        main.removeCallbacksAndMessages(null)
        active?.stop()
        active = null
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!wantRecording || active != null) return

        val name = "DASH_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyDashcam")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            appContext.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(values)
            .setDurationLimitMillis(segmentMs)
            .build()

        try {
            var pending = videoCapture.output.prepareRecording(appContext, options)
            if (enableAudio && ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                pending = pending.withAudioEnabled()
            }
            active = pending.start(ContextCompat.getMainExecutor(appContext)) { event ->
                onEvent(event)
            }
        } catch (e: Exception) {
            handleFailure("start failed: ${e.message}")
        }
    }

    private fun onEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        active = null
        if (!wantRecording) return

        when (event.error) {
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> {
                failures = 0
                trimStorage()
                startSegment()
            }

            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> {
                trimStorage()
                handleFailure("insufficient storage")
            }

            else -> handleFailure("error ${event.error}")
        }
    }

    private fun handleFailure(msg: String) {
        failures++
        onStatus?.invoke("LoopRecorder: $msg ($failures)")
        if (failures >= MAX_FAILURES) {
            wantRecording = false
            onStatus?.invoke("LoopRecorder: stopped after repeated errors")
            return
        }
        main.postDelayed({
            if (wantRecording && active == null) startSegment()
        }, RETRY_DELAY_MS)
    }

    /** Loop recording: delete oldest clips in Movies/MyDashcam while over the size cap or low on space. */
    @Suppress("DEPRECATION")
    private fun trimStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = appContext.contentResolver
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE)
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("Movies/MyDashcam%")
            val sort = "${MediaStore.Video.Media.DATE_ADDED} ASC"

            val ids = ArrayList<Long>()
            val sizes = ArrayList<Long>()
            resolver.query(uri, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    ids.add(c.getLong(idCol))
                    sizes.add(c.getLong(sizeCol))
                }
            }

            var total = 0L
            for (s in sizes) total += s
            var free = try {
                StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            } catch (e: Exception) {
                Long.MAX_VALUE
            }

            var i = 0
            // Always keep at least the newest clip.
            while (i < ids.size - 1 && (total > maxTotalBytes || free < minFreeBytes)) {
                try {
                    resolver.delete(ContentUris.withAppendedId(uri, ids[i]), null, null)
                    total -= sizes[i]
                    if (free != Long.MAX_VALUE) free += sizes[i]
                } catch (se: SecurityException) {
                    // File not owned by this install (e.g. reinstalled with a different signing key): skip it.
                }
                i++
            }
        } catch (e: Exception) {
            onStatus?.invoke("LoopRecorder: trim failed: ${e.message}")
        }
    }

    private companion object {
        const val MAX_FAILURES = 5
        const val RETRY_DELAY_MS = 2000L
    }
}
