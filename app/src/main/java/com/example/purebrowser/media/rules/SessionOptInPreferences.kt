package com.example.purebrowser.media.rules

import android.content.Context

/**
 * Thin storage adapter for [SessionOptIn] (T86), mirroring the [com.example.purebrowser.download.DownloadPreferences]
 * idiom: one private SharedPreferences file, one key per registrable domain (`site_<domain>`),
 * values limited to the encoded tri-state. No cookie, header or credential value is ever stored —
 * only the user's yes/no/unknown decision. All decisions stay in the pure core.
 */
class SessionOptInPreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("rule_session_opt_in", Context.MODE_PRIVATE)

    /** A store bound to this device's persisted decisions; writes go through synchronously. */
    fun sessionOptIn(): SessionOptIn = SessionOptIn(
        read = { domain -> SessionOptIn.decode(preferences.getString(key(domain), null)) },
        write = { domain, state ->
            check(preferences.edit().putString(key(domain), SessionOptIn.encode(state)).commit())
        },
    )

    private fun key(domain: String): String = "site_$domain"
}
