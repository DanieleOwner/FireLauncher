package dev.firelauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val launch: Intent,
)

object Apps {
    /** Le app fisse in home, nell'ordine in cui compaiono. */
    val FAVORITES = listOf(
        "com.stremio.one",
        "org.smarttube.stable",
        "it.rainet.androidtv",
        "com.amazon.avod",
        "com.plexapp.android",
    )

    const val STREMIO = "com.stremio.one"
    private const val APPSTORE = "com.amazon.venezia"
    private const val DOWNLOADER = "com.esaba.downloader"

    /** Pacchetti di sistema Amazon che non hanno senso nella lista app. */
    private val HIDDEN = setOf(
        "com.amazon.tv.launcher",
        "com.amazon.tv.website_launcher",
    )

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val own = context.packageName
        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        val seen = LinkedHashMap<String, AppEntry>()
        for (category in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == own || pkg in HIDDEN || seen.containsKey(pkg)) continue
                val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                val appInfo = info.activityInfo.applicationInfo
                val banner = runCatching {
                    info.activityInfo.loadBanner(pm) ?: appInfo.loadBanner(pm)
                }.getOrNull()
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                seen[pkg] = AppEntry(
                    pkg = pkg,
                    label = info.loadLabel(pm).toString(),
                    banner = banner?.toImage(320, 180),
                    icon = icon?.toImage(176, 176),
                    launch = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun storeIntent(pm: PackageManager): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(APPSTORE)
            ?: pm.getLaunchIntentForPackage(APPSTORE)
            ?: pm.getLeanbackLaunchIntentForPackage(DOWNLOADER)
            ?: pm.getLaunchIntentForPackage(DOWNLOADER))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Le impostazioni di Fire OS stanno dentro il launcher Amazon (scheda Impostazioni della sua home).
     * L'azione standard Android ACTION_SETTINGS porta invece a una schermata vuota di compatibilità,
     * quindi apro direttamente le activity del launcher, con quella standard solo come ultima risorsa.
     */
    fun settingsIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_MAIN).setComponent(
            android.content.ComponentName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.SettingsActivity")
        ),
        Intent(Intent.ACTION_MAIN).setComponent(
            android.content.ComponentName(AMAZON_LAUNCHER, "$AMAZON_LAUNCHER.ui.MainSettingsActivity")
        ),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"

    private fun Drawable.toImage(w: Int, h: Int): ImageBitmap = toBitmap(w, h).asImageBitmap()
}
