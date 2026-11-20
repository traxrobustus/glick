package glick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

object Palette {
    val Background = Color(0xFF101216)
    val Panel = Color(0xFF191C22)
    val Inset = Color(0xFF0B0D10)
    val Outline = Color(0xFF2B303A)
    val Text = Color(0xFFE7EAF0)
    val Muted = Color(0xFF8A93A3)
    val Beat = Color(0xFFFFB020)
    val Accent = Color(0xFFFF5A4F)
    val Sub = Color(0xFF45D0C1)
    val Poly = Color(0xFFB392F0)
    val Off = Color(0xFF2A2F38)
}

fun formatNumber(value: Double, decimals: Int): String {
    val scale = 10.0.pow(decimals)
    val scaled = round(value * scale).toLong()
    val whole = abs(scaled) / scale.toLong()
    val fraction = (abs(scaled) % scale.toLong()).toString().padStart(decimals, '0').trimEnd('0')
    val sign = if (scaled < 0) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}

fun formatClock(totalSeconds: Double): String {
    val s = totalSeconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = Palette.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        content()
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Palette.Text) {
    Text(text, modifier = modifier, color = color, fontSize = 13.sp, maxLines = 1)
}

/** A small on/off pill. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, color: Color = Palette.Beat, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 34.dp, height = 20.dp)
            .clip(CircleShape)
            .background(if (on) color else Palette.Off)
            .clickable { onChange(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) Palette.Inset else Palette.Muted))
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) Palette.Beat else Palette.Inset)
                .border(1.dp, if (checked) Palette.Beat else Palette.Outline, RoundedCornerShape(4.dp)),
        )
        Label(text)
    }
}

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color = Palette.Beat,
    horizontalPadding: Dp = 10.dp,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) color else Palette.Inset)
            .border(1.dp, if (selected) color else Palette.Outline, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Palette.Inset else Palette.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * An always-editable number. Typed text is committed as soon as it parses to a
 * value inside the range; anything else is dropped when focus leaves.
 */
@Composable
fun NumberField(
    value: Double,
    onChange: (Double) -> Unit,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    width: Dp = 52.dp,
    style: TextStyle = TextStyle(fontSize = 14.sp),
    framed: Boolean = true,
) {
    val shown = formatNumber(value, decimals)
    var text by remember(shown) { mutableStateOf(shown) }
    val frame = if (framed) {
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Inset)
            .border(1.dp, Palette.Outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 5.dp)
    } else Modifier
    BasicTextField(
        value = text,
        onValueChange = { typed ->
            val clean = typed.filter { it.isDigit() || it == '.' || it == '-' }.take(8)
            text = clean
            val parsed = clean.toDoubleOrNull()
            if (parsed != null && parsed in min..max) onChange(parsed)
        },
        singleLine = true,
        textStyle = style.merge(
            TextStyle(color = Palette.Text, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace),
        ),
        cursorBrush = SolidColor(Palette.Beat),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
            .width(width)
            .onFocusChanged { if (!it.isFocused) text = shown }
            .then(frame),
    )
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.Off)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    fieldWidth: Dp = 40.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StepButton("−") { onChange((value - 1).coerceIn(range)) }
        NumberField(
            value = value.toDouble(),
            onChange = { onChange(it.toInt()) },
            min = range.first.toDouble(),
            max = range.last.toDouble(),
            width = fieldWidth,
        )
        StepButton("+") { onChange((value + 1).coerceIn(range)) }
    }
}

/** Steps through a fixed list of choices (used for the note-length denominator). */
@Composable
fun ChoiceStepper(value: Int, choices: List<Int>, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val index = choices.indexOf(value).coerceAtLeast(0)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StepButton("−") { onChange(choices[(index - 1).coerceAtLeast(0)]) }
        Box(
            Modifier
                .width(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Inset)
                .border(1.dp, Palette.Outline, RoundedCornerShape(6.dp))
                .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(value.toString(), color = Palette.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
        StepButton("+") { onChange(choices[(index + 1).coerceAtMost(choices.lastIndex)]) }
    }
}

/** A slim horizontal fader, 0 to 1. */
@Composable
fun LevelSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Beat,
) {
    val current by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { current((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    current((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val track = 6.dp.toPx()
        val knob = 7.dp.toPx()
        val y = size.height / 2
        val usable = size.width - 2 * knob
        val x = knob + usable * value.coerceIn(0f, 1f)
        drawRoundRect(
            Palette.Inset,
            topLeft = Offset(0f, y - track / 2),
            size = Size(size.width, track),
            cornerRadius = CornerRadius(track / 2),
        )
        drawRoundRect(
            color.copy(alpha = 0.75f),
            topLeft = Offset(0f, y - track / 2),
            size = Size(x, track),
            cornerRadius = CornerRadius(track / 2),
        )
        drawCircle(Palette.Text, radius = knob, center = Offset(x, y))
    }
}

@Composable
fun Gap(size: Dp) = Spacer(Modifier.size(size))
