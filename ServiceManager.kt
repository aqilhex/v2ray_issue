package com.vpn.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.vpn.MyForegroundService
import libv2ray.Libv2ray

object ForegroundServiceManager {

    @RequiresApi(Build.VERSION_CODES.O)
    fun startService(context: Context,config:String) {
        val serviceIntent = Intent(context, MyForegroundService::class.java)
        serviceIntent.putExtra("config", config)

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopService(context: Context) {
        Log.d("debug", "Attempting to stop service")

        // Send broadcast intent to MyForegroundService to disconnect
        val disconnectIntent = Intent(context, MyForegroundService::class.java)
        disconnectIntent.action = "com.piazvpn.DISCONNECT"
        context.startService(disconnectIntent)  // Start service with the disconnect action

        Log.d("debug", "Disconnect intent sent")
    }
    fun isConfigValid(config: String): Boolean {
        return try {
            Libv2ray.testConfig(config)
            true // Return true if testing the config succeeds
        } catch (testException: Exception) {
            false // Return false if an exception occurs during testing
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val services = manager.getRunningServices(Int.MAX_VALUE)

        // Check if V2rayNgVpnService is in the list of running services
        for (service in services) {
            if (MyForegroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
