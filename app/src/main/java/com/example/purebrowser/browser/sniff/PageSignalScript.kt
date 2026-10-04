package com.example.purebrowser.browser.sniff

/** Shared media-keyword filter: the injected script and Kotlin-side checks use the same list. */
object MediaUrlFilter {
    val KEYWORDS = listOf(".m3u8", ".mp4", ".flv", ".webm", ".mkv", "playlist", "/hls/", "manifest", "playurl")
    fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }
}

/**
 * Read-only page observation script, injected into the main frame at navigation start.
 * [build] additionally embeds the rules layer's capture-endpoint regex sources: when a fetch/XHR
 * URL matches one, a bounded copy of the JSON response body is reported as an apiPayload. Only the
 * page's own promise/instance is handed back untouched — capture reads a clone or a property and
 * never consumes the original body.
 */
object PageSignalScript {
    private val TEMPLATE = """
        (function () {
          try {
            if (window.__pbSignalGuard) return;
            window.__pbSignalGuard = true;
            var KEYWORDS = __MEDIA_KEYWORDS__;
            var CAP = 262144;
            var budget = 0, budgetStart = Date.now();
            function report(payload) {
              try {
                var now = Date.now();
                if (now - budgetStart >= 2000) { budgetStart = now; budget = 0; }
                if (budget >= 64) return;
                budget++;
                if (window.PbSniffBridge) PbSniffBridge.signal(JSON.stringify(payload));
              } catch (e) {}
            }
            function isMediaUrl(url) {
              try {
                if (typeof url !== 'string' || !url) return false;
                var lower = url.toLowerCase();
                for (var i = 0; i < KEYWORDS.length; i++) if (lower.indexOf(KEYWORDS[i]) !== -1) return true;
                return false;
              } catch (e) { return false; }
            }
            var CAPTURE = [];
            try {
              var patterns = __CAPTURE_ENDPOINTS__;
              for (var pi = 0; pi < patterns.length; pi++) {
                try { CAPTURE.push(new RegExp(patterns[pi])); } catch (e) {}
              }
            } catch (e) {}
            function matchesCapture(url) {
              try {
                if (CAPTURE.length === 0 || typeof url !== 'string' || !url) return false;
                for (var i = 0; i < CAPTURE.length; i++) {
                  try { if (CAPTURE[i].test(url)) return true; } catch (e) {}
                }
              } catch (e) {}
              return false;
            }
            function reportPayload(url, content) {
              try {
                if (typeof content === 'string' && content.length > 0 && content.length <= CAP)
                  report({ type: 'apiPayload', url: url, content: content });
              } catch (e) {}
            }
            function captureFetchResponse(url, resp) {
              // Bounded read of a CLONE only: the page's own response object and body stay untouched.
              try {
                if (!resp || typeof resp.clone !== 'function' || !resp.headers || typeof resp.headers.get !== 'function') return;
                var type = String(resp.headers.get('content-type') || '').toLowerCase();
                if (type.indexOf('json') === -1) return;
                var declared = parseInt(resp.headers.get('content-length') || '0', 10);
                if (declared > CAP) return;
                var copy = resp.clone();
                if (copy.body && typeof copy.body.getReader === 'function' && typeof TextDecoder === 'function') {
                  var reader = copy.body.getReader(), decoder = new TextDecoder(), text = '';
                  function pump() {
                    reader.read().then(function (chunk) {
                      try {
                        if (chunk.value) text += decoder.decode(chunk.value, { stream: !chunk.done });
                        if (text.length > CAP) { try { reader.cancel(); } catch (e2) {} return; }
                        if (chunk.done) { reportPayload(url, text); return; }
                        pump();
                      } catch (e2) {}
                    }, function () {});
                  }
                  pump();
                } else if (typeof copy.text === 'function') {
                  copy.text().then(function (text) { reportPayload(url, text); }, function () {});
                }
              } catch (e) {}
            }
            var captureUrls = typeof WeakMap === 'function' ? new WeakMap() : null;
            var hookedXhrs = typeof WeakMap === 'function' ? new WeakMap() : null;
            var nativeOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function () {
              try {
                var url = String(arguments[1] === undefined ? '' : arguments[1]);
                if (isMediaUrl(url)) report({ type: 'mediaUrl', url: url });
                if (captureUrls && matchesCapture(url)) captureUrls.set(this, url);
              } catch (e) {}
              return nativeOpen.apply(this, arguments);
            };
            var nativeSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function () {
              try {
                var endpoint = captureUrls ? captureUrls.get(this) : null;
                if (endpoint && hookedXhrs && !hookedXhrs.has(this) && typeof this.addEventListener === 'function') {
                  hookedXhrs.set(this, true);
                  var target = endpoint;
                  this.addEventListener('load', function () {
                    // Property read only, and only for text responses; the page keeps its own body.
                    try {
                      if (this.responseType !== '' && this.responseType !== 'text') return;
                      reportPayload(target, this.responseText);
                    } catch (e) {}
                  });
                }
              } catch (e) {}
              return nativeSend.apply(this, arguments);
            };
            if (typeof window.fetch === 'function') {
              var nativeFetch = window.fetch;
              window.fetch = function () {
                var url = '', capture = false;
                try {
                  var input = arguments[0];
                  url = typeof input === 'string' ? input : (input && typeof input.url === 'string' ? input.url : '');
                  if (isMediaUrl(url)) report({ type: 'mediaUrl', url: url });
                  capture = matchesCapture(url);
                } catch (e) {}
                var promise = nativeFetch.apply(this, arguments);
                if (capture) {
                  try { promise.then(function (resp) { captureFetchResponse(url, resp); }, function () {}); } catch (e) {}
                }
                return promise;
              };
            }
            if (window.MediaSource && MediaSource.prototype && typeof MediaSource.prototype.addSourceBuffer === 'function') {
              var nativeAddSourceBuffer = MediaSource.prototype.addSourceBuffer;
              MediaSource.prototype.addSourceBuffer = function () {
                try { var mime = String(arguments[0] === undefined ? '' : arguments[0]); if (mime) report({ type: 'mseMime', mime: mime }); } catch (e) {}
                return nativeAddSourceBuffer.apply(this, arguments);
              };
            }
            if (typeof Blob === 'function' && typeof Proxy === 'function') {
              var NativeBlob = Blob;
              window.Blob = new Proxy(NativeBlob, {
                construct: function (Target, args) {
                  try {
                    var options = args[1];
                    var type = options && typeof options.type === 'string' ? options.type.toLowerCase() : '';
                    if (type === 'application/vnd.apple.mpegurl' || type === 'application/x-mpegurl') {
                      var parts = args[0], content = '', truncated = false;
                      if (Array.isArray(parts)) {
                        for (var i = 0; i < parts.length; i++) {
                          if (typeof parts[i] === 'string') content += parts[i];
                          else truncated = true;
                          if (content.length >= CAP) { content = content.slice(0, CAP); truncated = true; break; }
                        }
                      } else truncated = true;
                      report({ type: 'blobManifest', content: content, truncated: truncated });
                    }
                  } catch (e) {}
                  return Reflect.construct(Target, args);
                }
              });
            }
            function serializeConfig(value) {
              var raw;
              try { raw = JSON.stringify(value); } catch (e) { raw = undefined; }
              if (raw === undefined) { try { raw = String(value); } catch (e) { raw = ''; } }
              if (raw.length > CAP) raw = raw.slice(0, CAP);
              return raw;
            }
            function reportConfig(family, value) {
              try { if (value !== null && value !== undefined) report({ type: 'playerConfig', family: family, raw: serializeConfig(value) }); } catch (e) {}
            }
            function harvestConfigs() {
              try {
                for (var key in window) {
                  try {
                    if (!Object.prototype.hasOwnProperty.call(window, key)) continue;
                    if (key.indexOf('flashvars') === 0) reportConfig('flashvars', window[key]);
                    else if (key === 'kvsplayer' || key === 'html5player' || key === 'xplayerSettings' || key === 'stream_data') reportConfig(key, window[key]);
                  } catch (e) {}
                }
                try { if (window.initials && window.initials.xplayerSettings) reportConfig('xplayerSettings', window.initials.xplayerSettings); } catch (e) {}
              } catch (e) {}
            }
            var seenFrames = {};
            function scanIframes() {
              try {
                if (!document || !document.querySelectorAll) return;
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                  try {
                    var src = frames[i].getAttribute('src');
                    if (!src) continue;
                    var resolved = new URL(src, location.href);
                    if (resolved.protocol !== 'http:' && resolved.protocol !== 'https:') continue;
                    if (resolved.origin === location.origin || seenFrames[resolved.href]) continue;
                    seenFrames[resolved.href] = true;
                    report({ type: 'iframeSrc', url: resolved.href });
                  } catch (e) {}
                }
              } catch (e) {}
            }
            function secondPass() { harvestConfigs(); scanIframes(); }
            harvestConfigs();
            scanIframes();
            try {
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { try { secondPass(); } catch (e) {} });
              else setTimeout(function () { try { secondPass(); } catch (e) {} }, 0);
            } catch (e) {}
          } catch (e) {}
        })();
    """.trimIndent()

    /** Default script without any capture endpoints; [build] is the production entry point. */
    val JS: String = build(emptyList())

    /**
     * Materializes the script with the rules layer's capture-endpoint pattern sources embedded as a
     * JSON string array. Patterns are quoted with backslash/control escaping so regex sources stay
     * string data in the script — they are never interpolated as code.
     */
    fun build(captureEndpoints: List<String>): String = TEMPLATE
        .replace(
            "__MEDIA_KEYWORDS__",
            MediaUrlFilter.KEYWORDS.joinToString(prefix = "[", postfix = "]") { quote(it) },
        )
        .replace(
            "__CAPTURE_ENDPOINTS__",
            captureEndpoints.joinToString(prefix = "[", postfix = "]") { quote(it) },
        )

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c.code < 0x20 -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }
}
