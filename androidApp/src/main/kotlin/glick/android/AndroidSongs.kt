package glick.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import glick.core.song.AudioFileDecoder
import glick.core.song.PcmSink
import java.nio.ByteOrder

/** The display name and size of a document the user picked. */
internal fun Context.describe(uri: Uri): Pair<String, Long> {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val name = cursor.getString(0) ?: "song"
            val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            return name to size
        }
    }
    return (uri.lastPathSegment ?: "song") to 0L
}

/** Decodes any audio format the phone supports, through the platform's own codecs. */
class AndroidAudioDecoder(private val context: Context) : AudioFileDecoder {
    override fun fingerprint(id: String): String =
        runCatching { context.describe(Uri.parse(id)).second.toString() }.getOrDefault("0")

    override fun decode(id: String, sink: PcmSink, progress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(id), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("the file contains no audio")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val duration = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(input, null, null, 0)
            decoder.start()

            var sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var shorts = ShortArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (out >= 0) {
                    val samples = info.size / 2
                    if (samples > 0) {
                        if (shorts.size < samples) shorts = ShortArray(samples)
                        val buffer = decoder.getOutputBuffer(out)!!
                        buffer.position(info.offset)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, samples)
                        sink.write(sampleRate, channels, shorts, samples / channels)
                        if (duration > 0) progress((info.presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f))
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
