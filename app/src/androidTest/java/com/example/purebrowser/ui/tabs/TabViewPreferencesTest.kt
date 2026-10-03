package com.example.purebrowser.ui.tabs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TabViewPreferencesTest {
    @Test fun explicitChoiceSurvivesRecreatingPreferencesAndOnlyWritesLayout() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "t44-prefs-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(file, mode)
        }
        try {
            val preferences = TabViewPreferences(context)
            assertEquals(TabViewMode.GRID, preferences.read())
            preferences.write(TabViewMode.LIST)
            assertEquals(TabViewMode.LIST, TabViewPreferences(context).read())
            val storage = app.getSharedPreferences(file, 0)
            assertEquals(mapOf(TabViewPreferences.KEY to "LIST"), storage.all)
            // An empty synchronous commit waits for earlier apply() writes; no process/device reset.
            assertTrue(storage.edit().commit())
            val xml = java.io.File(app.applicationInfo.dataDir, "shared_prefs/$file.xml").readText()
            assertTrue(xml.contains("<string name=\"view_mode\">LIST</string>"))
            preferences.write(TabViewMode.GRID)
            assertEquals(TabViewMode.GRID, TabViewPreferences(context).read())
        } finally { app.deleteSharedPreferences(file) }
    }

    @Test fun corruptOrUnknownPreferenceFallsBackWithoutTouchingOtherKeys() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "t44-corrupt-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(file, mode)
        }
        try {
            val storage = app.getSharedPreferences(file, 0)
            storage.edit().putString("unrelated", "keep").putString(TabViewPreferences.KEY, "FUTURE").commit()
            assertEquals(TabViewMode.GRID, TabViewPreferences(context).read())
            storage.edit().putInt(TabViewPreferences.KEY, 42).commit()
            assertEquals(TabViewMode.GRID, TabViewPreferences(context).read())
            TabViewPreferences(context).write(TabViewMode.LIST)
            assertEquals("keep", storage.getString("unrelated", null))
        } finally { app.deleteSharedPreferences(file) }
    }

    @Test fun unavailableStorageDoesNotBlockLocalViewChoice() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("Storage unavailable")
        }
        val preferences = TabViewPreferences(context)
        assertEquals(TabViewMode.GRID, preferences.read())
        preferences.write(TabViewMode.LIST) // No exception propagates into the tool UI.
    }
}
