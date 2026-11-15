package fr.guill.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Écran de réglages (aussi l'icône « Jarvis » dans la liste des applis). */
class SettingsActivity : Activity() {

    private val bg = Color.rgb(3, 12, 18)
    private val subText = Color.rgb(143, 179, 191)
    private lateinit var sp: SharedPreferences
    private lateinit var thumbs: LinearLayout
    private val speaker by lazy { Speaker(this).apply { onUnavailable = { toast("Synthèse vocale indisponible sur ce téléphone.") } } }
    private var calStatus: TextView? = null
    private var cityStatus: TextView? = null
    private var locSwitch: Switch? = null
    private val accent get() = Prefs.color(this)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = Prefs.sp(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        build()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        runCatching { Reminders.schedule(this) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun save(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    // ---------------------------------------------------------------- interface
    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("J.A.R.V.I.S.", 26f, accent, bold = true).apply { letterSpacing = 0.18f })
        root.addView(label("Fond d'écran interactif", 14f, subText))
        root.addView(space(18))
        root.addView(actionButton("ACTIVER LE FOND D'ÉCRAN", filled = true) { activate() })

        // ---- Météo
        root.addView(section("MÉTÉO"))
        cityStatus = note(cityText()).also { root.addView(it) }
        root.addView(cityRow())
        locSwitch = Switch(this).apply {
            isChecked = Prefs.useLocation(this@SettingsActivity) && DataRepo.hasLocationPermission(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> onLocationSwitch(on) }
        }
        root.addView(row("Utiliser ma position", "Sinon, la ville choisie ci-dessus", locSwitch!!))

        // ---- Agenda
        root.addView(section("AGENDA GOOGLE"))
        calStatus = note("").also { root.addView(it) }
        updateCalStatus()
        root.addView(actionButton("AUTORISER L'ACCÈS À L'AGENDA") { requestCalendar() })
        root.addView(switchRow("Rappels vocaux avant les rendez-vous", Prefs.K_REMIND, Prefs.remind(this)))
        root.addView(choiceRow(
            "Prévenir",
            listOf("5 min avant" to 5, "10 min avant" to 10, "15 min avant" to 15, "30 min avant" to 30),
            Prefs.K_REMIND_MIN, 15
        ))
        root.addView(actionButton("VOIR MON AGENDA") { present(Brief.agenda(this), Speech.agendaToday(this)) })

        // ---- Briefing & marchés
        root.addView(section("BRIEFING & MARCHÉS"))
        root.addView(switchRow("Voix de Jarvis", Prefs.K_VOICE, Prefs.voice(this)))
        root.addView(note("Désactivée : Jarvis reste silencieux. Les infos s'affichent en texte sur le fond d'écran, le briefing et les rappels arrivent en notification."))
        root.addView(switchRow("Briefing du matin", Prefs.K_BRIEFING, Prefs.briefing(this)))
        root.addView(note("Jarvis fait le point (météo, agenda, marchés, batterie) la première fois que vous voyez l'écran d'accueil entre 5 h et midi."))
        root.addView(note("Marchés suivis : CAC 40, S&P 500, Nasdaq, Bitcoin, Ethereum, EUR/USD, EUR/JPY, USD/JPY. Touchez le bandeau sur le fond d'écran pour les entendre."))
        root.addView(space(8))
        root.addView(actionButton("VOIR LE BRIEFING") { briefingNow() })
        root.addView(space(8))
        root.addView(actionButton("ACTUALISER MÉTÉO ET MARCHÉS") { refreshNow() })

        root.addView(section("FONDS D'ÉCRAN"))
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(thumbs)
        root.addView(space(10))
        root.addView(actionButton("＋ AJOUTER DES IMAGES") { pick() })
        root.addView(note("Touchez une image pour l'afficher. Appui long pour la supprimer."))

        root.addView(choiceRow(
            "Changer automatiquement",
            listOf("Jamais" to 0, "À chaque retour à l'accueil" to -1, "Toutes les 5 min" to 5,
                "Toutes les 15 min" to 15, "Toutes les heures" to 60),
            Prefs.K_SLIDE, 0
        ))
        root.addView(seekRow("Assombrir le fond", Prefs.K_DIM, Prefs.dim(this)))

        root.addView(choiceRow(
            "Centre du réacteur",
            listOf("Météo" to 1, "Batterie" to 2, "Prochain rendez-vous" to 3, "Heure et date" to 0, "Rien (logo)" to 4),
            Prefs.K_CENTER, 1
        ))

        root.addView(section("COULEUR DE L'INTERFACE"))
        root.addView(colorRow())

        root.addView(section("ASSISTANT"))
        root.addView(nameRow())
        root.addView(switchRow("Afficher les panneaux d'infos", Prefs.K_PANELS, Prefs.panels(this)))
        root.addView(choiceRow(
            "Fluidité des animations",
            listOf("Fluide (30 i/s)" to 30, "Normale (20 i/s)" to 20, "Économie de batterie (figé)" to 1),
            Prefs.K_FPS, 20
        ))
        root.addView(space(12))
        root.addView(actionButton("VOIR LE RAPPORT") { testVoice() })
        root.addView(note("Sur l'écran d'accueil, touchez le réacteur, la météo, l'agenda ou le bandeau des marchés pour afficher les détails."))

        refreshThumbs()
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun note(t: String) = label(t, 13f, subText).apply { setPadding(0, dp(6), 0, dp(4)) }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun section(t: String) = label(t, 14f, accent, bold = true).apply {
        letterSpacing = 0.2f
        setPadding(0, dp(28), 0, dp(10))
    }

    private fun actionButton(t: String, filled: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        setTextColor(if (filled) Color.BLACK else accent)
        background = GradientDrawable().apply {
            setColor(if (filled) accent else Color.argb(60, 4, 40, 60))
            setStroke(dp(1), accent)
            cornerRadius = dp(4).toFloat()
        }
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener { onClick() }
    }

    private fun row(title: String, sub: String?, right: View): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 16f, Color.WHITE))
        if (sub != null) col.addView(label(sub, 13f, subText))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(right)
        return r
    }

    private fun choiceRow(title: String, options: List<Pair<String, Int>>, key: String, def: Int): View {
        val current = sp.getInt(key, def)
        val valueView = label(options.firstOrNull { it.second == current }?.first ?: options[0].first, 14f, accent)
        val r = row(title, null, valueView)
        r.setOnClickListener {
            val idx = options.indexOfFirst { it.second == sp.getInt(key, def) }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), idx) { d, which ->
                    save { putInt(key, options[which].second) }
                    valueView.text = options[which].first
                    d.dismiss()
                }
                .show()
        }
        return r
    }

    private fun seekRow(title: String, key: String, value: Int): View {
        val bar = SeekBar(this).apply {
            max = 90
            progress = value
            layoutParams = LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { save { putInt(key, progress) } }
            })
        }
        return row(title, "Pour mieux lire les infos", bar)
    }

    private fun switchRow(title: String, key: String, value: Boolean): View {
        val sw = Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> save { putBoolean(key, on) } }
        }
        return row(title, null, sw)
    }

    private fun nameRow(): View {
        val edit = EditText(this).apply {
            setText(sp.getString(Prefs.K_NAME, ""))
            hint = "Monsieur"
            setHintTextColor(subText)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            layoutParams = LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { save { putString(Prefs.K_NAME, s?.toString() ?: "") } }
            })
        }
        return row("Comment dois-je vous appeler ?", null, edit)
    }

    private fun colorRow(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sel = sp.getInt(Prefs.K_COLOR, 0)
        Prefs.COLORS.forEachIndexed { i, (_, col) ->
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                    if (i == sel) setStroke(dp(3), Color.WHITE)
                }
                setOnClickListener {
                    save { putInt(Prefs.K_COLOR, i) }
                    recreate()
                }
            }
            r.addView(v, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        }
        return r
    }

    // ---------------------------------------------------------------- images
    private fun refreshThumbs() {
        thumbs.removeAllViews()
        val files: List<File?> = listOf<File?>(null) + Prefs.images(this)
        val current = Prefs.current(this)
        val size = (resources.displayMetrics.widthPixels - dp(40) - dp(16)) / 3
        var line: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % 3 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                thumbs.addView(line)
            }
            val selected = (f == null && current.isEmpty()) || (f != null && f.name == current)
            val frame = FrameLayout(this).apply {
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    if (selected) setStroke(dp(3), accent) else setStroke(dp(1), Color.argb(90, 62, 232, 255))
                }
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(10, 20, 40)) }
            frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (f == null) {
                frame.addView(label("Nébuleuse\n(par défaut)", 12f, Color.WHITE).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                Thread {
                    val b = Backgrounds.thumb(f, size)
                    runOnUiThread { if (b != null) img.setImageBitmap(b) }
                }.start()
            }
            frame.setOnClickListener {
                save { putString(Prefs.K_CURRENT, f?.name ?: "") }
                refreshThumbs()
            }
            if (f != null) frame.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Supprimer cette image ?")
                    .setPositiveButton("Supprimer") { _, _ ->
                        f.delete()
                        save {
                            if (f.name == Prefs.current(this@SettingsActivity)) putString(Prefs.K_CURRENT, "")
                            putLong(Prefs.K_BGREV, System.currentTimeMillis())
                        }
                        refreshThumbs()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
                true
            }
            line?.addView(frame, LinearLayout.LayoutParams(size, (size * 16) / 9).apply {
                marginEnd = dp(8); bottomMargin = dp(8)
            })
        }
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(i, "Choisir des images"), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) for (k in 0 until clip.itemCount) uris += clip.getItemAt(k).uri
        else data.data?.let { uris += it }
        if (uris.isEmpty()) return
        Toast.makeText(this, "Import de ${uris.size} image(s)…", Toast.LENGTH_SHORT).show()
        Thread {
            var last: String? = null
            uris.forEachIndexed { k, u ->
                val name = "img_${System.currentTimeMillis()}_$k.jpg"
                runCatching {
                    contentResolver.openInputStream(u)?.use { input ->
                        File(Prefs.imagesDir(this), name).outputStream().use { input.copyTo(it) }
                    }
                    last = name
                }
            }
            runOnUiThread {
                save {
                    last?.let { putString(Prefs.K_CURRENT, it) }
                    putLong(Prefs.K_BGREV, System.currentTimeMillis())
                }
                refreshThumbs()
            }
        }.start()
    }

    // ---------------------------------------------------------------- actions
    private fun activate() {
        val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, JarvisWallpaperService::class.java)
        )
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(this, "Ouvrez Paramètres → Fond d'écran et choisissez Jarvis.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testVoice() {
        val s = runCatching { SystemInfo.read(this) }.getOrNull()
        val w = DataRepo.weather(this)
        present(Brief.report(this, s, w, DataRepo.quotes(this)), Speech.report(this, s, w))
    }

    /** Affiche une fiche « points clés » (et la lit si la voix est activée). */
    private fun present(card: Card, speech: String) {
        if (Prefs.voice(this)) speaker.say(speech)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setBackgroundColor(bg)
        }
        box.addView(label(card.title, 17f, accent, bold = true).apply { letterSpacing = 0.12f; setPadding(0, dp(8), 0, dp(6)) })
        val up = 0xFF45E08A.toInt()
        val down = 0xFFFF5B6B.toInt()
        val warn = 0xFFFFB347.toInt()
        for (sec in card.sections) {
            if (sec.title.isNotBlank()) box.addView(label(sec.title, 12f, accent, bold = true).apply {
                letterSpacing = 0.2f; setPadding(0, dp(14), 0, dp(4))
            })
            for (l in sec.lines) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                }
                row.addView(label(l.icon, 16f, Color.WHITE), LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(label(l.label, 15f, if (l.alert && l.value.isBlank()) warn else Color.rgb(205, 228, 236)).apply {
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (l.value.isNotBlank()) row.addView(label(l.value, 16f, if (l.alert) warn else Color.WHITE, bold = true).apply {
                    setPadding(dp(8), 0, 0, 0)
                })
                if (l.change.isNotBlank()) row.addView(label(l.change, 14f, when (l.trend) { 1 -> up; -1 -> down; else -> subText }, bold = true).apply {
                    setPadding(dp(10), 0, 0, 0)
                })
                box.addView(row)
            }
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(box) }
        AlertDialog.Builder(this).setView(scroll).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- météo
    private fun cityText(): String {
        val city = Prefs.city(this)
        return when {
            Prefs.useLocation(this) && DataRepo.hasLocationPermission(this) ->
                "Position du téléphone" + if (city.isNotBlank()) " (dernière : $city)" else ""
            city.isNotBlank() -> "Ville choisie : $city"
            else -> "Aucune ville choisie : tapez votre ville ou activez la position."
        }
    }

    private fun cityRow(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edit = EditText(this).apply {
            hint = "Ville (ex. Bordeaux)"
            setHintTextColor(subText)
            setTextColor(Color.WHITE)
            setText(Prefs.city(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        r.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(actionButton("OK") {
            val q = edit.text.toString().trim()
            if (q.isEmpty()) return@actionButton
            toast("Recherche de « $q »…")
            Thread {
                val res = Weather.geocode(q)
                runOnUiThread {
                    if (res == null) {
                        toast("Ville introuvable. Vérifiez l'orthographe et la connexion.")
                    } else {
                        save {
                            putString(Prefs.K_CITY, res.first)
                            putString(Prefs.K_LAT, res.second.toString())
                            putString(Prefs.K_LON, res.third.toString())
                            putBoolean(Prefs.K_USE_LOC, false)
                            putLong(Prefs.K_W_TIME, 0L)
                        }
                        locSwitch?.isChecked = false
                        edit.setText(res.first)
                        cityStatus?.text = cityText()
                        toast("Ville enregistrée : ${res.first} ✓")
                        Thread { DataRepo.refresh(this, force = true) }.start()
                    }
                }
            }.start()
        })
        return r
    }

    private fun onLocationSwitch(on: Boolean) {
        if (!on) {
            save { putBoolean(Prefs.K_USE_LOC, false) }
            cityStatus?.text = cityText()
            return
        }
        if (DataRepo.hasLocationPermission(this)) {
            save { putBoolean(Prefs.K_USE_LOC, true); putLong(Prefs.K_W_TIME, 0L) }
            fetchLocation()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun fetchLocation() {
        cityStatus?.text = "Recherche de votre position…"
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false))
            LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
        val onLoc: (android.location.Location?) -> Unit = { loc ->
            if (loc != null) {
                save { putString(Prefs.K_LAT, loc.latitude.toString()); putString(Prefs.K_LON, loc.longitude.toString()) }
                Thread {
                    DataRepo.placeName(this, loc.latitude, loc.longitude)?.let { n -> save { putString(Prefs.K_CITY, n) } }
                    DataRepo.refresh(this, force = true)
                    runOnUiThread { cityStatus?.text = cityText() }
                }.start()
            } else {
                runOnUiThread {
                    cityStatus?.text = cityText()
                    toast("Position introuvable pour l'instant. Activez la localisation du téléphone, ou tapez votre ville.")
                }
            }
        }
        DataRepo.lastKnown(this)?.let { onLoc(it) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { onLoc(it) }
            } else {
                lm.requestSingleUpdate(provider, { onLoc(it) }, mainLooper)
            }
        }.onFailure { onLoc(null) }
    }

    // ---------------------------------------------------------------- agenda
    private fun updateCalStatus() {
        calStatus?.text = if (Agenda.hasPermission(this)) {
            val n = Agenda.next(this)
            "✓ Agenda connecté." + if (n != null) " Prochain : ${n.title}." else " Aucun rendez-vous à venir."
        } else "Jarvis n'a pas encore accès à votre agenda."
    }

    private fun requestCalendar() {
        val perms = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            toast("L'accès à l'agenda est déjà autorisé ✓")
            updateCalStatus()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_CAL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_LOC -> {
                if (DataRepo.hasLocationPermission(this)) {
                    save { putBoolean(Prefs.K_USE_LOC, true); putLong(Prefs.K_W_TIME, 0L) }
                    fetchLocation()
                } else {
                    locSwitch?.isChecked = false
                    toast("Sans la position, tapez votre ville.")
                }
            }
            REQ_CAL -> {
                updateCalStatus()
                runCatching { Reminders.schedule(this) }
                if (!Agenda.hasPermission(this)) toast("Accès refusé. Vous pourrez l'autoriser plus tard.")
            }
        }
    }

    // ---------------------------------------------------------------- briefing & marchés
    private fun briefingNow() {
        toast("Préparation du briefing…")
        Thread {
            runCatching { DataRepo.refresh(this) }
            val s = runCatching { SystemInfo.read(this) }.getOrNull()
            val w = DataRepo.weather(this)
            val q = DataRepo.quotes(this)
            val card = Brief.briefing(this, s, w, q)
            val txt = Speech.briefing(this, s, w, q)
            runOnUiThread { present(card, txt) }
        }.start()
    }

    private fun refreshNow() {
        toast("Actualisation…")
        Thread {
            runCatching { DataRepo.refresh(this, force = true) }
            val w = DataRepo.weather(this)
            val q = DataRepo.quotes(this)
            runOnUiThread {
                val wt = if (w != null) "Météo : ${Math.round(w.temp)}° à ${w.place}" else "Météo : aucune ville"
                toast("$wt · Marchés : ${q.size} cours reçus")
            }
        }.start()
    }

    companion object {
        private const val REQ_PICK = 42
        private const val REQ_LOC = 43
        private const val REQ_CAL = 44
    }
}
