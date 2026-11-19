package com.music.bitchord.auth

import android.content.Intent
import android.content.pm.ActivityInfo
import java.io.IOException

internal class MicroGResolutionUnavailableException : IOException("The microG authorization screen is unavailable")

internal data class MicroGResolution(val intent: Intent, val id: String, val mayReturnEarly: Boolean)

/** A provider may delegate the Android account permission prompt to the platform. */
internal fun isExportedMicroGScreen(provider: String, screenPackage: String, screenClass: String, exported: Boolean): Boolean =
    exported && (screenPackage == provider ||
        (screenPackage == "android" && screenClass == "android.accounts.GrantCredentialsPermissionActivity"))

/** Android may return a canceled result as soon as a separate task opens. */
internal fun mayReturnEarly(flags: Int, launchMode: Int): Boolean =
    flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 || when (launchMode) {
        ActivityInfo.LAUNCH_SINGLE_TASK, ActivityInfo.LAUNCH_SINGLE_INSTANCE,
        ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK -> true
        else -> false
    }
