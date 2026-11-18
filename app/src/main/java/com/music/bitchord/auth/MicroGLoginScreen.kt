package com.music.bitchord.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.innertube.YouTubeTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MicroGLoginScreen(
    onDismiss: () -> Unit,
    onAuthorized: suspend (Account, YouTubeTokenProvider) -> Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    val scope = rememberCoroutineScope()
    val authorize by rememberUpdatedState(onAuthorized)
    val dismiss by rememberUpdatedState(onDismiss)
    var choosing by rememberSaveable { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        choosing = false
        if (result.resultCode != Activity.RESULT_OK) {
            dismiss()
            return@rememberLauncherForActivityResult
        }
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
        if (activity == null || name.isNullOrBlank() || type.isNullOrBlank()) {
            error = R.string.microg_authorization_failed
            return@rememberLauncherForActivityResult
        }
        authorizing = true
        scope.launch {
            try {
                val account = Account(name, type)
                val provider = MicroGTokenProvider(context, account)
                provider.authorize(activity)
                if (!authorize(account, provider)) error = R.string.microg_youtube_failed
            } catch (_: OperationCanceledException) {
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = R.string.microg_authorization_failed
            } finally {
                authorizing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.microg_sign_in)) },
        text = {
            Text(stringResource(error ?: if (authorizing) R.string.microg_authorizing else R.string.microg_account_prompt))
        },
        confirmButton = {
            TextButton(
                enabled = !authorizing && !choosing,
                onClick = {
                    error = null
                    try {
                        val types = microGAccountTypes(context)
                        if (types.isEmpty()) {
                            error = R.string.microg_unavailable
                        } else {
                            choosing = true
                            chooser.launch(AccountManager.newChooseAccountIntent(
                                null, null, types.toTypedArray(), null, null, null, null,
                            ))
                        }
                    } catch (_: Exception) {
                        choosing = false
                        error = R.string.microg_authorization_failed
                    }
                },
            ) { Text(stringResource(R.string.microg_choose_account)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
