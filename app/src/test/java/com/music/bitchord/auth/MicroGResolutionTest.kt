package com.music.bitchord.auth

import android.content.Intent
import android.content.pm.ActivityInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroGResolutionTest {
    private val provider = "app.revanced.android.gms"
    private val permissionScreen = "org.microg.gms.auth.AskPermissionActivity"

    @Test fun acceptsTheSelectedProvidersExportedScreen() {
        assertTrue(isExportedMicroGScreen(provider, provider, permissionScreen, true))
        assertTrue(isExportedMicroGScreen(provider, provider, "org.microg.gms.ui.UnpackingRedirectActivity", true))
    }

    @Test fun rejectsNonExportedAndUnrelatedScreens() {
        assertFalse(isExportedMicroGScreen(provider, provider, permissionScreen, false))
        assertFalse(isExportedMicroGScreen(provider, "some.other.app", permissionScreen, true))
    }

    @Test fun acceptsOnlyThePlatformAccountPermissionScreenFromAndroid() {
        val grantScreen = "android.accounts.GrantCredentialsPermissionActivity"
        assertTrue(isExportedMicroGScreen(provider, "android", grantScreen, true))
        assertFalse(isExportedMicroGScreen(provider, "some.other.app", grantScreen, true))
        assertFalse(isExportedMicroGScreen(provider, "android", permissionScreen, true))
    }

    @Test fun canceledResultMayPrecedeCompletionForSeparateTask() {
        assertTrue(mayReturnEarly(0, ActivityInfo.LAUNCH_SINGLE_TASK))
        assertTrue(mayReturnEarly(0, ActivityInfo.LAUNCH_SINGLE_INSTANCE))
        assertTrue(mayReturnEarly(0, ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK))
        assertTrue(mayReturnEarly(Intent.FLAG_ACTIVITY_NEW_TASK, ActivityInfo.LAUNCH_MULTIPLE))
        assertFalse(mayReturnEarly(0, ActivityInfo.LAUNCH_MULTIPLE))
        assertFalse(mayReturnEarly(0, ActivityInfo.LAUNCH_SINGLE_TOP))
    }
}
