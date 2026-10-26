package org.purplei2p.i2pd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.io.File

private data class LocalAddressBookEntry(val host: String, val destination: String)

@Composable
fun AddressBookSearchScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LocalAddressBookEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { readLocalAddressBook(File(context.filesDir, "i2pd/addressbook")) }
        loaded = true
    }

    val needle = query.trim().lowercase()
    val matches = if (needle.isEmpty()) entries.take(100) else entries.filter {
        it.host.lowercase().contains(needle) || it.destination.lowercase().contains(needle)
    }.take(500)

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.addressbook_title), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.addressbook_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.addressbook_local_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (loaded) {
            Text(
                stringResource(R.string.addressbook_count, entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (matches.isEmpty() && loaded) {
            Text(stringResource(R.string.addressbook_empty), modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(matches, key = { it.host }) { entry ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(entry.host, style = MaterialTheme.typography.titleSmall)
                    Text(
                        entry.destination,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(entry.destination))
                        Toast.makeText(context, R.string.addressbook_copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.addressbook_copy))
                    }
                }
            }
        }
    }
}

private fun readLocalAddressBook(directory: File): List<LocalAddressBookEntry> {
    val candidates = listOf("addresses.csv", "hosts.txt", "hosts.csv").map { File(directory, it) }
    return candidates.asSequence()
        .filter(File::isFile)
        .flatMap { file ->
            runCatching { file.readLines() }.getOrDefault(emptyList()).asSequence()
        }
        .mapNotNull(::parseAddressBookLine)
        .distinctBy(LocalAddressBookEntry::host)
        .sortedBy { it.host.lowercase() }
        .toList()
}

private fun parseAddressBookLine(raw: String): LocalAddressBookEntry? {
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return null
    val comma = line.indexOf(',')
    val equals = line.indexOf('=')
    val separator = listOf(comma, equals).filter { it > 0 }.minOrNull() ?: return null
    val host = line.substring(0, separator).trim().trim('"', '\'').lowercase()
    val destination = line.substring(separator + 1).trim().trim('"', '\'')
    if (!host.endsWith(".i2p") || destination.isBlank()) return null
    return LocalAddressBookEntry(host, destination)
}
