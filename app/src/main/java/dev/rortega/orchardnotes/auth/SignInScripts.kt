package dev.rortega.orchardnotes.auth

/**
 * Scripts injected into the sign-in WebView. They only observe or assist Apple's
 * own pages; credentials are never read.
 */
internal object SignInScripts {

    const val BRIDGE_NAME = "OrchardBridge"

    /**
     * Injected into www.icloud.com: reports the bodies of the web client's own
     * `accountLogin`/`validate` responses, so the app learns the moment sign-in
     * (including two-factor authentication) completes without polling Apple.
     */
    val SESSION_HOOK = """
        (function () {
          if (window.__orchardHooked) return;
          window.__orchardHooked = true;
          var pattern = /^https:\/\/setup\.icloud\.com(\.cn)?\/setup\/ws\/1\/(accountLogin|validate)/;
          function report(url, text) {
            try {
              if (url && pattern.test(url) && window.$BRIDGE_NAME) {
                window.$BRIDGE_NAME.postMessage(JSON.stringify({ url: url, body: text }));
              }
            } catch (e) {}
          }
          var originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function (input) {
              return originalFetch.apply(this, arguments).then(function (response) {
                try {
                  var url = response.url || (typeof input === 'string' ? input : input && input.url);
                  if (response.ok && url && pattern.test(url)) {
                    response.clone().text().then(function (text) { report(url, text); });
                  }
                } catch (e) {}
                return response;
              });
            };
          }
          var open = XMLHttpRequest.prototype.open;
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__orchardUrl = url;
            return open.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            xhr.addEventListener('load', function () {
              try {
                var url = xhr.responseURL || xhr.__orchardUrl;
                if (xhr.status < 200 || xhr.status >= 300 || !pattern.test(url)) return;
                var text = (xhr.responseType === '' || xhr.responseType === 'text')
                  ? xhr.responseText : JSON.stringify(xhr.response);
                report(url, text);
              } catch (e) {}
            });
            return send.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /**
     * Injected into Apple's sign-in frame (idmsa.apple.com): ticks "Keep me signed
     * in" once when it first appears, so the session survives app restarts the way
     * an app login is expected to. If the user unticks it, it is left alone.
     */
    val KEEP_ME_SIGNED_IN = """
        (function () {
          var done = false;
          function findCheckbox() {
            var box = document.getElementById('remember-me');
            if (box) return box;
            var labels = document.querySelectorAll('label');
            for (var i = 0; i < labels.length; i++) {
              if (/keep me signed in/i.test(labels[i].textContent || '')) {
                return labels[i].control || document.getElementById(labels[i].htmlFor);
              }
            }
            return null;
          }
          function tick() {
            if (done) return;
            var box = findCheckbox();
            if (box && box.type === 'checkbox') {
              done = true;
              if (!box.checked) box.click();
              if (observer) observer.disconnect();
            }
          }
          var observer = null;
          function start() {
            tick();
            if (!done) {
              observer = new MutationObserver(tick);
              observer.observe(document.documentElement, { childList: true, subtree: true });
            }
          }
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', start);
          } else {
            start();
          }
        })();
    """.trimIndent()

    /**
     * Run on demand for the troubleshooting report: a plain-text description of what the
     * page currently shows (address, text, frames, what covers the middle of the screen,
     * which browser APIs are missing), to tell a blank page from one that failed to draw.
     */
    val PAGE_SNAPSHOT = """
        (function () {
          function describe(el) {
            if (!el) return 'nothing';
            var name = el.tagName.toLowerCase();
            if (el.id) name += '#' + el.id;
            if (typeof el.className === 'string' && el.className.trim()) {
              name += '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.');
            }
            var style = getComputedStyle(el);
            return name + ' (background ' + style.backgroundColor + ', opacity ' + style.opacity + ')';
          }
          try {
            var body = document.body;
            var text = body ? (body.innerText || '').replace(/\s+/g, ' ').trim() : '';
            var frames = Array.prototype.map.call(document.querySelectorAll('iframe'), function (frame) {
              var box = frame.getBoundingClientRect();
              return '  ' + (frame.src || '(no src)').split(/[?#]/)[0] + ' ' + Math.round(box.width) + 'x' + Math.round(box.height);
            });
            var missing = ['fetch', 'Promise', 'ResizeObserver', 'IntersectionObserver', 'structuredClone', 'indexedDB',
              'BroadcastChannel', 'SharedWorker', 'Notification', 'PublicKeyCredential'].filter(function (name) {
              return typeof window[name] === 'undefined';
            });
            var bodyStyle = body ? getComputedStyle(body) : null;
            return [
              'url: ' + location.href.split(/[?#]/)[0],
              'ready: ' + document.readyState + ', elements: ' + document.getElementsByTagName('*').length,
              'viewport: ' + innerWidth + 'x' + innerHeight + ' at ' + devicePixelRatio + 'x, dark mode: ' +
                matchMedia('(prefers-color-scheme: dark)').matches,
              'body: ' + (bodyStyle ? body.scrollWidth + 'x' + body.scrollHeight + ', display ' + bodyStyle.display +
                ', visibility ' + bodyStyle.visibility + ', opacity ' + bodyStyle.opacity : 'none'),
              'middle of screen: ' + describe(document.elementFromPoint(innerWidth / 2, innerHeight / 2)),
              'text (' + text.length + ' chars): ' + text.slice(0, 200),
              'frames: ' + frames.length
            ].concat(frames, [
              'missing APIs: ' + (missing.join(', ') || 'none'),
              'user agent: ' + navigator.userAgent
            ]).join('\n');
          } catch (e) {
            return 'snapshot failed: ' + e;
          }
        })();
    """.trimIndent()
}
