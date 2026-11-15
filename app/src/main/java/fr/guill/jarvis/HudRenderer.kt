package fr.guill.jarvis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Zones touchables du fond d'écran. */
enum class Zone { REACTOR, TICKER, WEATHER, AGENDA, ENERGY, STORAGE, NONE }

/** Dessine l'interface façon J.A.R.V.I.S. sur le fond d'écran. */
class HudRenderer(ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var accent: Int = 0xFF3EE8FF.toInt()
    var userName = "Monsieur"
    var showPanels = true
    var animate = true
    var background: Bitmap? = null
    var offsetX = 0.5f

    // Données affichées
    var weather: Weather? = null
    var quotes: List<Quote> = emptyList()
    var nextEvent: Event? = null
    var agendaAllowed = false

    // Message texte affiché quand on touche une zone (Jarvis silencieux)
    private var message: String? = null
    private var messageUntil = 0L
    private var messageLayout: StaticLayout? = null
    private val msgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun showMessage(t: String, now: Long) {
        message = t
        messageUntil = now + 12_000
        messageLayout = null
    }

    fun clearMessage() { message = null; messageLayout = null }

    fun hasMessage(now: Long): Boolean = message != null && now < messageUntil

    private var w = 0
    private var h = 0
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var dashOuter: DashPathEffect? = null
    private var dashSeg: DashPathEffect? = null
    private val tickerRect = RectF()
    private val panelRects = List(4) { RectF() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val regular: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.FRANCE)
    private val dayFmt = SimpleDateFormat("EEE", Locale.FRANCE)
    private val rect = RectF()
    private val path = Path()

    private val warnColor = 0xFFFFB347.toInt()
    private val critColor = 0xFFFF4D5E.toInt()
    private val upColor = 0xFF45E08A.toInt()
    private val downColor = 0xFFFF5B6B.toInt()
    private val subColor = Color.rgb(169, 204, 216)

    private data class Panel(val title: String, val value: String, val sub: String, val frac: Float?, val level: Int)

    fun layout(width: Int, height: Int) {
        w = width
        h = height
        radius = min(w, h) * 0.35f
        cx = w / 2f
        cy = h * 0.37f
        val k = radius / 146f
        dashOuter = DashPathEffect(floatArrayOf(2 * k, 6 * k), 0f)
        dashSeg = DashPathEffect(floatArrayOf(40 * k, 18 * k, 6 * k, 18 * k), 0f)
        tickerRect.set(0f, dp(80f), w.toFloat(), dp(108f))
    }

    fun zoneAt(x: Float, y: Float): Zone {
        if (hypot(x - cx, y - cy) <= radius * 1.05f) return Zone.REACTOR
        if (tickerRect.contains(x, y)) return Zone.TICKER
        if (showPanels) {
            val zones = arrayOf(Zone.WEATHER, Zone.AGENDA, Zone.ENERGY, Zone.STORAGE)
            panelRects.forEachIndexed { i, r -> if (r.contains(x, y)) return zones[i] }
        }
        return Zone.NONE
    }

    private fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun acc(a: Float) = alpha(accent, a)

    private fun rot(t: Long, period: Long): Float = (t % period) * 360f / period

    fun draw(c: Canvas, now: Long, s: Snapshot?, talking: Boolean) {
        if (w == 0) return
        c.drawColor(Color.rgb(2, 7, 12))
        background?.let { bg ->
            val extra = (bg.width - w).coerceAtLeast(0)
            c.drawBitmap(bg, -extra * offsetX, 0f, bmpPaint)
        }
        drawTop(c, s)
        drawTicker(c, now)
        drawReactor(c, now, s, talking)
        if (showPanels) drawPanels(c, s, now)
        if (hasMessage(now)) drawMessage(c, now) else if (message != null) clearMessage()
    }

    private fun drawMessage(c: Canvas, now: Long) {
        val m = dp(16f)
        val pad = dp(16f)
        val boxW = w - 2 * m
        if (messageLayout == null) {
            msgPaint.typeface = regular
            msgPaint.textSize = dp(15f)
            msgPaint.color = Color.WHITE
            val txt = message ?: return
            messageLayout = StaticLayout.Builder.obtain(txt, 0, txt.length, msgPaint, (boxW - 2 * pad).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dp(3f), 1f)
                .setMaxLines(14)
                .build()
        }
        val lay = messageLayout ?: return
        val boxH = lay.height + 2 * pad + dp(18f)
        var top = cy + radius + dp(28f)
        if (top + boxH > h - dp(24f)) top = (h - dp(24f) - boxH).coerceAtLeast(dp(116f))
        val cut = dp(14f)
        path.reset()
        path.moveTo(m, top)
        path.lineTo(m + boxW - cut, top)
        path.lineTo(m + boxW, top + cut)
        path.lineTo(m + boxW, top + boxH)
        path.lineTo(m + cut, top + boxH)
        path.lineTo(m, top + boxH - cut)
        path.close()
        fill.color = Color.argb(225, 3, 14, 22)
        c.drawPath(path, fill)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = acc(0.7f)
        c.drawPath(path, stroke)
        fill.color = acc(1f)
        c.drawRect(m, top, m + dp(40f), top + dp(2.5f), fill)
        // barre de temps restant
        val left = ((messageUntil - now) / 12_000f).coerceIn(0f, 1f)
        fill.color = acc(0.5f)
        c.drawRect(m + pad, top + boxH - dp(8f), m + pad + (boxW - 2 * pad) * left, top + boxH - dp(6f), fill)
        c.save()
        c.translate(m + pad, top + pad)
        lay.draw(c)
        c.restore()
    }

    // ------------------------------------------------------------------ réacteur
    private fun drawReactor(c: Canvas, now: Long, s: Snapshot?, talking: Boolean) {
        val k = radius / 146f
        val t = if (animate) now else 0L

        val glow = if (talking) 0.30f + 0.25f * abs(sin(now / 160.0)).toFloat() else 0.26f
        fill.shader = RadialGradient(cx, cy, 128 * k, acc(glow), acc(0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 128 * k, fill)
        fill.shader = null

        stroke.setShadowLayer(dp(if (talking) 9f else 5f), 0f, 0f, acc(0.85f))

        c.save()
        c.rotate(rot(t, 40000), cx, cy)
        stroke.pathEffect = dashOuter
        stroke.strokeWidth = 1.4f * k
        stroke.color = acc(0.6f)
        c.drawCircle(cx, cy, 146 * k, stroke)
        stroke.pathEffect = null
        c.restore()

        stroke.strokeWidth = 7 * k
        stroke.color = acc(0.15f)
        c.drawCircle(cx, cy, 136 * k, stroke)
        val lvl = (s?.batPct ?: 0).coerceIn(0, 100)
        stroke.color = when {
            s == null -> acc(1f)
            !s.charging && lvl <= 15 -> critColor
            !s.charging && lvl <= 30 -> warnColor
            else -> acc(1f)
        }
        rect.set(cx - 136 * k, cy - 136 * k, cx + 136 * k, cy + 136 * k)
        c.drawArc(rect, -90f, 360f * lvl / 100f, false, stroke)

        stroke.clearShadowLayer()
        c.save()
        c.rotate(-rot(t, 26000), cx, cy)
        for (i in 0 until 72) {
            val long = i % 6 == 0
            val ang = Math.toRadians(i * 5.0)
            val r1 = (if (long) 111f else 116f) * k
            val r2 = 124f * k
            stroke.strokeWidth = (if (long) 2.2f else 1.1f) * k
            stroke.color = acc(if (long) 0.9f else 0.4f)
            val sn = sin(ang).toFloat()
            val cs = cos(ang).toFloat()
            c.drawLine(cx + r1 * sn, cy - r1 * cs, cx + r2 * sn, cy - r2 * cs, stroke)
        }
        c.restore()
        stroke.setShadowLayer(dp(5f), 0f, 0f, acc(0.85f))

        val sec = (now / 1000 % 60) + (now % 1000) / 1000f
        stroke.strokeWidth = 3 * k
        stroke.color = acc(0.12f)
        c.drawCircle(cx, cy, 104 * k, stroke)
        stroke.color = acc(1f)
        rect.set(cx - 104 * k, cy - 104 * k, cx + 104 * k, cy + 104 * k)
        c.drawArc(rect, -90f, sec / 60f * 360f, false, stroke)

        c.save()
        c.rotate(rot(t, 9000), cx, cy)
        stroke.pathEffect = dashSeg
        stroke.strokeWidth = 2 * k
        stroke.color = acc(0.65f)
        c.drawCircle(cx, cy, 94 * k, stroke)
        stroke.pathEffect = null
        c.restore()

        c.save()
        c.rotate(rot(t, 40000), cx, cy)
        stroke.strokeWidth = 1.6f * k
        stroke.color = acc(0.85f)
        rect.set(cx - 88 * k, cy - 88 * k, cx + 88 * k, cy + 88 * k)
        c.drawArc(rect, -90f, 60f, false, stroke)
        c.drawArc(rect, 90f, 60f, false, stroke)
        c.restore()
        stroke.clearShadowLayer()

        val d = Date(now)
        text.textAlign = Paint.Align.CENTER
        text.typeface = bold
        text.color = Color.WHITE
        text.textSize = radius * 0.36f
        text.letterSpacing = 0.04f
        text.setShadowLayer(dp(10f), 0f, 0f, acc(0.9f))
        c.drawText(timeFmt.format(d), cx, cy + radius * 0.1f, text)
        text.clearShadowLayer()

        text.typeface = regular
        text.textSize = radius * 0.105f
        text.letterSpacing = 0.2f
        text.color = acc(1f)
        c.drawText(dateFmt.format(d).replace(".", "").uppercase(Locale.FRANCE), cx, cy + radius * 0.27f, text)

        text.textSize = radius * 0.085f
        text.letterSpacing = 0.05f
        text.color = Color.rgb(185, 219, 230)
        c.drawText(fit("${SystemInfo.greeting()}, $userName", radius * 1.1f), cx, cy + radius * 0.42f, text)
    }

    // ------------------------------------------------------------------ barre du haut
    private fun drawTop(c: Canvas, s: Snapshot?) {
        val y = dp(62f)
        fill.color = acc(1f)
        c.drawCircle(dp(22f), y - dp(5f), dp(3.5f), fill)
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = dp(15f)
        text.letterSpacing = 0.18f
        text.color = acc(1f)
        text.setShadowLayer(dp(8f), 0f, 0f, acc(0.8f))
        c.drawText("J.A.R.V.I.S.", dp(32f), y, text)
        text.clearShadowLayer()
        if (s != null) {
            text.textAlign = Paint.Align.RIGHT
            text.typeface = regular
            text.textSize = dp(12f)
            text.letterSpacing = 0.1f
            c.drawText(String.format(Locale.FRANCE, "RAM %d %%  ·  %.0f °C", s.ramPct, s.batTemp), w - dp(18f), y, text)
        }
    }

    // ------------------------------------------------------------------ bandeau boursier
    private fun drawTicker(c: Canvas, now: Long) {
        fill.color = Color.argb(120, 2, 14, 22)
        c.drawRect(tickerRect, fill)
        fill.color = acc(0.35f)
        c.drawRect(tickerRect.left, tickerRect.top, tickerRect.right, tickerRect.top + dp(1f), fill)
        c.drawRect(tickerRect.left, tickerRect.bottom - dp(1f), tickerRect.right, tickerRect.bottom, fill)

        val baseline = tickerRect.centerY() + dp(4.5f)
        text.textAlign = Paint.Align.LEFT
        text.letterSpacing = 0.05f
        text.textSize = dp(13f)
        if (quotes.isEmpty()) {
            text.typeface = regular
            text.color = subColor
            c.drawText("MARCHÉS · en attente de connexion…", dp(14f), baseline, text)
            return
        }
        // Mesure la longueur totale d'un cycle
        val gap = dp(26f)
        var total = 0f
        for (q in quotes) total += itemWidth(q) + gap
        val speed = dp(28f) / 1000f // pixels par milliseconde
        val offset = if (animate) (now * speed) % total else 0f
        c.save()
        c.clipRect(tickerRect)
        var x = dp(14f) - offset
        while (x < w) {
            for (q in quotes) {
                drawItem(c, q, x, baseline)
                x += itemWidth(q) + gap
            }
        }
        c.restore()
    }

    private fun itemWidth(q: Quote): Float {
        text.typeface = bold
        var wd = text.measureText(q.label + " ")
        text.typeface = regular
        wd += text.measureText(q.valueText() + " ")
        wd += text.measureText(arrow(q) + q.pctText())
        return wd
    }

    private fun arrow(q: Quote) = when {
        q.pct == null -> ""
        q.pct > 0.005 -> "▲"
        q.pct < -0.005 -> "▼"
        else -> "■"
    }

    private fun drawItem(c: Canvas, q: Quote, x0: Float, y: Float) {
        var x = x0
        text.typeface = bold
        text.color = acc(1f)
        val l = q.label + " "
        c.drawText(l, x, y, text)
        x += text.measureText(l)
        text.typeface = regular
        text.color = Color.WHITE
        val v = q.valueText() + " "
        c.drawText(v, x, y, text)
        x += text.measureText(v)
        val p = q.pct
        text.color = when {
            p == null -> subColor
            p > 0.005 -> upColor
            p < -0.005 -> downColor
            else -> subColor
        }
        c.drawText(arrow(q) + q.pctText(), x, y, text)
    }

    // ------------------------------------------------------------------ panneaux
    private fun panels(s: Snapshot?, now: Long): List<Panel> {
        val wx = weather
        val weatherPanel = if (wx == null) Panel("MÉTÉO", "—", "Choisir une ville dans l'appli", null, 0)
        else Panel(
            "MÉTÉO" + if (wx.place.isNotBlank()) " · ${wx.place.uppercase(Locale.FRANCE)}" else "",
            "${wx.temp.roundToInt()}°  ${Weather.icon(wx.code, wx.isDay)}",
            if (wx.rainHour != null) "Pluie vers ${wx.rainHour} h · ${wx.min.roundToInt()}°/${wx.max.roundToInt()}°"
            else "${Weather.label(wx.code)} · ${wx.min.roundToInt()}°/${wx.max.roundToInt()}°",
            null, 0
        )

        val ev = nextEvent
        val agendaPanel = when {
            !agendaAllowed -> Panel("AGENDA", "—", "Autoriser dans l'appli Jarvis", null, 0)
            ev == null -> Panel("AGENDA", "Libre", "Aucun rendez-vous à venir", null, 0)
            else -> {
                val mins = (ev.begin - now) / 60000
                val time = timeFmt.format(Date(ev.begin))
                val value = when {
                    ev.begin <= now -> "En cours"
                    ev.begin < Agenda.dayStart(1) -> time
                    ev.begin < Agenda.dayStart(2) -> "Dem. $time"
                    else -> dayFmt.format(Date(ev.begin)).replace(".", "").replaceFirstChar { it.uppercase() } + " $time"
                }
                val soon = when {
                    ev.begin <= now -> ""
                    mins < 60 -> " · dans $mins min"
                    mins < 360 -> " · dans ${mins / 60} h"
                    else -> ""
                }
                Panel("AGENDA", value, ev.title + soon, null, if (ev.begin > now && mins <= 15) 1 else 0)
            }
        }

        if (s == null) return listOf(weatherPanel, agendaPanel, Panel("ÉNERGIE", "—", "", null, 0), Panel("STOCKAGE", "—", "", null, 0))
        val batLevel = when {
            !s.charging && s.batPct <= 15 -> 2
            !s.charging && s.batPct <= 30 -> 1
            else -> 0
        }
        val stoLevel = if (s.stoUsedPct >= 95) 2 else if (s.stoUsedPct >= 85) 1 else 0
        return listOf(
            weatherPanel,
            agendaPanel,
            Panel("ÉNERGIE", "${s.batPct} %", if (s.charging) "⚡ En charge" else "Sur batterie", s.batPct / 100f, batLevel),
            Panel("STOCKAGE", SystemInfo.go(s.stoFree), "libres sur ${SystemInfo.go(s.stoTotal)}", s.stoUsedPct / 100f, stoLevel),
        )
    }

    private fun drawPanels(c: Canvas, s: Snapshot?, now: Long) {
        val m = dp(16f)
        val gap = dp(10f)
        val ph = dp(96f)
        val pw = (w - 2 * m - gap) / 2f
        var top = cy + radius + dp(28f)
        val maxTop = h - dp(24f) - 2 * ph - gap
        if (top > maxTop) top = maxTop
        panels(s, now).forEachIndexed { i, p ->
            val x = m + (i % 2) * (pw + gap)
            val y = top + (i / 2) * (ph + gap)
            panelRects[i].set(x, y, x + pw, y + ph)
            drawPanel(c, x, y, pw, ph, p)
        }
    }

    private fun drawPanel(c: Canvas, x: Float, y: Float, pw: Float, ph: Float, p: Panel) {
        val col = when (p.level) { 2 -> critColor; 1 -> warnColor; else -> accent }
        val cut = dp(12f)
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x + pw - cut, y)
        path.lineTo(x + pw, y + cut)
        path.lineTo(x + pw, y + ph)
        path.lineTo(x + cut, y + ph)
        path.lineTo(x, y + ph - cut)
        path.close()
        fill.color = Color.argb(115, 4, 20, 30)
        c.drawPath(path, fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = alpha(col, 0.4f)
        c.drawPath(path, stroke)
        fill.color = col
        c.drawRect(x, y, x + dp(28f), y + dp(2f), fill)

        val px = x + dp(12f)
        val right = x + pw - dp(12f)
        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        text.letterSpacing = 0.2f
        text.textSize = dp(10.5f)
        text.color = col
        c.drawText(fit(p.title, right - px), px, y + dp(20f), text)

        text.typeface = bold
        text.letterSpacing = 0f
        text.textSize = dp(25f)
        text.color = if (p.level > 0) col else Color.WHITE
        c.drawText(fit(p.value, right - px), px, y + dp(50f), text)

        text.typeface = regular
        text.textSize = dp(12.5f)
        text.color = subColor
        c.drawText(fit(p.sub, right - px), px, y + dp(70f), text)

        p.frac?.let { f ->
            val by = y + ph - dp(14f)
            fill.color = alpha(col, 0.15f)
            c.drawRect(px, by, right, by + dp(4f), fill)
            fill.color = col
            c.drawRect(px, by, px + (right - px) * f.coerceIn(0f, 1f), by + dp(4f), fill)
        }
    }

    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
