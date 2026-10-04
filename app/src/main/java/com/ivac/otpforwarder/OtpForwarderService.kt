package com.ivac.otpforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Timer
import java.util.TimerTask

class OtpForwarderService : Service() {

    companion object {
        private const val TAG = "OtpForwarderService"
        private const val CHANNEL_ID = "ivac_otp_service_channel"
        private const val NOTIFICATION_ID = 2001
        private val httpClient = OkHttpClient()
    }

    private var dynamicSmsReceiver: SmsOtpReceiver? = null
    private var heartbeatTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerDynamicSmsReceiver()
        startBackgroundHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY // System will restart service if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatTimer?.cancel()
        unregisterDynamicSmsReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBackgroundHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendBackgroundPing()
            }
        }, 5000, 60000)
    }

    private fun sendBackgroundPing() {
        try {
            val prefs = getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)
            val devId = prefs.getString("device_id", "") ?: return
            if (devId.isBlank()) return

            val raw = prefs.getString("server_url", "https://ais-dev-r6fwghsme2uhic2o5q6fsu-466272014554.asia-southeast1.run.app")?.trimEnd('/') ?: return
            val pingUrl = if (raw.contains("/api/")) raw.substringBefore("/api/") + "/api/device/ping" else "$raw/api/device/ping"

            val json = JSONObject().apply {
                put("deviceId", devId)
                put("status", "ONLINE")
                put("sim1Number", prefs.getString("sim1_number", "") ?: "")
                put("sim2Number", prefs.getString("sim2_number", "") ?: "")
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(pingUrl).post(body).build()
            httpClient.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun registerDynamicSmsReceiver() {
        try {
            if (dynamicSmsReceiver == null) {
                dynamicSmsReceiver = SmsOtpReceiver()
                val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                    priority = 999
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(
                        this,
                        dynamicSmsReceiver!!,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(dynamicSmsReceiver, filter)
                }
                Log.i(TAG, "Dynamic SMS Receiver successfully registered in foreground service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering dynamic SMS receiver", e)
        }
    }

    private fun unregisterDynamicSmsReceiver() {
        try {
            dynamicSmsReceiver?.let {
                unregisterReceiver(it)
                dynamicSmsReceiver = null
                Log.i(TAG, "Dynamic SMS Receiver unregistered from foreground service")
            }
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "UDC SMS Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keep UDC SMS Assistant forwarder running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UDC SMS Assistant সক্রিয়")
            .setContentText("সিম ১ এবং সিম ২ এর ওটিপি লাইভ রাউটিং মনিটর করা হচ্ছে...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
