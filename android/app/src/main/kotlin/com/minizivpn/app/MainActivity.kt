package com.minizivpn.app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import java.io.File
import org.json.JSONObject
import android.util.Log
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.Bundle

/**
 * ZIVPN Turbo Main Activity
 * Optimized for high-performance tunneling and aggressive cleanup.
 */
class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.minizivpn.app/core"
    private val LOG_CHANNEL = "com.minizivpn.app/logs"
    private val processes = mutableListOf<Process>()
    private val REQUEST_VPN_CODE = 1
    
    private var logSink: EventChannel.EventSink? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure environment is clean on launch
        stopEngine()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, LOG_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    logSink = events
                    sendToLog("Logging system initialized.")
                }
                override fun onCancel(arguments: Any?) {
                    logSink = null
                }
            }
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "startCore") {
                val ip = call.argument<String>("ip") ?: ""
                val portRange = call.argument<String>("port_range") ?: "6000-19999"
                val pass = call.argument<String>("pass") ?: ""
                val obfs = call.argument<String>("obfs") ?: "hu``hqb`c"
                val multiplier = call.argument<Double>("recv_window_multiplier") ?: 1.0
                val udpMode = call.argument<String>("udp_mode") ?: "tcp"

                startEngine(ip, portRange, pass, obfs, multiplier, udpMode, result)
            } else if (call.method == "stopCore") {
                stopEngine()
                stopVpn()
                result.success("Stopped")
            } else if (call.method == "startVpn") {
                startVpn(result)
            } else {
                result.notImplemented()
            }
        }
    }

    private fun sendToLog(msg: String) {
        uiHandler.post {
            logSink?.success(msg)
        }
        Log.d("ZIVPN-Core", msg)
    }

    private fun startVpn(result: MethodChannel.Result) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN_CODE)
            result.success("REQUEST_PERMISSION")
            sendToLog("Requesting VPN permission...")
        } else {
            val serviceIntent = Intent(this, TunService::class.java)
            serviceIntent.action = TunService.ACTION_CONNECT
            startService(serviceIntent)
            result.success("STARTED")
            sendToLog("VPN Service started.")
        }
    }

    private fun stopVpn() {
        val serviceIntent = Intent(this, TunService::class.java)
        serviceIntent.action = TunService.ACTION_DISCONNECT
        startService(serviceIntent)
        sendToLog("VPN Service stopped.")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_CODE) {
            if (resultCode == RESULT_OK) {
                val serviceIntent = Intent(this, TunService::class.java)
                serviceIntent.action = TunService.ACTION_CONNECT
                startService(serviceIntent)
                sendToLog("VPN permission granted. Starting service.")
            } else {
                sendToLog("VPN permission denied.")
            }
        }
    }

    private fun startEngine(
        ip: String, 
        range: String, 
        pass: String, 
        obfs: String, 
        multiplier: Double, 
        udpMode: String,
        result: MethodChannel.Result
    ) {
        stopEngine() // Double ensure cleanup
        try { Thread.sleep(1500) } catch (e: Exception) {} // Wait for sockets to close
        
        // Save UDP Mode for TunService
        getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .edit()
            .putString("udp_mode", udpMode)
            .apply()
        
        sendToLog("Starting Turbo Engine (Hardcoded Mode)...")

        // HARDCODED CREDENTIALS
        val hcIp = "103.175.216.5"
        val hcPass = "maslexx68"
        val hcObfs = "hu``hqb`c"
        val hcRange = "6000-19999"

        try {
            val libDir = applicationInfo.nativeLibraryDir
            val libUz = File(libDir, "libuz.so").absolutePath
            val libLoad = File(libDir, "libload.so").absolutePath
            
            // --- 1. START HYSTERIA CORES (x4) ---
            val baseConn = 131072
            val baseWin = 327680
            val dynamicConn = (baseConn * multiplier).toInt()
            val dynamicWin = (baseWin * multiplier).toInt()
            
            val ports = listOf(20080, 20081, 20082, 20083)
            val tunnelTargets = mutableListOf<String>()

            for (port in ports) {
                val hyConfig = JSONObject()
                hyConfig.put("server", "$hcIp:$hcRange")
                hyConfig.put("obfs", hcObfs)
                hyConfig.put("auth", hcPass)
                
                val socks5Json = JSONObject()
                socks5Json.put("listen", "127.0.0.1:$port")
                hyConfig.put("socks5", socks5Json)
                
                hyConfig.put("insecure", true)
                hyConfig.put("recvwindowconn", dynamicConn)
                hyConfig.put("recvwindow", dynamicWin)
                
                // Gunakan format string JSON langsung sesuai permintaan sebelumnya
                val hyCmd = arrayListOf(libUz, "-s", hcObfs, "--config", hyConfig.toString())
                val hyPb = ProcessBuilder(hyCmd)
                hyPb.directory(filesDir)
                hyPb.environment()["LD_LIBRARY_PATH"] = libDir
                hyPb.redirectErrorStream(true)
                
                val p = hyPb.start()
                processes.add(p)
                startLogger(p, "HY-$port") 
                tunnelTargets.add("127.0.0.1:$port")
            }
            
            // Give Hysteria time to init
            Thread.sleep(1000)

            // --- 2. START LIBLOAD (Load Balancer) ---
            val lbCmd = mutableListOf(libLoad, "-lport", "7777", "-tunnel")
            lbCmd.addAll(tunnelTargets)
            
            val lbPb = ProcessBuilder(lbCmd)
            lbPb.directory(filesDir)
            lbPb.environment()["LD_LIBRARY_PATH"] = libDir
            val lbProcess = lbPb.start()
            processes.add(lbProcess)
            startLogger(lbProcess, "LB-7777")
            
            result.success("Turbo Engine Running (4 Cores + LB:7777)")
            
        } catch (e: Exception) {
            Log.e("MiniZIVPN", "Startup failed: ${e.message}")
            sendToLog("Startup Failed: ${e.message}")
            stopEngine()
            result.error("START_ERR", e.message, null)
        }
    }

    private fun startLogger(p: Process, tag: String) {
        Thread {
            try {
                p.inputStream.bufferedReader().use { r -> 
                    r.forEachLine { sendToLog("[$tag] $it") } 
                }
            } catch (e: Exception) {
                sendToLog("[$tag] Log stream ended.")
            }
        }.start()
        
        Thread {
            try {
                p.waitFor()
                sendToLog("[$tag] Process exited with code ${p.exitValue()}")
            } catch (e: Exception) {}
        }.start()
    }

    private fun stopEngine() {
        val intent = Intent(this, TunService::class.java)
        intent.action = TunService.ACTION_DISCONNECT
        startService(intent)

        processes.forEach { 
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    it.destroyForcibly()
                } else {
                    it.destroy()
                }
            } catch(e: Exception){} 
        }
        processes.clear()
        
        // Brute force cleanup for ALL instances of the cores
        try {
            val cleanupCmd = arrayOf("sh", "-c", "pkill -9 libuz; pkill -9 libload; pkill -9 libuz.so; pkill -9 libload.so")
            Runtime.getRuntime().exec(cleanupCmd).waitFor()
        } catch (e: Exception) {}
        
        sendToLog("Aggressive cleanup executed.")
    }
    
    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }
}