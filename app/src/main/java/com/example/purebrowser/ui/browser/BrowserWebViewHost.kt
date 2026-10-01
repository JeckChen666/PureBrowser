package com.example.purebrowser.ui.browser

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.purebrowser.browser.BrowserSession

@Composable
fun BrowserWebViewHost(session: BrowserSession, modifier: Modifier = Modifier) {
    key(session) {
        AndroidView(factory = { FrameLayout(it).also(session::mount) }, modifier = modifier,
            onRelease = session::unmount, update = session::mount)
    }
}
