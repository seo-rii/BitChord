package com.music.bitchord.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
}
