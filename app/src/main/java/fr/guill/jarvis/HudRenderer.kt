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
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

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

    private var w = 0
    private var h = 0
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var dashOuter: DashPathEffect? = null
    private var dashSeg: DashPathEffect? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val regular: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.FRANCE)
    private val rect = RectF()
    private val path = Path()

    private val warnColor = 0xFFFFB347.toInt()
    private val critColor = 0xFFFF4D5E.toInt()
    private val subColor = Color.rgb(169, 204, 216)

    private data class Panel(val title: String, val value: String, val sub: String, val frac: Float?, val level: Int)

    fun layout(width: Int, height: Int) {
        w = width
        h = height
        radius = min(w, h) * 0.37f
        cx = w / 2f
        cy = h * 0.33f
        val k = radius / 146f
        dashOuter = DashPathEffect(floatArrayOf(2 * k, 6 * k), 0f)
        dashSeg = DashPathEffect(floatArrayOf(40 * k, 18 * k, 6 * k, 18 * k), 0f)
    }

    fun hitReactor(x: Float, y: Float): Boolean = hypot(x - cx, y - cy) <= radius * 1.05f

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
        drawReactor(c, now, s, talking)
        drawTop(c)
        if (showPanels) drawPanels(c, s)
    }

    private fun drawReactor(c: Canvas, now: Long, s: Snapshot?, talking: Boolean) {
        val k = radius / 146f
        val t = if (animate) now else 0L

        // halo lumineux
        val glow = if (talking) 0.30f + 0.25f * abs(sin(now / 160.0)).toFloat() else 0.26f
        fill.shader = RadialGradient(cx, cy, 128 * k, acc(glow), acc(0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 128 * k, fill)
        fill.shader = null

        stroke.setShadowLayer(dp(if (talking) 9f else 5f), 0f, 0f, acc(0.85f))

        // anneau pointillé extérieur
        c.save()
        c.rotate(rot(t, 40000), cx, cy)
        stroke.pathEffect = dashOuter
        stroke.strokeWidth = 1.4f * k
        stroke.color = acc(0.6f)
        c.drawCircle(cx, cy, 146 * k, stroke)
        stroke.pathEffect = null
        c.restore()

        // anneau de batterie
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

        // graduations (sans halo pour rester léger)
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

        // trotteuse
        val sec = (now / 1000 % 60) + (now % 1000) / 1000f
        stroke.strokeWidth = 3 * k
        stroke.color = acc(0.12f)
        c.drawCircle(cx, cy, 104 * k, stroke)
        stroke.color = acc(1f)
        rect.set(cx - 104 * k, cy - 104 * k, cx + 104 * k, cy + 104 * k)
        c.drawArc(rect, -90f, sec / 60f * 360f, false, stroke)

        // anneau segmenté rapide
        c.save()
        c.rotate(rot(t, 9000), cx, cy)
        stroke.pathEffect = dashSeg
        stroke.strokeWidth = 2 * k
        stroke.color = acc(0.65f)
        c.drawCircle(cx, cy, 94 * k, stroke)
        stroke.pathEffect = null
        c.restore()

        // arcs intérieurs
        c.save()
        c.rotate(rot(t, 40000), cx, cy)
        stroke.strokeWidth = 1.6f * k
        stroke.color = acc(0.85f)
        rect.set(cx - 88 * k, cy - 88 * k, cx + 88 * k, cy + 88 * k)
        c.drawArc(rect, -90f, 60f, false, stroke)
        c.drawArc(rect, 90f, 60f, false, stroke)
        c.restore()
        stroke.clearShadowLayer()

        // heure, date, salutation
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

    private fun drawTop(c: Canvas) {
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
        text.textAlign = Paint.Align.RIGHT
        text.typeface = regular
        text.textSize = dp(12f)
        c.drawText(Build.MODEL.uppercase(Locale.FRANCE), w - dp(18f), y, text)
    }

    private fun panels(s: Snapshot?): List<Panel> {
        if (s == null) return listOf(
            Panel("ÉNERGIE", "—", "", null, 0), Panel("MÉMOIRE", "—", "", null, 0),
            Panel("STOCKAGE", "—", "", null, 0), Panel("TEMPÉRATURE", "—", "", null, 0)
        )
        val batLevel = when {
            !s.charging && s.batPct <= 15 -> 2
            !s.charging && s.batPct <= 30 -> 1
            else -> 0
        }
        val ramLevel = if (s.ramPct >= 90) 2 else if (s.ramPct >= 80) 1 else 0
        val stoLevel = if (s.stoUsedPct >= 95) 2 else if (s.stoUsedPct >= 85) 1 else 0
        val tLevel = if (s.batTemp >= 45f) 2 else if (s.batTemp >= 40f) 1 else 0
        return listOf(
            Panel("ÉNERGIE", "${s.batPct} %", if (s.charging) "⚡ En charge" else "Sur batterie", s.batPct / 100f, batLevel),
            Panel("MÉMOIRE", "${s.ramPct} %", "${SystemInfo.go(s.ramUsed)} / ${SystemInfo.go(s.ramTotal)}", s.ramPct / 100f, ramLevel),
            Panel("STOCKAGE", SystemInfo.go(s.stoFree), "libres sur ${SystemInfo.go(s.stoTotal)}", s.stoUsedPct / 100f, stoLevel),
            Panel(
                "TEMPÉRATURE", String.format(Locale.FRANCE, "%.0f °C", s.batTemp),
                when (tLevel) { 2 -> "Très chaud !"; 1 -> "Chaud"; else -> "Normale" }, s.batTemp / 50f, tLevel
            ),
        )
    }

    private fun drawPanels(c: Canvas, s: Snapshot?) {
        val m = dp(16f)
        val gap = dp(10f)
        val ph = dp(96f)
        val pw = (w - 2 * m - gap) / 2f
        var top = cy + radius + dp(30f)
        val maxTop = h - dp(24f) - 2 * ph - gap
        if (top > maxTop) top = maxTop
        panels(s).forEachIndexed { i, p ->
            drawPanel(c, m + (i % 2) * (pw + gap), top + (i / 2) * (ph + gap), pw, ph, p)
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
        c.drawText(p.title, px, y + dp(20f), text)

        text.typeface = bold
        text.letterSpacing = 0f
        text.textSize = dp(25f)
        text.color = if (p.level > 0) col else Color.WHITE
        c.drawText(p.value, px, y + dp(50f), text)

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

    /** Raccourcit un texte trop long pour la place disponible. */
    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
