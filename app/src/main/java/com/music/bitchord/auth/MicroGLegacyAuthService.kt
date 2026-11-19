package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.os.BundleCompat
import com.google.android.auth.IAuthManagerService
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

internal val MicroGAuthenticator.useLegacyTokenService: Boolean
    get() = packageName == "app.revanced.android.gms" && accountType == "app.revanced" &&
        versionName == "0.3.1.241017"

internal class MicroGLegacyProviderException(val errorCode: String) :
    IOException("microG could not authorize the account ($errorCode)")

private const val AUTH_DESCRIPTOR = "com.google.android.auth.IAuthManagerService"
private const val TOKEN_SERVICE = "com.google.android.gms.auth.GetToken"
private const val CONSENT_ACTIVITY = "org.microg.gms.auth.AskPermissionActivity"

// Unbinding cannot interrupt a synchronous Binder transaction. A stuck provider must
// neither block coroutine cancellation nor accumulate a new worker on each retry.
private val tokenWorkers = ThreadPoolExecutor(
    0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
    { task -> Thread(task, "microG token request").apply { isDaemon = true } },
)

/** Called within the grant request's I/O context and timeout. Only a missing service permits fallback. */
internal suspend fun requestLegacyMicroGGrant(
    context: Context,
    owner: MicroGAuthenticator,
    account: Account,
    tokenType: String,
): Bundle? {
    if (!owner.useLegacyTokenService || owner.accountType != account.type) {
        throw IOException("Unexpected microG account provider")
    }
    val app = context.applicationContext
    val component = ComponentName(owner.packageName, TOKEN_SERVICE)
    val info = try {
        app.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    if (info.packageName != component.packageName || info.name != component.className ||
        !info.exported || !info.enabled || !info.applicationInfo.enabled
    ) throw IOException("microG token service is unavailable")

    val connected = CompletableDeferred<IBinder>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (name == component) connected.complete(binder)
            else connected.completeExceptionally(IOException("Unexpected microG token service"))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            connected.completeExceptionally(IOException("microG token service disconnected"))
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }
    try {
        if (!app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)) {
            throw IOException("Could not bind microG token service")
        }
        return requestToken(connected.await(), account, tokenType, app.packageName, owner.packageName)
    } finally {
        try {
            app.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // bindService threw before Android registered the connection.
        }
    }
}

private suspend fun requestToken(
    binder: IBinder,
    account: Account,
    tokenType: String,
    callingPackage: String,
    providerPackage: String,
): Bundle = suspendCancellableCoroutine { continuation ->
    try {
        tokenWorkers.execute {
            if (!continuation.isActive) return@execute
            val result = try {
                if (binder.interfaceDescriptor != AUTH_DESCRIPTOR) throw IOException("Unexpected microG service interface")
                val extras = Bundle().apply { putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, callingPackage) }
                val response = IAuthManagerService.Stub.asInterface(binder).getTokenWithAccount(account, tokenType, extras)
                    ?: throw IOException("Empty microG response")
                Result.success(readLegacyMicroGGrant(response, account, tokenType, callingPackage, providerPackage))
            } catch (e: IOException) {
                Result.failure(e)
            } catch (_: Exception) {
                // Remote exceptions and malformed parcels may contain account or token data.
                Result.failure(IOException("Could not read microG token service response"))
            }
            continuation.resumeWith(result)
        }
    } catch (_: RejectedExecutionException) {
        continuation.resumeWith(Result.failure(IOException("microG token service is busy")))
    }
}

internal fun readLegacyMicroGGrant(
    response: Bundle,
    account: Account,
    tokenType: String,
    callingPackage: String,
    providerPackage: String,
): Bundle {
    if (response.getString(AccountManager.KEY_ACCOUNT_NAME) != account.name ||
        response.getString(AccountManager.KEY_ACCOUNT_TYPE) != account.type
    ) throw IOException("microG returned a response for a different account")
    val result = Bundle()
    when (response.getString("Error")) {
        "OK" -> {
            val token = response.getString(AccountManager.KEY_AUTHTOKEN)
            if (token.isNullOrBlank()) throw IOException("microG returned an empty grant")
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            result.putString(AccountManager.KEY_AUTHTOKEN, token)
        }
        "NeedPermission" -> {
            val consent = BundleCompat.getParcelable(response, "userRecoveryIntent", Intent::class.java)
                ?: throw IOException("microG did not supply its consent screen")
            if (consent.component != ComponentName(providerPackage, CONSENT_ACTIVITY) || consent.selector != null ||
                consent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) != account.name ||
                consent.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) != account.type ||
                consent.getStringExtra(AccountManager.KEY_ANDROID_PACKAGE_NAME) != callingPackage ||
                consent.getStringExtra(AccountManager.KEY_AUTHTOKEN) != tokenType
            ) throw IOException("Unexpected microG consent response")
            result.putParcelable(AccountManager.KEY_INTENT, consent)
        }
        "NetworkError" -> throw MicroGLegacyProviderException("NetworkError")
        else -> throw MicroGLegacyProviderException("ProviderError")
    }
    return result
}
