package com.example.purebrowser.browser.sniff

/** Shared media-keyword filter: the injected script and Kotlin-side checks use the same list. */
object MediaUrlFilter {
    val KEYWORDS = listOf(".m3u8", ".mp4", ".flv", ".webm", ".mkv", "playlist", "/hls/", "manifest", "playurl")
    fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }
}

/** Read-only page observation script, injected into the main frame at navigation start. */
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
            var nativeOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function () {
              try { var url = String(arguments[1] === undefined ? '' : arguments[1]); if (isMediaUrl(url)) report({ type: 'mediaUrl', url: url }); } catch (e) {}
              return nativeOpen.apply(this, arguments);
            };
            if (typeof window.fetch === 'function') {
              var nativeFetch = window.fetch;
              window.fetch = function () {
                try {
                  var input = arguments[0];
                  var url = typeof input === 'string' ? input : (input && typeof input.url === 'string' ? input.url : '');
                  if (isMediaUrl(url)) report({ type: 'mediaUrl', url: url });
                } catch (e) {}
                return nativeFetch.apply(this, arguments);
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

    val JS: String = TEMPLATE.replace(
        "__MEDIA_KEYWORDS__",
        MediaUrlFilter.KEYWORDS.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
    )
}
