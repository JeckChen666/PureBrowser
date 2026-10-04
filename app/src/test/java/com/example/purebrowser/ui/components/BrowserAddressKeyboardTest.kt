package com.example.purebrowser.ui.components

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressKeyboardTest {
    @Test fun uriIdentifiersAreNotAutoCorrectedByKeyboardConfiguration() {
        assertEquals(KeyboardType.Uri, BROWSER_ADDRESS_KEYBOARD_OPTIONS.keyboardType)
        assertEquals(ImeAction.Go, BROWSER_ADDRESS_KEYBOARD_OPTIONS.imeAction)
        assertEquals(false, BROWSER_ADDRESS_KEYBOARD_OPTIONS.autoCorrectEnabled)
    }
}
