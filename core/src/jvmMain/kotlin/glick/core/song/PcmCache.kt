package glick.core.song

import glick.core.SongRef
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

private const val MAGIC = 0x474C5043 // "GLPC"
private const val HEADER_BYTES = 20

/**
 * A decoded song on disk, mapped into memory: 16-bit interleaved samples
 * behind a small header. The operating system pages it in as it is played, so
 * a long song does not have to fit in the app's heap.
 */
class MappedAudioSource(file: File) : AudioSource {
    override val sampleRate: Int
    override val channels: Int
    override val frames: Long
    private val samples: ShortBuffer

    init {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= HEADER_BYTES) { "not a glick audio cache" }
            val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            require(mapped.int == MAGIC) { "not a glick audio cache" }
            sampleRate = mapped.int
            channels = mapped.int
            frames = mapped.long
            require(raf.length() == HEADER_BYTES + frames * channels * 2) { "audio cache is incomplete" }
            mapped.position(HEADER_BYTES)
            samples = mapped.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
    }

    override fun read(start: Long, left: FloatArray, right: FloatArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            val frame = start + i
            if (frame < 0 || frame >= frames) {
                left[offset + i] = 0f
                right[offset + i] = 0f
            } else {
                val at = (frame * channels).toInt()
                val l = samples.get(at) / 32768f
                left[offset + i] = l
                right[offset + i] = if (channels == 2) samples.get(at + 1) / 32768f else l
            }
        }
    }
}

/**
 * Decodes songs once and keeps the result, converted to the playback sample
 * rate, in [cacheDirectory]. Opening a song again is then immediate.
 */
class CachingSongLibrary(private val decoder: AudioFileDecoder, private val cacheDirectory: File) : SongLibrary {

    override fun open(ref: SongRef, sampleRate: Int, progress: (Float) -> Unit): AudioSource {
        val file = File(cacheDirectory, cacheName(ref, sampleRate))
        runCatching { if (file.isFile) return MappedAudioSource(file).also { file.setLastModified(System.currentTimeMillis()) } }
        cacheDirectory.mkdirs()
        val temporary = File(cacheDirectory, file.name + ".tmp")
        try {
            convert(ref, sampleRate, temporary, progress)
            check(temporary.renameTo(file) || (file.delete() && temporary.renameTo(file))) { "could not store the decoded song" }
        } finally {
            temporary.delete()
        }
        prune(keep = file)
        return MappedAudioSource(file)
    }

    private fun cacheName(ref: SongRef, sampleRate: Int): String {
        val key = "${ref.id}|${decoder.fingerprint(ref.id)}|$sampleRate"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) } + ".pcm"
    }

    private fun convert(ref: SongRef, targetRate: Int, output: File, progress: (Float) -> Unit) {
        var frames = 0L
        var outChannels = 0
        var resamplers: Array<Resampler>? = null
        var channelIn = FloatArray(0)
        var channelOut = FloatArray(0)
        var interleaved = ShortArray(0)

        DataOutputStream(BufferedOutputStream(output.outputStream(), 1 shl 16)).use { out ->
            out.write(ByteArray(HEADER_BYTES))
            val bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

            fun emit(count: Int) {
                var at = 0
                while (at < count * outChannels) {
                    bytes.clear()
                    val n = minOf(bytes.capacity() / 2, count * outChannels - at)
                    bytes.asShortBuffer().put(interleaved, at, n)
                    out.write(bytes.array(), 0, n * 2)
                    at += n
                }
                frames += count
            }

            fun writeChannelBlock(channel: Int, count: Int, flush: Boolean): Int {
                val resampler = resamplers!![channel]
                val needed = if (flush) 4096 else count * targetRate / resampler.inputRate + 64
                if (channelOut.size < needed) channelOut = FloatArray(needed)
                val produced = if (flush) resampler.flush(channelOut) else resampler.process(channelIn, count, channelOut)
                if (interleaved.size < produced * outChannels) interleaved = ShortArray(produced * outChannels)
                for (i in 0 until produced) {
                    interleaved[i * outChannels + channel] = (channelOut[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                return produced
            }

            decoder.decode(ref.id, { rate, channels, samples, count ->
                if (resamplers == null) {
                    require(rate in 4000..384_000 && channels >= 1) { "unsupported audio format" }
                    outChannels = minOf(channels, 2)
                    resamplers = Array(outChannels) { Resampler(rate, targetRate) }
                }
                if (channelIn.size < count) channelIn = FloatArray(count)
                var produced = 0
                for (channel in 0 until outChannels) {
                    for (i in 0 until count) channelIn[i] = samples[i * channels + channel] / 32768f
                    produced = writeChannelBlock(channel, count, flush = false)
                }
                emit(produced)
            }, progress)

            val active = checkNotNull(resamplers) { "the file contains no audio" }
            var tail = 0
            for (channel in active.indices) tail = writeChannelBlock(channel, 0, flush = true)
            emit(tail)
        }
        check(frames > 0) { "the file contains no audio" }
        check(frames * outChannels * 2 < Int.MAX_VALUE - HEADER_BYTES) { "the song is too long" }

        RandomAccessFile(output, "rw").use { raf ->
            raf.writeInt(MAGIC)
            raf.writeInt(targetRate)
            raf.writeInt(outChannels)
            raf.writeLong(frames)
        }
    }

    /** Keeps the three most recently used songs; decoded audio is large. */
    private fun prune(keep: File) {
        cacheDirectory.listFiles { f -> f.name.endsWith(".pcm") && f != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }
    }
}
