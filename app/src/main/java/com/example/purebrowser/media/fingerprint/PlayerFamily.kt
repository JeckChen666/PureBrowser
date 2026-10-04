package com.example.purebrowser.media.fingerprint

import java.util.Locale

/** Configuration shape of an embedded web player; picks the extraction strategy for harvested globals. */
enum class PlayerFamily { FLASHVARS, KVS, HTML5PLAYER, XPLAYER, STREAM_DATA, UNKNOWN }

object FamilyDetector {
    /** Names are compared case-insensitively on their final dotted segment; earlier families win ties. */
    fun detect(globalNames: Set<String>): PlayerFamily {
        val names = globalNames.map { it.substringAfterLast('.').lowercase(Locale.ROOT) }
        return when {
            names.any { it.startsWith("flashvars") } -> PlayerFamily.FLASHVARS
            "kvsplayer" in names -> PlayerFamily.KVS
            "html5player" in names -> PlayerFamily.HTML5PLAYER
            "xplayersettings" in names -> PlayerFamily.XPLAYER
            "stream_data" in names -> PlayerFamily.STREAM_DATA
            else -> PlayerFamily.UNKNOWN
        }
    }
}
