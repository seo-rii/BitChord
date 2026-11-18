package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.music.bitchord.data.innertube.YouTubeTokenProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Only the explicit sign-in flow can bring up consent or reauthentication UI. */
    internal suspend fun authorize(activity: Activity) {
        initialGrant.set(requestToken(activity = activity))
    }

    override suspend fun getToken(rejectedToken: String?): String {
        // Validate the exact grant the user approved on the next account-menu request.
        val first = initialGrant.getAndSet(null)
        if (first != null && rejectedToken == null) return first
        return requestToken(rejectedToken = rejectedToken)
    }

    private suspend fun requestToken(activity: Activity? = null, rejectedToken: String? = null): String =
        withContext(Dispatchers.IO) {
            if (account.type !in microGAccountTypes(appContext)) throw IOException("microG account provider unavailable")
            if (account !in manager.getAccountsByType(account.type)) throw IOException("microG account is no longer available")
            // Invalidate only the token rejected by the server. A newer grant may already be cached.
            if (rejectedToken != null) manager.invalidateAuthToken(account.type, rejectedToken)
            val result = withTimeoutOrNull(if (activity == null) 30_000L else 300_000L) {
                suspendCancellableCoroutine<Bundle> { continuation ->
                    val callback = AccountManagerCallback<Bundle> { future ->
                        continuation.resumeWith(runCatching { future.result })
                    }
                    val future = if (activity == null) {
                        manager.getAuthToken(account, TOKEN_TYPE, Bundle(), false, callback, null)
                    } else {
                        manager.getAuthToken(account, TOKEN_TYPE, Bundle(), activity, callback, null)
                    }
                    continuation.invokeOnCancellation { future.cancel(true) }
                }
            } ?: throw IOException("microG authorization timed out")

            if (result.getString(AccountManager.KEY_ACCOUNT_TYPE) != account.type ||
                result.getString(AccountManager.KEY_ACCOUNT_NAME) != account.name
            ) throw IOException("microG did not authorize the selected account")
            result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                ?: throw IOException("microG did not return a YouTube token")
        }
}
