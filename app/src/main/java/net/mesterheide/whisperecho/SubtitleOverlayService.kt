package net.mesterheide.whisperecho

import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.ColorInt
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
        const val MAX_LINES = 3                // Maximum number of lines to display
        const val MAX_BUFFER_SIZE = 10         // Maximum subtitles to keep in buffer
        const val SUBTITLE_TEXT_SIZE_SP = 28f  // Text size as scale-independent pixels
        const val FADE_DELAY_MS = 10_000L      // Time before fade starts (10 seconds)
        const val FADE_DURATION_MS = 500L      // Fade animation duration (0.5 seconds)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var subtitleView: TextView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var fadeJob: Job? = null

    data class Subtitle(
        val id: Int, var text: String, var startTimeMs: Long, var endTimeMs: Long
    )

    open class SubtitleSpan(
        val subtitleId: Int,
        @ColorInt protected open val bgColor: Int,
        @ColorInt protected open val textColor: Int
    ) : CharacterStyle(), UpdateAppearance {

        override fun updateDrawState(tp: TextPaint) {
            tp.bgColor = bgColor
            tp.color = textColor
        }
    }

    class FadingSpan(
        subtitleId: Int, @ColorInt bgColor: Int, @ColorInt textColor: Int
    ) : SubtitleSpan(subtitleId, bgColor, textColor) {

        var alpha: Int = 255

        override fun updateDrawState(tp: TextPaint) {
            super.updateDrawState(tp)
            tp.color = (tp.color and 0x00FFFFFF) or (alpha shl 24)
        }
    }

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

        val displayMetrics = resources.displayMetrics
        val windowWidth = (displayMetrics.widthPixels * 0.6).toInt() // 60% of screen width

        subtitleView = TextView(this).apply {
            setText(
                SpannableStringBuilder("Hello subtitles 👋").apply {
                    setSpan(
                        SubtitleSpan(
                            0, getColor(R.color.subtitle_bg), getColor(R.color.subtitle_fg)
                        ), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }, TextView.BufferType.SPANNABLE
            )
            //setLineSpacing(0f, 1.5f) // 8px gap between lines
            setTextColor(Color.WHITE)
            textSize = SUBTITLE_TEXT_SIZE_SP
            gravity = Gravity.START
        }

        val params = WindowManager.LayoutParams(
            windowWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
                    packet.data, 0, packet.length, Charsets.UTF_8
                )

                handleIncomingSrt(message)
            }
        }
    }

    private fun handleIncomingSrt(srt: String) {
        val subtitle = parseSrt(srt) ?: return
        subtitleBuffer.addOrUpdate(subtitle)

        val subtitles = subtitleBuffer.getNewest(MAX_BUFFER_SIZE / 2).toTypedArray()
        CoroutineScope(Dispatchers.Main).launch {
            updateOverlay(subtitles)
            startFadeCountdown()
        }
    }

    private fun updateOverlay(subtitles: Array<Subtitle>) {
        // Get current text from the TextView
        val currentText = SpannableString(subtitleView.text)
        val currentLineCount = subtitleView.lineCount

        // Find the last SubtitleSpan in the current text
        val lastSpan = currentText?.getSpans(0, currentText.length, SubtitleSpan::class.java)
            ?.maxByOrNull { currentText.getSpanStart(it) }

        // Initialize the index in the subtitles list from where we will append new subtitles
        var startIndex = 0

        // Try to find the subtitle corresponding to the last subtitle span
        val lastSubtitle = lastSpan?.let { span ->
            subtitles.find { it.id == span.subtitleId }
        }

        // Update the start index if a matching subtitle is found (redraw lastSpan!)
        if (lastSubtitle != null) {
            startIndex = subtitles.indexOf(lastSubtitle)
        }

        // Create the text to append: subtitles from startIndex to end of list
        val newText = SpannableStringBuilder()
        for (i in startIndex until subtitles.size) {
            val subtitle = subtitles[i]

            if (newText.isNotEmpty()) newText.append(' ') // space delimiter

            val start = newText.length
            newText.append(subtitle.text)
            val end = newText.length

            // Always add the identity / styling span
            newText.setSpan(
                SubtitleSpan(
                    subtitle.id, getColor(R.color.subtitle_bg), getColor(R.color.subtitle_fg)
                ), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            when {
                // First render: always fade
                lastSubtitle == null || subtitle.id > lastSubtitle.id -> {
                    newText.setSpan(
                        FadingSpan(
                            subtitle.id,
                            getColor(R.color.subtitle_bg),
                            getColor(R.color.subtitle_fg)
                        ), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // Seen and latest: fade changes
                subtitle.id == lastSubtitle.id && subtitle == subtitles.last() -> {
                    val oldText = lastSpan?.let { span ->
                        currentText?.subSequence(
                            currentText.getSpanStart(span), currentText.getSpanEnd(span)
                        )?.toString()
                    } ?: ""
                    val newTextValue = subtitle.text

                    // Find the unchanged prefix
                    val commonPrefixLength = oldText.commonPrefixWith(newTextValue).length

                    // If there is newly appended text, fade only that part
                    if (commonPrefixLength < newTextValue.length) {
                        val fadeStart = start + commonPrefixLength

                        newText.setSpan(
                            FadingSpan(
                                subtitle.id,
                                getColor(R.color.subtitle_bg),
                                getColor(R.color.subtitle_fg)
                            ), fadeStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        // TODO: Replace prefix comparison with Levenshtein-based diff
                        //       to handle mid-string edits more accurately.
                    }
                }
            }
        }

        // If no last span or subtitle was found, just set the TextView and exit
        if (lastSpan == null || lastSubtitle == null) {
            val newTextLayout = computeSubtitleLayout(newText)
            if (newTextLayout.lineCount > MAX_LINES) {
                newText.delete(0, newTextLayout.getLineStart(newTextLayout.lineCount - MAX_LINES))
            }
            subtitleView.text = newText
            newText.getSpans(0, newText.length, FadingSpan::class.java)
                .forEach { fadeInSpan(it, subtitleView) }
            return
        }

        // Prepend current text up to the last span plus a space delimiter
        val lastSpanStart = currentText.getSpanStart(lastSpan)
        val previousText = SpannableString(currentText.subSequence(0, lastSpanStart))
        previousText.getSpans(
            0, previousText.length, FadingSpan::class.java
        ).forEach { previousText.removeSpan(it) }

        // Define the builder that will hold the final spannable text
        val builder = SpannableStringBuilder(previousText)
        if (builder.isNotEmpty() && builder.last() != ' ') {
            builder.append(' ')
        }
        builder.append(newText)

        // Compute line differences
        val layout = computeSubtitleLayout(builder)
        var newLineCount = layout.lineCount
        var addedLines = newLineCount - currentLineCount

        // Handle removed lines (negative addedLines)
        if (addedLines < 0) {
            repeat(-addedLines) {
                builder.append("\n")
                newLineCount++
            } // pad the builder to keep line count consistent
        }

        // If the new text fits in the max lines, set it and exit
        if (newLineCount <= MAX_LINES) {
            subtitleView.text = builder
            builder.getSpans(0, builder.length, FadingSpan::class.java)
                .forEach { fadeInSpan(it, subtitleView) }
            return
        }

        // --- Sliding animation needed ---
        // Compute how many lines need to be removed from the top
        val linesToRemove = newLineCount - MAX_LINES

        // Find character position of the first newly visible line using layout
        val firstVisibleCharIndex = layout.getLineStart(linesToRemove)

        // Remove characters from beginning of builder
        builder.delete(0, firstVisibleCharIndex)

        // Prepare temporary text from the current text with same number of lines removed
        val tempText = SpannableStringBuilder(currentText)
        val currentLayout = computeSubtitleLayout(currentText)
        val currentFirstCharToRemove =
            currentLayout.getLineStart(linesToRemove.coerceAtMost(currentLayout.lineCount))
        tempText.delete(0, currentFirstCharToRemove)

        subtitleView.text = builder
        builder.getSpans(
            0, builder.length, FadingSpan::class.java
        ).forEach { fadeInSpan(it, subtitleView) }

//        // --- Animate sliding effect ---
//        subtitleView.text = tempText
//        subtitleView.post {
//            val transitionDistance = subtitleView.lineHeight * linesToRemove
//            subtitleView.animate().translationY(-transitionDistance.toFloat())
//                .setDuration((linesToRemove * 100L).toLong()).withEndAction {
//                    // Reset position and set final text
//                    subtitleView.translationY = 0f
//                    subtitleView.text = builder
//                    builder.getSpans(0, builder.length, FadingSpan::class.java)
//                        .forEach { fadeInSpan(it, subtitleView) }
//                }.start()
//        }
    }

    fun fadeInSpan(span: FadingSpan, textView: TextView) {
        span.alpha = 0

        ValueAnimator.ofInt(0, 255).apply {
            duration = 250L
            addUpdateListener { anim ->
                span.alpha = anim.animatedValue as Int
                textView.invalidate()
            }
            start()
        }
    }

    private fun computeSubtitleLayout(
        text: CharSequence
    ): StaticLayout {
        val width = subtitleView.width.takeIf { it > 0 } ?: subtitleView.measuredWidth

        return StaticLayout.Builder.obtain(text, 0, text.length, subtitleView.paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(
                subtitleView.lineSpacingExtra, subtitleView.lineSpacingMultiplier
            ).setBreakStrategy(subtitleView.breakStrategy)
            .setHyphenationFrequency(subtitleView.hyphenationFrequency).build()
    }

    private fun startFadeCountdown() {
        // Cancel previous fade timer if running
        fadeJob?.cancel()

        // Reset alpha to fully visible
        subtitleView.alpha = 1f

        // Start fade after delay
        fadeJob = CoroutineScope(Dispatchers.Main).launch {
            delay(SubtitleOverlayService.FADE_DELAY_MS) // wait 10s
            subtitleView.animate().alpha(0f).setDuration(SubtitleOverlayService.FADE_DURATION_MS)
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
        val text = lines.drop(2).joinToString("\n").trim()

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
