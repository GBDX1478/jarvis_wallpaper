package fr.guill.jarvis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.random.Random

/** Prépare l'image de fond (recadrée, assombrie, lignes de balayage) une seule fois. */
object Backgrounds {

    /** Largeur un peu plus grande que l'écran pour l'effet de parallaxe entre les pages. */
    const val PARALLAX = 1.12f

    fun build(file: File?, w: Int, h: Int, dim: Int, accent: Int): Bitmap {
        val bw = (w * PARALLAX).toInt()
        val out = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val src = file?.let { runCatching { decode(it, bw, h) }.getOrNull() }
        if (src != null) {
            val scale = max(bw / src.width.toFloat(), h / src.height.toFloat())
            val dw = src.width * scale
            val dh = src.height * scale
            val dst = RectF((bw - dw) / 2f, (h - dh) / 2f, (bw + dw) / 2f, (h + dh) / 2f)
            c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
        } else {
            nebula(c, bw, h)
        }

        c.drawColor(Color.argb((dim.coerceIn(0, 95) * 2.55f).toInt(), 0, 0, 0))
        val p = Paint()
        p.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Color.argb(160, 0, 0, 0)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, bw.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = (accent and 0x00FFFFFF) or (14 shl 24)
        var y = 0f
        while (y < h) {
            c.drawRect(0f, y, bw.toFloat(), y + 1f, p)
            y += 3f
        }
        return out
    }

    /** Miniature pour l'écran de réglages. */
    fun thumb(file: File, size: Int): Bitmap? = runCatching { decode(file, size, size) }.getOrNull()

    private fun decode(file: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Respecte l'orientation des photos prises au téléphone
        val rot = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rot == 0f) return bmp
        val m = Matrix().apply { postRotate(rot) }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r != bmp) bmp.recycle()
        return r
    }

    /** Fond par défaut : une nébuleuse générée. */
    private fun nebula(c: Canvas, w: Int, h: Int) {
        c.drawColor(Color.rgb(3, 4, 12))
        val r = Random(7)
        val cols = intArrayOf(0x2A0B5E, 0x0B4F7A, 0x6B1450, 0x0F6A6A, 0x3B1F8A)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 14) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val rad = (0.25f + r.nextFloat() * 0.5f) * max(w, h)
            p.shader = RadialGradient(
                x, y, rad, cols[i % cols.size] or (0x88 shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        p.shader = null
        p.color = Color.WHITE
        for (i in 0 until 700) {
            p.alpha = (70 + r.nextInt(185))
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, r.nextFloat() * 2.2f, p)
        }
    }
}
