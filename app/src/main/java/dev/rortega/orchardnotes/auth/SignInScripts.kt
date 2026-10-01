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
}
