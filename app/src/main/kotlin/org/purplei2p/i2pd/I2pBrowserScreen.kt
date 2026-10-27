package org.purplei2p.i2pd

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.purplei2p.i2pd.service.Lifecycle
import org.purplei2p.i2pd.service.RouterController
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun I2pBrowserScreen(
    initialUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val routerState by RouterController.state(context).collectAsStateWithLifecycle()
    val routerRunning = routerState.lifecycle == Lifecycle.Running
    var address by remember { mutableStateOf(initialUrl) }
    var proxyReady by remember { mutableStateOf(false) }
    var proxyError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialUrl) {
        address = initialUrl
        lastLoadedUrl = null
    }

    DisposableEffect(Unit) {
        var disposed = false
        var controller: ProxyController? = null
        val executor = ContextCompat.getMainExecutor(context)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyError = context.getString(R.string.browser_proxy_unsupported)
        } else {
            try {
                val proxyController = ProxyController.getInstance()
                controller = proxyController
                val proxy = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:4444")
                    // Keep local console and loopback-only app pages outside the proxy.
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .build()
                proxyController.setProxyOverride(proxy, executor, Runnable {
                    if (disposed) {
                        proxyController.clearProxyOverride(executor, Runnable {})
                    } else {
                        proxyReady = true
                    }
                })
            } catch (error: Exception) {
                proxyError = error.message ?: context.getString(R.string.browser_proxy_failed)
            }
        }
        onDispose {
            disposed = true
            runCatching { controller?.clearProxyOverride(executor, Runnable {}) }
        }
    }

    LaunchedEffect(proxyReady, routerRunning, initialUrl, webView) {
        val normalized = normalizeI2pBrowserUrl(initialUrl)
        val view = webView
        if (proxyReady && routerRunning && view != null && normalized != null && normalized != lastLoadedUrl) {
            lastLoadedUrl = normalized
            address = normalized
            view.loadUrl(normalized)
        }
    }

    fun openAddress(raw: String = address) {
        val normalized = normalizeI2pBrowserUrl(raw)
        if (normalized == null) {
            Toast.makeText(context, R.string.browser_invalid_address, Toast.LENGTH_SHORT).show()
            return
        }
        address = normalized
        if (!routerRunning) {
            Toast.makeText(context, R.string.browser_router_required, Toast.LENGTH_LONG).show()
            return
        }
        if (!proxyReady) {
            Toast.makeText(context, R.string.browser_proxy_waiting, Toast.LENGTH_SHORT).show()
            return
        }
        lastLoadedUrl = normalized
        webView?.loadUrl(normalized)
    }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }

    Surface(modifier.fillMaxSize()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.browser_address_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { openAddress() }),
                )
                TextButton(onClick = { openAddress() }) { Text(stringResource(R.string.browser_go)) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) {
                    Text(stringResource(R.string.browser_back))
                }
                TextButton(onClick = { if (webView?.canGoForward() == true) webView?.goForward() }) {
                    Text(stringResource(R.string.browser_forward))
                }
                TextButton(onClick = {
                    if (webView != null) webView?.reload() else openAddress()
                }) { Text(stringResource(R.string.browser_reload)) }
                Text(
                    stringResource(R.string.browser_proxy_label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                proxyError != null -> Text(
                    proxyError ?: stringResource(R.string.browser_proxy_failed),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                !proxyReady -> Text(
                    stringResource(R.string.browser_proxy_waiting),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !routerRunning -> Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.browser_router_required))
                    TextButton(onClick = { RouterController.start(context) }) {
                        Text(stringResource(R.string.vpn_start_router))
                    }
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loading = newProgress < 100
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val target = request.url
                                    if (target.scheme == "about" && target.toString() == "about:blank") return false
                                    if (isAllowedI2pWebUrl(target)) return false
                                    Toast.makeText(context, R.string.browser_only_i2p, Toast.LENGTH_SHORT).show()
                                    return true
                                }

                                @Deprecated("Deprecated in Android")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                    val target = Uri.parse(url)
                                    if (target.scheme == "about" && url == "about:blank") return false
                                    if (isAllowedI2pWebUrl(target)) return false
                                    return true
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val uri = request.url
                                    val scheme = uri.scheme?.lowercase()
                                    if (scheme in setOf("data", "blob", "about")) return null
                                    if (isAllowedI2pWebUrl(uri)) return null
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                }
                            }
                        }
                    },
                    update = { webView = it },
                    onRelease = {
                        if (webView === it) webView = null
                        it.stopLoading()
                        it.destroy()
                    },
                )
            }
        }
    }
}

internal fun normalizeI2pBrowserUrl(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty()) return null
    val candidate = if ("://" in input) input else "http://$input"
    val uri = Uri.parse(candidate)
    return candidate.takeIf { isAllowedI2pWebUrl(uri) && uri.userInfo == null }
}

private fun isAllowedI2pWebUrl(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme !in setOf("http", "https")) return false
    val host = uri.host?.lowercase() ?: return false
    return host.endsWith(".i2p") && host.length > ".i2p".length
}
