package ru.aska.icq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Держит приложение живым в фоне и показывает уведомления о сообщениях. */
class ConnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, "svc")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Аська")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun messageNotification(uin: String, text: String) {
        val n = NotificationCompat.Builder(this, "msg2")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(uin)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(uin.hashCode(), n)
    }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("svc", "Соединение", NotificationManager.IMPORTANCE_LOW))
            nm.deleteNotificationChannel("msg")
            val ch = NotificationChannel("msg2", "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(
                Uri.parse("android.resource://" + packageName + "/" + R.raw.msg),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            nm.createNotificationChannel(ch)
        }
        val first = statusNotification("Подключение…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, first)

        scope.launch {
            oscar.conn.collect { c ->
                nm.notify(1, statusNotification(when (c) {
                    Conn.ON -> "В сети"
                    Conn.RECONNECTING -> "Переподключение…"
                    else -> "Подключение…"
                }))
            }
        }
        scope.launch {
            oscar.incoming.collect { (uin, text) ->
                if (oscar.appVisible && oscar.openChat == uin) {
                    MediaPlayer.create(this@ConnService, R.raw.msg)?.apply {
                        setOnCompletionListener { it.release() }
                        start()
                    }
                } else messageNotification(uin, text)
            }
        }
        scope.launch {
            var prev = emptySet<String>()
            oscar.unread.collect { m ->
                (prev - m.keys).forEach { nm.cancel(it.hashCode()) }
                prev = m.keys
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
