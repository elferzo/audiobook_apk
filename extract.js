// Выполняется внутри открытой страницы книги и возвращает список глав.
// Ничего не знает о вёрстке конкретного сайта: ищет в скриптах страницы
// массив объектов со ссылками на аудиофайлы, а если не нашёл — любые ссылки на аудио.
(function () {
  var AUDIO = /\.(mp3|m4a|m4b|ogg|aac)(\?|#|$)/i;
  var out = { title: '', cover: '', page: location.href, tracks: [] };

  var ogTitle = document.querySelector('meta[property="og:title"]');
  out.title = (ogTitle && ogTitle.content) || document.title || '';
  var ogImage = document.querySelector('meta[property="og:image"]');
  out.cover = (ogImage && ogImage.content) || '';
  if (out.cover.indexOf('//') === 0) out.cover = location.protocol + out.cover;

  var seen = {};
  function add(url, title) {
    if (typeof url !== 'string' || !url) return false;
    url = url.replace(/\\\//g, '/');
    if (url.indexOf('//') === 0) url = location.protocol + url;
    if (url.indexOf('/') === 0) url = location.origin + url;
    if (url.indexOf('http') !== 0) return false;
    if (seen[url]) return false;
    seen[url] = 1;
    out.tracks.push({ url: url, title: typeof title === 'string' ? title : '' });
    return true;
  }

  function fromArray(arr) {
    var n = 0;
    for (var i = 0; i < arr.length; i++) {
      var it = arr[i];
      if (!it || typeof it !== 'object') continue;
      var u = it.url || it.file || it.src || it.mp3 || it.audio;
      if (typeof u === 'string' && AUDIO.test(u.replace(/\\\//g, '/'))) {
        if (add(u, it.title || it.name || '')) n++;
      }
    }
    return n;
  }

  // Ищем JSON-массив, внутри которого лежит позиция pos. Возвращает конец массива или -1.
  function arrayAround(text, pos) {
    var start = pos;
    for (var tries = 0; tries < 60; tries++) {
      start = text.lastIndexOf('[', start - 1);
      if (start < 0) return -1;
      var depth = 0, inStr = false, esc = false;
      for (var i = start; i < text.length; i++) {
        var c = text.charAt(i);
        if (inStr) {
          if (esc) esc = false;
          else if (c === '\\') esc = true;
          else if (c === '"') inStr = false;
          continue;
        }
        if (c === '"') { inStr = true; continue; }
        if (c === '[' || c === '{') depth++;
        else if (c === ']' || c === '}') {
          depth--;
          if (depth === 0) {
            if (i > pos) {
              try {
                var arr = JSON.parse(text.substring(start, i + 1));
                if (Array.isArray(arr) && fromArray(arr) > 0) return i;
              } catch (e) {}
            }
            break;
          }
        }
      }
    }
    return -1;
  }

  // 1. Плейлист в скриптах страницы
  var scripts = document.scripts;
  for (var s = 0; s < scripts.length; s++) {
    if (scripts[s].src) continue;
    var text = scripts[s].textContent || '';
    var from = 0;
    for (var guard = 0; guard < 2000; guard++) {
      var m = text.substring(from).search(/\.(mp3|m4a|m4b)/i);
      if (m < 0) break;
      var pos = from + m;
      var end = arrayAround(text, pos);
      from = end > pos ? end : pos + 4;
    }
  }

  // 2. Любые ссылки на аудио в коде страницы
  if (!out.tracks.length) {
    var html = document.documentElement.innerHTML;
    var re = /https?:\\?\/\\?\/[^"'\s<>]+?\.(?:mp3|m4a|m4b)(?:\?[^"'\s<>\\]*)?/gi;
    var found;
    while ((found = re.exec(html)) !== null) add(found[0], '');
  }

  // 3. То, что сейчас играет
  if (!out.tracks.length) {
    var media = document.querySelectorAll('audio, audio source');
    for (var a = 0; a < media.length; a++) add(media[a].currentSrc || media[a].src, '');
  }

  return JSON.stringify(out);
})();
