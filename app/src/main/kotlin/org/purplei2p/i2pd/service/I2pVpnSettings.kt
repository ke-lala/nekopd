package org.purplei2p.i2pd.service

import android.content.Context
import androidx.core.content.edit

/** Small shared-preference store used by the VPN UI and its foreground service. */
object I2pVpnSettings {
    private const val PREFS = "i2pd_vpn"
    private const val KEY_PACKAGES = "selected_packages"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ERROR = "last_error"

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet().orEmpty()

    fun setSelectedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit { putStringSet(KEY_PACKAGES, packages.toSet()) }
    }

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    internal fun markActive(context: Context) {
        prefs(context).edit {
            putBoolean(KEY_ACTIVE, true)
            remove(KEY_ERROR)
        }
    }

    internal fun markStopped(context: Context, error: String? = null) {
        prefs(context).edit {
            putBoolean(KEY_ACTIVE, false)
            if (error == null) remove(KEY_ERROR) else putString(KEY_ERROR, error)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
