package glick.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

actual fun formatDateTime(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))

@androidx.compose.runtime.Composable
actual fun BackHandler(onBack: () -> Unit) = androidx.activity.compose.BackHandler(onBack = onBack)
