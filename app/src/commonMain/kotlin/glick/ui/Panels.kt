package glick.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glick.core.AudioOutput
import glick.core.CircleAction
import glick.core.CircleMode
import glick.core.DENOMINATORS
import glick.core.MAX_BPM
import glick.core.MAX_NUMERATOR
import glick.core.MIN_BPM
import glick.core.Metronome
import glick.core.MetronomeState
import glick.core.MidiPort
import glick.core.ParamTarget
import glick.core.SLEEP_BPM
import glick.core.Transport
import kotlin.time.TimeMark
import kotlin.time.TimeSource

typealias Update = ((MetronomeState) -> MetronomeState) -> Unit

@Composable
fun TempoPanel(
    state: MetronomeState,
    playing: Boolean,
    update: Update,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val setBpm = { bpm: Double -> update { it.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM)) } }
    Panel("Tempo", modifier) {
        JogWheel(
            bpm = if (state.sleepMode) SLEEP_BPM else state.bpm,
            onBpmChange = setBpm,
            enabled = !state.sleepMode,
            modifier = Modifier.widthIn(max = 250.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (step in listOf(-10, -1, 1, 10)) {
                TextButton(
                    if (step > 0) "+$step" else "−${-step}",
                    onClick = { if (!state.sleepMode) setBpm(state.bpm + step) },
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 2.dp,
                )
            }
            var taps by remember { mutableStateOf(listOf<TimeMark>()) }
            TextButton(
                "Tap",
                onClick = {
                    val now = TimeSource.Monotonic.markNow()
                    val recent = taps.lastOrNull()?.let { it.elapsedNow().inWholeMilliseconds < 3000 } ?: false
                    taps = (if (recent) taps else emptyList()).takeLast(5) + now
                    if (taps.size >= 2 && !state.sleepMode) {
                        val span = taps.first().elapsedNow().inWholeMicroseconds / 1e6
                        setBpm(kotlin.math.round(60.0 * (taps.size - 1) / span))
                    }
                },
                modifier = Modifier.weight(1.3f),
            )
        }
        PlayButton(playing, onToggle)
    }
}

@Composable
private fun PlayButton(playing: Boolean, onToggle: () -> Unit) {
    val color = if (playing) Palette.Accent else Palette.Beat
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            if (playing) {
                drawRoundRect(Palette.Inset, cornerRadius = CornerRadius(2.dp.toPx()))
            } else {
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.1f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.1f, size.height)
                        close()
                    },
                    Palette.Inset,
                )
            }
        }
        Text(
            if (playing) "STOP" else "START",
            color = Palette.Inset,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
fun SignaturePanel(state: MetronomeState, update: Update, modifier: Modifier = Modifier) {
    Panel("Time signature", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Beats", Modifier.width(84.dp), Palette.Muted)
            Stepper(
                state.signature.numerator,
                { v -> update { it.copy(signature = it.signature.copy(numerator = v)) } },
                1..MAX_NUMERATOR,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Note length", Modifier.width(84.dp), Palette.Muted)
            ChoiceStepper(
                state.signature.denominator,
                DENOMINATORS,
                { v -> update { it.copy(signature = it.signature.copy(denominator = v)) } },
            )
        }
    }
}

@Composable
fun SilencePanel(state: MetronomeState, update: Update, modifier: Modifier = Modifier) {
    val silence = state.silence
    Panel(
        "Random silence",
        modifier,
        trailing = { Toggle(silence.enabled, { v -> update { it.copy(silence = it.silence.copy(enabled = v)) } }) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LevelSlider(
                silence.probability,
                { v -> update { it.copy(silence = it.silence.copy(probability = v)) } },
                Modifier.weight(1f),
            )
            Text(
                "${(silence.probability * 100).toInt()}%",
                color = if (silence.enabled) Palette.Text else Palette.Muted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(40.dp),
            )
        }
        Label("Chance that a beat drops out", color = Palette.Muted)
    }
}

@Composable
fun CirclePanel(
    state: MetronomeState,
    transport: State<Transport>,
    metronome: Metronome,
    modifier: Modifier = Modifier,
) {
    val circle = state.circle
    val update: Update = metronome::update
    val setCircle = { f: (glick.core.CircleTimer) -> glick.core.CircleTimer -> update { it.copy(circle = f(it.circle)) } }
    val completions by remember { derivedStateOf { transport.value.circleCompletions } }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(completions) {
        if (completions > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(450))
        }
    }

    Panel(
        "Circle timer",
        modifier,
        trailing = { Toggle(circle.enabled, { v -> setCircle { it.copy(enabled = v) } }) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(92.dp)) {
                    val stroke = 9.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawCircle(Palette.Beat.copy(alpha = 0.45f * flash.value), radius = size.minDimension / 2 - stroke)
                    drawArc(Palette.Inset, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(
                        if (circle.enabled) Palette.Beat else Palette.Off,
                        startAngle = -90f,
                        sweepAngle = 360f * transport.value.circleProgress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(completions.toString(), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("loops", color = Palette.Muted, fontSize = 10.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mode in CircleMode.entries) {
                        TextButton(mode.name, { setCircle { it.copy(mode = mode) } }, selected = circle.mode == mode)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Every", color = Palette.Muted)
                    if (circle.mode == CircleMode.Bars) {
                        Stepper(circle.bars, { v -> setCircle { it.copy(bars = v) } }, 1..9999)
                        Label("bars", color = Palette.Muted)
                    } else {
                        NumberField(circle.seconds, { v -> setCircle { it.copy(seconds = v) } }, 0.1, 86_400.0, decimals = 1, width = 64.dp)
                        Label("sec", color = Palette.Muted)
                    }
                }
                TextButton("Reset", onClick = metronome::resetCircle)
            }
        }
        CheckRow("Sound on completion", circle.sound, { v -> setCircle { it.copy(sound = v) } })

        Label("On completion", color = Palette.Muted)
        for (action in circle.actions) {
            ActionRow(
                action,
                onChange = { changed ->
                    setCircle { c -> c.copy(actions = c.actions.map { if (it.id == action.id) changed else it }) }
                },
                onRemove = { setCircle { c -> c.copy(actions = c.actions.filter { it.id != action.id }) } },
            )
        }
        TextButton("+ Add action", onClick = metronome::addCircleAction)
    }
}

@Composable
private fun ActionRow(action: CircleAction, onChange: (CircleAction) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Toggle(action.enabled, { onChange(action.copy(enabled = it)) })
        Box(Modifier.weight(1f)) {
            var open by remember { mutableStateOf(false) }
            TextButton(action.target.label, onClick = { open = true }, modifier = Modifier.fillMaxWidth())
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                for (target in ParamTarget.entries) {
                    DropdownMenuItem(
                        text = { Text(target.label, fontSize = 13.sp) },
                        onClick = {
                            open = false
                            onChange(action.copy(target = target, amount = target.defaultAmount))
                        },
                    )
                }
            }
        }
        NumberField(action.amount, { onChange(action.copy(amount = it)) }, -5000.0, 5000.0, decimals = 2, width = 58.dp)
        Label(action.target.unit, Modifier.width(32.dp), Palette.Muted)
        TextButton("×", onClick = onRemove)
    }
}

@Composable
fun SessionPanel(
    state: MetronomeState,
    transport: State<Transport>,
    metronome: Metronome,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    val update: Update = metronome::update
    val total = session.minutes * 60
    val remaining by remember(total) {
        derivedStateOf { kotlin.math.ceil(total - transport.value.sessionElapsedSeconds).coerceAtLeast(0.0) }
    }
    val finished by remember { derivedStateOf { transport.value.sessionFinished } }

    Panel(
        "Session timer",
        modifier,
        trailing = { Toggle(session.enabled, { v -> update { it.copy(session = it.session.copy(enabled = v)) } }) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                formatClock(remaining),
                color = when {
                    !session.enabled -> Palette.Muted
                    finished -> Palette.Accent
                    else -> Palette.Text
                },
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                session.minutes,
                { v -> update { it.copy(session = it.session.copy(minutes = v)) } },
                0.1, 6000.0, decimals = 1, width = 60.dp,
            )
            Label("min", color = Palette.Muted)
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(Palette.Inset, cornerRadius = radius)
            val fraction = (transport.value.sessionElapsedSeconds / total).coerceIn(0.0, 1.0).toFloat()
            if (session.enabled && fraction > 0f) {
                drawRoundRect(Palette.Beat, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            }
        }
        CheckRow(
            "Stop the click when time is up",
            session.stopOnCompletion,
            { v -> update { it.copy(session = it.session.copy(stopOnCompletion = v)) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton("Reset", onClick = metronome::resetSession)
            Label(if (finished) "Time is up" else "Pauses when the click stops", color = Palette.Muted)
        }
    }
}

@Composable
fun MidiPanel(state: MetronomeState, update: Update, midiPorts: () -> List<MidiPort>, modifier: Modifier = Modifier) {
    val midi = state.midi
    var ports by remember { mutableStateOf(midiPorts()) }
    Panel(
        "MIDI tempo sync",
        modifier,
        trailing = { Toggle(midi.enabled, { v -> update { it.copy(midi = it.midi.copy(enabled = v)) } }) },
    ) {
        Box {
            var open by remember { mutableStateOf(false) }
            val chosen = ports.firstOrNull { it.id == midi.deviceId }?.name
            TextButton(
                chosen ?: if (ports.isEmpty()) "No MIDI input found" else "Choose MIDI input",
                onClick = {
                    ports = midiPorts()
                    open = ports.isNotEmpty()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(open, onDismissRequest = { open = false }) {
                for (port in ports) {
                    DropdownMenuItem(
                        text = { Text(port.name, fontSize = 13.sp) },
                        onClick = {
                            open = false
                            update { it.copy(midi = it.midi.copy(deviceId = port.id)) }
                        },
                    )
                }
            }
        }
        Label("Clock sync is not connected yet", color = Palette.Muted)
    }
}

/** A centred sheet over a dimmed app; clicking outside it closes it. */
@Composable
private fun Overlay(
    title: String,
    onClose: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Inset.copy(alpha = 0.8f))
            .clickable(onClick = onClose)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Panel(
            title,
            Modifier
                .widthIn(max = 620.dp)
                .heightIn(max = 600.dp)
                .border(1.dp, Palette.Outline, RoundedCornerShape(12.dp))
                .clickable(enabled = false) {},
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions()
                    TextButton("Close", onClick = onClose, selected = true)
                }
            },
            content = content,
        )
    }
}

@Composable
fun AudioOverlay(audio: AudioOutput, onClose: () -> Unit) {
    val devices by audio.devices.collectAsStateCompat()
    val status by audio.status.collectAsStateCompat()
    val volume by audio.volume.collectAsStateCompat()
    LaunchedEffect(Unit) { audio.refreshDevices() }

    Overlay(
        "Audio output",
        onClose,
        actions = { TextButton("Refresh list", onClick = audio::refreshDevices) },
    ) {
        if (status.running) {
            Label(
                "Sound is on · ${status.sampleRate / 1000.0} kHz · about ${status.latencyMillis} ms of buffer",
                color = Palette.Sub,
            )
        } else {
            Text("No sound: ${status.error}", color = Palette.Accent, fontSize = 13.sp)
            Text(
                "The device may be missing or in use by another program. Choose another one below.",
                color = Palette.Muted,
                fontSize = 13.sp,
            )
        }

        Label("Playback device", color = Palette.Muted)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DeviceRow("System default", status.deviceId == null, status.running) { audio.selectDevice(null) }
            for (device in devices) {
                DeviceRow(device.name, status.deviceId == device.id, status.running) { audio.selectDevice(device.id) }
            }
        }
        if (devices.isEmpty()) Label("No playback device was found on this computer.", color = Palette.Muted)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Volume", color = Palette.Muted)
            LevelSlider(volume, audio::setVolume, Modifier.weight(1f))
            Text(
                "${(volume * 100).toInt()}%",
                color = Palette.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(44.dp),
            )
            TextButton("Test sound", onClick = audio::playTestSound)
        }
    }
}

@Composable
private fun DeviceRow(name: String, chosen: Boolean, working: Boolean, onClick: () -> Unit) {
    val mark = when {
        !chosen -> Palette.Outline
        working -> Palette.Sub
        else -> Palette.Accent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Inset)
            .border(1.dp, mark, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (chosen) mark else Palette.Off))
        Label(name, Modifier.weight(1f))
        if (chosen) Label(if (working) "in use" else "failed", color = mark)
    }
}

@Composable
fun PatternOverlay(metronome: Metronome, onClose: () -> Unit) {
    val patterns by metronome.patterns.collectAsStateCompat()
    var name by remember { mutableStateOf("") }

    Overlay("Patterns", onClose) {
        Label("Saves the grid and its time signature. Tempo stays as it is.", color = Palette.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.Beat),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Inset)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { field ->
                    if (name.isEmpty()) Text("Name for the current pattern", color = Palette.Muted, fontSize = 14.sp)
                    field()
                },
            )
            TextButton(
                if (patterns.any { it.name == name.trim() }) "Overwrite" else "Save",
                onClick = {
                    metronome.savePattern(name)
                    name = ""
                },
                selected = name.isNotBlank(),
            )
        }

        if (patterns.isEmpty()) Label("No saved pattern yet.", color = Palette.Muted)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (pattern in patterns) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.Inset)
                        .clickable {
                            metronome.loadPattern(pattern.name)
                            onClose()
                        }
                        .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pattern.signature.toString(), color = Palette.Beat, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Label(pattern.name, Modifier.weight(1f))
                    val lanes = pattern.subdivisions.filter { it.enabled }.map { "÷${it.division}" } +
                        pattern.polyVoices.filter { it.enabled }.map { "${it.pulses}:${pattern.signature.numerator}" }
                    Label(lanes.joinToString("  "), color = Palette.Muted)
                    TextButton("Delete", onClick = { metronome.deletePattern(pattern.name) })
                }
            }
        }
    }
}

@Composable
fun LogOverlay(metronome: Metronome, onClose: () -> Unit) {
    val entries by metronome.log.collectAsStateCompat()
    Overlay("Activity log", onClose, actions = { TextButton("Clear", onClick = metronome::clearLog) }) {
            val totalSeconds = entries.sumOf { it.durationMillis } / 1000.0
            Label("${entries.size} runs · ${formatClock(totalSeconds)} of click in total", color = Palette.Muted)
            if (entries.isEmpty()) {
                Label("Nothing yet. Each run is recorded when the click stops.", color = Palette.Muted)
            }
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries.size) { index ->
                    val entry = entries[entries.lastIndex - index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.Inset)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatDateTime(entry.startEpochMillis),
                            color = Palette.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            formatClock(entry.durationMillis / 1000.0),
                            color = Palette.Beat, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(64.dp),
                        )
                        val tempo = if (entry.startBpm == entry.endBpm) formatNumber(entry.startBpm, 1)
                        else "${formatNumber(entry.startBpm, 1)} → ${formatNumber(entry.endBpm, 1)}"
                        Label(
                            if (entry.sleepMode) "Sleep mode · $tempo BPM" else "$tempo BPM · ${entry.signature}",
                            Modifier.weight(1f),
                            Palette.Muted,
                        )
                    }
                }
            }
    }
}
