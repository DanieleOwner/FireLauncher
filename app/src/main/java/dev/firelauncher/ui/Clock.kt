package dev.firelauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val IT = Locale.ITALIAN

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L * (60 - Calendar.getInstance().get(Calendar.SECOND)).coerceAtLeast(1))
        }
    }
    return now
}

fun timeText(now: Long): String = SimpleDateFormat("HH:mm", IT).format(Date(now))

fun dateText(now: Long): String =
    SimpleDateFormat("EEEE d MMMM", IT).format(Date(now))

fun greeting(now: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..12 -> "Buongiorno"
        in 13..17 -> "Buon pomeriggio"
        else -> "Buonasera"
    }
}

private val MONTHS = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

/** "2026-03-12" -> "marzo 2026" */
fun monthYear(date: String): String {
    val p = date.split("-")
    val m = p.getOrNull(1)?.toIntOrNull() ?: return date
    return "${MONTHS.getOrElse(m - 1) { "" }} ${p[0]}"
}
