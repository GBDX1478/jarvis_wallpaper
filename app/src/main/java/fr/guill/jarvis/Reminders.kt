package fr.guill.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Programme un rappel vocal avant le prochain rendez-vous de l'agenda. */
object Reminders {
    const val CHANNEL = "rappels"
    private const val REQ = 7

    private fun pending(c: Context, e: Event?): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction("fr.guill.jarvis.RAPPEL")
        if (e != null) i.putExtra("title", e.title).putExtra("begin", e.begin).putExtra("loc", e.location)
        return PendingIntent.getBroadcast(c, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!Prefs.remind(c) || !Agenda.hasPermission(c)) {
            am.cancel(pending(c, null))
            return
        }
        val lead = Prefs.remindMinutes(c) * 60_000L
        val now = System.currentTimeMillis()
        val next = Agenda.events(c, now, now + 3 * 86_400_000L)
            .firstOrNull { !it.allDay && it.begin - lead > now + 5_000 }
        if (next == null) {
            am.cancel(pending(c, null))
            return
        }
        val at = next.begin - lead
        val pi = pending(c, next)
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Rappels de rendez-vous", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /** Notification « points clés » à partir d'une fiche. */
    fun notifyCard(c: Context, card: Card, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(c)
        val lines = card.plainLines()
        val style = Notification.InboxStyle().setBigContentTitle(card.title)
        lines.take(9).forEach { style.addLine(it) }
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(card.title)
            .setContentText(lines.take(3).joinToString("   "))
            .setStyle(style)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun notify(c: Context, text: String, title: String = "J.A.R.V.I.S.", id: Int = 1001) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(c)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

/** Reçoit l'alarme du rappel : notification + voix, puis programme le suivant. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val title = intent.getStringExtra("title")
        if (title == null) {
            Reminders.schedule(c)
            return
        }
        val text = Speech.reminder(title, intent.getLongExtra("begin", System.currentTimeMillis()), intent.getStringExtra("loc") ?: "")
        val begin = intent.getLongExtra("begin", System.currentTimeMillis())
        val mins = ((begin - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val loc = intent.getStringExtra("loc") ?: ""
        val hm = java.text.SimpleDateFormat("HH:mm", Locale.FRANCE).format(java.util.Date(begin))
        Reminders.notify(
            c,
            "🕒 $hm" + if (loc.isNotBlank()) "   📍 $loc" else "",
            "⏰ " + (if (mins <= 1) "Maintenant" else "Dans $mins min") + " · $title"
        )
        if (!Prefs.voice(c)) {
            Reminders.schedule(c)
            return
        }

        val result = goAsync()
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var tts: TextToSpeech? = null
        val finish = {
            if (!finished) {
                finished = true
                runCatching { tts?.shutdown() }
                runCatching { Reminders.schedule(c) }
                runCatching { result.finish() }
            }
        }
        main.postDelayed({ finish() }, 25_000)
        tts = TextToSpeech(c) init@{ status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) {
                main.post { finish() }
                return@init
            }
            t.language = Locale.FRENCH
            t.setPitch(0.9f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { finish() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { finish() } }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rappel")
        }
    }
}

/** Reprogramme le rappel après un redémarrage du téléphone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.schedule(context.applicationContext)
    }
}
