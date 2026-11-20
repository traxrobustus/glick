package glick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glick.core.MAX_DIVISION
import glick.core.MAX_POLY_PULSES
import glick.core.MetronomeState
import glick.core.Transport
import kotlin.math.ceil

private val ControlsWidth = 300.dp
private val LaneHeight = 34.dp

/**
 * Step-sequencer view of one bar. Every lane spans the full bar, so lanes with
 * different cell counts line up in time: the beat lane has one cell per beat,
 * a subdivision lane has `division` cells inside each beat, and a polyrhythm
 * lane spreads its pulses evenly across the bar.
 */
@Composable
fun Sequencer(
    state: MetronomeState,
    sleep: Boolean,
    transport: () -> Transport,
    update: ((MetronomeState) -> MetronomeState) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 160)
    val beats = state.signature.numerator

    /** Position in the bar from 0 until 1, or -1 when stopped. */
    val barPhase = {
        val t = transport()
        if (t.playing) (t.beat + t.beatPhase) / beats else -1.0
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Beat numbers: the column hint for everything below.
        Lane(compact, height = 18.dp, controls = { Label("Beat", color = Palette.Muted) }) { gridModifier ->
            Canvas(gridModifier) {
                val phase = barPhase()
                val current = if (phase < 0) -1 else (phase * beats).toInt()
                val cell = size.width / beats
                val every = ceil(18.dp.toPx() / cell).toInt().coerceAtLeast(1)
                for (i in 0 until beats step every) {
                    val on = i == current
                    centredText(
                        measurer, (i + 1).toString(), i * cell, cell,
                        if (on) Palette.Text else Palette.Muted,
                        if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Lane(
            compact,
            controls = {
                Box(Modifier.size(width = 34.dp, height = 20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Beat))
                }
                Label("Click", Modifier.width(52.dp))
                Label("tap = accent", Modifier.width(94.dp), color = Palette.Muted)
                LevelSlider(state.beatLevel, { v -> update { it.copy(beatLevel = v) } }, Modifier.weight(1f))
            },
        ) { gridModifier ->
            val accent by rememberUpdatedState(if (sleep) null else state.accentBeat)
            val count by rememberUpdatedState(beats)
            Canvas(
                gridModifier.pointerInput(sleep) {
                    if (sleep) return@pointerInput
                    detectTapGestures { tap ->
                        val index = (tap.x / size.width * count).toInt().coerceIn(0, count - 1)
                        update { it.copy(accentBeat = if (it.accentBeat == index) null else index) }
                    }
                },
            ) {
                val t = transport()
                drawCells(
                    cells = beats, phase = barPhase(), enabled = true, level = state.beatLevel,
                    color = { if (it == accent) Palette.Accent else Palette.Beat },
                    hollow = t.beatSilenced, measurer = measurer, label = { null },
                )
            }
        }

        state.subdivisions.forEachIndexed { i, line ->
            val on = line.enabled && !sleep
            Lane(
                compact,
                controls = {
                    Toggle(line.enabled, { v -> update { s -> s.withSub(i) { it.copy(enabled = v) } } }, Palette.Sub)
                    Label("Sub ${i + 1}", Modifier.width(52.dp))
                    Stepper(line.division, { v -> update { s -> s.withSub(i) { it.copy(division = v) } } }, 1..MAX_DIVISION)
                    LevelSlider(
                        line.level, { v -> update { s -> s.withSub(i) { it.copy(level = v) } } },
                        Modifier.weight(1f), Palette.Sub,
                    )
                },
            ) { gridModifier ->
                Canvas(gridModifier) {
                    drawCells(
                        cells = beats * line.division, phase = barPhase(), enabled = on, level = line.level,
                        color = { Palette.Sub }, hollow = transport().beatSilenced, measurer = measurer,
                        strong = { it % line.division == 0 },
                        label = { (it % line.division + 1).toString() },
                    )
                }
            }
        }

        state.polyVoices.forEachIndexed { i, voice ->
            val on = voice.enabled && !sleep
            Lane(
                compact,
                controls = {
                    Toggle(voice.enabled, { v -> update { s -> s.withPoly(i) { it.copy(enabled = v) } } }, Palette.Poly)
                    Label("${voice.pulses}:$beats", Modifier.width(52.dp))
                    Stepper(voice.pulses, { v -> update { s -> s.withPoly(i) { it.copy(pulses = v) } } }, 1..MAX_POLY_PULSES)
                    LevelSlider(
                        voice.level, { v -> update { s -> s.withPoly(i) { it.copy(level = v) } } },
                        Modifier.weight(1f), Palette.Poly,
                    )
                },
            ) { gridModifier ->
                Canvas(gridModifier) {
                    drawCells(
                        cells = voice.pulses, phase = barPhase(), enabled = on, level = voice.level,
                        color = { Palette.Poly }, hollow = false, measurer = measurer,
                        label = { (it + 1).toString() },
                    )
                }
            }
        }
    }
}

private fun MetronomeState.withSub(index: Int, f: (glick.core.SubdivisionLine) -> glick.core.SubdivisionLine) =
    copy(subdivisions = subdivisions.mapIndexed { i, l -> if (i == index) f(l) else l })

private fun MetronomeState.withPoly(index: Int, f: (glick.core.PolyVoice) -> glick.core.PolyVoice) =
    copy(polyVoices = polyVoices.mapIndexed { i, v -> if (i == index) f(v) else v })

@Composable
private fun Lane(
    compact: Boolean,
    height: androidx.compose.ui.unit.Dp = LaneHeight,
    controls: @Composable RowScope.() -> Unit,
    grid: @Composable (Modifier) -> Unit,
) {
    val spacing = Arrangement.spacedBy(8.dp)
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), spacing, Alignment.CenterVertically, controls)
            grid(Modifier.fillMaxWidth().height(height))
        }
    } else {
        Row(Modifier.fillMaxWidth().height(height), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Row(Modifier.width(ControlsWidth), spacing, Alignment.CenterVertically, controls)
            grid(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private fun DrawScope.centredText(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    width: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
) {
    val layout = measurer.measure(
        text,
        TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = weight),
    )
    drawText(
        layout,
        color = color,
        topLeft = Offset(x + (width - layout.size.width) / 2, (size.height - layout.size.height) / 2),
    )
}

/**
 * Draws one lane of [cells] equal steps. The step under the playhead lights up
 * and fades until the next one; with [hollow] it is only outlined, which is how
 * a randomly silenced beat shows.
 */
private fun DrawScope.drawCells(
    cells: Int,
    phase: Double,
    enabled: Boolean,
    level: Float,
    color: (Int) -> Color,
    hollow: Boolean,
    measurer: TextMeasurer,
    strong: (Int) -> Boolean = { true },
    label: (Int) -> String?,
) {
    val cell = size.width / cells
    val gap = when {
        cell > 8.dp.toPx() -> 2.dp.toPx()
        cell > 3f -> 1f
        else -> 0f
    }
    val corner = CornerRadius(minOf(4.dp.toPx(), (cell - gap) / 2))
    val position = phase * cells
    val active = if (phase < 0 || !enabled) -1 else position.toInt().coerceAtMost(cells - 1)
    val glow = if (active < 0) 0f else (1.0 - (position - active)).toFloat().coerceIn(0f, 1f)
    val showLabels = cell >= 16.dp.toPx()

    for (i in 0 until cells) {
        val topLeft = Offset(i * cell + gap / 2, 0f)
        val cellSize = Size((cell - gap).coerceAtLeast(1f), size.height)
        val base = if (enabled) color(i) else Palette.Off
        val rest = when {
            !enabled -> 0.55f
            strong(i) -> 0.16f + 0.26f * level
            else -> 0.08f + 0.18f * level
        }
        drawRoundRect(base.copy(alpha = rest), topLeft, cellSize, corner)
        if (i == active) {
            if (hollow) {
                drawRoundRect(base.copy(alpha = glow), topLeft, cellSize, corner, style = Stroke(1.5.dp.toPx()))
            } else {
                drawRoundRect(base.copy(alpha = 0.35f + 0.65f * glow), topLeft, cellSize, corner)
            }
        }
        if (showLabels) {
            val text = label(i) ?: continue
            val lit = i == active && !hollow
            centredText(
                measurer, text, i * cell, cell,
                when {
                    lit -> Palette.Inset
                    enabled -> base.copy(alpha = if (strong(i)) 0.95f else 0.6f)
                    else -> Palette.Muted.copy(alpha = 0.5f)
                },
            )
        }
    }
}
