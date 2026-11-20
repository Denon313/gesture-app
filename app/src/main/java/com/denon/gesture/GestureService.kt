package com.denon.gesture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.hypot

class GestureService : LifecycleService() {

    private var landmarker: HandLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var lastTs = 0L
    private val history = ArrayList<Pair<Long, Float>>()
    private var fistFrames = 0
    private var lastAction = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        if (landmarker == null) {
            setupLandmarker()
            startCamera()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Air Gesture", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Air Gesture is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupLandmarker() {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val bitmap = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(proxy.planes[0].buffer)
                    val ts = SystemClock.uptimeMillis()
                    if (ts > lastTs) {
                        lastTs = ts
                        val opts = ImageProcessingOptions.builder()
                            .setRotationDegrees(proxy.imageInfo.rotationDegrees)
                            .build()
                        landmarker?.detectAsync(BitmapImageBuilder(bitmap).build(), opts, ts)
                    }
                } catch (e: Exception) {
                } finally {
                    proxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            history.clear()
            fistFrames = 0
            return
        }

        val lm = hands[0]
        val now = SystemClock.uptimeMillis()

        fun d(a: Int, b: Int) =
            hypot((lm[a].x() - lm[b].x()).toDouble(), (lm[a].y() - lm[b].y()).toDouble())

        // finger is folded when its tip is closer to the wrist than its middle joint
        val folded = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18).all { (tip, pip) -> d(tip, 0) < d(pip, 0) }

        if (folded) {
            history.clear()
            fistFrames++
            if (fistFrames >= 6 && now - lastAction > COOLDOWN) {
                lastAction = now
                fistFrames = 0
                GestureAccessibilityService.instance?.goHome()
            }
            return
        }
        fistFrames = 0

        val y = lm[0].y()
        history.add(now to y)
        history.removeAll { now - it.first > 600 }

        if (history.size >= 3) {
            val moved = history.first().second - y
            if (moved > 0.25f && now - lastAction > COOLDOWN) {
                lastAction = now
                history.clear()
                GestureAccessibilityService.instance?.swipeUp()
            }
        }
    }

    override fun onDestroy() {
        landmarker?.close()
        landmarker = null
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.denon.gesture.STOP"
        private const val CHANNEL = "air_gesture"
        private const val COOLDOWN = 1500L
    }
}
