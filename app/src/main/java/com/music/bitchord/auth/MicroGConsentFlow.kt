package com.music.bitchord.auth

import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

internal class MicroGRequestTimeoutException : IOException("microG did not respond to the account request")
internal class MicroGConsentRequiredException : IOException("microG still requires account authorization")

/** Bounds provider I/O separately from the time the user spends on the consent screen. */
internal suspend fun <T : Any> awaitMicroGResponse(request: suspend () -> T): T =
    withTimeoutOrNull(30_000L) { request() } ?: throw MicroGRequestTimeoutException()

internal sealed interface MicroGGrantAction<out T> {
    data object VerifyAccount : MicroGGrantAction<Nothing>
    data class OpenScreen<T>(val screen: T, val visitedScreens: List<String>) : MicroGGrantAction<T>
}

/** Android can ask for account access before microG asks for reauthentication and scope consent. */
private const val MAX_INTERACTIVE_SCREENS = 3

/**
 * Advance one step of an explicit sign-in. Some providers deliver the token via AccountManager
 * even when the Activity returns an empty or canceled result. A fresh lookup can produce a
 * token or the next required screen. The caller keeps component IDs across Activity recreation.
 */
internal suspend fun <T : Any> advanceMicroGGrant(
    hasReturnedGrant: Boolean,
    visitedScreens: List<String>,
    requestGrant: suspend () -> T?,
    screenKey: (T) -> String,
): MicroGGrantAction<T> {
    if (hasReturnedGrant) return MicroGGrantAction.VerifyAccount
    val screen = requestGrant() ?: return MicroGGrantAction.VerifyAccount
    val key = screenKey(screen)
    if (key in visitedScreens || visitedScreens.size >= MAX_INTERACTIVE_SCREENS) {
        throw MicroGConsentRequiredException()
    }
    return MicroGGrantAction.OpenScreen(screen, visitedScreens + key)
}
