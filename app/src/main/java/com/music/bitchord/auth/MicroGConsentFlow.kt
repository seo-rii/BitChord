package com.music.bitchord.auth

import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

internal class MicroGRequestTimeoutException : IOException("microG did not respond to the account request")
internal class MicroGConsentRequiredException : IOException("microG still requires account authorization")

/** Bounds provider I/O separately from the time the user spends on the consent screen. */
internal suspend fun <T : Any> awaitMicroGResponse(request: suspend () -> T): T =
    withTimeoutOrNull(30_000L) { request() } ?: throw MicroGRequestTimeoutException()

/**
 * Some microG versions return a grant in the consent activity result. Others deliver it
 * only through AccountManager, even when the consent activity reports RESULT_CANCELED.
 * A silent lookup in that case may recover the grant. If it still requires consent, the
 * lookup must fail so that the user gets control back without reopening the prompt.
 */
internal suspend fun verifyMicroGConsent(
    acceptResult: () -> Boolean,
    requestAfterConsent: suspend () -> Unit,
    verifyAccount: suspend () -> Boolean,
): Boolean {
    if (!acceptResult()) requestAfterConsent()
    return verifyAccount()
}
