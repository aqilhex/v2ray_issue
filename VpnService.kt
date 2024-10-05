package com.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.vpn.dt.AppConfig.ANG_PACKAGE
import com.vpn.dt.MyContextWrapper
import com.vpn.manager.ForegroundServiceManager
import com.vpn.service.ServiceControl
import com.vpn.service.V2rayNgServiceManager
import com.vpn.utils.Utils
import go.Seq
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference

class MyForegroundService : VpnService(), ServiceControl {
    private val CHANNEL_ID = "ForegroundServiceChannel"
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private lateinit var process: Process
    val v2rayPoint: V2RayPoint = Libv2ray.newV2RayPoint(V2RayCallback(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()

            if (service != null) {
                Seq.setContext(service.applicationContext)
                Libv2ray.initV2Env(Utils.userAssetPath(service))
                Log.d("MyService", "V2Ray environment initialized.")
            } else {
                Log.e("MyService", "Failed to initialize V2Ray environment: serviceControl is null.")
            }
        }
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    companion object {
        private const val VPN_MTU = 1400
        private const val PRIVATE_VLAN4_CLIENT = "26.26.26.1"
        private const val PRIVATE_VLAN4_ROUTER = "26.26.26.2"
        private const val PRIVATE_VLAN6_CLIENT = "da26:2626::1"
        private const val PRIVATE_VLAN6_ROUTER = "da26:2626::2"
        private const val TUN2SOCKS = "libtun2socks.so"
    }
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }
    private inner class V2RayCallback : V2RayVPNServiceSupportsSet {
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            // called by go
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.d(ANG_PACKAGE, e.toString())
                -1
            }
        }

        override fun prepare(): Long {
            return 0
        }

        override fun protect(l: Long): Boolean {
            val serviceControl = serviceControl?.get() ?: return true
            return serviceControl.vpnProtect(l.toInt())
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            //Logger.d(s)
            return 0
        }

        override fun setup(s: String): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            //Logger.d(s)
            return try {
                serviceControl.startService()
                0
            } catch (e: Exception) {
                Log.d(ANG_PACKAGE, e.toString())
                -1
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate() {
        super.onCreate()
        Log.d("debug", "onCreate event")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2rayNgServiceManager.serviceControl = SoftReference(this)
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("My Service")
            .setContentText("Running...")
            .setSmallIcon(R.drawable.ic_stat_name)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("debug", "onStartCommand event")

        // Handle disconnect intent
        when (intent?.action) {
            "com.piazvpn.DISCONNECT" -> {
                Log.d("debug", "Received disconnect request")
                disconnect()  // Call your disconnect method
                return START_NOT_STICKY
            }
            else -> {
                // Your existing onStartCommand logic
                val config = intent?.getStringExtra("config")
                // Check if the configuration string is valid
                if (config.isNullOrEmpty()) {
                    Log.d("debug", "onStartCommand event: START_NOT_STICKY")
                    stopSelf() // Stop the service if no valid configuration is provided
                    return START_NOT_STICKY
                }

                // Check if VPN preparation is needed
                try {
                    if (prepare(this) == null) {
                        Log.d("debug", "VPN ESTABLISHMENT event: $config")
                        setup()
                        // Proceed with VPN establishment
                        connect(config) // Placeholder for your VPN connection logic
                    }else{
                        Log.d("debug", "No permission allowed")
                    }
                } catch (e: SecurityException) {
                    Log.e("debug", "SecurityException: ${e.message}")
                    stopSelf() // Stop service in case of a security exception
                } catch (e: Exception) {
                    Log.e("debug", "Error during VPN preparation: ${e.message}")
                    // Handle specific errors if necessary
                    stopSelf() // Stop service in case of an error
                }
            }
        }

        Log.d("debug", "onStartCommand event: START_STICKY")
        return START_STICKY // Service will restart if terminated by the system
    }

    private fun connect(config: String) {
        try {
            // Logic to establish a VPN connection using the provided config
            if (!ForegroundServiceManager.isConfigValid(config)) {
                Log.e("debug", "Config JSON is not correct")
                return
            }
            v2rayPoint.domainName = "elma.ns.cloudflare.com:2087"

            // Proceed with VPN establishment
            if (!v2rayPoint.isRunning) {
                // Apply config and run v2ray
                v2rayPoint.configureFileContent = config
                try {
                    v2rayPoint.runLoop(false)
                } catch (e: Exception) {
                    Log.e(ANG_PACKAGE, "Error running v2rayPoint loop: ${e.message}")
                }
            } else {
                Log.e("debug", "It's already running: ${v2rayPoint.isRunning}")
            }
            Log.d("debug", "Establishing VPN connection with config: $config")
        } catch (e: Exception) {
            Log.e("debug", "Failed to establish VPN connection: ${e.message}")
            stopSelf() // Stop service if VPN connection fails
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun disconnect() {
        Log.d("debug", "Disconnect method called")
        isRunning = false

        // Unregister network callback if the SDK version supports it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
                Log.d("debug", "Network callback unregistered")
            } catch (e: Exception) {
                Log.e("debug", "Failed to unregister network callback: ${e.message}")
            }
        }

        // Stop V2Ray if it is running
        if (v2rayPoint.isRunning) {
            try {
                v2rayPoint.stopLoop() // Stop the V2Ray loop
                Log.d("debug", "V2Ray point stopped successfully")
            } catch (e: Exception) {
                Log.e("debug", "Failed to stop V2Ray point: ${e.message}")
            }
        }

        // Stop the foreground service and clean up
        serviceControl?.get()?.getService()?.stopForeground(true) // Stop the foreground notification
        Log.d("debug", "Foreground notification stopped")

        // Destroy the tun2socks process
        try {
            if (::process.isInitialized && process.isAlive) {
                Log.d(packageName, "Destroying tun2socks process")
                process.destroy()
            }
        } catch (e: Exception) {
            Log.e(packageName, "Failed to destroy process: ${e.message}")
        }

        // Close the VPN interface if it exists
        try {
            if (::mInterface.isInitialized) {
                mInterface.close() // Safely close the interface
                Log.d("debug", "VPN interface closed")
            }
        } catch (ignored: Exception) {
            Log.e("debug", "Failed to close VPN interface: ${ignored.message}")
        }

        stopSelf() // Stop the service after cleanup
    }
    private fun setup() {
        // Close the VPN interface if it exists
        try {
            if (::mInterface.isInitialized) {
                mInterface.close() // Safely close the interface
                Log.d("debug", "VPN interface closed")
            }
        } catch (ignored: Exception) {
            Log.e("debug", "Failed to close VPN interface: ${ignored.message}")
        }

        val builder = Builder()
        builder.setMtu(VPN_MTU)
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        builder.addRoute("0.0.0.0", 0)
        builder.setSession("CONNECTION REMARK")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Register the network callback
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e("VPNSetup", "Error requesting network", e)
            }
        }

        // Create a new interface using the builder and save the parameters.
        try {
            mInterface = builder.establish() ?: throw Exception("Failed to establish VPN interface")
            isRunning = true
            runTun2socks()
        } catch (e: Exception) {
            Log.e("VPNSetup", "Error during VPN setup", e)
            stopSelf()
        }
    }

    private fun runTun2socks() {
        val socksPort = 10808
        val tun2socksPath = File(applicationContext.applicationInfo.nativeLibraryDir, "libtun2socks.so").absolutePath
        val cmd = arrayListOf(
            tun2socksPath,
            "--netif-ipaddr", PRIVATE_VLAN4_ROUTER,
            "--netif-netmask", "255.255.255.252",
            "--socks-server-addr", "127.0.0.1:$socksPort",
            "--tunmtu", VPN_MTU.toString(),
            "--sock-path", "sock_path",//File(applicationContext.filesDir, "sock_path").absolutePath,
            "--enable-udprelay",
            "--loglevel", "notice"
        )

        Log.d(packageName, cmd.toString())

        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder.directory(applicationContext.filesDir).start()
            Thread {
                Log.d(packageName, "libtun2socks.so check")
                process.waitFor()
                Log.d(packageName, "libtun2socks.so exited")
                if (isRunning) {
                    Log.d(packageName, "libtun2socks.so restart")
                    runTun2socks()
                }
            }.start()
            Log.d(packageName, process.toString())
            sendFd()
        } catch (e: Exception) {
            Log.d(packageName, e.toString())
        }
    }
    @OptIn(DelicateCoroutinesApi::class)
    private fun sendFd() {
        val fd = mInterface.fileDescriptor
        val path = File(applicationContext.filesDir, "sock_path").absolutePath
        Log.d(packageName, "Socket path: $path")

        GlobalScope.launch(Dispatchers.IO) {
            var tries = 0
            while (tries <= 5) {
                try {
                    delay(50L shl tries)
                    Log.d(packageName, "sendFd tries: $tries")
                    LocalSocket().use { localSocket ->
                        localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                        localSocket.setFileDescriptorsForSend(arrayOf(fd))
                        localSocket.outputStream.write(42)
                        Log.d(packageName, "File descriptor sent successfully.")
                    }
                    break
                } catch (e: IOException) {
                    Log.e(packageName, "IOException in sendFd: ${e.message}", e)
                    if (tries >= 5) break
                    tries++
                } catch (e: Exception) {
                    Log.e(packageName, "Exception in sendFd: ${e.message}", e)
                    if (tries >= 5) break
                    tries++
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Return null as it's not a bound service
    }
    override fun onRevoke() {
        super.onRevoke()
        Log.d("debug", "VPN revoked by the user.")
        stopSelf() // Call your disconnect method to cleanly disconnect the VPN
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources here
        Log.d("debug", "Service destroyed")
        // Additional cleanup logic if necessary
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setup()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun stopService() {
        disconnect()
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }
    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase,  Utils.getLocale(newBase))
        }
        super.attachBaseContext(context)
    }
}
