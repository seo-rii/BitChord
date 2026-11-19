package com.music.bitchord.auth

import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal data class MicroGAuthenticator(
    val packageName: String,
    val accountType: String,
    val displayName: String,
    val versionName: String?,
) {
    val displayLabel: String
        get() = if (versionName.isNullOrBlank()) displayName else "$displayName $versionName"

    // These Vanced releases can return an existing token but cannot present a new grant.
    // mMicroG shares the package name and does implement the permission screen.
    val lacksKnownConsentFlow: Boolean
        get() = packageName == "com.mgoogle.android.gms" &&
            (versionName == "0.2.24.220220" || versionName == "0.2.27.230755")
}

/** Match Android's registered authenticator, since different microG forks use different account types. */
internal fun supportsMicroGAccount(
    packageName: String,
    accountType: String,
    hasMicroGLoginService: () -> Boolean,
): Boolean = when (packageName) {
    "com.mgoogle.android.gms" -> accountType == "com.mgoogle" // Vanced microG and mMicroG
    "app.revanced.android.gms" -> accountType == "app.revanced" // ReVanced and Morphe
    "org.microg.gms" -> accountType == "com.google"
    "com.google.android.gms" -> accountType == "com.google" && hasMicroGLoginService()
    else -> false
}

private fun registeredMicroGAuthenticators(context: Context): List<AuthenticatorDescription> =
    AccountManager.get(context).authenticatorTypes.filter { authenticator ->
        supportsMicroGAccount(authenticator.packageName, authenticator.type) {
            // Stock Play Services shares this package and account type with system microG.
            val service = ComponentName(authenticator.packageName, "org.microg.gms.auth.loginservice.GoogleLoginService")
            try {
                context.packageManager.getServiceInfo(service, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

internal fun microGAccountTypes(context: Context): List<String> =
    registeredMicroGAuthenticators(context).map { it.type }.distinct()

internal fun microGAuthenticator(context: Context, accountType: String): MicroGAuthenticator? {
    val authenticator = registeredMicroGAuthenticators(context).firstOrNull { it.type == accountType } ?: return null
    val pm = context.packageManager
    return try {
        val info = pm.getPackageInfo(authenticator.packageName, 0)
        val label = info.applicationInfo?.loadLabel(pm)?.toString().orEmpty().ifBlank { authenticator.packageName }
        MicroGAuthenticator(authenticator.packageName, authenticator.type, label, info.versionName)
    } catch (_: Exception) {
        // Reading the installed app's optional label/version may fail while it is being updated.
        MicroGAuthenticator(authenticator.packageName, authenticator.type, authenticator.packageName, null)
    }
}
