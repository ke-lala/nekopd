package org.purplei2p.i2pd.service

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import org.purplei2p.i2pd.daemon.api.StartParams
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Prepares the private data directory (filesDir/i2pd) and builds i2pd start parameters.
 *
 * Bundled assets: on the first start of an installation all of them are copied; an app update
 * only replaces the certificates and the web interface of torrents tunnels (torrents/web, from
 * i2pd contrib). Configuration and the address book belong to the user from then on; files deleted
 * or switched off by the user do not come back; downloads in torrents/ are not touched.
 */
class DataDirPreparer(private val context: Context) {

    val dataDir: File get() = File(context.filesDir, DATA_DIR_NAME)

    fun prepare(): StartParams {
        val root = dataDir
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create $root")

        val stamp = File(root, ASSETS_STAMP)
        val version = appVersionCode().toString()
        when (stamp.takeIf { it.exists() }?.readText()?.trim()) {
            version -> Unit
            null -> {
                Log.i(TAG, "Copying bundled assets to $root")
                for (asset in ASSETS) copyAsset(context.assets, asset, root)
                stamp.writeText(version)
            }
            else -> {
                Log.i(TAG, "App updated, replacing certificates and torrents web interface in $root")
                replaceCertificates()
                replaceAsset(TORRENTS_WEB)
                stamp.writeText(version)
            }
        }

        // Missing files of the torrents web interface are put back on every start: installations made
        // before it was bundled get it without a version change, and it is not user data
        copyAsset(context.assets, TORRENTS_WEB, root)

        // Seed Java I2P's release address list once on upgrades too, without replacing user entries.
        seedJavaAddressBookDefaults(root)
        // i2pd rewrites addressbook/addresses.csv when it stops. Apply app-owned imports only now,
        // before the native router starts, so an in-memory router cannot overwrite a new import.
        mergeImportedAddressBook(root)

        return StartParams(
            dataDir = root.absolutePath,
            args = listOf(
                "--datadir=${root.absolutePath}",
                "--conf=${File(root, "i2pd.conf").absolutePath}",
                "--tunconf=${File(root, "tunnels.conf").absolutePath}",
                "--tunnelsdir=${File(root, "tunnels.d").absolutePath}",
                "--certsdir=${File(root, CERTIFICATES).absolutePath}",
            ),
            language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT),
        )
    }

    /**
     * Copies an asset file or directory; existing files are left untouched, and so are files
     * the user switched off (tunnel files renamed to *.disabled).
     */
    private fun copyAsset(assets: AssetManager, path: String, root: File) {
        val children = assets.list(path).orEmpty()
        val target = File(root, path)
        if (children.isEmpty()) {
            if (target.exists() || File(target.path + DISABLED_SUFFIX).exists()) return
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            assets.open(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) throw IOException("Cannot create $target")
        } else {
            target.mkdirs()
            for (child in children) copyAsset(assets, "$path/$child", root)
        }
    }

    /** One-time additive migration, preserving existing entries and never replacing a conflict. */
    private fun seedJavaAddressBookDefaults(root: File) {
        val addressBook = File(root, ADDRESSBOOK_DIR)
        val marker = File(addressBook, JAVA_DEFAULTS_MARKER)
        if (marker.isFile) return
        runCatching {
            val defaults = context.assets.open(JAVA_DEFAULTS_ASSET).bufferedReader().use { it.readLines() }
            val count = mergeCsvMappings(File(addressBook, ADDRESSBOOK_INDEX), defaults)
            marker.writeText(JAVA_DEFAULTS_VERSION)
            Log.i(TAG, "Merged $count Java I2P default address book entries")
        }.onFailure { Log.w(TAG, "Could not seed Java I2P address book defaults", it) }
    }

    /** Merge app-owned imported mappings before i2pd opens its own persistent index. */
    private fun mergeImportedAddressBook(root: File) {
        val imports = AddressBookImportPaths.mappings(context)
        if (!imports.isFile) return
        runCatching {
            val count = mergeCsvMappings(File(File(root, ADDRESSBOOK_DIR), ADDRESSBOOK_INDEX), imports.readLines())
            if (count > 0) Log.i(TAG, "Applied $count imported address book entries")
        }.onFailure { Log.w(TAG, "Could not apply imported address book entries", it) }
    }

    /** Append valid host,b32 CSV mappings not already present; the existing address always wins. */
    private fun mergeCsvMappings(target: File, sourceLines: List<String>): Int {
        val existingLines = if (target.isFile) target.readLines() else emptyList()
        val knownHosts = existingLines.mapNotNull(::csvHost).toMutableSet()
        val additions = sourceLines.mapNotNull { raw ->
            val line = raw.trim()
            val host = csvHost(line) ?: return@mapNotNull null
            val comma = line.indexOf(',')
            val b32 = line.substring(comma + 1).trim().lowercase(Locale.ROOT)
            if (!b32.matches(B32_PATTERN) || !knownHosts.add(host)) return@mapNotNull null
            "$host,$b32"
        }
        if (additions.isEmpty() && target.isFile) return 0
        target.parentFile?.mkdirs()
        val content = (existingLines + additions).joinToString(separator = "\n", postfix = "\n")
        val tmp = File(target.path + ".tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) target.writeText(content)
        } finally {
            tmp.delete()
        }
        return additions.size
    }

    private fun csvHost(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#')) return null
        val comma = line.indexOf(',')
        if (comma <= 0 || comma == line.lastIndex) return null
        val host = line.substring(0, comma).trim().lowercase(Locale.ROOT)
        return host.takeIf { it.endsWith(".i2p") && it.length <= 67 && it.matches(HOST_PATTERN) }
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Long {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(info)
    }

    /** Copies the bundled configuration files that are missing (after "reset to bundled"). */
    fun restoreConfiguration() {
        for (asset in CONFIG_ASSETS) copyAsset(context.assets, asset, dataDir)
    }

    /** Deletes the certificates and copies the bundled ones. */
    fun replaceCertificates() = replaceAsset(CERTIFICATES)

    private fun replaceAsset(path: String) {
        File(dataDir, path).deleteRecursively()
        copyAsset(context.assets, path, dataDir)
    }

    private companion object {
        const val TAG = "i2pd-datadir"
        const val DATA_DIR_NAME = "i2pd"
        const val ASSETS_STAMP = ".assets-version"
        const val CERTIFICATES = "certificates"
        const val DISABLED_SUFFIX = ".disabled"
        const val ADDRESSBOOK_DIR = "addressbook"
        const val ADDRESSBOOK_INDEX = "addresses.csv"
        const val JAVA_DEFAULTS_ASSET = "addressbook/addresses.csv"
        const val JAVA_DEFAULTS_MARKER = ".java-defaults-v1"
        const val JAVA_DEFAULTS_VERSION = "1"
        val HOST_PATTERN = Regex("[a-z0-9.-]+\\.i2p")
        val B32_PATTERN = Regex("[a-z2-7]{52}")
        val CONFIG_ASSETS = listOf("i2pd.conf", "tunnels.conf", "tunnels.d")
        /** Web interface the torrents RPC server serves when "web" is on (TorrentsRPCSession::HandleWeb). */
        const val TORRENTS_WEB = "torrents/web"
        val ASSETS = CONFIG_ASSETS + listOf(ADDRESSBOOK_DIR, CERTIFICATES, TORRENTS_WEB)
    }
}
