package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.core.os.BundleCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicroGLegacyGrantResponseTest {
    private val account = Account("selected@example.invalid", "app.revanced")
    private val provider = "app.revanced.android.gms"
    private val caller = "com.dev.bitchord"
    private val scope = "oauth2:example.scope"
    private val consentActivity = ComponentName(provider, "org.microg.gms.auth.AskPermissionActivity")

    private fun response(status: String) = Bundle().apply {
        putString("Error", status)
        putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
        putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
    }

    private fun consent() = Intent().setComponent(consentActivity).apply {
        putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name)
        putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type)
        putExtra(AccountManager.KEY_ANDROID_PACKAGE_NAME, caller)
        putExtra(AccountManager.KEY_AUTHTOKEN, scope)
    }

    private fun consentResponse(intent: Intent) = response("NeedPermission").apply {
        putParcelable("userRecoveryIntent", intent)
    }

    private fun read(response: Bundle) = readLegacyMicroGGrant(response, account, scope, caller, provider)

    @Test fun readsTokenWithoutOpeningProviderTokenDetails() {
        val success = response("OK").apply {
            putString(AccountManager.KEY_AUTHTOKEN, "synthetic-grant")
            putBundle("tokenDetails", Bundle().apply { putParcelable("TokenData", ProviderOnlyTokenData()) })
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(success)
            parcel.setDataPosition(0)
            val fromBinder = requireNotNull(parcel.readBundle(null))
            val grant = read(fromBinder)
            assertEquals(account.name, grant.getString(AccountManager.KEY_ACCOUNT_NAME))
            assertEquals(account.type, grant.getString(AccountManager.KEY_ACCOUNT_TYPE))
            assertEquals("synthetic-grant", grant.getString(AccountManager.KEY_AUTHTOKEN))
            assertFalse(grant.containsKey("tokenDetails"))
        } finally {
            parcel.recycle()
        }
    }

    @Test fun acceptsConsentForTheSelectedAccountAndCallingApp() {
        val result = read(consentResponse(consent()))
        val screen = requireNotNull(BundleCompat.getParcelable(result, AccountManager.KEY_INTENT, Intent::class.java))
        assertEquals(consentActivity, screen.component)
        assertEquals(caller, screen.getStringExtra(AccountManager.KEY_ANDROID_PACKAGE_NAME))
        assertEquals(scope, screen.getStringExtra(AccountManager.KEY_AUTHTOKEN))
        assertFalse(result.containsKey(AccountManager.KEY_AUTHTOKEN))
    }

    @Test fun rejectsMissingOrMismatchedAccount() {
        val success = response("OK").apply { putString(AccountManager.KEY_AUTHTOKEN, "synthetic-grant") }
        for (original in listOf(success, consentResponse(consent()))) {
            for (key in listOf(AccountManager.KEY_ACCOUNT_NAME, AccountManager.KEY_ACCOUNT_TYPE)) {
                assertThrows(IOException::class.java) { read(Bundle(original).apply { remove(key) }) }
                assertThrows(IOException::class.java) { read(Bundle(original).apply { putString(key, "other") }) }
            }
        }
    }

    @Test fun rejectsUntrustedOrMismatchedConsent() {
        val untrusted = listOf(
            consent().setComponent(ComponentName("invalid.example", consentActivity.className)),
            consent().setComponent(ComponentName(provider, "invalid.example.OtherActivity")),
            consent().apply { selector = Intent("invalid.example.SELECT") },
            consent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "other@example.invalid"),
            consent().putExtra(AccountManager.KEY_ACCOUNT_TYPE, "other"),
            consent().putExtra(AccountManager.KEY_ANDROID_PACKAGE_NAME, "invalid.example"),
            consent().putExtra(AccountManager.KEY_AUTHTOKEN, "oauth2:other.scope"),
        )
        for (screen in untrusted) {
            assertThrows(IOException::class.java) { read(consentResponse(screen)) }
        }
        assertThrows(IOException::class.java) { read(response("NeedPermission")) }
    }

    /** Stands in for microG's private Parcelable nested in its tokenDetails bundle. */
    class ProviderOnlyTokenData : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(0)

        companion object {
            @JvmField val CREATOR = object : Parcelable.Creator<ProviderOnlyTokenData> {
                override fun createFromParcel(source: Parcel): ProviderOnlyTokenData =
                    error("BitChord unpacked provider-specific token data")

                override fun newArray(size: Int) = arrayOfNulls<ProviderOnlyTokenData>(size)
            }
        }
    }
}
