package dev.firelauncher.data

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class WeatherNow(val temperature: Int, val symbol: String, val label: String)

data class Place(val name: String, val lat: Double, val lon: Double)

/** Meteo da Open-Meteo: gratuito, senza chiave. */
object Weather {
    private const val TAG = "Weather"

    suspend fun current(lat: Double, lon: Double): WeatherNow? = withContext(Dispatchers.IO) {
        runCatching {
            val json = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
            )
            val cur = JSONObject(json).getJSONObject("current")
            val (symbol, label) = describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
            WeatherNow(cur.getDouble("temperature_2m").roundToInt(), symbol, label)
        }.onFailure { Log.w(TAG, "meteo non disponibile", it) }.getOrNull()
    }

    suspend fun geocode(city: String): Place? = withContext(Dispatchers.IO) {
        runCatching {
            val json = get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=it&format=json&name=" +
                    Uri.encode(city.trim())
            )
            val r = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return@runCatching null
            Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
        }.onFailure { Log.w(TAG, "città non trovata", it) }.getOrNull()
    }

    /** Codici WMO -> simbolo e descrizione breve. */
    private fun describe(code: Int, day: Boolean): Pair<String, String> = when (code) {
        0 -> (if (day) "☀" else "☾") to "Sereno"
        1 -> (if (day) "🌤" else "☾") to "Poco nuvoloso"
        2 -> "⛅" to "Nuvoloso"
        3 -> "☁" to "Coperto"
        45, 48 -> "🌫" to "Nebbia"
        in 51..57 -> "🌦" to "Pioviggine"
        in 61..67 -> "🌧" to "Pioggia"
        in 71..77, 85, 86 -> "❄" to "Neve"
        in 80..82 -> "🌧" to "Rovesci"
        in 95..99 -> "⛈" to "Temporale"
        else -> "☁" to ""
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
