package fr.guill.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import java.util.Locale

/** Écran de réglages (aussi l'icône « Jarvis » dans la liste des applis). */
class SettingsActivity : Activity() {

    private val bg = Color.rgb(3, 12, 18)
    private val subText = Color.rgb(143, 179, 191)
    private lateinit var sp: SharedPreferences
    private lateinit var thumbs: LinearLayout
    private var tts: TextToSpeech? = null
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
        tts?.shutdown()
        super.onDestroy()
    }

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
        root.addView(actionButton("TESTER LE RAPPORT VOCAL") { testVoice() })
        root.addView(note("Sur l'écran d'accueil, touchez le réacteur pour entendre le rapport de Jarvis."))

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
        val txt = SystemInfo.report(Prefs.userName(this), SystemInfo.read(this))
        val t = tts
        if (t != null) {
            t.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "test")
            return
        }
        tts = TextToSpeech(this) init@{ status ->
            val engine = tts ?: return@init
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale.FRENCH
                engine.setPitch(0.9f)
                engine.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "test")
            } else {
                Toast.makeText(this, "Synthèse vocale indisponible sur ce téléphone.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val REQ_PICK = 42
    }
}
