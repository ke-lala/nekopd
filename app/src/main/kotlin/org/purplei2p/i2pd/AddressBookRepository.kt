package org.purplei2p.i2pd

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.purplei2p.i2pd.service.AddressBookImportPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

internal data class AddressBookEntry(val host: String, val b32: String) {
    val b32Address: String get() = "$b32.b32.i2p"
}

internal data class AddressBookImportResult(
    val added: Int,
    val duplicates: Int,
    val conflicts: Int,
    val invalid: Int,
)

/** Reads i2pd's index and standard hosts.txt files, with the bundled Java I2P seed as fallback. */
internal object AddressBookRepository {
    private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    private const val MAX_IMPORT_RECORDS = 100_000
    private const val MAX_LINE_LENGTH = 4096
    private const val I2PD_DIR = "i2pd"
    private const val APP_IMPORT_DIR = "i2pd-addressbook"
    private const val INDEX_FILE = "addresses.csv"

    fun read(context: Context): List<AddressBookEntry> {
        val records = linkedMapOf<String, AddressBookEntry>()
        val dataDir = File(context.filesDir, I2PD_DIR)
        addLines(records, File(dataDir, "addressbook/addresses.csv"))
        addLines(records, File(dataDir, "addressbook/local.csv"))
        addLines(records, File(dataDir, "hosts.txt"))
        addLines(records, AddressBookImportPaths.mappings(context))
        // Users can search defaults before they have started the router for the first time.
        runCatching {
            context.assets.open("addressbook/addresses.csv").bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val entry = parseLine(raw) ?: return@forEach
                    if (entry.host !in records) records[entry.host] = entry
                }
            }
        }
        return records.values.sortedBy { it.host }
    }

    fun import(context: Context, uri: Uri): AddressBookImportResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) throw IOException("Address book file is too large")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: throw IOException("Could not read the selected file")

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val parsed = mutableListOf<AddressBookEntry>()
        var invalid = 0
        for (line in text.lineSequence().take(MAX_IMPORT_RECORDS + 1)) {
            if (line.length > MAX_LINE_LENGTH) {
                invalid++
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val entry = parseLine(trimmed)
            if (entry == null) invalid++ else parsed += entry
        }
        if (parsed.isEmpty()) throw IOException("No valid I2P address entries were found")

        val existing = read(context).associateBy { it.host }.toMutableMap()
        val importedFile = AddressBookImportPaths.mappings(context)
        val stored = if (importedFile.isFile) importedFile.readLines() else emptyList()
        val accepted = linkedMapOf<String, AddressBookEntry>()
        var duplicates = 0
        var conflicts = 0
        for (entry in parsed) {
            val previous = existing[entry.host] ?: accepted[entry.host]
            when {
                previous == null -> {
                    accepted[entry.host] = entry
                    existing[entry.host] = entry
                }
                previous.b32 == entry.b32 -> duplicates++
                else -> conflicts++
            }
        }
        if (accepted.isNotEmpty()) {
            importedFile.parentFile?.mkdirs()
            val lines = stored + accepted.values.map { "${it.host},${it.b32}" }
            val tmp = File(importedFile.path + ".tmp")
            val content = lines.joinToString(separator = "\n", postfix = "\n")
            try {
                tmp.writeText(content)
                if (!tmp.renameTo(importedFile)) importedFile.writeText(content)
            } finally {
                tmp.delete()
            }
        }
        return AddressBookImportResult(accepted.size, duplicates, conflicts, invalid)
    }

    internal fun parseLine(raw: String): AddressBookEntry? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith('#') || line.length > MAX_LINE_LENGTH) return null
        val separator = listOf(line.indexOf('='), line.indexOf(',')).filter { it > 0 }.minOrNull() ?: return null
        val host = line.substring(0, separator).trim().trim('"', '\'').lowercase(Locale.ROOT)
        if (!isValidHostname(host)) return null
        val value = line.substring(separator + 1).substringBefore('#').trim().trim('"', '\'')
        val b32 = parseB32(value) ?: destinationB32(value) ?: return null
        return AddressBookEntry(host, b32)
    }

    private fun addLines(records: MutableMap<String, AddressBookEntry>, file: File) {
        if (!file.isFile || file.length() > MAX_IMPORT_BYTES) return
        runCatching {
            file.bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val entry = parseLine(raw) ?: return@forEach
                    if (entry.host !in records) records[entry.host] = entry
                }
            }
        }
    }

    private fun isValidHostname(host: String): Boolean {
        if (host.length !in 6..67 || !host.endsWith(".i2p") || host.endsWith(".b32.i2p")) return false
        if (!host.matches(HOST_PATTERN) || host.startsWith('.') || host.startsWith('-')) return false
        if (".." in host || ".-" in host || "-." in host) return false
        val labels = host.removeSuffix(".i2p").split('.')
        if (labels.any { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') }) return false
        if (labels.any { "--" in it && !it.startsWith("xn--") }) return false
        return true
    }

    private fun parseB32(raw: String): String? {
        val value = raw.trim().lowercase(Locale.ROOT)
        val bare = if (value.endsWith(".b32.i2p")) value.removeSuffix(".b32.i2p") else value
        return bare.takeIf { it.matches(B32_PATTERN) }
    }

    /** I2P's base64 alphabet uses '-' and '~' where RFC 4648 uses '+' and '/'. */
    private fun destinationB32(raw: String): String? {
        val value = raw.filterNot(Char::isWhitespace).replace('-', '+').replace('~', '/')
        if (value.length !in 500..4096 || value.length % 4 == 1) return null
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        val destination = runCatching { Base64.decode(padded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (destination.size < 387) return null
        val hash = MessageDigest.getInstance("SHA-256").digest(destination)
        return encodeBase32(hash)
    }

    internal fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val result = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) result.append(alphabet[(buffer shl (5 - bits)) and 31])
        return result.toString()
    }

    private val HOST_PATTERN = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.i2p")
    private val B32_PATTERN = Regex("[a-z2-7]{52}")
}
