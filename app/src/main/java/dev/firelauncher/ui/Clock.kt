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

fun timeText(now: Long): String = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(now))

fun dateText(now: Long, locale: Locale): String =
    SimpleDateFormat(if (locale.language == "it") "EEEE d MMMM" else "EEEE, MMMM d", locale).format(Date(now))

@Composable
fun greeting(now: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..12 -> tr("Buongiorno", "Good morning")
        in 13..17 -> tr("Buon pomeriggio", "Good afternoon")
        else -> tr("Buonasera", "Good evening")
    }
}

/** "2026-03-12" -> "marzo 2026" / "March 2026" */
fun monthYear(date: String, locale: Locale): String {
    val p = date.split("-")
    val y = p.getOrNull(0)?.toIntOrNull() ?: return date
    val m = p.getOrNull(1)?.toIntOrNull() ?: return date
    val cal = Calendar.getInstance().apply { set(y, m - 1, 1) }
    val text = SimpleDateFormat("LLLL yyyy", locale).format(cal.time)
    // In italiano i mesi vanno in minuscolo ("uscito a marzo 2026").
    return if (locale.language == "it") text.lowercase(locale) else text
}
