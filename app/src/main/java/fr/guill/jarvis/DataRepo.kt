package fr.guill.jarvis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale

/** Récupère météo et marchés, et les garde en cache dans les réglages. */
object DataRepo {
    private const val WEATHER_TTL = 15 * 60_000L
    private const val MARKETS_TTL = 10 * 60_000L
    private val lock = Any()

    fun weather(c: Context): Weather? = Weather.fromJson(Prefs.sp(c).getString(Prefs.K_W_JSON, null))
    fun quotes(c: Context): List<Quote> = Markets.fromJson(Prefs.sp(c).getString(Prefs.K_M_JSON, null))

    fun isStale(c: Context): Boolean {
        val sp = Prefs.sp(c)
        val now = System.currentTimeMillis()
        return now - sp.getLong(Prefs.K_W_TIME, 0) > WEATHER_TTL || now - sp.getLong(Prefs.K_M_TIME, 0) > MARKETS_TTL
    }

    /** À appeler hors du fil principal (réseau). */
    fun refresh(c: Context, force: Boolean = false) {
        synchronized(lock) { doRefresh(c, force) }
    }

    private fun doRefresh(c: Context, force: Boolean) {
        val sp = Prefs.sp(c)
        val now = System.currentTimeMillis()
        if (force || now - sp.getLong(Prefs.K_W_TIME, 0) > WEATHER_TTL) {
            val loc = location(c)
            if (loc != null) {
                Weather.fetch(loc.first, loc.second, loc.third)?.let {
                    sp.edit().putString(Prefs.K_W_JSON, it.toJson()).putLong(Prefs.K_W_TIME, now).apply()
                }
            }
        }
        if (force || now - sp.getLong(Prefs.K_M_TIME, 0) > MARKETS_TTL) {
            val q = Markets.fetchAll()
            if (q.isNotEmpty()) sp.edit().putString(Prefs.K_M_JSON, Markets.toJson(q)).putLong(Prefs.K_M_TIME, now).apply()
        }
    }

    fun hasLocationPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** (lat, lon, nom du lieu) : position du téléphone si autorisée, sinon la ville choisie. */
    fun location(c: Context): Triple<Double, Double, String>? {
        if (Prefs.useLocation(c) && hasLocationPermission(c)) {
            lastKnown(c)?.let { l ->
                val name = placeName(c, l.latitude, l.longitude) ?: Prefs.city(c)
                Prefs.sp(c).edit()
                    .putString(Prefs.K_LAT, l.latitude.toString())
                    .putString(Prefs.K_LON, l.longitude.toString())
                    .putString(Prefs.K_CITY, name)
                    .apply()
                return Triple(l.latitude, l.longitude, name)
            }
        }
        val lat = Prefs.lat(c) ?: return null
        val lon = Prefs.lon(c) ?: return null
        return Triple(lat, lon, Prefs.city(c))
    }

    @Suppress("MissingPermission")
    fun lastKnown(c: Context): Location? {
        if (!hasLocationPermission(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    fun placeName(c: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(c, Locale.FRANCE).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()
}
