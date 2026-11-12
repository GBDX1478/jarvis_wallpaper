package fr.guill.jarvis

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.util.Calendar
import java.util.Locale

data class Snapshot(
    val batPct: Int,
    val charging: Boolean,
    val batTemp: Float,
    val ramUsed: Long,
    val ramTotal: Long,
    val stoFree: Long,
    val stoTotal: Long,
) {
    val ramPct: Int get() = if (ramTotal > 0) (ramUsed * 100 / ramTotal).toInt() else 0
    val stoUsedPct: Int get() = if (stoTotal > 0) ((stoTotal - stoFree) * 100 / stoTotal).toInt() else 0
}

object SystemInfo {
    fun read(c: Context): Snapshot {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val st = StatFs(Environment.getDataDirectory().path)
        return Snapshot(
            batPct = if (level >= 0 && scale > 0) level * 100 / scale else 0,
            charging = charging,
            batTemp = temp,
            ramUsed = mi.totalMem - mi.availMem,
            ramTotal = mi.totalMem,
            stoFree = st.availableBytes,
            stoTotal = st.totalBytes,
        )
    }

    fun go(bytes: Long): String {
        val g = bytes / 1073741824.0
        return if (g >= 100) String.format(Locale.FRANCE, "%.0f Go", g)
        else String.format(Locale.FRANCE, "%.1f Go", g)
    }

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Bonne nuit"
            h < 18 -> "Bonjour"
            else -> "Bonsoir"
        }
    }

    /** Texte du rapport vocal. */
    fun report(name: String, s: Snapshot): String {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val parts = mutableListOf(
            "${greeting()} $name.",
            if (m == 0) "Il est $h heures." else "Il est $h heures $m.",
            "Batterie à ${s.batPct} pour cent" + (if (s.charging) ", en charge." else "."),
        )
        if (!s.charging && s.batPct <= 20) parts += "Je vous recommande de brancher le chargeur."
        parts += "Mémoire utilisée à ${s.ramPct} pour cent."
        val freeGo = (s.stoFree / 1073741824.0).toInt()
        parts += "Il reste $freeGo gigaoctets de stockage."
        if (s.stoUsedPct >= 90) parts += "Attention, le stockage est presque plein."
        if (s.batTemp >= 42) parts += "La batterie chauffe : ${s.batTemp.toInt()} degrés."
        parts += "Tous les systèmes sont opérationnels."
        return parts.joinToString(" ")
    }
}
