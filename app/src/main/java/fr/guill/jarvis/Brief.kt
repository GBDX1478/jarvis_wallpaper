package fr.guill.jarvis

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fiches « points clés » affichées sur le fond d'écran, dans l'appli et en notification.
 * trend : 1 = hausse (vert), -1 = baisse (rouge), 0 = neutre. alert = valeur à surveiller (orange).
 */
data class Line(
    val icon: String,
    val label: String,
    val value: String = "",
    val change: String = "",
    val trend: Int = 0,
    val alert: Boolean = false,
)

data class Section(val title: String, val lines: List<Line>)

data class Card(val title: String, val sections: List<Section>) {
    /** Lignes texte pour une notification. */
    fun plainLines(): List<String> = sections.flatMap { s ->
        s.lines.map { l ->
            listOf(l.icon, l.label, l.value, l.change).filter { it.isNotBlank() }.joinToString("  ")
        }
    }
}

object Brief {
    private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private fun t(ms: Long) = hm.format(Date(ms))
    private fun r(d: Double) = d.roundToInt()

    private fun arrow(p: Double?) = when {
        p == null -> ""
        p > 0.005 -> "▲"
        p < -0.005 -> "▼"
        else -> "■"
    }

    private fun trend(p: Double?) = when {
        p == null -> 0
        p > 0.005 -> 1
        p < -0.005 -> -1
        else -> 0
    }

    // ------------------------------------------------------------------ morceaux
    private fun weatherLines(w: Weather?, full: Boolean): List<Line> {
        if (w == null) return listOf(Line("🌍", "Aucune ville", "à choisir dans l'appli", alert = true))
        val out = mutableListOf(
            Line(Weather.icon(w.code, w.isDay), Weather.label(w.code), "${r(w.temp)}°"),
            Line("🌡️", "Min / Max", "${r(w.min)}° / ${r(w.max)}°"),
        )
        out += if (w.rainHour != null) Line("☔", "Pluie", "vers ${w.rainHour} h", alert = true)
        else Line("☂️", "Pluie", if (w.rainMax >= 40) "${w.rainMax} %" else "non")
        if (full) out += Line(Weather.icon(w.tmrCode), "Demain", "${r(w.tmrMin)}° / ${r(w.tmrMax)}°", Weather.label(w.tmrCode))
        return out
    }

    private fun eventLine(e: Event, now: Long): Line {
        val time = if (e.allDay) "Journée" else t(e.begin)
        val label = e.title + if (e.location.isNotBlank()) " · ${e.location}" else ""
        val soon = !e.allDay && e.begin > now && e.begin - now <= 30 * 60_000
        val current = !e.allDay && e.begin <= now
        return Line(if (current) "🔴" else "🗓️", label, if (current) "En cours" else time, alert = soon)
    }

    private fun agendaSections(c: Context, maxToday: Int, maxTomorrow: Int): List<Section> {
        if (!Agenda.hasPermission(c)) return listOf(Section("AGENDA", listOf(Line("🔒", "Accès à autoriser", "dans l'appli", alert = true))))
        val now = System.currentTimeMillis()
        val (today, tomorrow) = Agenda.todayAndTomorrow(c)
        val out = mutableListOf<Section>()
        out += Section(
            "AUJOURD'HUI · ${today.size} RDV",
            if (today.isEmpty()) listOf(Line("✅", "Rien de prévu")) else today.take(maxToday).map { eventLine(it, now) } +
                if (today.size > maxToday) listOf(Line("➕", "${today.size - maxToday} autre(s)")) else emptyList()
        )
        if (maxTomorrow > 0) out += Section(
            "DEMAIN · ${tomorrow.size} RDV",
            if (tomorrow.isEmpty()) listOf(Line("✅", "Rien de prévu")) else tomorrow.take(maxTomorrow).map { eventLine(it, now) }
        )
        return out
    }

    private fun quoteLine(q: Quote): Line {
        val icon = when (q.kind) { 1 -> "🪙"; 2 -> "💱"; else -> "📈" }
        return Line(icon, q.label, q.valueText(), (arrow(q.pct) + " " + q.pctText()).trim(), trend(q.pct))
    }

    private fun systemLines(s: Snapshot?): List<Line> {
        s ?: return emptyList()
        val lowBat = !s.charging && s.batPct <= 20
        return listOf(
            Line(if (s.charging) "⚡" else "🔋", "Batterie", "${s.batPct} %", if (s.charging) "en charge" else "", alert = lowBat),
            Line("💾", "Stockage libre", SystemInfo.go(s.stoFree), "${s.stoUsedPct} % utilisés", alert = s.stoUsedPct >= 90),
            Line("🧠", "Mémoire", "${s.ramPct} %", alert = s.ramPct >= 90),
            Line("🌡️", "Température", "${s.batTemp.roundToInt()} °C", alert = s.batTemp >= 42),
        )
    }

    // ------------------------------------------------------------------ fiches
    fun weather(w: Weather?): Card =
        Card("MÉTÉO" + if (w != null && w.place.isNotBlank()) " · ${w.place.uppercase(Locale.FRANCE)}" else "",
            listOf(Section("", weatherLines(w, full = true))))

    fun agenda(c: Context): Card = Card("AGENDA", agendaSections(c, 6, 3))

    fun markets(quotes: List<Quote>): Card {
        if (quotes.isEmpty()) return Card("MARCHÉS", listOf(Section("", listOf(Line("📡", "Pas de données", "vérifier internet", alert = true)))))
        val groups = listOf(0 to "INDICES", 1 to "CRYPTOS", 2 to "DEVISES")
        return Card("MARCHÉS", groups.mapNotNull { (k, name) ->
            val l = quotes.filter { it.kind == k }
            if (l.isEmpty()) null else Section(name, l.map { quoteLine(it) })
        })
    }

    fun report(c: Context, s: Snapshot?, w: Weather?, quotes: List<Quote>): Card {
        val now = System.currentTimeMillis()
        val sections = mutableListOf<Section>()
        sections += Section("SYSTÈME", systemLines(s))
        val ev = if (Agenda.hasPermission(c)) Agenda.next(c) else null
        val glance = mutableListOf<Line>()
        if (w != null) glance += Line(Weather.icon(w.code, w.isDay), "Météo", "${r(w.temp)}°", Weather.label(w.code))
        glance += if (ev != null) eventLine(ev, now).copy(icon = "🗓️") else Line("🗓️", "Agenda", "libre")
        quotes.firstOrNull { it.label == "CAC 40" }?.let { glance += quoteLine(it) }
        sections += Section("EN UN COUP D'ŒIL", glance)
        return Card("RAPPORT · ${t(now)}", sections)
    }

    fun briefing(c: Context, s: Snapshot?, w: Weather?, quotes: List<Quote>): Card {
        val date = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date()).uppercase(Locale.FRANCE)
        val sections = mutableListOf<Section>()
        sections += Section("MÉTÉO" + if (w != null && w.place.isNotBlank()) " · ${w.place.uppercase(Locale.FRANCE)}" else "", weatherLines(w, full = false))
        sections += agendaSections(c, 4, 0)
        val keyMarkets = quotes.filter { it.label in setOf("CAC 40", "S&P 500", "NASDAQ", "BTC", "EUR/USD") }
        if (keyMarkets.isNotEmpty()) sections += Section("MARCHÉS", keyMarkets.map { quoteLine(it) })
        s?.let { sections += Section("SYSTÈME", systemLines(it).take(2)) }
        return Card("BRIEFING · $date", sections)
    }
}
