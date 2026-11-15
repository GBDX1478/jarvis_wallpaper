package fr.guill.jarvis

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Construit les phrases prononcées par Jarvis. */
object Speech {

    private fun hourText(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return when {
            h == 12 && m == 0 -> "midi"
            h == 0 && m == 0 -> "minuit"
            m == 0 -> "$h heures"
            else -> "$h heures $m"
        }
    }

    private fun deg(d: Double) = "${d.roundToInt()} degrés"

    private fun pct(p: Double): String = String.format(Locale.FRANCE, "%.1f", abs(p)).replace(",0", "") + " pour cent"

    fun dateText(): String = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date())

    // ------------------------------------------------------------------ morceaux
    fun weatherShort(w: Weather?): String? {
        w ?: return null
        val where = if (w.place.isNotBlank()) " à ${w.place}" else ""
        var s = "Il fait ${deg(w.temp)}$where, ${Weather.label(w.code).lowercase(Locale.FRANCE)}."
        if (w.rainHour != null) s += " Pluie probable vers ${w.rainHour} heures, prévoyez un parapluie."
        return s
    }

    fun weatherFull(w: Weather?): String {
        w ?: return "Je n'ai pas encore de données météo. Choisissez une ville dans l'application Jarvis."
        return weatherShort(w) +
            " Aujourd'hui, maximum ${deg(w.max)}, minimum ${deg(w.min)}." +
            " Demain : ${Weather.label(w.tmrCode).lowercase(Locale.FRANCE)}, entre ${deg(w.tmrMin)} et ${deg(w.tmrMax)}."
    }

    private fun eventText(e: Event, withDay: Boolean = false): String {
        val day = if (withDay) dayWord(e.begin) + " " else ""
        val loc = if (e.location.isNotBlank()) ", ${e.location}" else ""
        return if (e.allDay) "${day}toute la journée : ${e.title}$loc" else "${day}à ${hourText(e.begin)} : ${e.title}$loc"
    }

    private fun dayWord(t: Long): String = when {
        t < Agenda.dayStart(1) -> "aujourd'hui"
        t < Agenda.dayStart(2) -> "demain"
        else -> SimpleDateFormat("EEEE", Locale.FRANCE).format(Date(t))
    }

    fun agendaToday(c: Context): String {
        if (!Agenda.hasPermission(c)) return "Je n'ai pas accès à votre agenda. Autorisez-le dans l'application Jarvis."
        val (today, tomorrow) = Agenda.todayAndTomorrow(c)
        val sb = StringBuilder()
        when (today.size) {
            0 -> sb.append("Aucun rendez-vous de prévu pour le reste de la journée.")
            1 -> sb.append("Vous avez un rendez-vous aujourd'hui, ${eventText(today[0])}.")
            else -> sb.append("Vous avez ${today.size} rendez-vous aujourd'hui. ")
                .append(today.take(5).joinToString(" ; ") { eventText(it) }).append(".")
        }
        if (tomorrow.isNotEmpty()) {
            sb.append(" Demain, ").append(if (tomorrow.size == 1) "un rendez-vous" else "${tomorrow.size} rendez-vous")
                .append(", le premier ").append(eventText(tomorrow[0]).replaceFirst("à ", "à ")).append(".")
        }
        return sb.toString()
    }

    fun nextEvent(c: Context): String? {
        val e = Agenda.next(c) ?: return null
        val now = System.currentTimeMillis()
        if (e.begin <= now) return "En ce moment : ${e.title}."
        return "Prochain rendez-vous ${eventText(e, withDay = true)}."
    }

    fun markets(quotes: List<Quote>, short: Boolean = false): String {
        if (quotes.isEmpty()) return "Je n'ai pas encore les cours de la bourse. Vérifiez la connexion internet."
        val parts = mutableListOf<String>()
        for (q in quotes) {
            if (short && q.kind == 2 && q.label != "EUR/USD") continue
            if (short && q.label == "ETH") continue
            val p = q.pct
            when (q.kind) {
                2 -> {
                    val v = String.format(Locale.FRANCE, if (q.decimals >= 4) "%.3f" else "%.1f", q.value)
                    val unit = when (q.label) { "EUR/USD" -> "dollar"; else -> "yens" }
                    val who = when (q.label) { "EUR/USD" -> "Un euro vaut"; "EUR/JPY" -> "Un euro vaut"; else -> "Un dollar vaut" }
                    parts += "$who $v $unit"
                }
                else -> parts += when {
                    p == null -> "${q.spoken} est à ${q.valueText()}"
                    abs(p) < 0.05 -> "${q.spoken} est stable"
                    p > 0 -> "${q.spoken} est en hausse de ${pct(p)}"
                    else -> "${q.spoken} est en baisse de ${pct(p)}"
                }
            }
        }
        return parts.joinToString(". ") + "."
    }

    // ------------------------------------------------------------------ discours complets
    /** Rapport quand on touche le réacteur. */
    fun report(c: Context, s: Snapshot?, w: Weather?): String {
        val parts = mutableListOf("${SystemInfo.greeting()} ${Prefs.userName(c)}.", "Il est ${hourText(System.currentTimeMillis())}.")
        weatherShort(w)?.let { parts += it }
        nextEvent(c)?.let { parts += it }
        if (s != null) {
            parts += "Batterie à ${s.batPct} pour cent" + (if (s.charging) ", en charge." else ".")
            if (!s.charging && s.batPct <= 20) parts += "Je vous recommande de brancher le chargeur."
            if (s.stoUsedPct >= 90) parts += "Attention, le stockage est presque plein."
            if (s.batTemp >= 42) parts += "La batterie chauffe : ${s.batTemp.toInt()} degrés."
        }
        parts += "Tous les systèmes sont opérationnels."
        return parts.joinToString(" ")
    }

    /** Briefing du matin. */
    fun briefing(c: Context, s: Snapshot?, w: Weather?, quotes: List<Quote>): String {
        val parts = mutableListOf("${SystemInfo.greeting()} ${Prefs.userName(c)}. Nous sommes ${dateText()}.")
        if (w != null) parts += weatherShort(w)!! + " Maximum ${deg(w.max)}."
        parts += agendaToday(c)
        if (quotes.isNotEmpty()) parts += "Côté marchés : " + markets(quotes, short = true)
        if (s != null) parts += "Batterie à ${s.batPct} pour cent."
        parts += "Excellente journée."
        return parts.joinToString(" ")
    }

    /** Rappel avant un rendez-vous. */
    fun reminder(title: String, begin: Long, location: String): String {
        val mins = ((begin - System.currentTimeMillis()) / 60000.0).roundToInt().coerceAtLeast(0)
        val loc = if (location.isNotBlank()) ", à l'adresse suivante : $location" else ""
        return if (mins <= 1) "Rappel : $title commence maintenant$loc."
        else "Rappel : dans $mins minutes, $title$loc."
    }
}
