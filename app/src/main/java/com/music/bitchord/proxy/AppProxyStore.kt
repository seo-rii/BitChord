package com.music.bitchord.proxy

import android.content.Context
import com.music.bitchord.data.AppProxyKind
import com.music.bitchord.data.AppProxyRoute

/** Stores non-secret endpoints only; no WireGuard private keys or passwords. */
object AppProxyStore {
    private const val PREFS = "bitchord_app_proxy_v1"
    private const val KEY_WG_DEFAULT_MIGRATED = "wireguard_default_v2_applied"

    @Synchronized
    fun read(context: Context): AppProxyRoute {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val previous = prefs.getString("mode", null)
            val migrated = prefs.getBoolean(KEY_WG_DEFAULT_MIGRATED, false)
            // Early builds imported profiles without updating the default selection.
            val kind = if (previous == null || (!migrated && previous == AppProxyKind.DIRECT.name)) {
                if (WireGuardProfileStore.hasProfile(context)) AppProxyKind.WIREGUARD_LOCAL
                else AppProxyKind.DIRECT
            } else {
                AppProxyKind.valueOf(previous)
            }
            if (!migrated || previous == null) {
                val changes = prefs.edit().putBoolean(KEY_WG_DEFAULT_MIGRATED, true)
                if (previous != kind.name) {
                    changes.putString("mode", kind.name).putString("host", "").putInt("port", 0)
                }
                changes.apply()
            }
            if (kind == AppProxyKind.WIREGUARD_LOCAL) {
                // Native binds a private loopback port on each process start.
                AppProxyRoute(kind, "127.0.0.1", 9)
            } else {
                AppProxyRoute(
                    kind,
                    prefs.getString("host", "").orEmpty(),
                    prefs.getInt("port", 0),
                ).validated()
            }
        } catch (_: IllegalArgumentException) {
            AppProxyRoute.FAIL_CLOSED
        } catch (_: ClassCastException) {
            AppProxyRoute.FAIL_CLOSED
        }
    }

    @Synchronized
    fun save(context: Context, route: AppProxyRoute) {
        val checked = route.validated()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WG_DEFAULT_MIGRATED, true)
            .putString("mode", checked.kind.name)
            // Never persist the randomly bound WireGuard port.
            .putString("host", if (checked.kind == AppProxyKind.WIREGUARD_LOCAL) "" else checked.host)
            .putInt("port", if (checked.kind == AppProxyKind.WIREGUARD_LOCAL) 0 else checked.port)
            .commit().also { saved ->
                // When relaunching immediately, async apply() could lose the
                // selected mode before it reaches disk.
                check(saved) { "Could not persist proxy mode before restart" }
            }
    }
}
