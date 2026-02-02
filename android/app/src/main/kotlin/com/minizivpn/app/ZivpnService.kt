package com.minizivpn.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import android.app.PendingIntent
import java.net.InetAddress
import java.util.LinkedList
import androidx.annotation.Keep

/**
 * ZIVPN TunService
 * Handles the VpnService interface and integrates with hev-socks5-tunnel.
 */
@Keep
class ZivpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.minizivpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.minizivpn.app.DISCONNECT"
        
        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("ZIVPN-Tun", "Native library load failed: ${e.message}")
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    // Native Methods (linked to hev-jni.c)
    private external fun TProxyStartService(configPath: String, fd: Int)
    private external fun TProxyStopService()
    private external fun TProxyGetStats(): LongArray?

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                connect()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        if (vpnInterface != null) return

        Log.i("ZIVPN-Tun", "Initializing ZIVPN Tunneling...")
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 1. Build VPN Interface
        val builder = Builder()
        builder.setSession("MiniZivpn")
        builder.setConfigureIntent(pendingIntent)
        builder.setMtu(1280) // Lower MTU to prevent packet fragmentation (Crucial for Browsing)
        builder.addRoute("0.0.0.0", 0) // Route ALL traffic
        
        // CRITICAL: Exclude own app to prevent VPN Loop
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e("TunService", "Failed to exclude own app: ${e.message}")
        }
        
        // DNS is REQUIRED for internet to work properly through VPN
        builder.addDnsServer("8.8.8.8") // Google DNS as primary
        builder.addDnsServer("1.1.1.1") // Cloudflare DNS
        builder.addDnsServer("198.18.0.2") // MapDNS as fallback
        builder.addAddress("172.19.0.1", 24)

        try {
            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: return

            Log.i("TunService", "VPN Interface established. FD: $fd")

            val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            val udpMode = prefs.getString("udp_mode", "tcp") ?: "tcp"

            val configContent = """
                tunnel:
                  name: tun0
                  ipv4: 172.19.0.1
                
                socks5:
                  port: 7777
                  address: 127.0.0.1
                  udp: $udpMode

                mapdns:
                  address: 198.18.0.2
                  port: 53
                  network: 198.18.0.0
                  netmask: 255.254.0.0
            """.trimIndent()

            val configFile = File(cacheDir, "tunnel_config.yaml")
            configFile.writeText(configContent)

            TProxyStartService(configFile.absolutePath, fd)
            Log.i("TunService", "Native Tunnel Started")
            
            prefs.edit().putBoolean("flutter.vpn_running", true).apply()

        } catch (e: Throwable) {
            Log.e("TunService", "Error starting VPN: ${e.message}")
            stopSelf()
        }
    }

    private fun disconnect() {
        Log.i("TunService", "Stopping VPN...")
        TProxyStopService()
        vpnInterface?.close()
        vpnInterface = null
        
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        prefs.edit().putBoolean("flutter.vpn_running", false).apply()
        
        stopSelf()
    }
}
