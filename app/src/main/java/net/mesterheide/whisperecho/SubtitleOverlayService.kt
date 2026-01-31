package net.mesterheide.whisperecho

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.LinkedList

class SubtitleOverlayService : Service() {
    companion object {
        const val UDP_PORT = 5555              // UDP listening port
        const val MAX_VISIBLE = 2              // Number of newest subtitles to display
        const val MAX_BUFFER_SIZE = 50         // Maximum subtitles to keep in buffer
        const val SUBTITLE_TEXT_SIZE_SP = 28f  // Text size as scale-independent pixels
        const val FADE_DELAY_MS = 10_000L      // Time before fade starts (10 seconds)
        const val FADE_DURATION_MS = 500L      // Fade animation duration (0.5 seconds)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var subtitleView: TextView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var fadeJob: Job? = null

    data class Subtitle(
        val id: Int,
        var text: String,
        var startTimeMs: Long,
        var endTimeMs: Long
    )

    private val subtitleBuffer = object {
        private val buffer = LinkedList<Subtitle>()
        private val maxSize = MAX_BUFFER_SIZE

        @Synchronized
        fun addOrUpdate(sub: Subtitle) {
            // Find index of existing subtitle with same ID
            val index = buffer.indexOfFirst { it.id == sub.id }

            if (index != -1) {
                val existing = buffer[index]
                // Only replace if the new subtitle is newer
                if (sub.endTimeMs >= existing.endTimeMs) {
                    buffer[index] = sub // replace at same position
                }
                // Older subtitle → ignore
            } else {
                // New subtitle → append
                buffer.addLast(sub)
            }

            // Evict oldest if buffer exceeds maxSize
            while (buffer.size > maxSize) {
                buffer.removeFirst()
            }
        }


        @Synchronized
        fun getNewest(n: Int): List<Subtitle> {
            // last N items in normal order
            val start = maxOf(0, buffer.size - n)
            return buffer.subList(start, buffer.size)
        }

        @Synchronized
        fun clear() {
            buffer.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()

        startUdpListener()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        subtitleView = TextView(this).apply {
            text = "Hello subtitles 👋"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            textSize = SUBTITLE_TEXT_SIZE_SP
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80 // distance from bottom
        }

        windowManager.addView(subtitleView, params)
    }

    private fun startUdpListener() {
        serviceScope.launch {
            val socket = DatagramSocket(UDP_PORT)
            socket.broadcast = true

            val buffer = ByteArray(8192)

            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val message = String(
                    packet.data,
                    0,
                    packet.length,
                    Charsets.UTF_8
                )

                handleIncomingSrt(message)
            }
        }
    }

    private fun handleIncomingSrt(srt: String) {
        val subtitle = parseSrt(srt) ?: return
        subtitleBuffer.addOrUpdate(subtitle)

        val newest = subtitleBuffer.getNewest(MAX_VISIBLE)
        CoroutineScope(Dispatchers.Main).launch {
            updateOverlay(newest)
            startFadeCountdown()
        }
    }

    private fun updateOverlay(subtitles: List<Subtitle>) {
        val combinedText = subtitles.joinToString("\n") { it.text }
        subtitleView.text = combinedText
    }

    private fun startFadeCountdown() {
        // Cancel previous fade timer if running
        fadeJob?.cancel()

        // Reset alpha to fully visible
        subtitleView.alpha = 1f

        // Start fade after delay
        fadeJob = CoroutineScope(Dispatchers.Main).launch {
            delay(SubtitleOverlayService.FADE_DELAY_MS) // wait 10s
            subtitleView.animate()
                .alpha(0f)
                .setDuration(SubtitleOverlayService.FADE_DURATION_MS)
                .start()
        }
    }

    fun parseSrt(srt: String): Subtitle? {
        val lines = srt.trim().lines()
        if (lines.size < 3) return null // ID, timestamps, text

        // Line 0 → subtitle ID
        val id = lines[0].toIntOrNull() ?: return null

        // Line 1 → timestamps "HH:MM:SS,mmm --> HH:MM:SS,mmm"
        val times = lines[1].split("-->")
        if (times.size != 2) return null
        val startTimeMs = parseTimestamp(times[0].trim())
        val endTimeMs = parseTimestamp(times[1].trim())

        // Lines 2+ → subtitle text
        val text = lines.drop(2).joinToString("\n")

        return Subtitle(id, text, startTimeMs, endTimeMs)
    }

    private fun parseTimestamp(ts: String): Long {
        val clean = ts.replace(',', '.')
        val parts = clean.split(":") // ["HH", "MM", "SS.mmm"]
        if (parts.size != 3) return 0L

        val hours = parts[0].toLongOrNull() ?: 0
        val minutes = parts[1].toLongOrNull() ?: 0
        val secondsParts = parts[2].split(".")
        val seconds = secondsParts[0].toLongOrNull() ?: 0
        val millis = secondsParts.getOrNull(1)?.toLongOrNull() ?: 0

        return ((hours * 3600 + minutes * 60 + seconds) * 1000) + millis
    }

    override fun onDestroy() {
        windowManager.removeView(subtitleView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
