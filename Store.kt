package com.kvu.offline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Track(val title: String, val url: String, val file: String)

/** Сохранённая позиция прослушивания. */
class Pos(val index: Int, val ms: Int, val speed: Float)

/**
 * Книга = папка на телефоне:
 *   meta.json  — название, адрес страницы, список глав (пишется при скачивании)
 *   pos.json   — где остановились (пишет только плеер)
 *   001.mp3 …  — главы; глава считается скачанной, если файл существует
 */
class Book(val id: String, val dir: File) {
    var title: String = ""
    var page: String = ""
    var cover: String = ""
    val tracks = ArrayList<Track>()

    fun trackFile(i: Int): File = File(dir, tracks[i].file)
    fun isDone(i: Int): Boolean = i in tracks.indices && trackFile(i).exists()
    fun doneCount(): Int = tracks.indices.count { isDone(it) }
    fun complete(): Boolean = tracks.isNotEmpty() && doneCount() == tracks.size
    fun coverFile(): File = File(dir, "cover.jpg")

    fun saveMeta() {
        dir.mkdirs()
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(JSONObject().put("title", t.title).put("url", t.url).put("file", t.file))
        }
        val o = JSONObject()
            .put("title", title)
            .put("page", page)
            .put("cover", cover)
            .put("tracks", arr)
        Store.writeAtomic(File(dir, "meta.json"), o.toString())
    }

    fun savePos(index: Int, ms: Int, speed: Float) {
        if (!dir.exists()) return
        val o = JSONObject()
            .put("index", index)
            .put("ms", ms)
            .put("speed", speed.toDouble())
        try {
            Store.writeAtomic(File(dir, "pos.json"), o.toString())
        } catch (e: Exception) {
        }
    }

    fun loadPos(): Pos {
        return try {
            val o = JSONObject(File(dir, "pos.json").readText())
            val i = o.optInt("index", 0).coerceIn(0, maxOf(0, tracks.size - 1))
            Pos(i, maxOf(0, o.optInt("ms", 0)), o.optDouble("speed", 1.0).toFloat())
        } catch (e: Exception) {
            Pos(0, 0, 1.0f)
        }
    }
}

object Store {
    fun root(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "books")
        d.mkdirs()
        return d
    }

    /** Один и тот же адрес книги всегда даёт одну и ту же папку — повторное скачивание докачивает. */
    fun idFor(page: String): String {
        val key = page.substringBefore('#').substringBefore('?').trimEnd('/')
        return "b" + Integer.toHexString(key.hashCode())
    }

    fun dirFor(ctx: Context, id: String): File = File(root(ctx), id)

    fun load(ctx: Context, id: String): Book? {
        val dir = dirFor(ctx, id)
        val f = File(dir, "meta.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val b = Book(id, dir)
            b.title = o.optString("title")
            b.page = o.optString("page")
            b.cover = o.optString("cover")
            val arr = o.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                b.tracks.add(Track(t.optString("title"), t.optString("url"), t.optString("file")))
            }
            b
        } catch (e: Exception) {
            null
        }
    }

    fun all(ctx: Context): List<Book> {
        val dirs = root(ctx).listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }
            .mapNotNull { load(ctx, it.name) }
            .sortedBy { it.title.lowercase() }
    }

    fun delete(b: Book) {
        b.dir.deleteRecursively()
    }

    fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}
