package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.os.BundleCompat
import com.music.bitchord.data.innertube.YouTubeTokenProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/youtube"

/** Restrict the system chooser and later token requests to installed microG authenticators. */
internal fun microGAccountTypes(context: Context): List<String> =
    AccountManager.get(context).authenticatorTypes.filter { authenticator ->
        when (authenticator.packageName) {
            "app.revanced.android.gms" -> authenticator.type == "app.revanced"
            "org.microg.gms" -> authenticator.type == "com.google"
            "com.google.android.gms" -> authenticator.type == "com.google" && try {
                // System microG and stock Play Services use the same package and account type.
                val service = ComponentName(authenticator.packageName, "org.microg.gms.auth.loginservice.GoogleLoginService")
                context.packageManager.getServiceInfo(service, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            else -> false
        }
    }.map { it.type }.distinct()

class MicroGTokenProvider(context: Context, private val account: Account) : YouTubeTokenProvider {
    private val appContext = context.applicationContext
    private val manager = AccountManager.get(appContext)
    private val initialGrant = AtomicReference<String?>(null)

    /** Separate the bounded token request from the time the user spends in the provider's UI. */
    internal suspend fun prepareAuthorization(): Intent? {
        val response = requestGrant()
        val consent = BundleCompat.getParcelable(response, AccountManager.KEY_INTENT, Intent::class.java)
        if (consent == null) initialGrant.set(requireToken(response))
        return consent
    }

    /** Some providers return the grant directly from the consent Activity. */
    internal fun acceptConsentResult(data: Intent?): Boolean {
        val token = data?.extras?.let(::selectedAccountToken) ?: return false
        initialGrant.set(token)
        return true
    }

    /** Older authenticators report consent through AccountManager only. Probe the grant once. */
    internal suspend fun finishAuthorization() {
        initialGrant.set(requireToken(requestGrant()))
    }

    override suspend fun getToken(rejectedToken: String?): String {
        // Validate the exact grant the user approved on the next account-menu request.
        val first = initialGrant.getAndSet(null)
        if (first != null && rejectedToken == null) return first
        return requireToken(requestGrant(rejectedToken))
    }

    private fun selectedAccountToken(result: Bundle): String? {
        if (result.getString(AccountManager.KEY_ACCOUNT_TYPE) != account.type ||
            result.getString(AccountManager.KEY_ACCOUNT_NAME) != account.name
        ) return null
        return result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
    }

    private fun requireToken(result: Bundle): String {
        if (result.containsKey(AccountManager.KEY_INTENT)) throw MicroGConsentRequiredException()
        return selectedAccountToken(result) ?: throw IOException("microG did not return a token for the selected account")
    }

    private suspend fun requestGrant(rejectedToken: String? = null): Bundle =
        awaitMicroGResponse {
            withContext(Dispatchers.IO) {
                if (account.type !in microGAccountTypes(appContext)) throw IOException("microG account provider unavailable")
                if (rejectedToken != null) manager.invalidateAuthToken(account.type, rejectedToken)
                suspendCancellableCoroutine<Bundle> { continuation ->
                    val callback = AccountManagerCallback<Bundle> { future ->
                        continuation.resumeWith(runCatching { future.result })
                    }
                    val future = manager.getAuthToken(account, TOKEN_TYPE, Bundle(), false, callback, null)
                    continuation.invokeOnCancellation { future.cancel(true) }
                }
            }
        }
}
