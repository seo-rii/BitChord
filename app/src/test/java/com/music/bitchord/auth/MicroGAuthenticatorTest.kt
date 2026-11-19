package com.music.bitchord.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroGAuthenticatorTest {
    private fun supports(packageName: String, type: String): Boolean =
        supportsMicroGAccount(packageName, type) { error("Only the system package needs a service check") }

    @Test fun acceptsDeclaredAccountTypesForLegacyAndCurrentPackages() {
        assertTrue(supports("com.mgoogle.android.gms", "com.mgoogle"))
        assertTrue(supports("app.revanced.android.gms", "app.revanced"))
        assertTrue(supports("org.microg.gms", "com.google"))
    }

    @Test fun ignoresUnrecognizedTypesAndPackageOwners() {
        assertFalse(supports("com.mgoogle.android.gms", "com.google"))
        assertFalse(supports("app.revanced.android.gms", "com.mgoogle"))
        assertFalse(supports("unrelated.provider", "app.revanced"))
        assertFalse(supports("com.google.android.gms", "com.google.work"))
    }

    @Test fun systemAccountRequiresTheMicroGService() {
        assertTrue(supportsMicroGAccount("com.google.android.gms", "com.google") { true })
        assertFalse(supportsMicroGAccount("com.google.android.gms", "com.google") { false })
    }

    @Test fun installedProviderVersionIsOptionalInTheDiagnosticLabel() {
        val provider = MicroGAuthenticator("com.mgoogle.android.gms", "com.mgoogle", "Vanced microG", "0.2.24")
        assertEquals("Vanced microG 0.2.24", provider.displayLabel)
        assertEquals("Vanced microG", provider.copy(versionName = null).displayLabel)
    }

    @Test fun legacyConsentWarningIsLimitedToVerifiedReleases() {
        val vanced = MicroGAuthenticator("com.mgoogle.android.gms", "com.mgoogle", "Vanced microG", "0.2.24.220220")
        assertTrue(vanced.lacksKnownConsentFlow)
        assertTrue(vanced.copy(versionName = "0.2.27.230755").lacksKnownConsentFlow)
        assertFalse(vanced.copy(versionName = "mMicroG 0.2.27.231613-vtm-noen").lacksKnownConsentFlow)
        assertFalse(vanced.copy(packageName = "org.microg.gms").lacksKnownConsentFlow)
        assertFalse(vanced.copy(versionName = null).lacksKnownConsentFlow)
    }
}
