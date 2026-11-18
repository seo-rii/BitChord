package com.music.bitchord.auth

import android.accounts.Account
import android.content.Context
import com.music.bitchord.data.innertube.Innertube

/** Restore the same identity for the UI and playback after a switch or process restart. */
internal fun applyYouTubeSession(context: Context, session: GoogleAccountSession?) {
    val name = session?.microGAccountName
    val type = session?.microGAccountType
    if (session != null && !name.isNullOrBlank() && !type.isNullOrBlank()) {
        Innertube.useOAuth(session.accountId, MicroGTokenProvider(context, Account(name, type)))
    } else {
        Innertube.cookie = session?.cookie?.takeIf { it.isNotBlank() }
    }
}
