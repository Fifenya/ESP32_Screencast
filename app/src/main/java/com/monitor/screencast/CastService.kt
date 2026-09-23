package com.monitor.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class CastService : Service() {

    private val CW = 480   // capture width
    private val CH = 640   // capture height
    private val SW = 240   // send width  (ESP32 screen)
    private val SH = 320   // send height

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var intervalMs = 100L
    private var quality = 60
    private var url = "http://192.168.1.102/frame"
    private var lastSend = 0L
    private var sentCount = 0L
    private var errCount = 0L
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val ip = intent.getStringExtra("ip") ?: "192.168.1.102"
        val fps = intent.getIntExtra("fps", 10)
        quality = intent.getIntExtra("quality", 60)
        intervalMs = (1000L / fps.coerceIn(1, 20))
        url = "http://$ip/frame"

        val notif = buildNotification("Connecting to $ip ...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        val resultCode = intent.getIntExtra("resultCode", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        reader = ImageReader.newInstance(CW, CH, PixelFormat.RGBA_8888, 3)

        virtualDisplay = projection?.createVirtualDisplay(
            "ESP32Cast",
            CW, CH, 320,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )

        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastSend < intervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastSend = now

            val bmp = imageToBitmap(image)
            image.close()
            if (bmp != null) {
                val small = Bitmap.createScaledBitmap(bmp, SW, SH, true)
                if (small !== bmp) bmp.recycle()
                val jpeg = bitmapToJpeg(small)
                small.recycle()
                sender.execute { sendFrame(jpeg) }
            }
        }, handler)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * CW
            val raw = Bitmap.createBitmap(CW + rowPadding / pixelStride, CH, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            raw.copyPixelsFromBuffer(buffer)
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(raw, 0, 0, CW, CH)
                if (cropped !== raw) raw.recycle()
                cropped
            } else raw
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmapToJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun sendFrame(jpeg: ByteArray) {
    var socket: java.net.Socket? = null
    try {
        socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(host, 8081), 2000)
        socket.soTimeout = 2000
        val out = socket.outputStream
        val size = jpeg.size
        out.write(byteArrayOf(
            ((size shr 24) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        ))
        out.write(jpeg)
        out.flush()
        sentCount++
    } catch (e: Exception) {
        errCount++
    } finally {
        try { socket?.close() } catch (_: Exception) {}
    }

    val now = System.currentTimeMillis()
    if (now - lastNotif > 2000) {
        lastNotif = now
        handler.post {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification("Sent: $sentCount | Err: $errCount | $host:8081"))
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        var ch = nm.getNotificationChannel("cast")
        if (ch == null) {
            ch = NotificationChannel("cast", "Screen Cast", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, "cast")
            .setContentTitle("ESP32 Screen Cast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            virtualDisplay?.release()
            reader?.close()
            projection?.stop()
        } catch (e: Exception) { }
        sender.shutdownNow()
        super.onDestroy()
    }
}
