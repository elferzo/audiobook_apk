package com.kvu.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Скачивает главы книг по очереди. Работает и при погашенном экране. */
class DownloadService : Service() {

    companion object {
        const val CH = "downloads"
        const val NID = 1
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        @Volatile var currentId: String? = null
        @Volatile var lastError: String? = null
        @Volatile var cancelId: String? = null

        private val lock = Any()
        private val queue = ArrayList<String>()
        private var running = false

        fun enqueue(ctx: Context, id: String) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra("id", id))
        }

        fun isQueued(id: String): Boolean {
            if (currentId == id) return true
            return synchronized(lock) { queue.contains(id) }
        }

        fun busy(): Boolean = synchronized(lock) { running }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Загрузка книг", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NID, note("Подготовка…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id = intent?.getStringExtra("id")
        var start = false
        synchronized(lock) {
            if (id != null && !queue.contains(id) && currentId != id) queue.add(id)
            if (!running) {
                running = true
                start = true
            }
        }
        if (start) Thread { work() }.start()
        return START_NOT_STICKY
    }

    private fun work() {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiopolka:download")
        wl.acquire(6 * 60 * 60 * 1000L)
        try {
            while (true) {
                val id: String = synchronized(lock) {
                    if (queue.isEmpty()) {
                        running = false
                        null
                    } else {
                        queue.removeAt(0)
                    }
                } ?: break
                currentId = id
                try {
                    process(id)
                } catch (e: Exception) {
                    lastError = e.message ?: e.toString()
                }
                currentId = null
            }
        } finally {
            if (wl.isHeld) wl.release()
            main.post {
                val idle = synchronized(lock) { !running }
                if (idle) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun process(id: String) {
        val b = Store.load(this, id) ?: return
        val ua = getSharedPreferences("app", MODE_PRIVATE).getString("ua", null) ?: DEFAULT_UA

        if (b.cover.startsWith("http") && !b.coverFile().exists()) {
            try {
                fetch(b.cover, b.coverFile(), b.page, ua, false)
            } catch (e: Exception) {
            }
        }

        var failed = 0
        for (i in b.tracks.indices) {
            if (cancelId == id) {
                cancelId = null
                return
            }
            if (!b.dir.exists()) return
            if (b.isDone(i)) continue
            update(b.title, i, b.tracks.size)

            var ok = false
            var err: Exception? = null
            for (attempt in 1..3) {
                try {
                    fetch(b.tracks[i].url, b.trackFile(i), b.page, ua, true)
                    ok = true
                    break
                } catch (e: Exception) {
                    err = e
                    if (!b.dir.exists()) return
                    try {
                        Thread.sleep(1500L * attempt)
                    } catch (ie: InterruptedException) {
                    }
                }
            }
            if (!ok) {
                failed++
                lastError = b.title + ": глава " + (i + 1) + " — " + (err?.message ?: "ошибка сети")
                if (failed >= 3) break
            }
        }
        if (failed == 0) lastError = null
    }

    private fun fetch(url: String, dest: File, referer: String, ua: String, audio: Boolean) {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
            val cookies = try {
                CookieManager.getInstance().getCookie(url)
            } catch (e: Exception) {
                null
            }
            if (!cookies.isNullOrEmpty()) c.setRequestProperty("Cookie", cookies)
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")

            val code = c.responseCode
            if (code == 416) {
                part.delete()
                throw IOException("сервер отклонил докачку, повтор")
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            val type = c.contentType ?: ""
            if (audio && type.startsWith("text/")) throw IOException("сервер вернул не аудио")

            val append = code == 206
            if (!append) have = 0L
            val expected = c.contentLengthLong
            c.inputStream.use { input ->
                FileOutputStream(part, append).use { out -> input.copyTo(out, 64 * 1024) }
            }
            if (expected > 0 && part.length() != have + expected) throw IOException("обрыв загрузки")
            if (part.length() == 0L) throw IOException("пустой файл")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("не удалось сохранить файл")
        } finally {
            c.disconnect()
        }
    }

    private fun note(text: String, done: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Скачиваю книгу")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            b.setProgress(total, done, false)
            b.setSubText("$done из $total")
        }
        return b.build()
    }

    private fun update(text: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(NID, note(text, done, total))
    }
}
