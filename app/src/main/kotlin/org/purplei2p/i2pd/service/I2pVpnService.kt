package org.purplei2p.i2pd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService as HevTProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Routes only the user-selected app packages through the local i2pd SOCKS5 proxy. */
class I2pVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var routerWatcher: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startAsForeground()
        if (tun == null) startTunnel()
        return if (tun == null) START_NOT_STICKY else START_STICKY
    }

    private fun startTunnel() {
        val router = RouterController.state(this).value
        if (router.lifecycle != Lifecycle.Running) {
            fail("Start i2pd first; the router must be running")
            return
        }

        val selectedPackages = I2pVpnSettings.selectedPackages(this).filter { it != packageName }
        if (selectedPackages.isEmpty()) {
            fail("Select at least one installed app")
            return
        }

        val builder = Builder()
            .setSession(getString(R.string.vpn_notification_title))
            .setMtu(TUN_MTU)
            .setBlocking(false)
            .addAddress(TUN_IPV4, 32)
            .addAddress(TUN_IPV6, 128)
            // Full routes make unsupported/public destinations fail closed through i2pd SOCKS;
            // they must not silently fall back to the phone's direct network.
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(MAPPED_DNS)

        var installed = 0
        for (appPackage in selectedPackages) {
            try {
                builder.addAllowedApplication(appPackage)
                installed++
            } catch (_: PackageManager.NameNotFoundException) {
                // The app was removed since the user selected it; skip the stale preference.
            }
        }
        if (installed == 0) {
            fail("No selected apps are installed")
            return
        }

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            fail("Cannot establish VPN: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (descriptor == null) {
            fail("Android did not grant the VPN interface")
            return
        }

        val config = File(cacheDir, "i2pd-vpn.yml")
        try {
            config.writeText(HEV_CONFIG)
        } catch (e: IOException) {
            descriptor.close()
            fail("Cannot write VPN configuration: ${e.message ?: "I/O error"}")
            return
        }

        tun = descriptor
        val started = try {
            HevTProxyService.TProxyStartService(config.absolutePath, descriptor.fd)
        } catch (e: Throwable) {
            false
        }
        if (!started) {
            fail("TUN-to-SOCKS engine did not start")
            return
        }

        I2pVpnSettings.markActive(this)
        watchRouter()
        scope.launch {
            kotlinx.coroutines.delay(800)
            if (tun != null && !runCatching { HevTProxyService.TProxyIsRunning() }.getOrDefault(false)) {
                fail("TUN-to-SOCKS engine stopped unexpectedly")
            }
        }
    }

    private fun watchRouter() {
        routerWatcher?.cancel()
        routerWatcher = scope.launch {
            RouterController.state(this@I2pVpnService).collect { state ->
                if (tun != null && state.lifecycle != Lifecycle.Running) {
                    fail("i2pd stopped; VPN disconnected to avoid routing without the I2P router")
                }
            }
        }
    }

    private fun fail(message: String) {
        stopTunnel(message)
        stopSelf()
    }

    private fun stopTunnel(error: String? = null) {
        routerWatcher?.cancel()
        routerWatcher = null
        if (tun != null) {
            runCatching { HevTProxyService.TProxyStopService() }
            try {
                tun?.close()
            } catch (_: IOException) {
            }
            tun = null
        }
        I2pVpnSettings.markStopped(this, error)
        stopForegroundCompat()
    }

    override fun onRevoke() {
        stopTunnel("VPN permission was revoked")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, I2pVpnService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.vpn_notification_stop), stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "org.purplei2p.i2pd.vpn.START"
        const val ACTION_STOP = "org.purplei2p.i2pd.vpn.STOP"

        private const val CHANNEL_ID = "i2pd_vpn"
        private const val NOTIFICATION_ID = 3702
        private const val TUN_MTU = 1500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val MAPPED_DNS = "198.18.0.2"

        private val HEV_CONFIG = """
            tunnel:
              mtu: 1500
              icmp: 'reply'
            socks5:
              address: 127.0.0.1
              port: 4447
              udp: 'udp'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 240.0.0.0
              netmask: 240.0.0.0
              cache-size: 10000
            misc:
              task-stack-size: 81920
        """.trimIndent()
    }
}
