package fr.guill.jarvis

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.SurfaceHolder
import java.util.Locale

class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = JarvisEngine()

    inner class JarvisEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx = this@JarvisWallpaperService
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = HudRenderer(ctx)
        private var visible = false
        private var width = 0
        private var height = 0
        private var fps = 20
        private var slide = 0
        private var snap: Snapshot? = null
        private var lastSnap = 0L
        private var lastSlide = System.currentTimeMillis()
        private var bgJob = 0

        private var tts: TextToSpeech? = null
        private var ttsReady = false
        @Volatile private var speaking = false

        private val frame = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Prefs.sp(ctx).registerOnSharedPreferenceChangeListener(this)
            applyPrefs()
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            Prefs.sp(ctx).unregisterOnSharedPreferenceChangeListener(this)
            tts?.shutdown()
            tts = null
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            renderer.layout(w, h)
            loadBackground()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                if (slide == -1 && !isPreview) Prefs.next(ctx)
                lastSnap = 0L
                drawFrame()
            } else {
                handler.removeCallbacks(frame)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int
        ) {
            renderer.offsetX = xOffset
            if (visible && fps <= 1) drawFrame()
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && renderer.hitReactor(x.toFloat(), y.toFloat())) {
                speak()
            }
            return null
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            applyPrefs()
            if (key == Prefs.K_CURRENT || key == Prefs.K_DIM || key == Prefs.K_COLOR || key == Prefs.K_BGREV) {
                loadBackground()
            }
            if (visible) drawFrame()
        }

        private fun applyPrefs() {
            renderer.accent = Prefs.color(ctx)
            renderer.userName = Prefs.userName(ctx)
            renderer.showPanels = Prefs.panels(ctx)
            fps = Prefs.fps(ctx)
            renderer.animate = fps > 1
            slide = Prefs.slide(ctx)
        }

        private fun loadBackground() {
            if (width == 0 || height == 0) return
            val job = ++bgJob
            val file = Prefs.currentFile(ctx)
            val dim = Prefs.dim(ctx)
            val accent = Prefs.color(ctx)
            val w = width
            val h = height
            Thread {
                val bmp: Bitmap? = runCatching { Backgrounds.build(file, w, h, dim, accent) }.getOrNull()
                handler.post {
                    if (job == bgJob && bmp != null) {
                        renderer.background = bmp
                        drawFrame()
                    }
                }
            }.start()
        }

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            val now = System.currentTimeMillis()
            if (now - lastSnap > 3000) {
                snap = runCatching { SystemInfo.read(ctx) }.getOrNull() ?: snap
                lastSnap = now
            }
            if (slide > 0 && !isPreview && now - lastSlide > slide * 60_000L) {
                lastSlide = now
                Prefs.next(ctx)
            }

            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
                if (c != null) renderer.draw(c, now, snap, speaking)
            } catch (e: Exception) {
            } finally {
                if (c != null) runCatching { holder.unlockCanvasAndPost(c) }
            }

            if (visible) {
                val delay = when {
                    speaking -> 33L
                    fps <= 1 -> 1000L
                    else -> 1000L / fps
                }
                handler.postDelayed(frame, delay)
            }
        }

        private fun speak() {
            val s = snap ?: runCatching { SystemInfo.read(ctx) }.getOrNull() ?: return
            val txt = SystemInfo.report(Prefs.userName(ctx), s)
            val engine = tts
            if (engine != null && ttsReady) {
                say(engine, txt)
                return
            }
            if (engine == null) {
                tts = TextToSpeech(ctx) init@{ status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    val t = tts ?: return@init
                    if (ttsReady) {
                        t.language = Locale.FRENCH
                        t.setPitch(0.9f)
                        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) { speaking = true }
                            override fun onDone(utteranceId: String?) { speaking = false }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { speaking = false }
                        })
                        say(t, txt)
                    }
                }
            }
        }

        private fun say(t: TextToSpeech, txt: String) {
            speaking = true
            t.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
            handler.post { if (visible) drawFrame() }
        }
    }
}
