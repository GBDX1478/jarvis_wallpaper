package fr.guill.jarvis

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Réglages partagés entre l'écran de réglages et le fond d'écran. */
object Prefs {
    val COLORS = listOf(
        "Arc" to 0xFF3EE8FF.toInt(),
        "Mark" to 0xFFFF6A3D.toInt(),
        "Or" to 0xFFFFC44D.toInt(),
        "Spectre" to 0xFFB77BFF.toInt(),
        "Matrice" to 0xFF45FF8F.toInt(),
        "Glace" to 0xFFE8F6FF.toInt(),
    )

    const val K_COLOR = "color"
    const val K_DIM = "dim"
    const val K_NAME = "name"
    const val K_SLIDE = "slide"      // 0 = jamais, -1 = à chaque retour à l'accueil, sinon minutes
    const val K_FPS = "fps"          // images par seconde (1 = économie)
    const val K_PANELS = "panels"
    const val K_CURRENT = "current"  // nom du fichier image affiché ("" = fond par défaut)
    const val K_BGREV = "bgrev"      // change quand la liste d'images change

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun color(c: Context): Int = COLORS.getOrElse(sp(c).getInt(K_COLOR, 0)) { COLORS[0] }.second
    fun dim(c: Context): Int = sp(c).getInt(K_DIM, 40)
    fun userName(c: Context): String =
        sp(c).getString(K_NAME, "")?.trim()?.takeIf { it.isNotEmpty() } ?: "Monsieur"
    fun slide(c: Context): Int = sp(c).getInt(K_SLIDE, 0)
    fun fps(c: Context): Int = sp(c).getInt(K_FPS, 20)
    fun panels(c: Context): Boolean = sp(c).getBoolean(K_PANELS, true)
    fun current(c: Context): String = sp(c).getString(K_CURRENT, "") ?: ""

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
