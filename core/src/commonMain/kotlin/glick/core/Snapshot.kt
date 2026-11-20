package glick.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the desktop window was. */
@Serializable
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** How far the timers had run. */
@Serializable
data class TimerProgress(
    val sessionElapsedSeconds: Double = 0.0,
    val sessionFinished: Boolean = false,
    val circleElapsedSeconds: Double = 0.0,
    val circleBars: Int = 0,
    val circleCompletions: Int = 0,
)

/**
 * Everything needed to bring the app back as it was closed. Fields added in
 * later versions get their default when an older file is read.
 */
@Serializable
data class Snapshot(
    val version: Int = 1,
    val state: MetronomeState = MetronomeState(),
    val timers: TimerProgress = TimerProgress(),
    /** Identifier of the song that was loaded, if any. */
    val song: SongRef? = null,
    val window: WindowBounds? = null,
) {
    fun encode(): String = format.encodeToString(serializer(), this)

    companion object {
        private val format = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Reads a snapshot; anything unreadable gives null, so the app starts with defaults. */
        fun decode(text: String?): Snapshot? =
            if (text.isNullOrBlank()) null
            else runCatching { format.decodeFromString(serializer(), text) }.getOrNull()
                ?.let { it.copy(state = it.state.sanitized()) }
    }
}

/** An audio file the user chose: a platform locator and the name to show. */
@Serializable
data class SongRef(val id: String, val name: String)

/** Small named text documents kept between runs (the snapshot, one project per song). */
interface TextStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

class InMemoryTextStore : TextStore {
    private val documents = mutableMapOf<String, String>()
    override fun read(name: String): String? = documents[name]
    override fun write(name: String, text: String) { documents[name] = text }
}
