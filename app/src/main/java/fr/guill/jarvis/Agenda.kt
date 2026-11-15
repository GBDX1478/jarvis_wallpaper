package fr.guill.jarvis

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.Calendar
import java.util.TimeZone

data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

/** Lit les agendas synchronisés sur le téléphone (Google Agenda, etc.). */
object Agenda {

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(c: Context, from: Long, to: Long): List<Event> {
        if (!hasPermission(c)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<Event>()
        runCatching {
            c.contentResolver.query(
                uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val allDay = cur.getInt(3) == 1
                    var begin = cur.getLong(1)
                    var end = cur.getLong(2)
                    if (allDay) { // les journées entières sont stockées en UTC
                        begin = utcDayToLocal(begin)
                        end = utcDayToLocal(end)
                    }
                    if (end <= from) continue
                    out += Event(cur.getString(0) ?: "(sans titre)", begin, end, allDay, cur.getString(4) ?: "")
                }
            }
        }
        return out.sortedBy { it.begin }
    }

    /** Évènements d'aujourd'hui (pas encore terminés) et de demain. */
    fun todayAndTomorrow(c: Context): Pair<List<Event>, List<Event>> {
        val now = System.currentTimeMillis()
        val startTomorrow = dayStart(1)
        val endTomorrow = dayStart(2)
        val all = events(c, now, endTomorrow)
        return all.filter { it.begin < startTomorrow } to all.filter { it.begin >= startTomorrow }
    }

    /** Prochain évènement non « journée entière » à venir (ou en cours), dans les 7 jours. */
    fun next(c: Context): Event? {
        val now = System.currentTimeMillis()
        return events(c, now, now + 7 * 86_400_000L).firstOrNull { !it.allDay && it.end > now }
    }

    fun dayStart(offsetDays: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offsetDays)
    }.timeInMillis

    private fun utcDayToLocal(utc: Long): Long {
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply {
            clear()
            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}
