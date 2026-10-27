package org.purplei2p.i2pd

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.purplei2p.i2pd.service.I2pVpnSettings
import java.io.ByteArrayOutputStream
import java.io.File

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")
private const val BACKGROUND_FILE_NAME = "web-console-background.jpg"
private const val MAX_BACKGROUND_EDGE = 1920

/** i2pd web console. Navigation outside loopback is blocked. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebConsoleScreen(
    url: String,
    onBack: () -> Unit,
    onOpenAddressBook: () -> Unit = {},
    onOpenBrowser: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backgroundDataUri by remember { mutableStateOf<String?>(null) }
    var showWebApps by remember { mutableStateOf(false) }
    val currentBackground by rememberUpdatedState(backgroundDataUri)
    val currentOnOpenAddressBook by rememberUpdatedState(onOpenAddressBook)
    val currentOnOpenBrowser by rememberUpdatedState(onOpenBrowser)
    val webView = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        backgroundDataUri = withContext(Dispatchers.IO) {
            context.consoleBackgroundFile().takeIf(File::isFile)?.readBytes()?.let {
                "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            // Resize and copy into private app storage: no broad photo/storage permission is needed,
            // and the chosen image remains available the next time the console is opened.
            val activity = context
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { importConsoleBackground(activity, uri) } }
                    .onSuccess { backgroundDataUri = it }
                    .onFailure { Toast.makeText(activity, R.string.console_background_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    LaunchedEffect(backgroundDataUri) {
        val view = webView.value ?: return@LaunchedEffect
        val data = backgroundDataUri
        if (data == null) {
            view.evaluateJavascript(
                "document.getElementById('cue-console-background')?.remove();",
                null,
            )
        } else {
            applyConsoleBackground(view, data)
        }
    }

    LaunchedEffect(showWebApps) {
        val view = webView.value ?: return@LaunchedEffect
        if (showWebApps) {
            val html = context.assets.open("webapps/index.html").bufferedReader().use { it.readText() }
            view.loadDataWithBaseURL("http://127.0.0.1:7070/cue-webapps/", html, "text/html", "UTF-8", null)
        } else {
            view.loadUrl(url)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(url, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showWebApps = !showWebApps }) {
                    Text(stringResource(if (showWebApps) R.string.console_show_console else R.string.console_open_webapps))
                }
                TextButton(onClick = { imagePicker.launch("image/*") }) {
                    Text(stringResource(R.string.console_choose_background))
                }
                if (backgroundDataUri != null) {
                    TextButton(onClick = {
                        backgroundDataUri = null
                        scope.launch {
                            withContext(Dispatchers.IO) { context.consoleBackgroundFile().delete() }
                        }
                    }) { Text(stringResource(R.string.console_clear_background)) }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        webView.value = this
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url
                                if (target.host in LOOPBACK_HOSTS) return false
                                if (target.scheme == "i2papp" && target.host == "addressbook") {
                                    currentOnOpenAddressBook()
                                    return true
                                }
                                if (target.scheme == "i2papp" && target.host == "browser") {
                                    val browserUrl = target.getQueryParameter("url")
                                    if (browserUrl != null && normalizeI2pBrowserUrl(browserUrl) != null) {
                                        currentOnOpenBrowser(browserUrl)
                                    } else {
                                        Toast.makeText(context, R.string.browser_invalid_address, Toast.LENGTH_SHORT).show()
                                    }
                                    return true
                                }
                                if (target.host?.endsWith(".i2p", ignoreCase = true) == true && target.scheme in setOf("http", "https")) {
                                    if (!I2pVpnSettings.isActive(context)) {
                                        Toast.makeText(context, R.string.webapps_vpn_required, Toast.LENGTH_LONG).show()
                                        return true
                                    }
                                    val selectedPackages = I2pVpnSettings.selectedPackages(context)
                                    val browserIntent = Intent(Intent.ACTION_VIEW, target)
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                    @Suppress("DEPRECATION")
                                    val handlers = context.packageManager.queryIntentActivities(browserIntent, 0)
                                    @Suppress("DEPRECATION")
                                    val defaultPackage = context.packageManager.resolveActivity(browserIntent, 0)?.activityInfo?.packageName
                                    val browserPackage = defaultPackage?.takeIf { it in selectedPackages }
                                        ?: handlers.firstNotNullOfOrNull { result ->
                                            result.activityInfo?.packageName?.takeIf(selectedPackages::contains)
                                        }
                                    if (browserPackage == null) {
                                        Toast.makeText(context, R.string.webapps_browser_not_selected, Toast.LENGTH_LONG).show()
                                        return true
                                    }
                                    browserIntent.setPackage(browserPackage)
                                    try {
                                        context.startActivity(browserIntent)
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        Toast.makeText(context, R.string.webapps_browser_missing, Toast.LENGTH_SHORT).show()
                                    }
                                    return true
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String) {
                                if (isLoopbackUrl(pageUrl)) currentBackground?.let { applyConsoleBackground(view, it) }
                            }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = {
                    if (webView.value === it) webView.value = null
                    it.destroy()
                },
            )
        }
    }
}

private fun Context.consoleBackgroundFile() = File(filesDir, BACKGROUND_FILE_NAME)

private suspend fun importConsoleBackground(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_BACKGROUND_EDGE) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val scaled = if (maxOf(decoded.width, decoded.height) > MAX_BACKGROUND_EDGE) {
        val factor = MAX_BACKGROUND_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt(), (decoded.height * factor).toInt(), true).also {
            if (it !== decoded) decoded.recycle()
        }
    } else decoded

    val output = ByteArrayOutputStream()
    try {
        scaled.compress(Bitmap.CompressFormat.JPEG, 84, output)
    } finally {
        scaled.recycle()
    }
    val bytes = output.toByteArray()
    context.consoleBackgroundFile().writeBytes(bytes)
    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

@SuppressLint("JavascriptInterface")
private fun applyConsoleBackground(view: WebView, dataUri: String) {
    // The payload is base64 only. Apply as a stylesheet to the local loopback console; external
    // navigation is rejected by the WebViewClient above.
    val script = """(()=>{let s=document.getElementById('cue-console-background');if(!s){s=document.createElement('style');s.id='cue-console-background';document.head.appendChild(s);}s.textContent='html,body{background-image:url("$dataUri") !important;background-size:cover !important;background-position:center center !important;background-attachment:fixed !important;background-color:transparent !important;}';})()"""
    view.evaluateJavascript(script, null)
}

/** True if [url] points to this device, so it may be shown in the web console. */
fun isLoopbackUrl(url: String): Boolean = url.toUri().host in LOOPBACK_HOSTS
