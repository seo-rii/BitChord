package com.music.bitchord.proxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.AppProxyKind
import com.music.bitchord.data.AppProxyRoute

/** Minimal settings: Off or built-in WireGuard only. */
class AppProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val importer = rememberWireGuardImportUiState()
            val profileInstalled = WireGuardProfileStore.hasProfile(this@AppProxySettingsActivity)
            val savedRoute = remember {
                AppProxyStore.read(this@AppProxySettingsActivity)
            }
            val modes = remember {
                buildList {
                    add(AppProxyKind.DIRECT)
                    add(AppProxyKind.WIREGUARD_LOCAL)
                    if (savedRoute != AppProxyRoute.FAIL_CLOSED && savedRoute.kind !in this) {
                        add(savedRoute.kind)
                    }
                }
            }
            var selectedMode by remember { mutableStateOf(savedRoute.kind) }
            var restartForMode by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }

            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.fourpril_proxy_settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )

                        for (mode in modes) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedMode = mode; error = null },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedMode == mode,
                                    onClick = { selectedMode = mode; error = null },
                                )
                                Text(
                                    when (mode) {
                                        AppProxyKind.DIRECT -> stringResource(R.string.fourpril_proxy_off)
                                        AppProxyKind.WIREGUARD_LOCAL -> stringResource(R.string.fourpril_proxy_wireguard)
                                        else -> "${mode.name} (${savedRoute.host}:${savedRoute.port})"
                                    },
                                )
                            }
                        }

                        Text(
                            stringResource(
                                if (profileInstalled) R.string.fourpril_conf_saved
                                else R.string.fourpril_conf_missing,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        OutlinedButton(
                            onClick = importer.openConf,
                            enabled = !importer.importing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.fourpril_pick_conf_button))
                        }

                        importer.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedMode in modes && !importer.importing,
                            onClick = {
                                if (selectedMode == AppProxyKind.WIREGUARD_LOCAL &&
                                    !WireGuardProfileStore.hasProfile(this@AppProxySettingsActivity)
                                ) {
                                    error = getString(R.string.fourpril_conf_missing)
                                } else {
                                    try {
                                        AppProxyStore.save(
                                            this@AppProxySettingsActivity,
                                            when (selectedMode) {
                                                AppProxyKind.DIRECT -> AppProxyRoute.DIRECT
                                                AppProxyKind.WIREGUARD_LOCAL -> AppProxyRoute(
                                                    AppProxyKind.WIREGUARD_LOCAL, "127.0.0.1", 9,
                                                )
                                                else -> savedRoute
                                            },
                                        )
                                        restartForMode = true
                                    } catch (_: Exception) {
                                        error = getString(R.string.fourpril_conf_import_error)
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.fourpril_proxy_apply_button))
                        }
                    }
                }

                // Importing a .conf is successful only after native validation,
                // encrypted storage and persisted WireGuard selection. It then
                // shows the exact same mandatory restart dialog as onboarding.
                WireGuardImportRestartDialog(importer)

                if (restartForMode && !importer.restartRequired) {
                    AlertDialog(
                        onDismissRequest = {},
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        ),
                        title = { Text(stringResource(R.string.fourpril_proxy_restart_title)) },
                        text = { Text(stringResource(R.string.fourpril_proxy_restart_message)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    AppProxyRelaunchActivity.restartFrom(this@AppProxySettingsActivity)
                                },
                            ) {
                                Text(stringResource(R.string.fourpril_proxy_restart_confirm))
                            }
                        },
                    )
                }
            }
        }
    }
}
