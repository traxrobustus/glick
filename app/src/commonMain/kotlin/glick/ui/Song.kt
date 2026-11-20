package glick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glick.core.DENOMINATORS
import glick.core.MAX_NUMERATOR
import glick.core.Metronome
import glick.core.MetronomeState
import glick.core.SongMode
import glick.core.SongRef
import glick.core.Transport
import glick.core.song.BeatDetector
import glick.core.song.SongGrid
import glick.core.song.SongSession
import glick.core.song.SongTimeline
import glick.core.song.SongView
import glick.core.song.WaveformPeaks
import glick.core.song.deleteBeat
import glick.core.song.doubleTempo
import glick.core.song.halveTempo
import glick.core.song.moveBeat
import glick.core.song.addBeat
import glick.core.song.setBarStart
import glick.core.song.setLoop
import glick.core.song.setSignature
import glick.core.song.steadied
import glick.core.song.unpinBeat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The song view: load an audio file, see its waveform with the detected beats
 * and bars, correct them, choose the loop, and choose who follows whom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongPanel(
    session: SongSession,
    metronome: Metronome,
    state: MetronomeState,
    transport: () -> Transport,
    pickSong: suspend () -> SongRef?,
    sampleRate: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val view by session.view.collectAsStateCompat()
    val scope = rememberCoroutineScope()
    var selected by remember(view.ref) { mutableIntStateOf(-1) }
    val grid = view.grid
    val row = Arrangement.spacedBy(8.dp)

    Panel(
        "Song",
        modifier,
        trailing = {
            Row(horizontalArrangement = row) {
                if (view.ref != null) TextButton("Close song", onClick = session::close)
                TextButton(
                    "Open audio file",
                    onClick = { scope.launch { pickSong()?.let { session.load(it, sampleRate) } } },
                    selected = view.ref == null,
                )
            }
        },
    ) {
        val name = view.ref?.name
        when {
            name == null -> Text(
                "Load a song (WAV, FLAC or MP3) to practise with it. glick finds its beats and bars, " +
                    "and keeps the click and the song together.",
                color = Palette.Muted, fontSize = 13.sp,
            )
            view.error != null -> {
                Label(name)
                Text("This file could not be loaded: ${view.error}", color = Palette.Accent, fontSize = 13.sp)
            }
            view.busy != null -> {
                Label("$name · ${view.busy}…")
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(Palette.Inset, cornerRadius = radius)
                    drawRoundRect(Palette.Beat, size = Size(size.width * view.progress.coerceIn(0.02f, 1f), size.height), cornerRadius = radius)
                }
            }
        }

        if (grid != null && view.ready) {
            val timeline = remember(grid) { SongTimeline(grid) }
            Label("$name · ${formatClock(view.durationSeconds)} · ${timeline.barCount} bars")

            FlowRow(horizontalArrangement = row, verticalArrangement = row) {
                TextButton(
                    "Click follows song",
                    { metronome.update { it.copy(songMode = SongMode.ClickFollowsSong) } },
                    selected = state.songMode == SongMode.ClickFollowsSong,
                )
                TextButton(
                    "Song follows click",
                    { metronome.update { it.copy(songMode = SongMode.SongFollowsClick) } },
                    selected = state.songMode == SongMode.SongFollowsClick,
                )
                Row(Modifier.width(200.dp), row, Alignment.CenterVertically) {
                    Label("Song level", color = Palette.Muted)
                    LevelSlider(state.songLevel, { v -> metronome.update { it.copy(songLevel = v) } }, Modifier.weight(1f))
                }
            }
            Text(
                if (state.songMode == SongMode.ClickFollowsSong) {
                    "The click takes its tempo from the song's beats. Changing the tempo plays both faster or slower."
                } else {
                    "The click keeps the tempo you set and the song is stretched to it."
                },
                color = Palette.Muted, fontSize = 13.sp,
            )

            Waveform(
                view = view,
                grid = grid,
                timeline = timeline,
                transport = transport,
                selected = selected,
                onSelect = { selected = it },
                onEdit = session::edit,
                height = if (compact) 190.dp else 250.dp,
            )

            FlowRow(horizontalArrangement = row, verticalArrangement = row) {
                TextButton("Undo", session::undo, selected = false, color = Palette.Muted)
                TextButton("Redo", session::redo)
                TextButton("Steady tempo", { session.edit { it.steadied() } })
                TextButton("Beats ×2", { session.edit { it.doubleTempo() }; selected = -1 })
                TextButton("Beats ÷2", { session.edit { it.halveTempo() }; selected = -1 })
                TextButton("Analyse again", { session.redetect() })
                TextButton("Loop whole song", { session.edit { it.copy(loopStartBar = 0, loopEndBar = null) } })
            }

            // When the tempo is known, detection can start from it instead of guessing.
            var knownBpm by remember(view.ref, grid.knownBpm) {
                mutableDoubleStateOf(grid.knownBpm ?: kotlin.math.round(timeline.bpmOfBeat(grid.beats.size / 2)))
            }
            Row(horizontalArrangement = row, verticalAlignment = Alignment.CenterVertically) {
                Label("Known tempo", color = Palette.Muted)
                NumberField(
                    knownBpm, { knownBpm = it },
                    BeatDetector.MIN_KNOWN_BPM, BeatDetector.MAX_KNOWN_BPM, decimals = 2, width = 76.dp,
                )
                Label("BPM", color = Palette.Muted)
                TextButton("Analyse again at this tempo", { session.redetect(knownBpm); selected = -1 }, selected = grid.knownBpm == knownBpm)
            }

            val beat = selected.takeIf { it in grid.beats.indices }
            if (beat == null) {
                Text(
                    "Tap a beat marker in the top strip to select it, drag it to move it. Double-tap the waveform " +
                        "to add a beat. Drag the ends of the bottom strip to set the loop; they snap to bars.",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            } else {
                val bar = timeline.barOfBeat(beat).coerceAtLeast(0)
                val signature = timeline.signatureOfBar(bar)
                FlowRow(horizontalArrangement = row, verticalArrangement = row) {
                    Row(horizontalArrangement = row, verticalAlignment = Alignment.CenterVertically) {
                        Label("Beat ${beat + 1}, bar ${bar + 1}", color = Palette.Beat)
                    }
                    TextButton("Bar starts here", { session.edit { it.setBarStart(beat) } })
                    TextButton("Delete beat", { session.edit { it.deleteBeat(beat) }; selected = -1 })
                    if (grid.beats[beat].pinned) TextButton("Unpin", { session.edit { it.unpinBeat(beat) } })
                    Row(horizontalArrangement = row, verticalAlignment = Alignment.CenterVertically) {
                        Label("From bar ${bar + 1}:", color = Palette.Muted)
                        Stepper(
                            signature.numerator,
                            { v -> session.edit { it.setSignature(bar, signature.copy(numerator = v)) } },
                            1..MAX_NUMERATOR,
                        )
                        ChoiceStepper(
                            signature.denominator, DENOMINATORS,
                            { v -> session.edit { it.setSignature(bar, signature.copy(denominator = v)) } },
                        )
                    }
                }
            }
        }
    }
}

/** What is visible of the song: the time at the left edge and the zoom. */
private class Viewport(start: Double, secondsPerPixel: Double) {
    var start by mutableDoubleStateOf(start)
    var secondsPerPixel by mutableDoubleStateOf(secondsPerPixel)
    var width by mutableFloatStateOf(1f)

    fun x(seconds: Double): Float = ((seconds - start) / secondsPerPixel).toFloat()
    fun seconds(x: Float): Double = start + x * secondsPerPixel

    fun zoom(factor: Double, around: Float, duration: Double) {
        val anchor = seconds(around)
        secondsPerPixel = (secondsPerPixel * factor).coerceIn(0.0002, max(0.0002, duration / width))
        start = anchor - around * secondsPerPixel
        clamp(duration)
    }

    fun pan(pixels: Float, duration: Double) {
        start -= pixels * secondsPerPixel
        clamp(duration)
    }

    fun clamp(duration: Double) {
        start = start.coerceIn(0.0, max(0.0, duration - width * secondsPerPixel))
    }
}

@Composable
private fun Waveform(
    view: SongView,
    grid: SongGrid,
    timeline: SongTimeline,
    transport: () -> Transport,
    selected: Int,
    onSelect: (Int) -> Unit,
    onEdit: ((SongGrid) -> SongGrid) -> Unit,
    height: Dp,
) {
    val peaks = view.peaks ?: return
    val duration = view.durationSeconds
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val port = remember(view.ref) { Viewport(0.0, 0.0) }
    val currentGrid by rememberUpdatedState(grid)
    val currentTimeline by rememberUpdatedState(timeline)

    // A beat or loop end being dragged is only previewed; the edit is made on release.
    var draggedBeat by remember { mutableIntStateOf(-1) }
    var draggedBeatSeconds by remember { mutableDoubleStateOf(0.0) }
    var draggedLoopEnd by remember { mutableStateOf<Boolean?>(null) }
    var draggedLoopBar by remember { mutableIntStateOf(0) }

    // Keep the playhead on screen while playing.
    LaunchedEffect(view.ref) {
        while (true) {
            withFrameNanos { }
            val at = transport().songSeconds
            if (at >= 0 && draggedBeat < 0 && port.secondsPerPixel > 0) {
                val x = port.x(at)
                if (x < 0 || x > port.width) {
                    port.start = at - port.width * 0.1 * port.secondsPerPixel
                    port.clamp(duration)
                }
            }
        }
    }

    fun nearestBeat(x: Float, tolerance: Float): Int {
        val t = port.seconds(x)
        val index = currentGrid.beats.indices.minByOrNull { abs(currentGrid.beats[it].seconds - t) } ?: return -1
        return if (abs(port.x(currentGrid.beats[index].seconds) - x) <= tolerance) index else -1
    }

    fun nearestBar(x: Float): Int {
        val t = port.seconds(x)
        return (0..currentTimeline.barCount).minByOrNull { abs(currentTimeline.barStartSeconds(it) - t) } ?: 0
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Inset)
            .onSizeChanged {
                port.width = it.width.toFloat().coerceAtLeast(1f)
                if (port.secondsPerPixel == 0.0) port.secondsPerPixel = duration / port.width
                port.clamp(duration)
            }
            // Mouse wheel: zoom around the pointer; with a sideways wheel or Shift, scroll.
            .pointerInput(view.ref) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta
                        if (abs(delta.x) > abs(delta.y)) port.pan(-delta.x * 40f, duration)
                        else port.zoom(if (delta.y > 0) 1.25 else 0.8, change.position.x, duration)
                        change.consume()
                    }
                }
            },
    ) {
        // Beat markers and bar numbers. Drag a marker to move the beat.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(view.ref) {
                    detectTapGestures { onSelect(nearestBeat(it.x, 22.dp.toPx())) }
                }
                .pointerInput(view.ref) {
                    detectDragGestures(
                        onDragStart = { at ->
                            draggedBeat = nearestBeat(at.x, 22.dp.toPx())
                            if (draggedBeat >= 0) {
                                draggedBeatSeconds = currentGrid.beats[draggedBeat].seconds
                                onSelect(draggedBeat)
                            }
                        },
                        onDragEnd = {
                            val index = draggedBeat
                            val to = draggedBeatSeconds
                            if (index >= 0) onEdit { it.moveBeat(index, to) }
                            draggedBeat = -1
                        },
                        onDragCancel = { draggedBeat = -1 },
                    ) { change, drag ->
                        change.consume()
                        if (draggedBeat >= 0) draggedBeatSeconds = (draggedBeatSeconds + drag.x * port.secondsPerPixel).coerceIn(0.0, duration)
                        else port.pan(drag.x, duration)
                    }
                },
        ) {
            val shown = if (draggedBeat >= 0) grid.moveBeat(draggedBeat, draggedBeatSeconds) else grid
            drawMarkers(port, shown, SongTimeline(shown), selected, measurer)
        }

        // The waveform. Drag to scroll, pinch to zoom, double-tap to add a beat.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(view.ref) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f) port.zoom(1.0 / zoom, centroid.x, duration)
                        port.pan(pan.x, duration)
                    }
                }
                .pointerInput(view.ref) {
                    detectTapGestures(
                        onTap = { onSelect(nearestBeat(it.x, 14.dp.toPx())) },
                        onDoubleTap = { at ->
                            val seconds = port.seconds(at.x)
                            onEdit { it.addBeat(seconds) }
                        },
                    )
                },
        ) {
            val shown = if (draggedBeat >= 0) grid.moveBeat(draggedBeat, draggedBeatSeconds) else grid
            val shownTimeline = if (draggedBeat >= 0) SongTimeline(shown) else timeline
            var loopStart = shownTimeline.loopStartBar
            var loopEnd = shownTimeline.loopEndBar
            when (draggedLoopEnd) {
                false -> loopStart = draggedLoopBar.coerceIn(0, loopEnd - 1)
                true -> loopEnd = draggedLoopBar.coerceIn(loopStart + 1, shownTimeline.barCount)
                null -> {}
            }
            drawWave(port, peaks, shownTimeline.barStartSeconds(loopStart), shownTimeline.barStartSeconds(loopEnd))
            drawGrid(port, shown, shownTimeline, selected)
            val at = transport().songSeconds
            if (at >= 0) {
                val x = port.x(at)
                drawLine(Palette.Text, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }

        // The loop. Drag either end; it snaps to the nearest bar line.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(view.ref) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val startX = port.x(currentTimeline.barStartSeconds(currentTimeline.loopStartBar))
                            val endX = port.x(currentTimeline.barStartSeconds(currentTimeline.loopEndBar))
                            draggedLoopEnd = abs(at.x - endX) < abs(at.x - startX)
                            draggedLoopBar = nearestBar(at.x)
                        },
                        onDragEnd = {
                            val end = draggedLoopEnd
                            val bar = draggedLoopBar
                            val t = currentTimeline
                            if (end == true) onEdit { it.setLoop(t.loopStartBar, max(bar, t.loopStartBar + 1)) }
                            if (end == false) onEdit { it.setLoop(min(bar, t.loopEndBar - 1), t.loopEndBar) }
                            draggedLoopEnd = null
                        },
                        onDragCancel = { draggedLoopEnd = null },
                    ) { change, _ ->
                        change.consume()
                        draggedLoopBar = nearestBar(change.position.x)
                    }
                },
        ) {
            var loopStart = timeline.loopStartBar
            var loopEnd = timeline.loopEndBar
            when (draggedLoopEnd) {
                false -> loopStart = draggedLoopBar.coerceIn(0, loopEnd - 1)
                true -> loopEnd = draggedLoopBar.coerceIn(loopStart + 1, timeline.barCount)
                null -> {}
            }
            val from = port.x(timeline.barStartSeconds(loopStart))
            val to = port.x(timeline.barStartSeconds(loopEnd))
            val bar = 8.dp.toPx()
            val top = size.height - bar - 3.dp.toPx()
            drawRoundRect(Palette.Beat.copy(alpha = 0.8f), Offset(from, top), Size((to - from).coerceAtLeast(2f), bar), CornerRadius(3.dp.toPx()))
            for (x in listOf(from, to)) {
                drawRoundRect(Palette.Text, Offset(x - 3.dp.toPx(), 2.dp.toPx()), Size(6.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(3.dp.toPx()))
            }
            val label = measurer.measure(
                "loop: bars ${loopStart + 1} to $loopEnd",
                TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
            val labelX = (from + 8.dp.toPx()).coerceIn(4f, max(4f, size.width - label.size.width - 4f))
            drawText(label, Palette.Beat, Offset(labelX, 1.dp.toPx()))
        }
    }
}

/** The outline of the audio, brighter inside the loop. */
private fun DrawScope.drawWave(port: Viewport, peaks: WaveformPeaks, loopFrom: Double, loopTo: Double) {
    val middle = size.height / 2
    val blockSeconds = peaks.blockFrames.toDouble() / peaks.sampleRate
    val loopFromX = port.x(loopFrom)
    val loopToX = port.x(loopTo)
    drawRect(Palette.Panel, Offset(loopFromX, 0f), Size((loopToX - loopFromX).coerceAtLeast(0f), size.height))
    var x = 0f
    while (x < size.width) {
        val first = (port.seconds(x) / blockSeconds).toInt().coerceAtLeast(0)
        val last = (port.seconds(x + 1f) / blockSeconds).toInt().coerceAtMost(peaks.blocks - 1)
        if (first <= last) {
            var low = 0f
            var high = 0f
            for (block in first..last) {
                if (peaks.low[block] < low) low = peaks.low[block]
                if (peaks.high[block] > high) high = peaks.high[block]
            }
            val inside = x in loopFromX..loopToX
            drawLine(
                if (inside) Palette.Sub else Palette.Muted.copy(alpha = 0.55f),
                Offset(x, middle - high * middle * 0.95f),
                Offset(x, middle - low * middle * 0.95f + 1f),
            )
        }
        x += 1f
    }
}

/** Beat and bar lines over the waveform. */
private fun DrawScope.drawGrid(port: Viewport, grid: SongGrid, timeline: SongTimeline, selected: Int) {
    val beats = grid.beats
    if (beats.size < 2) return
    val spacing = (beats.last().seconds - beats.first().seconds) / (beats.size - 1) / port.secondsPerPixel
    for (bar in 0..timeline.barCount) {
        val x = port.x(timeline.barStartSeconds(bar))
        if (x in 0f..size.width) drawLine(Palette.Beat.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
    }
    if (spacing >= 5) {
        beats.forEachIndexed { i, beat ->
            val x = port.x(beat.seconds)
            if (x in 0f..size.width) {
                val color = if (i == selected) Palette.Accent else Palette.Text.copy(alpha = 0.28f)
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (i == selected) 2.dp.toPx() else 1f)
            }
        }
    }
}

/** The top strip: a handle per beat (filled when pinned) and the bar numbers. */
private fun DrawScope.drawMarkers(port: Viewport, grid: SongGrid, timeline: SongTimeline, selected: Int, measurer: TextMeasurer) {
    val beats = grid.beats
    if (beats.size < 2) return
    val spacing = (beats.last().seconds - beats.first().seconds) / (beats.size - 1) / port.secondsPerPixel
    val style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val barWidth = spacing * timeline.signatureOfBar(0).numerator
    val every = max(1, (36.dp.toPx() / barWidth.coerceAtLeast(0.01)).toInt() + 1)
    for (bar in 0 until timeline.barCount step every) {
        val x = port.x(timeline.barStartSeconds(bar))
        if (x < -40f || x > size.width) continue
        drawText(measurer.measure((bar + 1).toString(), style), Palette.Beat, Offset(x + 3.dp.toPx(), 0f))
    }
    if (spacing < 7) return
    val radius = min(5.dp.toPx(), (spacing / 2.5).toFloat())
    beats.forEachIndexed { i, beat ->
        val x = port.x(beat.seconds)
        if (x < -radius || x > size.width + radius) return@forEachIndexed
        val centre = Offset(x, size.height - radius - 3.dp.toPx())
        val color = when {
            i == selected -> Palette.Accent
            timeline.barOfBeat(i) >= 0 && timeline.barStartBeat(timeline.barOfBeat(i)) == i -> Palette.Beat
            else -> Palette.Muted
        }
        if (beat.pinned || i == selected) drawCircle(color, radius, centre)
        else drawCircle(color, radius, centre, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}
