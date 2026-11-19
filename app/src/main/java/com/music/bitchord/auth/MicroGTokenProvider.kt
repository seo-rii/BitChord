package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.music.bitchord.data.innertube.YouTubeTokenProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/youtube"

class MicroGTokenProvider(context: Context, private val account: Account) : YouTubeTokenProvider {
    private val appContext = context.applicationContext
    private val manager = AccountManager.get(appContext)
    private val initialGrant = AtomicReference<String?>(null)

    /** Separate the bounded token request from the time the user spends in the provider's UI. */
    internal suspend fun prepareAuthorization(): MicroGResolution? {
        val response = requestGrant()
        val intent = BundleCompat.getParcelable(response, AccountManager.KEY_INTENT, Intent::class.java)
        if (intent == null) {
            initialGrant.set(requireToken(response))
            return null
        }
        return withContext(Dispatchers.IO) { resolveScreen(intent) }
    }

    private fun resolveScreen(intent: Intent): MicroGResolution {
        val target = intent.component ?: throw MicroGResolutionUnavailableException()
        val owner = microGAuthenticator(appContext, account.type) ?: throw MicroGResolutionUnavailableException()
        if (intent.selector != null) throw MicroGResolutionUnavailableException()

        // A provider trampoline may contain just a PendingIntent. Preserve it unmodified.
        val supplied = IntentCompat.getParcelableExtra(intent, "account", Account::class.java)
        val name = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = intent.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
        if ((supplied != null && supplied != account) || (name != null && name != account.name) ||
            (type != null && type != account.type)
        ) throw MicroGResolutionUnavailableException()

        val info = appContext.packageManager.resolveActivity(intent, 0)?.activityInfo
            ?: throw MicroGResolutionUnavailableException()
        if (info.packageName != target.packageName || info.name != target.className ||
            !isExportedMicroGScreen(owner.packageName, info.packageName, info.name, info.exported) ||
            !info.enabled || !info.applicationInfo.enabled
        ) throw MicroGResolutionUnavailableException()
        val permission = info.permission
        if (permission != null && appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw MicroGResolutionUnavailableException()
        }

        // Use the real activity (including for an alias). Each fresh trampoline may carry a
        // different URI or one-shot PendingIntent even when it opens the same consent screen.
        val id = target.packageName + "/" + (info.targetActivity ?: target.className)
        return MicroGResolution(intent, id, mayReturnEarly(intent.flags, info.launchMode))
    }

    /** Some providers return the grant directly from the consent Activity. */
    internal fun acceptConsentResult(data: Intent?): Boolean {
        val token = data?.extras?.let(::selectedAccountToken) ?: return false
        initialGrant.set(token)
        return true
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
