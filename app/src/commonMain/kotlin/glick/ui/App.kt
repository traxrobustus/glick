package glick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glick.core.AudioOutput
import glick.core.Metronome
import glick.core.MidiPort
import glick.core.SLEEP_BPM
import glick.core.SongRef
import glick.core.song.SongSession
import kotlinx.coroutines.flow.StateFlow

/** Local date and time of a log entry, e.g. "2026-11-20 14:03". */
expect fun formatDateTime(epochMillis: Long): String

@Composable
internal fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

@Composable
fun App(
    metronome: Metronome,
    midiPorts: () -> List<MidiPort>,
    audio: AudioOutput? = null,
    song: SongSession? = null,
    pickSong: (suspend () -> SongRef?)? = null,
) {
    val state by metronome.state.collectAsState()
    val transport = metronome.transport.collectAsState()
    val playing by remember { derivedStateOf { transport.value.playing } }
    var showLog by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showPatterns by remember { mutableStateOf(false) }
    var showSong by remember { mutableStateOf(false) }
    // A song opened for the first time, waiting for the user to say whether its tempo is known.
    var tempoQuestion by remember { mutableStateOf<SongRef?>(null) }
    val audioStatus = audio?.status?.collectAsState()?.value
    val audioRunning = audioStatus?.running == true

    // A song is prepared for one sample rate; when the device's rate changes it is prepared again.
    val sampleRate = audioStatus?.sampleRate?.takeIf { it > 0 } ?: 48_000
    LaunchedEffect(song, sampleRate) {
        val loaded = song?.view?.value?.ref ?: return@LaunchedEffect
        if (song.preparedSampleRate != 0 && song.preparedSampleRate != sampleRate) song.load(loaded, sampleRate)
    }

    // The audio thread owns the clock. Without sound, display frames keep the
    // transport (and so the timers and the playhead) moving.
    LaunchedEffect(metronome, audioRunning) {
        if (audioRunning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            metronome.advance(now - last)
            last = now
        }
    }

    val colors = darkColorScheme(
        primary = Palette.Beat,
        background = Palette.Background,
        surface = Palette.Panel,
        surfaceContainer = Palette.Panel,
        onSurface = Palette.Text,
    )
    MaterialTheme(colorScheme = colors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
            val compact = maxWidth < 900.dp
            val gap = Arrangement.spacedBy(12.dp)
            val dim = Modifier.alpha(if (state.sleepMode) 0.4f else 1f)

            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp), gap) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("glick", color = Palette.Beat, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Label(
                        if (state.sleepMode) "Sleep mode · steady ${SLEEP_BPM.toInt()} BPM click"
                        else "${formatNumber(state.bpm, 1)} BPM · ${state.signature}",
                        Modifier.weight(1f),
                        Palette.Muted,
                    )
                    TextButton(
                        "Sleep mode",
                        onClick = { metronome.update { it.copy(sleepMode = !it.sleepMode) } },
                        selected = state.sleepMode,
                        color = Palette.Poly,
                    )
                    if (song != null && !compact) {
                        TextButton("Song", onClick = { showSong = !showSong }, selected = showSong)
                    }
                    if (audio != null) {
                        TextButton(
                            if (audioRunning) "Audio" else "Audio: no sound",
                            onClick = { showAudio = true },
                            selected = !audioRunning,
                            color = Palette.Accent,
                        )
                    }
                    TextButton("Activity log", onClick = { showLog = true })
                }

                val tempo = @Composable { m: Modifier -> TempoPanel(state, playing, metronome::update, metronome::toggle, m) }
                val signature = @Composable { m: Modifier -> SignaturePanel(state, metronome::update, m.then(dim)) }
                val silence = @Composable { m: Modifier -> SilencePanel(state, metronome::update, m.then(dim)) }
                val sequencer = @Composable { m: Modifier ->
                    Panel(
                        "Pattern · tap a step: normal, accent, mute · M mutes a lane",
                        m.then(dim),
                        trailing = { TextButton("Patterns", onClick = { showPatterns = true }) },
                    ) {
                        Sequencer(
                            state, state.sleepMode, { transport.value }, metronome::update, compact,
                            outputDelayMillis = if (audioRunning) audioStatus?.latencyMillis ?: 0 else 0,
                        )
                    }
                }
                val circle = @Composable { m: Modifier -> CirclePanel(state, transport, metronome, m.then(dim)) }
                val session = @Composable { m: Modifier -> SessionPanel(state, transport, metronome, m) }
                val midi = @Composable { m: Modifier -> MidiPanel(state, metronome::update, midiPorts, m.then(dim)) }
                val songView = @Composable { m: Modifier ->
                    if (song != null && pickSong != null) {
                        SongPanel(
                            song, metronome, state, { transport.value }, pickSong,
                            onOpen = { ref -> if (song.hasMap(ref)) song.load(ref, sampleRate) else tempoQuestion = ref },
                            outputDelayMillis = if (audioRunning) audioStatus?.latencyMillis ?: 0 else 0,
                            compact, m.then(dim),
                        )
                    }
                }
                val wide = Modifier.fillMaxWidth()

                if (compact) {
                    // On a phone each desktop column becomes a tab.
                    var tab by remember { mutableStateOf(0) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val tabs = listOf("Tempo", "Pattern", "Timers") + if (song != null) listOf("Song") else emptyList()
                        tabs.forEachIndexed { index, name ->
                            TextButton(name, { tab = index }, Modifier.weight(1f), selected = tab == index)
                        }
                        TextButton(
                            if (playing) "Stop" else "Start",
                            onClick = metronome::toggle,
                            modifier = Modifier.weight(1f),
                            selected = true,
                            color = if (playing) Palette.Accent else Palette.Beat,
                        )
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), gap) {
                        when (tab) {
                            0 -> { tempo(wide); signature(wide); silence(wide) }
                            1 -> sequencer(wide)
                            2 -> { circle(wide); session(wide); midi(wide) }
                            else -> songView(wide)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxSize(), gap) {
                        Column(Modifier.width(270.dp).verticalScroll(rememberScrollState()), gap) {
                            tempo(wide); signature(wide); silence(wide)
                        }
                        if (showSong) {
                            // The song view takes the place of the pattern and timer columns.
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), gap) {
                                songView(wide)
                            }
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), gap) {
                                sequencer(wide)
                            }
                            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()), gap) {
                                circle(wide); session(wide); midi(wide)
                            }
                        }
                    }
                }
            }

            if (showLog) LogOverlay(metronome) { showLog = false }
            if (showPatterns) PatternOverlay(metronome) { showPatterns = false }
            if (showAudio && audio != null) AudioOverlay(audio) { showAudio = false }
            tempoQuestion?.let { ref ->
                SongTempoOverlay(
                    ref,
                    initialSignature = state.signature,
                    onLoad = { setup ->
                        tempoQuestion = null
                        song?.load(ref, sampleRate, setup)
                    },
                    onCancel = { tempoQuestion = null },
                )
            }
        }
    }
}
