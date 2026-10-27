package org.purplei2p.i2pd

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.purplei2p.i2pd.service.RouterController

@Composable
fun AddressBookSearchScreen(
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val routerState by RouterController.state(context).collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AddressBookEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var pendingRestart by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<AddressBookImportResult?>(null) }
    var importRestartRequired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { AddressBookRepository.read(context) }
        loaded = true
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { AddressBookRepository.import(context, uri) } }
                    .onSuccess { result ->
                        entries = withContext(Dispatchers.IO) { AddressBookRepository.read(context) }
                        pendingRestart = result.added > 0 && routerState.lifecycle.hasRouterProcess
                        importRestartRequired = pendingRestart
                        importReport = result
                    }
                    .onFailure {
                        Toast.makeText(context, R.string.addressbook_import_failed, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    val needle = query.trim().lowercase()
    val matches = if (needle.isEmpty()) entries.take(100) else entries.filter {
        it.host.lowercase().contains(needle) || it.b32Address.lowercase().contains(needle)
    }.take(500)

    importReport?.let { result ->
        AlertDialog(
            onDismissRequest = { importReport = null },
            title = { Text(stringResource(R.string.addressbook_import_done)) },
            text = {
                Column {
                    Text(stringResource(R.string.addressbook_import_report, result.added, result.duplicates, result.conflicts, result.invalid))
                    if (importRestartRequired) {
                        Text(stringResource(R.string.addressbook_import_restart_hint), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importReport = null }) { Text(stringResource(R.string.action_done)) }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.addressbook_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) {
                Text(stringResource(R.string.addressbook_import))
            }
            TextButton(onClick = { onOpenBrowser("") }) {
                Text(stringResource(R.string.addressbook_browser))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.addressbook_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.addressbook_source_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (pendingRestart) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.addressbook_restart_note),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    RouterController.restart(context)
                    pendingRestart = false
                }) { Text(stringResource(R.string.addressbook_restart)) }
            }
        }
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
                        entry.b32Address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(entry.b32Address))
                            Toast.makeText(context, R.string.addressbook_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.addressbook_copy_b32))
                        }
                        TextButton(onClick = { onOpenBrowser("http://${entry.host}/") }) {
                            Text(stringResource(R.string.addressbook_open))
                        }
                    }
                }
            }
        }
    }
}
