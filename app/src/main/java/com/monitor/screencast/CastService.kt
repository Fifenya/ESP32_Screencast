package com.monitor.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class CastService : Service() {

    private val CW = 480
    private val CH = 640
    private val SW = 240
    private val SH = 320
    private val PORT = 8081

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val handler = Handler(Looper.getMainLooper())

    // Переиспользуемые объекты (без GC-пауз)
    private var rawBitmap: Bitmap? = null
    private var outBitmap: Bitmap? = null
    private var outCanvas: Canvas? = null
    private val jpegOut = ByteArrayOutputStream(64 * 1024)

    // Постоянное соединение
    private var socket: Socket? = null
    private var sockOut: OutputStream? = null

    private var intervalMs = 66L   // ~15 fps по умолчанию
    private var quality = 75
    private var rotDeg = 0
    private var host = "192.168.1.102"
    private var lastSend = 0L
    private var sentCount = 0L
    private var errCount = 0L
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        host = intent.getStringExtra("ip") ?: "192.168.1.102"
        val fps = intent.getIntExtra("fps", 15)
        quality = intent.getIntExtra("quality", 75)
        rotDeg = intent.getIntExtra("rot", 0)
        intervalMs = (1000L / fps.coerceIn(1, 20))

        val notif = buildNotification("Connecting to $host:$PORT ...")
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
        captureThread = HandlerThread("cast-capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        reader = ImageReader.newInstance(CW, CH, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection?.createVirtualDisplay(
            "ESP32Cast",
            CW, CH, 320,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            captureHandler
        )

        outBitmap = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
        outCanvas = Canvas(outBitmap!!)

        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastSend < intervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastSend = now

            grabToRawBitmap(image)
            image.close()

            val jpeg = renderAndCompress()
            if (jpeg != null) sendFrame(jpeg)
        }, captureHandler)
    }

    private fun grabToRawBitmap(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * CW
        val needW = CW + rowPadding / pixelStride

        if (rawBitmap == null || rawBitmap!!.width != needW) {
            rawBitmap?.recycle()
            rawBitmap = Bitmap.createBitmap(needW, CH, Bitmap.Config.ARGB_8888)
        }
        buffer.rewind()
        rawBitmap!!.copyPixelsFromBuffer(buffer)
    }

    // Поворот через canvas + letterbox, без лишних аллокаций
    private fun renderAndCompress(): ByteArray? {
        val raw = rawBitmap ?: return null
        val out = outBitmap ?: return null
        val canvas = outCanvas ?: return null
        return try {
            val srcW: Int
            val srcH: Int
            val swap = (rotDeg == 90 || rotDeg == 270)
            if (swap) {
                // исходный контент в raw повёрнут: учитываем при вписывании
                srcW = if (raw.width > CW) CW else raw.width
                srcH = CH
            } else {
                srcW = if (raw.width > CW) CW else raw.width
                srcH = CH
            }

            val scale: Float
            val dw: Int
            val dh: Int
            if (swap) {
                scale = minOf(SW.toFloat() / srcH, SH.toFloat() / srcW)
                dw = (srcH * scale).toInt()
                dh = (srcW * scale).toInt()
            } else {
                scale = minOf(SW.toFloat() / srcW, SH.toFloat() / srcH)
                dw = (srcW * scale).toInt()
                dh = (srcH * scale).toInt()
            }

            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.rotate(rotDeg.toFloat(), SW / 2f, SH / 2f)
            canvas.drawBitmap(
                raw,
                Rect(0, 0, srcW, srcH),
                Rect((SW - dw) / 2, (SH - dh) / 2, (SW + dw) / 2, (SH + dh) / 2),
                null
            )
            canvas.restore()

            jpegOut.reset()
            out.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)
            jpegOut.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureSocket(): Boolean {
        val s = socket
        if (s != null && s.isConnected && !s.isClosed && sockOut != null) return true
        closeSocket()
        return try {
            val ns = Socket()
            ns.connect(InetSocketAddress(host, PORT), 1500)
            ns.tcpNoDelay = true          // отключаем Nagle — меньше задержка
            ns.soTimeout = 2000
            ns.sendBufferSize = 64 * 1024
            sockOut = ns.outputStream
            socket = ns
            true
        } catch (e: Exception) {
            closeSocket()
            false
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        sockOut = null
    }

    private fun sendFrame(jpeg: ByteArray) {
        if (!ensureSocket()) {
            errCount++
        } else {
            try {
                val out = sockOut!!
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
                closeSocket()   // следующий кадр переподключится
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastNotif > 2000) {
            lastNotif = now
            handler.post {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(1, buildNotification("Sent: $sentCount | Err: $errCount | $host:$PORT"))
            }
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
        closeSocket()
        captureThread?.quitSafely()
        rawBitmap?.recycle()
        outBitmap?.recycle()
        super.onDestroy()
    }
}
