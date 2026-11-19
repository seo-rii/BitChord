package com.music.bitchord.auth

import android.accounts.Account
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyMicroGDiscoveryTest {
    @Test fun discoversInstalledTeamVancedAuthenticatorWithoutAnAccount() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLegacyMicroG") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue("System chooser must include the legacy account type", "com.mgoogle" in microGAccountTypes(context))
        val provider = requireNotNull(microGAuthenticator(context, "com.mgoogle"))
        assertEquals("com.mgoogle.android.gms", provider.packageName)
        assertEquals("com.mgoogle", provider.accountType)
        assertEquals("0.2.24.220220", provider.versionName)
        assertTrue(provider.lacksKnownConsentFlow)
    }

    @Test fun receivesLegacyTokenServiceErrorForMissingAccount() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLegacyMicroG") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = requireNotNull(microGAuthenticator(context, "app.revanced"))
        assertEquals("app.revanced.android.gms", owner.packageName)
        assertEquals("0.3.1.241017", owner.versionName)

        // This release returns NetworkError for an unknown account before contacting Google.
        val account = Account("missing-account@example.invalid", owner.accountType)
        val provider = MicroGTokenProvider(context, account)
        val failure = assertThrows(MicroGLegacyProviderException::class.java) {
            runBlocking { provider.prepareAuthorization() }
        }
        assertEquals("NetworkError", failure.errorCode)
    }
}
