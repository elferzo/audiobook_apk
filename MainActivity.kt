package com.kvu.offline

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_HOME = "https://knigavuhe.org/"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val h = Handler(Looper.getMainLooper())
    private val captured: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

    private lateinit var tabShelf: Button
    private lateinit var tabCatalog: Button
    private lateinit var shelfScroll: ScrollView
    private lateinit var shelfList: LinearLayout
    private lateinit var catalogBox: LinearLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var pageProgress: ProgressBar
    private var web: WebView? = null

    private lateinit var playerBox: LinearLayout
    private lateinit var tvBook: TextView
    private lateinit var tvChapter: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnSleep: Button

    private var seeking = false
    private var shelfSig = ""
    private var pollCount = 0
    private var idleBook: Book? = null
    private var idlePos: Pos? = null
    private var resumed = false

    private val poll = object : Runnable {
        override fun run() {
            if (!resumed) return
            updatePlayer()
            if (pollCount % 4 == 0 && shelfScroll.visibility == View.VISIBLE) refreshShelf(false)
            pollCount++
            h.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)

    // ---------- построение экрана ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabShelf = button("Моя полка") { showShelf() }
        tabCatalog = button("Каталог") { showCatalog() }
        tabs.addView(tabShelf, LinearLayout.LayoutParams(0, WRAP, 1f))
        tabs.addView(tabCatalog, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))

        val content = FrameLayout(this)
        shelfScroll = ScrollView(this)
        shelfList = LinearLayout(this)
        shelfList.orientation = LinearLayout.VERTICAL
        shelfList.setPadding(dp(12), dp(8), dp(12), dp(8))
        shelfScroll.addView(shelfList, FrameLayout.LayoutParams(MATCH, WRAP))
        content.addView(shelfScroll, FrameLayout.LayoutParams(MATCH, MATCH))

        catalogBox = LinearLayout(this)
        catalogBox.orientation = LinearLayout.VERTICAL
        pageProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pageProgress.max = 100
        pageProgress.visibility = View.GONE
        catalogBox.addView(pageProgress, LinearLayout.LayoutParams(MATCH, dp(3)))
        webHolder = FrameLayout(this)
        catalogBox.addView(webHolder, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(small("←") { web?.let { if (it.canGoBack()) it.goBack() } }, LinearLayout.LayoutParams(dp(52), WRAP))
        bar.addView(small("⟳") { web?.reload() }, LinearLayout.LayoutParams(dp(52), WRAP))
        bar.addView(button("⬇ Скачать книгу") { grabBook() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(small("⚙") { askHome() }, LinearLayout.LayoutParams(dp(52), WRAP))
        catalogBox.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(catalogBox, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        buildPlayer()
        root.addView(playerBox, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            }
        }

        if (Store.all(this).isEmpty()) showCatalog() else showShelf()
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun small(text: String, onClick: () -> Unit): Button {
        val b = button(text, onClick)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, b.paddingTop, 0, b.paddingBottom)
        return b
    }

    private fun label(text: String, sizeSp: Float, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun buildPlayer() {
        playerBox = LinearLayout(this)
        playerBox.orientation = LinearLayout.VERTICAL
        playerBox.setPadding(dp(12), dp(6), dp(12), dp(6))
        playerBox.visibility = View.GONE

        val line = View(this)
        line.setBackgroundColor(0x33888888)
        playerBox.addView(line, LinearLayout.LayoutParams(MATCH, dp(1)))

        tvBook = label("", 15f, true)
        tvBook.maxLines = 1
        tvBook.ellipsize = TextUtils.TruncateAt.END
        tvBook.setPadding(0, dp(6), 0, 0)
        playerBox.addView(tvBook)
        tvChapter = label("", 13f, false)
        tvChapter.maxLines = 1
        tvChapter.ellipsize = TextUtils.TruncateAt.END
        playerBox.addView(tvChapter)

        val seekRow = LinearLayout(this)
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        tvPos = label("0:00", 12f, false)
        tvDur = label("0:00", 12f, false)
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = time(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val target = seek.progress
                control(PlayerService.A_SEEK) { it.putExtra("pos", target) }
            }
        })
        seekRow.addView(tvPos)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        seekRow.addView(tvDur)
        playerBox.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        btnPlay = small("▶") { control(PlayerService.A_TOGGLE) }
        btnPlay.textSize = 20f
        val w = LinearLayout.LayoutParams(0, WRAP, 1f)
        row.addView(small("⏮") { control(PlayerService.A_PREV) }, w)
        row.addView(small("−15") { control(PlayerService.A_REL) { it.putExtra("delta", -15000) } }, w)
        row.addView(btnPlay, LinearLayout.LayoutParams(0, WRAP, 1.4f))
        row.addView(small("+30") { control(PlayerService.A_REL) { it.putExtra("delta", 30000) } }, w)
        row.addView(small("⏭") { control(PlayerService.A_NEXT) }, w)
        playerBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        btnSpeed = small("1×") { askSpeed() }
        btnSleep = small("Сон") { askSleep() }
        row2.addView(small("Главы") { askChapter() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row2.addView(btnSpeed, LinearLayout.LayoutParams(0, WRAP, 1f))
        row2.addView(btnSleep, LinearLayout.LayoutParams(0, WRAP, 1f))
        playerBox.addView(row2, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // ---------- вкладки ----------

    private fun showShelf() {
        shelfScroll.visibility = View.VISIBLE
        catalogBox.visibility = View.GONE
        tabShelf.isEnabled = false
        tabCatalog.isEnabled = true
        refreshShelf(true)
    }

    private fun showCatalog() {
        shelfScroll.visibility = View.GONE
        catalogBox.visibility = View.VISIBLE
        tabShelf.isEnabled = true
        tabCatalog.isEnabled = false
        if (web == null) createWeb()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (catalogBox.visibility == View.VISIBLE) {
            if (w != null && w.canGoBack()) w.goBack() else showShelf()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        idleBook = null
        idlePos = null
        h.removeCallbacks(poll)
        h.post(poll)
    }

    override fun onPause() {
        resumed = false
        h.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    // ---------- каталог (встроенный браузер) ----------

    private fun createWeb() {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        CookieManager.getInstance().setAcceptCookie(true)
        prefs().edit().putString("ua", s.userAgentString).apply()

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme ?: ""
                return !(scheme == "http" || scheme == "https")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                captured.clear()
                pageProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageProgress.visibility = View.GONE
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val path = (uri.path ?: "").lowercase()
                if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".m4b")) {
                    val u = uri.toString()
                    if (!captured.contains(u)) captured.add(u)
                }
                return null
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageProgress.progress = newProgress
            }
        }
        webHolder.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        web = w
        w.loadUrl(prefs().getString("home", DEFAULT_HOME) ?: DEFAULT_HOME)
    }

    private fun askHome() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setText(prefs().getString("home", DEFAULT_HOME))
        AlertDialog.Builder(this)
            .setTitle("Адрес каталога")
            .setMessage("Если сайт переехал на другой адрес, укажите его здесь.")
            .setView(input)
            .setPositiveButton("Открыть") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) url = DEFAULT_HOME
                if (!url.startsWith("http")) url = "https://$url"
                prefs().edit().putString("home", url).apply()
                web?.loadUrl(url)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Кнопка «Скачать книгу»: спрашиваем у открытой страницы список глав. */
    private fun grabBook() {
        val w = web ?: return
        val js = try {
            assets.open("extract.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            toast("Не найден extract.js")
            return
        }
        w.evaluateJavascript(js) { res -> onExtracted(res) }
    }

    private fun onExtracted(res: String?) {
        var o = JSONObject()
        try {
            val inner = JSONTokener(res ?: "null").nextValue()
            if (inner is String) o = JSONObject(inner)
        } catch (e: Exception) {
        }
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        val urls = ArrayList<String>()
        val titles = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val u = t.optString("url")
            if (u.startsWith("http")) {
                urls.add(u)
                titles.add(t.optString("title"))
            }
        }
        if (urls.isEmpty()) {
            synchronized(captured) {
                for (u in captured) {
                    urls.add(u)
                    titles.add("")
                }
            }
        }
        if (urls.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Главы не найдены")
                .setMessage(
                    "На этой странице не видно аудио. Откройте страницу самой книги (где плеер), " +
                        "при необходимости запустите воспроизведение на пару секунд и нажмите «Скачать книгу» ещё раз."
                )
                .setPositiveButton("Понятно", null)
                .show()
            return
        }
        val page = o.optString("page").ifEmpty { web?.url ?: "" }
        val title = cleanTitle(o.optString("title")).ifEmpty { "Книга" }
        val cover = o.optString("cover")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Глав: ${urls.size}. Скачать на телефон?")
            .setPositiveButton("Скачать") { _, _ -> startDownload(page, title, cover, urls, titles) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("\\s*[-—–|]\\s*(слушать|аудиокнига|скачать|онлайн).*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(слушать|аудиокнига)\\s+(онлайн|бесплатно).*$", RegexOption.IGNORE_CASE), "")
        return t.trim()
    }

    private fun startDownload(page: String, title: String, cover: String, urls: List<String>, titles: List<String>) {
        val id = Store.idFor(page)
        val b = Book(id, Store.dirFor(this, id))
        b.title = title
        b.page = page
        b.cover = cover
        for (i in urls.indices) {
            val path = urls[i].substringBefore('?').substringBefore('#')
            var ext = path.substringAfterLast('.', "mp3").lowercase()
            if (ext != "mp3" && ext != "m4a" && ext != "m4b" && ext != "ogg" && ext != "aac") ext = "mp3"
            val name = String.format("%03d.%s", i + 1, ext)
            val t = titles.getOrNull(i)?.trim() ?: ""
            b.tracks.add(Track(if (t.isNotEmpty()) t else "Глава ${i + 1}", urls[i], name))
        }
        try {
            b.saveMeta()
        } catch (e: Exception) {
            toast("Не удалось сохранить книгу: ${e.message}")
            return
        }
        DownloadService.lastError = null
        DownloadService.enqueue(this, id)
        toast("Скачиваю «$title»")
        showShelf()
    }

    // ---------- полка ----------

    private fun refreshShelf(force: Boolean) {
        val books = Store.all(this)
        val playingId = PlayerService.instance?.book?.id
        val sb = StringBuilder()
        for (b in books) {
            sb.append(b.id).append(':').append(b.doneCount()).append('/').append(b.tracks.size)
                .append(DownloadService.isQueued(b.id)).append(';')
        }
        sb.append(DownloadService.lastError).append(playingId)
        val sig = sb.toString()
        if (!force && sig == shelfSig) return
        shelfSig = sig

        shelfList.removeAllViews()
        val err = DownloadService.lastError
        if (err != null) {
            val e = label("Ошибка загрузки — $err. Нажмите «Докачать»; если не помогло, откройте книгу в каталоге и скачайте заново.", 13f, false)
            e.setTextColor(0xFFD9534F.toInt())
            e.setPadding(0, 0, 0, dp(8))
            shelfList.addView(e)
        }
        if (books.isEmpty()) {
            val t = label("Полка пуста.\n\nОткройте «Каталог», найдите книгу, зайдите на её страницу и нажмите «Скачать книгу». После этого её можно слушать без интернета.", 15f, false)
            t.setPadding(0, dp(24), 0, 0)
            shelfList.addView(t)
            return
        }
        for (b in books) shelfList.addView(bookRow(b, playingId == b.id))
    }

    private fun bookRow(b: Book, current: Boolean): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(8))

        val done = b.doneCount()
        val total = b.tracks.size
        val queued = DownloadService.isQueued(b.id)
        box.addView(label((if (current) "▶ " else "") + b.title, 16f, true))
        val status = when {
            queued -> "Скачивается: $done из $total"
            done < total -> "Скачано $done из $total"
            else -> {
                val p = b.loadPos()
                "Готово к прослушиванию · глава ${p.index + 1} из $total"
            }
        }
        box.addView(label(status, 13f, false))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val listen = button("Слушать") {
            PlayerService.send(this, PlayerService.A_PLAY) {
                it.putExtra("id", b.id)
                it.putExtra("index", -1)
            }
        }
        listen.isEnabled = done > 0
        row.addView(listen)
        if (done < total && !queued) {
            row.addView(button("Докачать") {
                DownloadService.lastError = null
                DownloadService.enqueue(this, b.id)
                refreshShelf(true)
            })
        }
        row.addView(button("Удалить") { confirmDelete(b) })
        box.addView(row)

        val line = View(this)
        line.setBackgroundColor(0x33888888)
        box.addView(line, LinearLayout.LayoutParams(MATCH, dp(1)))
        return box
    }

    private fun confirmDelete(b: Book) {
        AlertDialog.Builder(this)
            .setTitle("Удалить книгу?")
            .setMessage(b.title)
            .setPositiveButton("Удалить") { _, _ ->
                if (PlayerService.instance?.book?.id == b.id) PlayerService.send(this, PlayerService.A_STOP)
                if (DownloadService.currentId == b.id) DownloadService.cancelId = b.id
                Store.delete(b)
                if (prefs().getString("last", null) == b.id) prefs().edit().remove("last").apply()
                idleBook = null
                refreshShelf(true)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- панель плеера ----------

    /** Команда плееру. Если плеер ещё не запущен — сначала открываем последнюю книгу. */
    private fun control(action: String, fill: (android.content.Intent) -> Unit = {}) {
        val svc = PlayerService.instance
        if (svc?.book == null) {
            val b = idleBook ?: return
            PlayerService.send(this, PlayerService.A_PLAY) {
                it.putExtra("id", b.id)
                it.putExtra("index", -1)
            }
            return
        }
        PlayerService.send(this, action, fill)
    }

    private fun updatePlayer() {
        val svc = PlayerService.instance
        val live = svc?.book
        val b: Book?
        val index: Int
        val pos: Int
        val dur: Int
        val playing: Boolean
        val speed: Float
        if (svc != null && live != null) {
            idleBook = null
            idlePos = null
            b = live
            index = svc.index
            pos = svc.position()
            dur = svc.duration()
            playing = svc.isPlaying()
            speed = svc.speed
        } else {
            if (idleBook == null) {
                val id = prefs().getString("last", null)
                idleBook = if (id != null) Store.load(this, id) else null
                idlePos = idleBook?.loadPos()
            }
            b = idleBook
            val p = idlePos
            index = p?.index ?: 0
            pos = p?.ms ?: 0
            dur = 0
            playing = false
            speed = p?.speed ?: 1.0f
        }
        if (b == null || b.tracks.isEmpty()) {
            playerBox.visibility = View.GONE
            return
        }
        playerBox.visibility = View.VISIBLE
        tvBook.text = b.title
        val ct = b.tracks.getOrNull(index)?.title ?: ""
        tvChapter.text = "${index + 1}/${b.tracks.size} · " + (if (ct.isNotBlank()) ct else "Глава ${index + 1}")
        btnPlay.text = if (playing) "⏸" else "▶"
        btnSpeed.text = speedText(speed)
        seek.isEnabled = dur > 0
        if (!seeking) {
            seek.max = if (dur > 0) dur else 1
            seek.progress = if (dur > 0) pos.coerceIn(0, dur) else 0
            tvPos.text = time(pos)
        }
        tvDur.text = if (dur > 0) time(dur) else "—"

        val sleepText = when {
            svc == null -> "Сон"
            svc.sleepChapter -> "Сон: глава"
            svc.sleepAt > 0 -> {
                val left = (svc.sleepAt - SystemClock.elapsedRealtime()) / 60000 + 1
                "Сон: $left мин"
            }
            else -> "Сон"
        }
        btnSleep.text = sleepText
    }

    private fun speedText(s: Float): String {
        val x = Math.round(s * 100)
        return if (x % 100 == 0) "${x / 100}×" else "${x / 100},${(x % 100).toString().padStart(2, '0').trimEnd('0')}×"
    }

    private fun askSpeed() {
        val values = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val names = Array(values.size) { speedText(values[it]) }
        AlertDialog.Builder(this)
            .setTitle("Скорость")
            .setItems(names) { _, which ->
                val v = values[which]
                if (PlayerService.instance?.book != null) {
                    PlayerService.send(this, PlayerService.A_SPEED) { it.putExtra("speed", v) }
                } else {
                    toast("Сначала запустите книгу")
                }
            }
            .show()
    }

    private fun askSleep() {
        if (PlayerService.instance?.book == null) {
            toast("Сначала запустите книгу")
            return
        }
        val mins = intArrayOf(0, 15, 30, 45, 60, -1)
        val names = arrayOf("Выключить", "15 минут", "30 минут", "45 минут", "60 минут", "До конца главы")
        AlertDialog.Builder(this)
            .setTitle("Таймер сна")
            .setItems(names) { _, which ->
                val m = mins[which]
                PlayerService.send(this, PlayerService.A_SLEEP) { it.putExtra("min", m) }
            }
            .show()
    }

    private fun askChapter() {
        val b = PlayerService.instance?.book ?: idleBook ?: return
        val names = Array(b.tracks.size) { i ->
            val t = b.tracks[i].title
            (if (b.isDone(i)) "" else "⏳ ") + (i + 1) + ". " + (if (t.isNotBlank()) t else "Глава ${i + 1}")
        }
        AlertDialog.Builder(this)
            .setTitle(b.title)
            .setItems(names) { _, which ->
                if (!b.isDone(which)) {
                    toast("Эта глава ещё не скачана")
                } else {
                    PlayerService.send(this, PlayerService.A_PLAY) {
                        it.putExtra("id", b.id)
                        it.putExtra("index", which)
                    }
                }
            }
            .show()
    }

    private fun time(ms: Int): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
