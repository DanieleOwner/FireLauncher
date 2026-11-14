package dev.firelauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.firelauncher.data.UiLanguage
import java.util.Locale

/**
 * Traduzioni dell'interfaccia: ogni testo è scritto in italiano e in inglese nel punto in cui si usa,
 * con tr("…", "…"). Titoli e trame dei film restano quelli di TMDB.
 */
val LocalUiLanguage = staticCompositionLocalOf { UiLanguage.IT }

@Composable
@ReadOnlyComposable
fun tr(it: String, en: String): String = if (LocalUiLanguage.current == UiLanguage.EN) en else it

@Composable
@ReadOnlyComposable
fun uiLocale(): Locale = LocalUiLanguage.current.locale

val UiLanguage.locale: Locale get() = if (this == UiLanguage.EN) Locale.ENGLISH else Locale.ITALIAN
