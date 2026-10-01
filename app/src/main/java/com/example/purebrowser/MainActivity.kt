package com.example.purebrowser

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.theme.PureBrowserTheme
import com.example.purebrowser.ui.browser.BrowserScreen
import com.example.purebrowser.ui.browser.BrowserViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: BrowserViewModel = viewModel()
            val data = model.data.collectAsStateWithLifecycle()
            val dark = when(data.value.theme){ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.DARK->true;ThemeMode.LIGHT->false}
            val view=LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window,view).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
                if(Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
            }
            PureBrowserTheme(mode = data.value.theme) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){BrowserScreen(model)}
            }
        }
    }
}
