package com.kvu.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast

/** Офлайн-плеер: играет скачанные главы, управляется с экрана блокировки и гарнитуры. */
class PlayerService : Service() {

    companion object {
        const val CH = "player"
        const val NID = 2

        const val A_PLAY = "com.kvu.offline.PLAY"      // id, index (-1 = продолжить с сохранённого места)
        const val A_TOGGLE = "com.kvu.offline.TOGGLE"
        const val A_NEXT = "com.kvu.offline.NEXT"
        const val A_PREV = "com.kvu.offline.PREV"
        const val A_REL = "com.kvu.offline.REL"        // delta, мс
        const val A_SEEK = "com.kvu.offline.SEEK"      // pos, мс
        const val A_SPEED = "com.kvu.offline.SPEED"    // speed
        const val A_SLEEP = "com.kvu.offline.SLEEP"    // min: 0 выкл, -1 до конца главы
        const val A_STOP = "com.kvu.offline.STOP"

        @Volatile var instance: PlayerService? = null

        fun send(ctx: Context, action: String, fill: (Intent) -> Unit = {}) {
            val i = Intent(ctx, PlayerService::class.java).setAction(action)
            fill(i)
            ctx.startForegroundService(i)
        }
    }

    var book: Book? = null
        private set
    var index = 0
        private set
    var speed = 1.0f
        private set
    var sleepAt = 0L          // момент остановки по SystemClock.elapsedRealtime(), 0 = выключен
        private set
    var sleepChapter = false  // остановиться в конце главы
        private set

    private var mp: MediaPlayer? = null
    private var art: Bitmap? = null
    private var resumeOnGain = false
    private var ticks = 0
    private val h = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private lateinit var focusReq: AudioFocusRequest
    private var noisyRegistered = false

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pause()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (sleepAt > 0 && SystemClock.elapsedRealtime() >= sleepAt) {
                sleepAt = 0
                pause()
            }
            if (isPlaying()) {
                if (ticks % 5 == 0) savePos()
                h.postDelayed(this, 1000)
            }
        }
    }

    fun isPlaying(): Boolean = try {
        mp?.isPlaying == true
    } catch (e: Exception) {
        false
    }

    fun position(): Int = try {
        mp?.currentPosition ?: 0
    } catch (e: Exception) {
        0
    }

    fun duration(): Int = try {
        maxOf(0, mp?.duration ?: 0)
    } catch (e: Exception) {
        0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Плеер", NotificationManager.IMPORTANCE_LOW)
        )

        focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnGain = false
                        pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (isPlaying()) {
                            resumeOnGain = true
                            pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnGain) {
                            resumeOnGain = false
                            play()
                        }
                    }
                }
            }
            .build()

        session = MediaSession(this, "audiopolka")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = prev()
            override fun onFastForward() = seekRel(30000)
            override fun onRewind() = seekRel(-15000)
            override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
        })
        session.isActive = true

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisy, filter)
        }
        noisyRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NID, buildNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            A_PLAY -> {
                val id = intent.getStringExtra("id")
                if (id != null) open(id, intent.getIntExtra("index", -1))
            }
            A_TOGGLE -> if (isPlaying()) pause() else play()
            A_NEXT -> next()
            A_PREV -> prev()
            A_REL -> seekRel(intent.getIntExtra("delta", 0))
            A_SEEK -> seekTo(intent.getIntExtra("pos", 0))
            A_SPEED -> changeSpeed(intent.getFloatExtra("speed", 1.0f))
            A_SLEEP -> setSleep(intent.getIntExtra("min", 0))
            A_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        if (book == null) shutdown()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaying()) shutdown()
    }

    override fun onDestroy() {
        savePos()
        release()
        h.removeCallbacks(tick)
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisy)
            } catch (e: Exception) {
            }
        }
        session.release()
        instance = null
        super.onDestroy()
    }

    // ---------- управление ----------

    private fun open(id: String, idx: Int) {
        val cur = book
        if (cur != null && cur.id == id && mp != null && (idx == -1 || idx == index)) {
            if (!isPlaying()) play()
            return
        }
        savePos()
        val b = Store.load(this, id)
        if (b == null || b.tracks.isEmpty()) {
            toast("Книга не найдена на телефоне")
            return
        }
        release()
        book = b
        art = loadArt(b)
        val p = b.loadPos()
        speed = p.speed
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("last", id).apply()
        if (idx == -1) {
            index = p.index
            load(p.index, maxOf(0, p.ms - 3000), true)
        } else {
            index = idx.coerceIn(0, b.tracks.size - 1)
            load(index, 0, true)
        }
    }

    private fun load(i: Int, pos: Int, autoplay: Boolean) {
        val b = book ?: return
        if (i !in b.tracks.indices) return
        val f = b.trackFile(i)
        if (!f.exists()) {
            toast("Глава ${i + 1} ещё не скачана")
            refresh()
            return
        }
        release()
        index = i
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setOnCompletionListener { onDone() }
            p.setOnErrorListener { _, _, _ ->
                toast("Не удалось воспроизвести главу ${index + 1}")
                refresh()
                true
            }
            p.setDataSource(f.path)
            p.prepare()
            if (pos > 0) p.seekTo(pos.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (e: Exception) {
            try {
                p.release()
            } catch (e2: Exception) {
            }
            toast("Файл главы ${i + 1} повреждён — удалите книгу и скачайте заново")
            refresh()
            return
        }
        mp = p
        b.savePos(index, pos, speed)
        if (autoplay) play() else refresh()
    }

    private fun play() {
        val p = mp
        if (p == null) {
            val b = book ?: return
            if (b.isDone(index)) load(index, 0, true)
            return
        }
        val am = getSystemService(AudioManager::class.java)
        if (am.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            refresh()
            return
        }
        try {
            p.start()
            p.playbackParams = p.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
        }
        h.removeCallbacks(tick)
        h.postDelayed(tick, 1000)
        refresh()
    }

    private fun pause() {
        val p = mp
        try {
            if (p != null && p.isPlaying) p.pause()
        } catch (e: Exception) {
        }
        savePos()
        refresh()
    }

    private fun next() {
        val b = book ?: return
        if (index + 1 < b.tracks.size) load(index + 1, 0, true)
    }

    private fun prev() {
        if (position() > 5000 || index == 0) seekTo(0) else load(index - 1, 0, true)
    }

    private fun seekRel(delta: Int) {
        val d = duration()
        if (d <= 0) return
        seekTo((position() + delta).coerceIn(0, maxOf(0, d - 500)))
    }

    private fun seekTo(pos: Int) {
        val p = mp ?: return
        try {
            p.seekTo(pos.toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (e: Exception) {
        }
        book?.savePos(index, pos, speed)
        refresh(pos)
    }

    private fun changeSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 3.0f)
        val p = mp
        try {
            if (p != null && p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(speed)
        } catch (e: Exception) {
        }
        savePos()
        refresh()
    }

    private fun setSleep(min: Int) {
        when {
            min == 0 -> {
                sleepAt = 0
                sleepChapter = false
            }
            min < 0 -> {
                sleepAt = 0
                sleepChapter = true
            }
            else -> {
                sleepAt = SystemClock.elapsedRealtime() + min * 60000L
                sleepChapter = false
            }
        }
    }

    private fun onDone() {
        val b = book ?: return
        val last = index + 1 >= b.tracks.size
        if (sleepChapter) {
            sleepChapter = false
            if (!last && b.isDone(index + 1)) load(index + 1, 0, false) else seekTo(0)
            return
        }
        when {
            last -> {
                seekTo(0)
                toast("Книга закончилась")
            }
            b.isDone(index + 1) -> load(index + 1, 0, true)
            else -> {
                toast("Следующая глава ещё не скачана")
                refresh()
            }
        }
    }

    private fun savePos() {
        val b = book ?: return
        if (mp == null) return
        b.savePos(index, position(), speed)
    }

    private fun release() {
        val p = mp
        mp = null
        if (p != null) {
            try {
                p.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun shutdown() {
        savePos()
        release()
        h.removeCallbacks(tick)
        try {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focusReq)
        } catch (e: Exception) {
        }
        book = null
        sleepAt = 0
        sleepChapter = false
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- уведомление и экран блокировки ----------

    private fun refresh(knownPos: Int = -1) {
        val b = book
        val playing = isPlaying()
        val pos = if (knownPos >= 0) knownPos else position()

        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    pos.toLong(),
                    if (playing) speed else 0f
                )
                .build()
        )

        if (b != null) {
            val md = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, chapterTitle(b))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, b.title)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, b.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
            val a = art
            if (a != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, a)
            session.setMetadata(md.build())
            getSystemService(NotificationManager::class.java).notify(NID, buildNote())
        }
    }

    private fun chapterTitle(b: Book): String {
        val t = b.tracks.getOrNull(index)?.title ?: ""
        return if (t.isNotBlank()) t else "Глава ${index + 1}"
    }

    private fun action(icon: Int, title: String, act: String, code: Int): Notification.Action {
        val pi = PendingIntent.getForegroundService(
            this, code, Intent(this, PlayerService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNote(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val nb = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
        val b = book
        if (b == null) {
            return nb.setContentTitle("Аудиополка").build()
        }
        val playing = isPlaying()
        nb.setContentTitle(chapterTitle(b))
            .setContentText(b.title)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_previous, "Назад", A_PREV, 1))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Пауза", A_TOGGLE, 2)
                else action(android.R.drawable.ic_media_play, "Играть", A_TOGGLE, 2)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Вперёд", A_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", A_STOP, 4))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val a = art
        if (a != null) nb.setLargeIcon(a)
        return nb.build()
    }

    private fun loadArt(b: Book): Bitmap? {
        val f = b.coverFile()
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(f.path, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
