package glick

import glick.core.SongRef
import glick.core.song.AudioFileDecoder
import glick.core.song.PcmSink
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.awt.FileDialog
import java.awt.Frame
import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** Opens the system file chooser for an audio file. */
fun pickSongFile(): SongRef? {
    val dialog = FileDialog(null as Frame?, "Open an audio file", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in SUPPORTED }
    dialog.file = SUPPORTED.joinToString(";") { "*.$it" }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val file = File(dialog.directory, name)
    return SongRef(id = file.absolutePath, name = file.name)
}

private val SUPPORTED = listOf("wav", "flac", "mp3")

/** Decodes WAV with Java Sound, MP3 with JLayer and FLAC with jFLAC. */
class DesktopAudioDecoder : AudioFileDecoder {
    override fun fingerprint(id: String): String = File(id).let { "${it.length()}-${it.lastModified()}" }

    override fun decode(id: String, sink: PcmSink, progress: (Float) -> Unit) {
        val file = File(id)
        require(file.isFile) { "the file is no longer there" }
        when (file.extension.lowercase()) {
            "mp3" -> decodeMp3(file, sink, progress)
            "flac" -> decodeFlac(file, sink, progress)
            else -> decodeWav(file, sink, progress)
        }
    }

    private fun decodeWav(file: File, sink: PcmSink, progress: (Float) -> Unit) {
        AudioSystem.getAudioInputStream(file).use { original ->
            val base = original.format
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false)
            require(AudioSystem.isConversionSupported(target, base)) { "this kind of WAV file is not supported" }
            AudioSystem.getAudioInputStream(target, original).use { pcm ->
                val bytes = ByteArray(8192 * target.frameSize)
                val shorts = ShortArray(bytes.size / 2)
                val total = file.length().toFloat()
                var done = 0L
                while (true) {
                    var filled = 0
                    while (filled < bytes.size) {
                        val n = pcm.read(bytes, filled, bytes.size - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    val frames = filled / target.frameSize
                    if (frames == 0) break
                    for (i in 0 until frames * base.channels) {
                        shorts[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                    }
                    sink.write(base.sampleRate.toInt(), base.channels, shorts, frames)
                    done += filled
                    progress((done / total).coerceAtMost(1f))
                    if (filled < bytes.size) break
                }
            }
        }
    }

    private fun decodeMp3(file: File, sink: PcmSink, progress: (Float) -> Unit) {
        val total = file.length().toFloat()
        file.inputStream().use { raw ->
            val counted = raw.channel
            val stream = Bitstream(BufferedInputStream(raw, 1 shl 16))
            val decoder = Decoder()
            var frameCount = 0
            try {
                while (true) {
                    val header = stream.readFrame() ?: break
                    val buffer = decoder.decodeFrame(header, stream) as SampleBuffer
                    val channels = buffer.channelCount
                    sink.write(buffer.sampleFrequency, channels, buffer.buffer, buffer.bufferLength / channels)
                    stream.closeFrame()
                    if (++frameCount % 200 == 0) progress((counted.position() / total).coerceAtMost(1f))
                }
            } finally {
                stream.close()
            }
        }
    }

    private fun decodeFlac(file: File, sink: PcmSink, progress: (Float) -> Unit) {
        file.inputStream().buffered(1 shl 16).use { input ->
            val decoder = FLACDecoder(input)
            decoder.addPCMProcessor(object : PCMProcessor {
                private var info: StreamInfo? = null
                private var shorts = ShortArray(0)
                private var done = 0L

                override fun processStreamInfo(streamInfo: StreamInfo) {
                    info = streamInfo
                }

                override fun processPCM(pcm: ByteData) {
                    val stream = checkNotNull(info)
                    val bytesPerSample = stream.bitsPerSample / 8
                    require(bytesPerSample in 2..3) { "only 16 and 24 bit FLAC files are supported" }
                    val samples = pcm.len / bytesPerSample
                    if (shorts.size < samples) shorts = ShortArray(samples)
                    val data = pcm.data
                    // Little-endian samples; the top two bytes of each are the 16 bits to keep.
                    for (i in 0 until samples) {
                        val top = i * bytesPerSample + bytesPerSample - 2
                        shorts[i] = ((data[top].toInt() and 0xff) or (data[top + 1].toInt() shl 8)).toShort()
                    }
                    val frames = samples / stream.channels
                    sink.write(stream.sampleRate, stream.channels, shorts, frames)
                    done += frames
                    if (stream.totalSamples > 0) progress((done.toFloat() / stream.totalSamples).coerceAtMost(1f))
                }
            })
            decoder.decode()
        }
    }
}
