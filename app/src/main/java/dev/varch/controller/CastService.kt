package dev.varch.controller

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.varch.controller.net.VarchClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * Casts this phone's screen to the desktop. The screen is encoded as H.264 by
 * the phone's hardware encoder and sent over a WebSocket; the desktop shows
 * it in a viewer window. Android requires a foreground service with a visible
 * notification for as long as the screen is being captured.
 */
class CastService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    // Read by the encoder's callbacks, which run on the worker thread.
    @Volatile
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var size: Triple<Int, Int, Int>? = null
    private var socket: WebSocket? = null
    private var congested = false
    private var ended = false

    private val rotation = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resize()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val grant = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_GRANT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_GRANT)
        }
        if (intent?.action == ACTION_STOP || grant == null) {
            end(null)
            return START_NOT_STICKY
        }
        val pairing = Store(this).load()
        // The service must be in the foreground before it may touch the projection.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(pairing?.hostName ?: getString(R.string.app_name)),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        if (pairing == null) {
            end(getString(R.string.shortcut_not_paired))
            return START_NOT_STICKY
        }
        casting = true
        error = null
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant)

        // Connect first: the stream's opening bytes describe the video and must not be lost.
        socket = VarchClient().openPath(pairing.endpoint, pairing.token, "/v1/cast/phone", object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (!ended) startEncoder() }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { end(null) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = when (response?.code) {
                    409 -> "Another phone is already casting to the desktop."
                    503 -> "The desktop could not open a viewer. Check that mpv is installed."
                    401 -> "This phone is no longer paired."
                    // No response means the link dropped, which is also how a closed viewer looks.
                    else -> if (codec == null) "Can't reach the desktop." else null
                }
                main.post { end(message) }
            }
        })
        return START_NOT_STICKY
    }

    private fun startEncoder() {
        val projection = projection ?: return end("Screen capture was not allowed.")
        val (width, height, dpi) = captureSize()
        try {
            worker = HandlerThread("varch-cast").also { it.start() }
            val input = openEncoder(width, height)

            // Android 14 requires a callback before a display is created.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { end(null) }
                }
            }, main)
            display = projection.createVirtualDisplay(
                "varch-cast", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null,
            )
            size = Triple(width, height, dpi)
            getSystemService(DisplayManager::class.java).registerDisplayListener(rotation, main)
        } catch (e: Exception) {
            // Encoders differ between phones, and a refused size or a revoked grant both surface here.
            end("This phone could not start screen capture.")
        }
    }

    /** Starts an encoder for frames of the given size and returns the surface it reads from. */
    private fun openEncoder(width: Int, height: Int): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            // A still screen produces no frames; repeating the last one keeps the viewer fed.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        // An encoder replaced after a rotation must not write into its successor's stream.
                        if (buffer != null && info.size > 0 && codec === this@CastService.codec) {
                            buffer.position(info.offset).limit(info.offset + info.size)
                            val vital = info.flags and (MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            send(codec, buffer.toByteString(), vital)
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: IllegalStateException) {
                        // The encoder was released while this callback was queued.
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    main.post { if (codec === this@CastService.codec) end("The phone's video encoder failed.") }
                }
            }, Handler(worker!!.looper))
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = encoder.createInputSurface()
            // Before the start: the encoder's first output describes the video and must pass the check above.
            codec = encoder
            surface = input
            encoder.start()
            return input
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
    }

    /**
     * Follows the screen when it turns. The stream takes the screen's new
     * shape, so the viewer fills its window instead of showing a landscape
     * picture boxed inside a portrait one. A projection allows one display
     * only, so the display is kept and handed a new encoder.
     */
    private fun resize() {
        val display = display ?: return
        val next = captureSize()
        if (ended || next == size) return
        val (width, height, dpi) = next
        val oldCodec = codec
        val oldSurface = surface
        try {
            val input = openEncoder(width, height)
            display.resize(width, height, dpi)
            display.surface = input
            size = next
            congested = false
        } catch (e: Exception) {
            end("This phone could not follow the screen's rotation.")
        } finally {
            runCatching { oldCodec?.stop() }
            runCatching { oldCodec?.release() }
            runCatching { oldSurface?.release() }
        }
    }

    /**
     * Sends one encoded buffer. When the link falls behind, ordinary frames
     * are dropped and a fresh keyframe is requested, which costs a moment of
     * artefacts instead of an ever-growing delay.
     */
    private fun send(codec: MediaCodec, bytes: okio.ByteString, vital: Boolean) {
        val socket = socket ?: return
        if (socket.queueSize() > MAX_QUEUED_BYTES && !vital) {
            if (!congested) {
                congested = true
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }
            return
        }
        congested = false
        socket.send(bytes)
    }

    /** The screen size as it is turned now, scaled so its longer side is at most [MAX_SIDE], with even dimensions. */
    private fun captureSize(): Triple<Int, Int, Int> {
        // Read from the display itself: a service's own metrics do not follow rotation.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val (w, h) = metrics.widthPixels to metrics.heightPixels
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        fun even(value: Int) = ((value * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return Triple(even(w), even(h), resources.displayMetrics.densityDpi)
    }

    /** Stops everything. Safe to call more than once and from any state. */
    private fun end(message: String?) {
        if (ended) return
        ended = true
        casting = false
        if (message != null) error = message
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotation)
        runCatching { display?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        runCatching { projection?.stop() }
        socket?.close(1000, null)
        worker?.quitSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        end(null)
        super.onDestroy()
    }

    private fun notification(host: String) = run {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_cast), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.cast_notification, host))
            .setOngoing(true)
            .addAction(0, getString(R.string.cast_stop), stop)
            .build()
    }

    companion object {
        /** Whether a cast is running, for the UI. */
        var casting by mutableStateOf(false)
            private set

        /** Why the last cast ended, when it ended on its own. */
        var error by mutableStateOf<String?>(null)
            private set

        /** Starts casting with the grant returned by the system's screen-capture prompt. */
        fun start(context: Context, grant: Intent) {
            error = null
            context.startForegroundService(Intent(context, CastService::class.java).putExtra(EXTRA_GRANT, grant))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }

        private const val ACTION_STOP = "dev.varch.controller.STOP_CAST"
        private const val EXTRA_GRANT = "grant"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 2
        private const val MAX_SIDE = 1280
        private const val BIT_RATE = 4_000_000
        private const val FRAME_RATE = 30
        private const val KEYFRAME_SECONDS = 2
        private const val MAX_QUEUED_BYTES = 1_500_000L
    }
}
