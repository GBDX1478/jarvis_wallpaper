package fr.guill.jarvis

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Réglages partagés entre l'écran de réglages, le fond d'écran et les rappels. */
object Prefs {
    val COLORS = listOf(
        "Arc" to 0xFF3EE8FF.toInt(),
        "Mark" to 0xFFFF6A3D.toInt(),
        "Or" to 0xFFFFC44D.toInt(),
        "Spectre" to 0xFFB77BFF.toInt(),
        "Matrice" to 0xFF45FF8F.toInt(),
        "Glace" to 0xFFE8F6FF.toInt(),
    )

    // Apparence
    const val K_COLOR = "color"
    const val K_DIM = "dim"
    const val K_NAME = "name"
    const val K_SLIDE = "slide"      // 0 = jamais, -1 = à chaque retour à l'accueil, sinon minutes
    const val K_FPS = "fps"          // images par seconde (1 = économie)
    const val K_PANELS = "panels"
    const val K_CURRENT = "current"  // nom du fichier image affiché ("" = fond par défaut)
    const val K_BGREV = "bgrev"      // change quand la liste d'images change

    // Météo
    const val K_USE_LOC = "use_loc"
    const val K_CITY = "city"
    const val K_LAT = "lat"
    const val K_LON = "lon"

    // Agenda & briefing
    const val K_REMIND = "remind"
    const val K_REMIND_MIN = "remind_min"
    const val K_BRIEFING = "briefing"
    const val K_VOICE = "voice"
    const val K_CENTER = "center"   // 0 heure, 1 météo, 2 batterie, 3 agenda, 4 rien
    const val K_LAST_BRIEF = "last_brief"

    // Caches (écrits par DataRepo)
    const val K_W_JSON = "cache_weather"
    const val K_W_TIME = "cache_weather_t"
    const val K_M_JSON = "cache_markets"
    const val K_M_TIME = "cache_markets_t"

    /** Clés qui ne changent que les données affichées (pas besoin de recharger le fond). */
    val CACHE_KEYS = setOf(K_W_JSON, K_W_TIME, K_M_JSON, K_M_TIME, K_LAST_BRIEF)

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun color(c: Context): Int = COLORS.getOrElse(sp(c).getInt(K_COLOR, 0)) { COLORS[0] }.second
    fun dim(c: Context): Int = sp(c).getInt(K_DIM, 40)
    fun userName(c: Context): String =
        sp(c).getString(K_NAME, "")?.trim()?.takeIf { it.isNotEmpty() } ?: "Monsieur"
    fun slide(c: Context): Int = sp(c).getInt(K_SLIDE, 0)
    fun fps(c: Context): Int = sp(c).getInt(K_FPS, 20)
    fun panels(c: Context): Boolean = sp(c).getBoolean(K_PANELS, true)
    fun current(c: Context): String = sp(c).getString(K_CURRENT, "") ?: ""

    fun useLocation(c: Context): Boolean = sp(c).getBoolean(K_USE_LOC, false)
    fun city(c: Context): String = sp(c).getString(K_CITY, "") ?: ""
    fun lat(c: Context): Double? = sp(c).getString(K_LAT, null)?.toDoubleOrNull()
    fun lon(c: Context): Double? = sp(c).getString(K_LON, null)?.toDoubleOrNull()

    fun remind(c: Context): Boolean = sp(c).getBoolean(K_REMIND, true)
    fun remindMinutes(c: Context): Int = sp(c).getInt(K_REMIND_MIN, 15)
    fun briefing(c: Context): Boolean = sp(c).getBoolean(K_BRIEFING, true)
    fun voice(c: Context): Boolean = sp(c).getBoolean(K_VOICE, false)
    fun center(c: Context): Int = sp(c).getInt(K_CENTER, 1)

    fun imagesDir(c: Context): File = File(c.filesDir, "fonds").apply { mkdirs() }
    fun images(c: Context): List<File> =
        imagesDir(c).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun currentFile(c: Context): File? {
        val name = current(c)
        if (name.isEmpty()) return null
        return File(imagesDir(c), name).takeIf { it.exists() }
    }

    /** Passe à l'image suivante (diaporama). */
    fun next(c: Context) {
        val list = images(c)
        if (list.size < 2) return
        val i = list.indexOfFirst { it.name == current(c) }
        sp(c).edit().putString(K_CURRENT, list[(i + 1) % list.size].name).apply()
    }
}
