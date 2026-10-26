package org.purplei2p.i2pd

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.purplei2p.i2pd.service.I2pVpnService
import org.purplei2p.i2pd.service.I2pVpnSettings
import org.purplei2p.i2pd.service.Lifecycle
import org.purplei2p.i2pd.service.RouterController

private data class VpnLaunchableApp(val packageName: String, val label: String)

@Composable
fun VpnSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val routerState by RouterController.state(context).collectAsStateWithLifecycle()
    val routerRunning = routerState.lifecycle == Lifecycle.Running
    var selected by remember { mutableStateOf(I2pVpnSettings.selectedPackages(context)) }
    var active by remember { mutableStateOf(I2pVpnSettings.isActive(context)) }
    var serviceError by remember { mutableStateOf(I2pVpnSettings.lastError(context)) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val apps = remember(context) { queryLaunchableApps(context) }

    LaunchedEffect(Unit) {
        while (true) {
            active = I2pVpnSettings.isActive(context)
            serviceError = I2pVpnSettings.lastError(context)
            delay(700)
        }
    }

    fun startVpnService() {
        message = null
        val intent = Intent(context, I2pVpnService::class.java).setAction(I2pVpnService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService()
        else message = context.getString(R.string.vpn_permission_denied)
    }

    fun requestStart() {
        if (!routerRunning) {
            message = context.getString(R.string.vpn_router_not_running)
            return
        }
        if (selected.isEmpty()) {
            message = context.getString(R.string.vpn_select_app)
            return
        }
        val permission = VpnService.prepare(context)
        if (permission == null) startVpnService() else permissionRequest.launch(permission)
    }

    fun updateSelected(packageName: String, checked: Boolean) {
        val updated = if (checked) selected + packageName else selected - packageName
        selected = updated
        I2pVpnSettings.setSelectedPackages(context, updated)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(if (active) R.string.vpn_status_running else R.string.vpn_status_stopped),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.vpn_scope_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        if (!routerRunning) {
            Text(
                stringResource(R.string.vpn_router_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { RouterController.start(context) }, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.vpn_start_router))
            }
        }
        serviceError?.takeIf { !active }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Button(onClick = {
                    val intent = Intent(context, I2pVpnService::class.java).setAction(I2pVpnService.ACTION_STOP)
                    context.startService(intent)
                    message = null
                }) { Text(stringResource(R.string.vpn_stop)) }
            } else {
                Button(onClick = ::requestStart, enabled = routerRunning && selected.isNotEmpty()) {
                    Text(stringResource(R.string.vpn_start))
                }
            }
            Text(
                stringResource(R.string.vpn_selected_count, selected.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.vpn_app_picker_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.packageName }) { app ->
                val checked = app.packageName in selected
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !active) { updateSelected(app.packageName, !checked) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        enabled = !active,
                        onCheckedChange = { updateSelected(app.packageName, it) },
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (apps.isEmpty()) {
            Text(stringResource(R.string.vpn_no_apps), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Suppress("DEPRECATION")
private fun queryLaunchableApps(context: android.content.Context): List<VpnLaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { result ->
            val appInfo = result.activityInfo?.applicationInfo ?: return@mapNotNull null
            val packageName = appInfo.packageName
            if (packageName == context.packageName) null
            else VpnLaunchableApp(packageName, result.loadLabel(pm).toString())
        }
        .distinctBy(VpnLaunchableApp::packageName)
        .sortedBy { it.label.lowercase() }
}
