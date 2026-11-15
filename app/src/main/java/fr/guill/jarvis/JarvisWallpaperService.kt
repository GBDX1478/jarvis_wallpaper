package fr.guill.jarvis

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = JarvisEngine()

    inner class JarvisEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx = this@JarvisWallpaperService
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = HudRenderer(ctx)
        private val speaker = Speaker(ctx)
        private var visible = false
        private var width = 0
        private var height = 0
        private var fps = 20
        private var slide = 0
        private var snap: Snapshot? = null
        private var lastSnap = 0L
        private var lastAgenda = 0L
        private var lastRefreshCheck = 0L
        private var refreshing = false
        private var lastSlide = System.currentTimeMillis()
        private var bgJob = 0

        private val frame = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Prefs.sp(ctx).registerOnSharedPreferenceChangeListener(this)
            speaker.onSpeakingChanged = { if (visible) drawFrame() }
            applyPrefs()
            loadCaches()
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            Prefs.sp(ctx).unregisterOnSharedPreferenceChangeListener(this)
            speaker.shutdown()
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
                lastAgenda = 0L
                if (!isPreview) maybeBriefing() else maybeRefresh()
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
            if (action == WallpaperManager.COMMAND_TAP) onTap(renderer.zoneAt(x.toFloat(), y.toFloat()))
            return null
        }

        private fun onTap(zone: Zone) {
            if (zone == Zone.NONE) return
            if (speaker.speaking) { speaker.stop(); return } // toucher à nouveau coupe la parole
            if (renderer.hasMessage(System.currentTimeMillis())) { renderer.clearMessage(); drawFrame(); return }
            val s = snap ?: runCatching { SystemInfo.read(ctx) }.getOrNull()
            when (zone) {
                Zone.WEATHER -> present(Brief.weather(renderer.weather)) { Speech.weatherFull(renderer.weather) }
                Zone.AGENDA -> present(Brief.agenda(ctx)) { Speech.agendaToday(ctx) }
                Zone.TICKER -> present(Brief.markets(renderer.quotes)) { Speech.markets(renderer.quotes) }
                else -> present(Brief.report(ctx, s, renderer.weather, renderer.quotes)) { Speech.report(ctx, s, renderer.weather) }
            }
            maybeRefresh()
        }

        /** Affiche la fiche sur le fond d'écran (et la lit à voix haute si la voix est activée). */
        private fun present(card: Card, speech: () -> String) {
            renderer.showCard(card, System.currentTimeMillis())
            if (Prefs.voice(ctx)) speaker.say(speech())
            drawFrame()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            if (key != null && key in Prefs.CACHE_KEYS) {
                loadCaches()
            } else {
                applyPrefs()
                if (key == Prefs.K_CURRENT || key == Prefs.K_DIM || key == Prefs.K_COLOR || key == Prefs.K_BGREV) loadBackground()
                if (key == Prefs.K_LAT || key == Prefs.K_USE_LOC) maybeRefresh(force = true)
            }
            if (visible) drawFrame()
        }

        private fun applyPrefs() {
            renderer.accent = Prefs.color(ctx)
            renderer.userName = Prefs.userName(ctx)
            renderer.showPanels = Prefs.panels(ctx)
            renderer.centerMode = Prefs.center(ctx)
            fps = Prefs.fps(ctx)
            renderer.animate = fps > 1
            slide = Prefs.slide(ctx)
        }

        private fun loadCaches() {
            renderer.weather = DataRepo.weather(ctx)
            renderer.quotes = DataRepo.quotes(ctx)
        }

        private fun refreshAgenda() {
            renderer.agendaAllowed = Agenda.hasPermission(ctx)
            renderer.nextEvent = if (renderer.agendaAllowed) Agenda.next(ctx) else null
        }

        /** Met à jour météo et marchés si les données sont trop anciennes. */
        private fun maybeRefresh(force: Boolean = false, then: (() -> Unit)? = null) {
            if (refreshing) { then?.let { cb -> handler.postDelayed({ cb() }, 6000) }; return }
            if (!force && !DataRepo.isStale(ctx)) { then?.invoke(); return }
            refreshing = true
            Thread {
                runCatching { DataRepo.refresh(ctx, force) }
                handler.post {
                    refreshing = false
                    loadCaches()
                    runCatching { Reminders.schedule(ctx) }
                    then?.invoke()
                    if (visible) drawFrame()
                }
            }.start()
        }

        /** Briefing du matin : à la première apparition de l'écran d'accueil entre 5 h et midi. */
        private fun maybeBriefing() {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val sp = Prefs.sp(ctx)
            if (!Prefs.briefing(ctx) || hour !in 5..11 || sp.getString(Prefs.K_LAST_BRIEF, "") == today) {
                maybeRefresh()
                return
            }
            sp.edit().putString(Prefs.K_LAST_BRIEF, today).apply()
            maybeRefresh(force = DataRepo.isStale(ctx)) {
                val s = snap ?: runCatching { SystemInfo.read(ctx) }.getOrNull()
                val card = Brief.briefing(ctx, s, renderer.weather, renderer.quotes)
                Reminders.notifyCard(ctx, card, 1002)
                present(card) { Speech.briefing(ctx, s, renderer.weather, renderer.quotes) }
            }
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
            if (now - lastAgenda > 60_000) {
                runCatching { refreshAgenda() }
                lastAgenda = now
            }
            if (visible && now - lastRefreshCheck > 60_000) {
                lastRefreshCheck = now
                maybeRefresh()
            }
            if (slide > 0 && !isPreview && now - lastSlide > slide * 60_000L) {
                lastSlide = now
                Prefs.next(ctx)
            }

            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
                if (c != null) renderer.draw(c, now, snap, speaker.speaking)
            } catch (e: Exception) {
            } finally {
                if (c != null) runCatching { holder.unlockCanvasAndPost(c) }
            }

            if (visible) {
                val delay = when {
                    speaker.speaking -> 33L
                    renderer.hasMessage(now) && fps <= 1 -> 250L
                    fps <= 1 -> 1000L
                    else -> 1000L / fps
                }
                handler.postDelayed(frame, delay)
            }
        }
    }
}
