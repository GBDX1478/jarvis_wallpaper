package fr.guill.jarvis

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class Weather(
    val place: String,
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val max: Double,
    val min: Double,
    val rainMax: Int,
    val rainHour: Int?,     // heure de la prochaine pluie probable (aujourd'hui), ou null
    val tmrCode: Int,
    val tmrMax: Double,
    val tmrMin: Double,
) {
    fun toJson(): String = JSONObject().apply {
        put("place", place); put("temp", temp); put("code", code); put("isDay", isDay)
        put("max", max); put("min", min); put("rainMax", rainMax); put("rainHour", rainHour ?: -1)
        put("tmrCode", tmrCode); put("tmrMax", tmrMax); put("tmrMin", tmrMin)
    }.toString()

    companion object {
        fun fromJson(s: String?): Weather? = runCatching {
            val o = JSONObject(s ?: return null)
            Weather(
                o.getString("place"), o.getDouble("temp"), o.getInt("code"), o.getBoolean("isDay"),
                o.getDouble("max"), o.getDouble("min"), o.getInt("rainMax"),
                o.getInt("rainHour").takeIf { it >= 0 },
                o.getInt("tmrCode"), o.getDouble("tmrMax"), o.getDouble("tmrMin"),
            )
        }.getOrNull()

        /** Interroge Open-Meteo (gratuit, sans clé). */
        fun fetch(lat: Double, lon: Double, place: String): Weather? {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,is_day&hourly=precipitation_probability" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=2",
                lat, lon
            )
            val o = JSONObject(Net.get(url) ?: return null)
            val cur = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            val hourly = o.getJSONObject("hourly")

            // Prochaine pluie probable (≥ 60 %) dans les 12 heures, aujourd'hui seulement
            val curTime = cur.getString("time")            // ex. 2026-11-15T18:45
            val curHourKey = curTime.substring(0, 13)      // 2026-11-15T18
            val times = hourly.getJSONArray("time")
            val probs = hourly.getJSONArray("precipitation_probability")
            var rainHour: Int? = null
            var start = -1
            for (i in 0 until times.length()) if (times.getString(i).startsWith(curHourKey)) { start = i; break }
            if (start >= 0) {
                for (i in start until minOf(start + 12, times.length())) {
                    val t = times.getString(i)
                    if (!t.startsWith(curTime.substring(0, 10))) break
                    if (!probs.isNull(i) && probs.getInt(i) >= 60) { rainHour = t.substring(11, 13).toInt(); break }
                }
            }
            val dCode = daily.getJSONArray("weather_code")
            val dMax = daily.getJSONArray("temperature_2m_max")
            val dMin = daily.getJSONArray("temperature_2m_min")
            val dRain = daily.getJSONArray("precipitation_probability_max")
            return Weather(
                place = place,
                temp = cur.getDouble("temperature_2m"),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                max = dMax.getDouble(0), min = dMin.getDouble(0),
                rainMax = if (dRain.isNull(0)) 0 else dRain.getInt(0),
                rainHour = rainHour,
                tmrCode = dCode.getInt(1), tmrMax = dMax.getDouble(1), tmrMin = dMin.getDouble(1),
            )
        }

        /** Trouve une ville par son nom. Renvoie (nom, lat, lon). */
        fun geocode(name: String): Triple<String, Double, Double>? = runCatching {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" +
                URLEncoder.encode(name.trim(), "UTF-8")
            val res = JSONObject(Net.get(url) ?: return null).optJSONArray("results") ?: return null
            if (res.length() == 0) return null
            val r = res.getJSONObject(0)
            Triple(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
        }.getOrNull()

        fun label(code: Int): String = when (code) {
            0 -> "Ciel dégagé"
            1 -> "Peu nuageux"
            2 -> "Partiellement nuageux"
            3 -> "Couvert"
            45, 48 -> "Brouillard"
            51, 53, 55, 56, 57 -> "Bruine"
            61, 63, 65, 66, 67 -> "Pluie"
            71, 73, 75, 77 -> "Neige"
            80, 81, 82 -> "Averses"
            85, 86 -> "Averses de neige"
            95, 96, 99 -> "Orage"
            else -> "Variable"
        }

        fun icon(code: Int, day: Boolean = true): String = when (code) {
            0 -> if (day) "☀️" else "🌙"
            1 -> if (day) "🌤️" else "🌙"
            2 -> "⛅"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57, 80, 81, 82 -> "🌦️"
            61, 63, 65, 66, 67 -> "🌧️"
            71, 73, 75, 77, 85, 86 -> "❄️"
            95, 96, 99 -> "⛈️"
            else -> "🌡️"
        }
    }
}
