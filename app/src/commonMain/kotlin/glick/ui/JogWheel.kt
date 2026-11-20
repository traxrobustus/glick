package glick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glick.core.MAX_BPM
import glick.core.MIN_BPM
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Rotary tempo control. Dragging around the ring turns it; one turn is worth
 * more BPM the faster the tempo already is, so 5 to 5000 stays reachable while
 * slow tempos keep single-BPM precision. The mouse wheel nudges by 1 BPM.
 */
@Composable
fun JogWheel(
    bpm: Double,
    onBpmChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentBpm by rememberUpdatedState(bpm)
    val setBpm by rememberUpdatedState(onBpmChange)
    var angle by remember { mutableFloatStateOf(0f) }

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var carry = 0.0
                    detectDragGestures(onDragStart = { carry = 0.0 }) { change, drag ->
                        change.consume()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val now = change.position - center
                        val before = now - drag
                        var delta = atan2(now.y, now.x) - atan2(before.y, before.x)
                        if (delta > PI) delta -= (2 * PI).toFloat()
                        if (delta < -PI) delta += (2 * PI).toFloat()
                        angle += delta
                        val bpmPerTurn = max(30.0, currentBpm * 0.3)
                        carry += delta / (2 * PI) * bpmPerTurn
                        val steps = carry.toInt()
                        if (steps != 0) {
                            carry -= steps
                            setBpm((currentBpm.toInt() + steps).toDouble().coerceIn(MIN_BPM, MAX_BPM))
                        }
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val scroll = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (scroll == 0f) continue
                            val step = if (scroll < 0) 1 else -1
                            angle += step * 0.12f
                            setBpm((currentBpm.toInt() + step).toDouble().coerceIn(MIN_BPM, MAX_BPM))
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val radius = size.minDimension / 2
            val ring = radius * 0.30f
            val mid = radius - ring / 2
            drawCircle(
                Brush.radialGradient(listOf(Palette.Off, Palette.Inset), radius = radius),
                radius = radius,
            )
            drawCircle(Palette.Outline, radius = radius, style = Stroke(1.dp.toPx()))
            // Grip ticks turn with the wheel.
            val ticks = 48
            for (i in 0 until ticks) {
                val a = angle + i * (2 * PI.toFloat() / ticks)
                val long = i % 4 == 0
                val inner = radius - ring * (if (long) 0.78f else 0.55f)
                val outer = radius - ring * 0.22f
                drawLine(
                    if (long) Palette.Muted else Palette.Outline,
                    start = center + Offset(cos(a) * inner, sin(a) * inner),
                    end = center + Offset(cos(a) * outer, sin(a) * outer),
                    strokeWidth = (if (long) 2 else 1).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // Finger dimple.
            val dimple = center + Offset(cos(angle - PI.toFloat() / 2) * mid, sin(angle - PI.toFloat() / 2) * mid)
            drawCircle(Palette.Inset, radius = ring * 0.30f, center = dimple)
            drawCircle(
                if (enabled) Palette.Beat else Palette.Muted,
                radius = ring * 0.30f,
                center = dimple,
                style = Stroke(2.dp.toPx()),
            )
            // Display well.
            drawCircle(Palette.Inset, radius = radius - ring)
            drawCircle(Palette.Outline, radius = radius - ring, style = Stroke(1.dp.toPx()))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (enabled) {
                NumberField(
                    value = bpm,
                    onChange = onBpmChange,
                    min = MIN_BPM,
                    max = MAX_BPM,
                    decimals = 1,
                    width = 130.dp,
                    style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
                    framed = false,
                )
            } else {
                Text(formatNumber(bpm, 1), color = Palette.Muted, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Text("BPM", color = Palette.Muted, fontSize = 12.sp, letterSpacing = 2.sp)
        }
    }
}
