package com.example.purebrowser.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.media.ResourceSniffer

/** A live, bounded tab session. Removing from a host does not destroy its browsing history. */
class BrowserSession(
    val recordId: String,
    appContext: Context,
    record: TabRecord,
    message: (String) -> Unit,
    changed: (BrowserPage) -> Unit,
    visited: (String, String) -> Unit,
    link: (String) -> Unit,
) {
    private val app = appContext.applicationContext
    private val context = MutableContextWrapper(app)
    val sniffer = ResourceSniffer()
    val engine = BrowserEngine(sniffer, message, record.url, changed, visited, link)
    private var webView: WebView? = null

    fun mount(host: FrameLayout) {
        context.baseContext = host.context
        val view = webView ?: WebView(context).also { webView = it; engine.attach(it) }
        if (view.parent !== host) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.removeAllViews()
            host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            engine.resume()
        }
    }
    /** Only the existing mounted view; callers must never construct an inactive tab for preview. */
    fun mountedPreviewView(): WebView? = webView?.takeIf { it.parent != null }

    fun unmount(host: FrameLayout) {
        val view = webView ?: return
        if (view.parent === host) {
            host.removeView(view)
            engine.pause()
            context.baseContext = app
        }
    }
    fun destroy() {
        webView?.let { (it.parent as? ViewGroup)?.removeView(it); engine.detach(it) }
        webView = null
        context.baseContext = app
    }
}
