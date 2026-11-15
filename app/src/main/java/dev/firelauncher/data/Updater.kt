package dev.firelauncher.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import dev.firelauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamenti dell'app dalle release del repository GitHub (compilate da GitHub Actions).
 * Attivo solo nelle build pubblicate (BuildConfig.UPDATES_ENABLED): chi compila da sé
 * aggiorna ricompilando, perché una firma diversa non permetterebbe l'aggiornamento.
 *
 * Le release hanno tag "build-<numero>": il numero è il versionCode dell'APK.
 */
object Updater {
    private const val TAG = "Updater"

    data class Release(val versionCode: Int, val apkUrl: String)

    val enabled: Boolean get() = BuildConfig.UPDATES_ENABLED

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "FireLauncher")
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            try {
                if (conn.responseCode != 200) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val code = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@runCatching null
                val assets = json.optJSONArray("assets") ?: return@runCatching null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@runCatching null
                Release(code, apk.getString("browser_download_url"))
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "controllo aggiornamenti non riuscito", it) }.getOrNull()
    }

    /** Scarica l'APK e apre l'installer di Fire OS, che chiede conferma all'utente. */
    suspend fun install(context: Context, release: Release, onProgress: (Int) -> Unit) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "FireLauncher.apk")
        withContext(Dispatchers.IO) {
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "FireLauncher")
            try {
                val total = conn.contentLengthLong.coerceAtLeast(1)
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
