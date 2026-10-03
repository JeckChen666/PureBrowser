package com.example.purebrowser.ui.browser

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.purebrowser.browser.BrowserSession

@Composable
fun BrowserWebViewHost(session: BrowserSession, modifier: Modifier = Modifier, active: Boolean = true, previewSource: (android.webkit.WebView?) -> Unit = {}) {
    key(session) {
        AndroidView(factory = { FrameLayout(it).also { host -> session.mount(host);previewSource(session.mountedPreviewView()) } }, modifier = modifier,
            onRelease = { host -> previewSource(null);session.unmount(host) }, update = { host -> session.mount(host);previewSource(session.mountedPreviewView());host.visibility=if(active) android.view.View.VISIBLE else android.view.View.INVISIBLE;if(!active)session.engine.pause() })
    }
}
