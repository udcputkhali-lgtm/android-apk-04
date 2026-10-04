package com.ivac.otpforwarder

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etSim1Phone: EditText
    private lateinit var etSim2Phone: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnTestServerConnection: Button
    private lateinit var btnSendTestOtpDirect: Button
    private lateinit var tvSim1Status: TextView
    private lateinit var tvSim2Status: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnRequestBattery: Button
    private lateinit var llActivityContainer: LinearLayout
    private lateinit var tvNoActivityPlaceholder: TextView
    private lateinit var tvLiveIndicator: TextView

    // Device ID & Database Pairing UI
    private lateinit var tvDeviceIdBadge: TextView
    private lateinit var tvDeviceBatteryBadge: TextView
    private lateinit var tvPairingStatusBadge: TextView
    private lateinit var tvDeviceHeartbeatStatus: TextView

    // Live Captured SMS UI
    private lateinit var tvSmsStatusBadge: TextView
    private lateinit var tvSmsDetectionSummary: TextView
    private lateinit var tvSmsMeta: TextView
    private lateinit var tvSmsOtpCode: TextView
    private lateinit var btnCopyOtp: Button
    private lateinit var tvSmsForwardStatus: TextView
    private lateinit var tvSmsFullBody: TextView
    private lateinit var btnCheckInbox: Button
    private lateinit var btnSimulateTestSms: Button

    // History UI
    private lateinit var llSmsHistoryContainer: LinearLayout
    private lateinit var tvNoHistoryPlaceholder: TextView
    private lateinit var btnClearHistory: Button

    // Permission Manager Alert UI
    private lateinit var cardPermissionAlert: com.google.android.material.card.MaterialCardView
    private lateinit var tvPermissionIcon: TextView
    private lateinit var tvPermissionTitle: TextView
    private lateinit var tvPermissionBadge: TextView
    private lateinit var tvPermissionDesc: TextView
    private lateinit var llPermissionButtons: LinearLayout
    private lateinit var btnOpenAppSettings: Button
    private lateinit var btnShowPermissionGuide: Button
    private lateinit var btnRequestPermissionsDirect: Button

    private val httpClient = OkHttpClient()
    private var pollTimer: Timer? = null

    private val smsBroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.ivac.otpforwarder.ACTION_SMS_CAPTURED" -> {
                    val smsText = intent.getStringExtra("smsText") ?: ""
                    val sender = intent.getStringExtra("sender") ?: ""
                    val simSlot = intent.getIntExtra("simSlot", 1)
                    val otpCode = intent.getStringExtra("otpCode") ?: ""
                    val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                    val status = intent.getStringExtra("status") ?: "SENDING"

                    updateCapturedSmsUI(smsText, sender, simSlot, otpCode, timestamp, status, "সার্ভারে পাঠানো হচ্ছে...")
                    loadSmsHistoryFromPrefs()
                }
                "com.ivac.otpforwarder.ACTION_SMS_STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: ""
                    val detail = intent.getStringExtra("detail") ?: ""
                    updateForwardStatusUI(status, detail)
                    loadSmsHistoryFromPrefs()
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 1001
        private const val SERVER_BASE_URL = "https://ivac-appointment-assistant.ai.studio"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ivac_otp_prefs", Context.MODE_PRIVATE)

        // View bindings
        etSim1Phone = findViewById(R.id.etSim1Phone)
        etSim2Phone = findViewById(R.id.etSim2Phone)
        tvSim1Status = findViewById(R.id.tvSim1Status)
        tvSim2Status = findViewById(R.id.tvSim2Status)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnSave = findViewById(R.id.btnSave)
        btnRequestBattery = findViewById(R.id.btnRequestBattery)
        llActivityContainer = findViewById(R.id.llActivityContainer)
        tvNoActivityPlaceholder = findViewById(R.id.tvNoActivityPlaceholder)
        tvLiveIndicator = findViewById(R.id.tvLiveIndicator)

        // Device ID & Pairing view bindings
        tvDeviceIdBadge = findViewById(R.id.tvDeviceIdBadge)
        tvDeviceBatteryBadge = findViewById(R.id.tvDeviceBatteryBadge)
        tvPairingStatusBadge = findViewById(R.id.tvPairingStatusBadge)
        tvDeviceHeartbeatStatus = findViewById(R.id.tvDeviceHeartbeatStatus)

        // Live SMS Detection view bindings
        tvSmsStatusBadge = findViewById(R.id.tvSmsStatusBadge)
        tvSmsDetectionSummary = findViewById(R.id.tvSmsDetectionSummary)
        tvSmsMeta = findViewById(R.id.tvSmsMeta)
        tvSmsOtpCode = findViewById(R.id.tvSmsOtpCode)
        btnCopyOtp = findViewById(R.id.btnCopyOtp)
        tvSmsForwardStatus = findViewById(R.id.tvSmsForwardStatus)
        tvSmsFullBody = findViewById(R.id.tvSmsFullBody)
        btnCheckInbox = findViewById(R.id.btnCheckInbox)
        btnSimulateTestSms = findViewById(R.id.btnSimulateTestSms)

        // History view bindings
        llSmsHistoryContainer = findViewById(R.id.llSmsHistoryContainer)
        tvNoHistoryPlaceholder = findViewById(R.id.tvNoHistoryPlaceholder)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        // Permission Manager views
        cardPermissionAlert = findViewById(R.id.cardPermissionAlert)
        tvPermissionIcon = findViewById(R.id.tvPermissionIcon)
        tvPermissionTitle = findViewById(R.id.tvPermissionTitle)
        tvPermissionBadge = findViewById(R.id.tvPermissionBadge)
        tvPermissionDesc = findViewById(R.id.tvPermissionDesc)
        llPermissionButtons = findViewById(R.id.llPermissionButtons)
        btnOpenAppSettings = findViewById(R.id.btnOpenAppSettings)
        btnShowPermissionGuide = findViewById(R.id.btnShowPermissionGuide)
        btnRequestPermissionsDirect = findViewById(R.id.btnRequestPermissionsDirect)

        btnOpenAppSettings.setOnClickListener {
            openAppSettings()
        }

        btnShowPermissionGuide.setOnClickListener {
            showPermissionHelpDialog()
        }

        btnRequestPermissionsDirect.setOnClickListener {
            checkAndRequestPermissions()
        }

        // Load saved phone numbers & server URL
        etServerUrl = findViewById(R.id.etServerUrl)
        btnTestServerConnection = findViewById(R.id.btnTestServerConnection)
        btnSendTestOtpDirect = findViewById(R.id.btnSendTestOtpDirect)

        etSim1Phone.setText(prefs.getString("sim1_number", ""))
        etSim2Phone.setText(prefs.getString("sim2_number", ""))
        etServerUrl.setText(prefs.getString("server_url", SERVER_BASE_URL))

        btnTestServerConnection.setOnClickListener {
            testServerConnection()
        }

        btnSendTestOtpDirect.setOnClickListener {
            simulateTestSmsArrival()
        }

        // Event listeners
        btnSave.setOnClickListener {
            val s1 = etSim1Phone.text.toString().trim()
            val s2 = etSim2Phone.text.toString().trim()
            val srv = etServerUrl.text.toString().trim().ifBlank { SERVER_BASE_URL }.trimEnd('/')

            prefs.edit()
                .putString("sim1_number", s1)
                .putString("sim2_number", s2)
                .putString("server_url", srv)
                .apply()

            Toast.makeText(this, "সিম নম্বর ও সার্ভার লিঙ্ক সফলভাবে সংরক্ষণ করা হয়েছে!", Toast.LENGTH_SHORT).show()
            testServerConnection()
            fetchRecentActivities()
        }

        btnCopyOtp.setOnClickListener {
            val code = tvSmsOtpCode.text.toString().trim()
            if (code.isNotBlank() && code != "------" && !code.contains("⚠️")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("IVAC OTP", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 ওটিপি কোড [$code] কপি করা হয়েছে!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "কপি করার মতো কোনো ওটিপি কোড নেই", Toast.LENGTH_SHORT).show()
            }
        }

        btnCheckInbox.setOnClickListener {
            checkInboxForLatestIvacSms()
        }

        btnSimulateTestSms.setOnClickListener {
            simulateTestSmsArrival()
        }

        btnClearHistory.setOnClickListener {
            prefs.edit().putString("sms_history_json", "[]").apply()
            loadSmsHistoryFromPrefs()
            Toast.makeText(this, "হিস্ট্রি মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
        }

        btnRequestBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // Initialize features
        checkAndRefreshPermissionsUI()
        checkAndRequestPermissions()
        detectSimSlots()
        startForwarderService()
        startActivityPolling()
        loadLatestSmsFromPrefs()
        loadSmsHistoryFromPrefs()
        registerDeviceWithServer()
    }

    override fun onResume() {
        super.onResume()
        checkAndRefreshPermissionsUI()
        detectSimSlots()
        loadLatestSmsFromPrefs()
        loadSmsHistoryFromPrefs()
        registerDeviceWithServer()

        val filter = IntentFilter().apply {
            addAction("com.ivac.otpforwarder.ACTION_SMS_CAPTURED")
            addAction("com.ivac.otpforwarder.ACTION_SMS_STATUS_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, smsBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsBroadcastReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(smsBroadcastReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollTimer?.cancel()
    }

    private fun loadLatestSmsFromPrefs() {
        val smsText = prefs.getString("last_sms_text", null)
        if (smsText.isNullOrBlank()) {
            tvSmsStatusBadge.text = "⏳ অপেক্ষমাণ"
            tvSmsStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsDetectionSummary.text = "মেসেজ ডিটেকশন ইঞ্জিন প্রস্তুত। আইভ্যাক বা এক্সটেনশন থেকে মেসেজ আসলে স্বয়ংক্রিয়ভাবে এখানে দেখা যাবে।"
            tvSmsDetectionSummary.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsMeta.text = "📶 সিম: রেডি • প্রেরক: অপেক্ষমাণ • রিয়েল-টাইম ডিটেকশন সক্রিয়"
            tvSmsOtpCode.text = "------"
            tvSmsOtpCode.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsForwardStatus.text = "সার্ভার স্ট্যাটাস: রেডি (অপেক্ষমাণ)"
            tvSmsForwardStatus.setTextColor(Color.parseColor("#94A3B8"))
            tvSmsFullBody.text = "এখনও কোনো এসএমএস আসেনি। নতুন এসএমএস এলে পুরো বার্তাটি এখানে তাৎক্ষণিক দেখা যাবে..."
            return
        }

        val sender = prefs.getString("last_sms_sender", "অজ্ঞাত") ?: "অজ্ঞাত"
        val simSlot = prefs.getInt("last_sms_sim_slot", 1)
        val otpCode = prefs.getString("last_sms_otp", "") ?: ""
        val timestamp = prefs.getLong("last_sms_timestamp", System.currentTimeMillis())
        val status = prefs.getString("last_sms_forward_status", "SUCCESS") ?: "SUCCESS"
        val detail = prefs.getString("last_sms_status_detail", "সফল") ?: "সফল"

        updateCapturedSmsUI(smsText, sender, simSlot, otpCode, timestamp, status, detail)
    }

    private fun updateCapturedSmsUI(
        smsText: String,
        sender: String,
        simSlot: Int,
        otpCode: String,
        timestamp: Long,
        status: String,
        detail: String
    ) {
        val sdf = SimpleDateFormat("hh:mm:ss a, dd MMM", Locale.getDefault())
        val timeStr = sdf.format(Date(timestamp))

        val senderDisplay = if (sender.isNotBlank()) sender else "IVAC"
        val isIvacRelated = smsText.contains("ivac", ignoreCase = true) ||
                smsText.contains("visa", ignoreCase = true) ||
                sender.contains("ivac", ignoreCase = true)

        if (otpCode.isNotBlank()) {
            if (isIvacRelated) {
                tvSmsStatusBadge.text = "🟢 আইভ্যাক ওটিপি ডিটেক্টেড"
                tvSmsStatusBadge.setTextColor(Color.parseColor("#34D399"))
                tvSmsDetectionSummary.text = "✅ আইভ্যাকের ওটিপি মেসেজ সফলভাবে সনাক্ত ও ক্যাপচার করা হয়েছে!"
                tvSmsDetectionSummary.setTextColor(Color.parseColor("#34D399"))
            } else {
                tvSmsStatusBadge.text = "🟢 ওটিপি সনাক্ত হয়েছে"
                tvSmsStatusBadge.setTextColor(Color.parseColor("#38BDF8"))
                tvSmsDetectionSummary.text = "✅ মেসেজ থেকে ওটিপি কোড সফলভাবে ডিটেক্ট করা হয়েছে!"
                tvSmsDetectionSummary.setTextColor(Color.parseColor("#38BDF8"))
            }

            tvSmsOtpCode.text = otpCode
            tvSmsOtpCode.setTextColor(Color.parseColor("#34D399"))
        } else {
            tvSmsStatusBadge.text = "⚠️ ওটিপি বিহীন মেসেজ"
            tvSmsStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
            tvSmsDetectionSummary.text = "⚠️ মেসেজ রিসিভ হয়েছে, তবে এতে কোনো ওটিপি কোড সনাক্ত করা যায়নি।"
            tvSmsDetectionSummary.setTextColor(Color.parseColor("#F59E0B"))

            tvSmsOtpCode.text = "⚠️ কোড নেই"
            tvSmsOtpCode.setTextColor(Color.parseColor("#F59E0B"))
        }

        tvSmsMeta.text = "📶 সিম $simSlot • প্রেরক: $senderDisplay • সময়: $timeStr"
        tvSmsFullBody.text = smsText

        updateForwardStatusUI(status, detail)
    }

    private fun updateForwardStatusUI(status: String, detail: String) {
        when (status) {
            "SUCCESS" -> {
                tvSmsForwardStatus.text = "✅ ক্লাউড সার্ভারে পৌঁছেছে ➔ ব্রাউজার এক্সটেনশন অটোফিলে প্রস্তুত ($detail)"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#A7F3D0"))
            }
            "SENDING" -> {
                tvSmsForwardStatus.text = "⏳ সার্ভারে পাঠানো হচ্ছে... ($detail)"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#FDE68A"))
            }
            "FAILED" -> {
                tvSmsForwardStatus.text = "❌ সার্ভারে পাঠাতে ব্যর্থ: $detail"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#FCA5A5"))
            }
            "NO_OTP" -> {
                tvSmsForwardStatus.text = "⚠️ ওটিপি কোড না থাকায় সার্ভারে ফরোয়ার্ড করা হয়নি"
                tvSmsForwardStatus.setTextColor(Color.parseColor("#FCD34D"))
            }
            else -> {
                tvSmsForwardStatus.text = detail.ifBlank { "স্ট্যাটাস: $status" }
                tvSmsForwardStatus.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }
    }

    /**
     * Checks phone's SMS Inbox directly using ContentResolver.
     * Guarantees detection even if phone was asleep or background receiver had OEM delay!
     */
    private fun checkInboxForLatestIvacSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), PERMISSION_REQ_CODE)
            Toast.makeText(this, "মেসেজ পড়ার জন্য READ_SMS পারমিশন প্রয়োজন", Toast.LENGTH_SHORT).show()
            return
        }

        var cursor: Cursor? = null
        try {
            val inboxUri = Uri.parse("content://sms/inbox")
            cursor = contentResolver.query(
                inboxUri,
                arrayOf("_id", "address", "body", "date", "sub_id"),
                null,
                null,
                "date DESC LIMIT 10"
            )

            if (cursor != null && cursor.moveToFirst()) {
                var foundCandidate: Boolean = false

                do {
                    val address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: ""
                    val body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: ""
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))

                    // 🔒 STRICT IVAC FILTER: Check using SmsOtpReceiver.isIvacMessage
                    val isIvac = SmsOtpReceiver.isIvacMessage(address, body)

                    if (isIvac) {
                        val otp = SmsOtpReceiver.extractOtpCode(body)
                        foundCandidate = true

                        // Save and update UI
                        val simSlot = 1
                        val timeNow = if (date > 0) date else System.currentTimeMillis()
                        val initStatus = if (!otp.isNullOrBlank()) "SENDING" else "NO_OTP"

                        prefs.edit()
                            .putString("last_sms_text", body)
                            .putString("last_sms_sender", address)
                            .putInt("last_sms_sim_slot", simSlot)
                            .putString("last_sms_otp", otp ?: "")
                            .putLong("last_sms_timestamp", timeNow)
                            .putString("last_sms_forward_status", initStatus)
                            .putString("last_sms_status_detail", "আইভ্যাক ইনবক্স থেকে পাওয়া গেছে")
                            .apply()

                        SmsOtpReceiver.saveSmsToHistory(this, body, address, simSlot, otp ?: "", timeNow, initStatus)

                        updateCapturedSmsUI(body, address, simSlot, otp ?: "", timeNow, initStatus, "আইভ্যাক ইনবক্স থেকে ডিটেক্টেড")
                        loadSmsHistoryFromPrefs()

                        if (!otp.isNullOrBlank()) {
                            val targetPhone = prefs.getString("sim1_number", "auto")?.ifBlank { "auto" } ?: "auto"
                            forwardOtpManually(targetPhone, otp, simSlot, address, body)
                        }

                        Toast.makeText(this, "✅ ইনবক্স থেকে সর্বশেষ IVAC ওটিপি বার্তা পাওয়া গেছে!", Toast.LENGTH_SHORT).show()
                        break
                    }
                } while (cursor.moveToNext())

                if (!foundCandidate) {
                    Toast.makeText(this, "ইনবক্সে কোনো IVAC ওটিপি মেসেজ মেলেনি। অন্যান্য সব ব্যক্তিগত বার্তা ফিল্টার করা হয়েছে।", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "ইনবক্সে কোনো এসএমএস খুঁজে পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query SMS inbox", e)
            Toast.makeText(this, "ইনবক্স চেক ত্রুটি: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        } finally {
            cursor?.close()
        }
    }

    private fun loadSmsHistoryFromPrefs() {
        val historyJson = prefs.getString("sms_history_json", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(historyJson)
            llSmsHistoryContainer.removeAllViews()

            if (jsonArray.length() == 0) {
                tvNoHistoryPlaceholder.visibility = View.VISIBLE
                llSmsHistoryContainer.addView(tvNoHistoryPlaceholder)
                return
            }

            tvNoHistoryPlaceholder.visibility = View.GONE
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val smsText = item.optString("smsText", "")
                val sender = item.optString("sender", "IVAC")
                val simSlot = item.optInt("simSlot", 1)
                val otpCode = item.optString("otpCode", "")
                val timestamp = item.optLong("timestamp", System.currentTimeMillis())
                val timeStr = sdf.format(Date(timestamp))

                val itemView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 10, 12, 10)
                    setBackgroundColor(Color.parseColor(if (i % 2 == 0) "#0F172A" else "#131E35"))
                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        updateCapturedSmsUI(smsText, sender, simSlot, otpCode, timestamp, "SUCCESS", "হিস্ট্রি থেকে দেখা হচ্ছে")
                        Toast.makeText(this@MainActivity, "মেসেজটি বিস্তারিত স্ক্রিনে লোড করা হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                }

                val rowHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val tvItemTitle = TextView(this).apply {
                    text = "📶 সিম $simSlot • $sender"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvItemTime = TextView(this).apply {
                    text = timeStr
                    textSize = 10f
                    setTextColor(Color.parseColor("#94A3B8"))
                }

                rowHeader.addView(tvItemTitle)
                rowHeader.addView(tvItemTime)
                itemView.addView(rowHeader)

                val tvItemOtp = TextView(this).apply {
                    text = if (otpCode.isNotBlank()) "🔑 OTP: $otpCode" else "ℹ️ সাধারণ বার্তা"
                    textSize = 11f
                    setTextColor(Color.parseColor(if (otpCode.isNotBlank()) "#34D399" else "#94A3B8"))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 4, 0, 2)
                }
                itemView.addView(tvItemOtp)

                val tvItemSnippet = TextView(this).apply {
                    text = if (smsText.length > 70) smsText.substring(0, 70) + "..." else smsText
                    textSize = 11f
                    setTextColor(Color.parseColor("#CBD5E1"))
                }
                itemView.addView(tvItemSnippet)

                llSmsHistoryContainer.addView(itemView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying SMS history", e)
        }
    }

    private fun simulateTestSmsArrival() {
        val wordList = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine")
        val digitList = (1..6).map { (0..9).random() }
        val testOtp = digitList.joinToString("")
        val testWords = digitList.map { wordList[it] }.joinToString("-")
        val testSender = "IVACBD"
        val testMsg = "(IVACBD) For security, type the following sequence when prompted $testWords"
        val timeNow = System.currentTimeMillis()

        prefs.edit()
            .putString("last_sms_text", testMsg)
            .putString("last_sms_sender", testSender)
            .putInt("last_sms_sim_slot", 1)
            .putString("last_sms_otp", testOtp)
            .putLong("last_sms_timestamp", timeNow)
            .putString("last_sms_forward_status", "SENDING")
            .putString("last_sms_status_detail", "টেস্ট ওটিপি পুশ হচ্ছে...")
            .apply()

        SmsOtpReceiver.saveSmsToHistory(this, testMsg, testSender, 1, testOtp, timeNow, "SENDING")

        updateCapturedSmsUI(testMsg, testSender, 1, testOtp, timeNow, "SENDING", "টেস্ট ওটিপি পুশ হচ্ছে...")
        loadSmsHistoryFromPrefs()

        val s1 = prefs.getString("sim1_number", "")?.trim() ?: ""
        val s2 = prefs.getString("sim2_number", "")?.trim() ?: ""
        val targetPhone = if (s1.isNotBlank()) s1 else if (s2.isNotBlank()) s2 else "auto"
        val targetSlot = if (s1.isNotBlank()) 1 else if (s2.isNotBlank()) 2 else 1

        Toast.makeText(this, "🧪 টেস্ট মেসেজ! ওটিপি: $testOtp (সিম $targetSlot: $targetPhone)", Toast.LENGTH_SHORT).show()
        forwardOtpManually(targetPhone, testOtp, targetSlot, testSender, testMsg)
    }

    private fun getPushUrl(): String {
        val raw = prefs.getString("server_url", SERVER_BASE_URL)?.trim()?.trimEnd('/') ?: SERVER_BASE_URL
        return if (raw.endsWith("/api/otp/push")) raw else "$raw/api/otp/push"
    }

    private fun getRegisterUrl(): String {
        val raw = prefs.getString("server_url", SERVER_BASE_URL)?.trim()?.trimEnd('/') ?: SERVER_BASE_URL
        return if (raw.contains("/api/")) {
            raw.substringBefore("/api/") + "/api/device/register"
        } else {
            "$raw/api/device/register"
        }
    }

    private fun getPingUrl(): String {
        val raw = prefs.getString("server_url", SERVER_BASE_URL)?.trim()?.trimEnd('/') ?: SERVER_BASE_URL
        return if (raw.contains("/api/")) {
            raw.substringBefore("/api/") + "/api/device/ping"
        } else {
            "$raw/api/device/ping"
        }
    }

    private fun getActivityUrl(): String {
        val raw = prefs.getString("server_url", SERVER_BASE_URL)?.trim()?.trimEnd('/') ?: SERVER_BASE_URL
        return if (raw.contains("/api/")) {
            raw.substringBefore("/api/") + "/api/activity/history"
        } else {
            "$raw/api/activity/history"
        }
    }

    private fun getOrCreateDeviceId(): String {
        var devId = prefs.getString("device_id", "") ?: ""
        if (devId.isBlank()) {
            val randomSuffix = (1000 + (Math.random() * 8999).toInt()).toString()
            val rawId = try {
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeLast(4)?.uppercase(Locale.ROOT) ?: "MOB"
            } catch (e: Exception) {
                "MOB"
            }
            devId = "APP-$rawId$randomSuffix"
            prefs.edit().putString("device_id", devId).apply()
        }
        return devId
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bStatus = registerReceiver(null, ifilter)
            val status = bStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            Pair(if (level >= 0) level else 85, isCharging)
        } catch (e: Exception) {
            Pair(85, false)
        }
    }

    private fun registerDeviceWithServer(showFeedbackToast: Boolean = false) {
        val devId = getOrCreateDeviceId()
        val (batLevel, isCharging) = getBatteryInfo()
        val s1 = prefs.getString("sim1_number", "")?.trim() ?: ""
        val s2 = prefs.getString("sim2_number", "")?.trim() ?: ""
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"

        runOnUiThread {
            tvDeviceIdBadge.text = "📱 আইডি: $devId"
            tvDeviceBatteryBadge.text = "🔋 $batLevel%${if (isCharging) " ⚡" else ""}"
        }

        val json = JSONObject().apply {
            put("deviceId", devId)
            put("model", model)
            if (s1.isNotBlank()) put("sim1Number", s1)
            if (s2.isNotBlank()) put("sim2Number", s2)
            put("sim1Carrier", tvSim1Status.text.toString())
            put("sim2Carrier", tvSim2Status.text.toString())
            put("batteryLevel", batLevel)
            put("isCharging", isCharging)
            put("status", "ONLINE")
        }

        val registerUrl = getRegisterUrl()
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(registerUrl).post(body).build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvDeviceHeartbeatStatus.text = "ডাটাবেজ: সংযোগ ব্যর্থ"
                    tvDeviceHeartbeatStatus.setTextColor(Color.parseColor("#FCA5A5"))
                    if (showFeedbackToast) {
                        Toast.makeText(this@MainActivity, "⚠️ সার্ভার সংযোগ ত্রুটি: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val resStr = response.body?.string() ?: ""
                        var pairedCount = 0
                        try {
                            if (resStr.isNotBlank()) {
                                val resJson = JSONObject(resStr)
                                pairedCount = resJson.optInt("activePairedSessions", 0)
                            }
                        } catch (e: Exception) {}

                        runOnUiThread {
                            tvDeviceHeartbeatStatus.text = "ডাটাবেজ: অনলাইন 🟢"
                            tvDeviceHeartbeatStatus.setTextColor(Color.parseColor("#34D399"))
                            if (pairedCount > 0) {
                                tvPairingStatusBadge.text = "🔗 $pairedCount টি এক্সটেনশনের সাথে পেয়ার্ড"
                                tvPairingStatusBadge.setTextColor(Color.parseColor("#34D399"))
                                tvPairingStatusBadge.setBackgroundColor(Color.parseColor("#064E3B"))
                            } else {
                                tvPairingStatusBadge.text = "⏳ এক্সটেনশন পেয়ারিংয়ের অপেক্ষায়"
                                tvPairingStatusBadge.setTextColor(Color.parseColor("#93C5FD"))
                                tvPairingStatusBadge.setBackgroundColor(Color.parseColor("#1E293B"))
                            }
                            if (showFeedbackToast) {
                                Toast.makeText(this@MainActivity, "✅ সার্ভার ও ডাটাবেজ সংযোগ সফল! আইডি: $devId", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            tvDeviceHeartbeatStatus.text = "ডাটাবেজ: ত্রুটি (${response.code})"
                            tvDeviceHeartbeatStatus.setTextColor(Color.parseColor("#FCA5A5"))
                            if (showFeedbackToast) {
                                Toast.makeText(this@MainActivity, "সার্ভার এরর HTTP ${response.code}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        })
    }

    private fun testServerConnection() {
        Toast.makeText(this, "সার্ভার কানেকশন টেস্ট করা হচ্ছে...", Toast.LENGTH_SHORT).show()
        registerDeviceWithServer(showFeedbackToast = true)
        fetchRecentActivities()
    }

    private fun forwardOtpManually(
        targetPhone: String,
        otpCode: String,
        simSlot: Int,
        sender: String,
        fullMsg: String
    ) {
        val pushUrl = getPushUrl()
        val devId = getOrCreateDeviceId()

        val json = JSONObject().apply {
            put("deviceId", devId)
            put("phoneNumber", targetPhone)
            put("otpCode", otpCode)
            put("simSlot", simSlot)
            put("simName", if (simSlot == 2) "SIM 2" else "SIM 1")
            put("rawSender", sender)
            put("rawText", fullMsg)
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(pushUrl).post(requestBody).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    updateForwardStatusUI("FAILED", e.localizedMessage ?: "নেটওয়ার্ক কানেকশন ফেইল্ড")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    var statusDetail = "HTTP ${response.code} (সফল)"
                    if (response.isSuccessful) {
                        try {
                            val resBody = response.body?.string() ?: ""
                            if (resBody.isNotBlank()) {
                                val jsonRes = JSONObject(resBody)
                                val matchedTicket = jsonRes.optJSONObject("matchedTicket")
                                if (matchedTicket != null) {
                                    val extId = matchedTicket.optString("extensionId", "")
                                    if (extId.isNotBlank()) {
                                        statusDetail = "🎯 এক্সটেনশন $extId-এ ওটিপি সফলভাবে পৌঁছেছে ও অটোফিল হচ্ছে!"
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                        runOnUiThread {
                            updateForwardStatusUI("SUCCESS", statusDetail)
                        }
                    } else {
                        runOnUiThread {
                            updateForwardStatusUI("FAILED", "HTTP ${response.code} সার্ভার ত্রুটি")
                        }
                    }
                }
            }
        })
    }

    private var heartbeatCounter = 0
    private fun startActivityPolling() {
        pollTimer?.cancel()
        pollTimer = Timer()
        pollTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                fetchRecentActivities()
                heartbeatCounter++
                if (heartbeatCounter % 12 == 0) { // every ~42 seconds
                    registerDeviceWithServer()
                }
            }
        }, 1000, 3500)
    }

    private fun fetchRecentActivities() {
        val url = getActivityUrl()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvLiveIndicator.text = "🟡 RECONNECTING"
                    tvLiveIndicator.setTextColor(Color.parseColor("#FBBF24"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return
                        try {
                            val trimmed = body.trim()
                            val jsonArray = if (trimmed.startsWith("[")) {
                                JSONArray(trimmed)
                            } else {
                                val obj = JSONObject(trimmed)
                                obj.optJSONArray("activities") ?: JSONArray()
                            }
                            runOnUiThread {
                                tvLiveIndicator.text = "🟢 LIVE"
                                tvLiveIndicator.setTextColor(Color.parseColor("#34D399"))
                                updateActivityUI(jsonArray)
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        })
    }

    private fun updateActivityUI(activities: JSONArray) {
        if (activities.length() == 0) {
            tvNoActivityPlaceholder.visibility = View.VISIBLE
            return
        }

        tvNoActivityPlaceholder.visibility = View.GONE
        llActivityContainer.removeAllViews()

        val maxItems = 4
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

        for (i in 0 until minOf(activities.length(), maxItems)) {
            val act = activities.getJSONObject(i)
            val type = act.optString("type", "INFO")
            val title = act.optString("title", "অ্যাক্টিভিটি")
            val message = act.optString("message", "")
            val timestamp = act.optLong("timestamp", System.currentTimeMillis())
            val phone = act.optString("phone", "")
            val timeStr = sdf.format(Date(timestamp))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(Color.parseColor(if (i % 2 == 0) "#0F172A" else "#131E35"))
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val tvTitle = TextView(this).apply {
                text = title
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val tColor = when (type) {
                    "LOGIN_SUCCESS" -> "#34D399"
                    "WAITING_OTP" -> "#FCD34D"
                    "FILE_UPLOADED" -> "#A5B4FC"
                    "SLOT_RELEASED" -> "#6EE7B7"
                    "PAYMENT_INITIATED" -> "#FDBA74"
                    else -> "#93C5FD"
                }
                setTextColor(Color.parseColor(tColor))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTime = TextView(this).apply {
                text = timeStr
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
            }

            headerRow.addView(tvTitle)
            headerRow.addView(tvTime)
            card.addView(headerRow)

            val tvMsg = TextView(this).apply {
                text = message + (if (phone.isNotBlank()) " • $phone" else "")
                textSize = 11f
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(0, 4, 0, 0)
            }
            card.addView(tvMsg)

            llActivityContainer.addView(card)
        }
    }

    private fun checkAndRefreshPermissionsUI() {
        val hasReceiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasReadSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        val isSmsReady = hasReceiveSms && hasReadSms

        if (isSmsReady) {
            // GREEN STATE - PERMISSIONS OK
            cardPermissionAlert.setCardBackgroundColor(Color.parseColor("#064E3B"))
            cardPermissionAlert.strokeColor = Color.parseColor("#10B981")
            tvPermissionIcon.text = "✅"
            tvPermissionTitle.text = "SMS পারমিশন সক্রিয় • ওটিপি প্রস্তুত"
            tvPermissionTitle.setTextColor(Color.parseColor("#34D399"))
            tvPermissionBadge.text = "সক্রিয় (READY)"
            tvPermissionBadge.setBackgroundColor(Color.parseColor("#047857"))
            tvPermissionBadge.setTextColor(Color.parseColor("#D1FAE5"))
            tvPermissionDesc.text = "সিম ১ ও সিম ২ এর সমস্ত ওটিপি মেসেজ স্বয়ংক্রিয়ভাবে ডিটেক্ট হবে এবং ব্রাউজার এক্সটেনশনে ফরোয়ার্ড করা হবে।"
            tvPermissionDesc.setTextColor(Color.parseColor("#A7F3D0"))
            llPermissionButtons.visibility = View.GONE
            btnRequestPermissionsDirect.visibility = View.GONE
        } else {
            // RED WARNING STATE - PERMISSION BLOCKED / NOT GRANTED
            cardPermissionAlert.setCardBackgroundColor(Color.parseColor("#450A0A"))
            cardPermissionAlert.strokeColor = Color.parseColor("#EF4444")
            tvPermissionIcon.text = "🚨"
            tvPermissionTitle.text = "SMS পারমিশন বন্ধ আছে! মেসেজ ডিটেক্ট হচ্ছে না"
            tvPermissionTitle.setTextColor(Color.parseColor("#FCA5A5"))
            tvPermissionBadge.text = "অ্যাকশন প্রয়োজন"
            tvPermissionBadge.setBackgroundColor(Color.parseColor("#991B1B"))
            tvPermissionBadge.setTextColor(Color.parseColor("#FEE2E2"))
            tvPermissionDesc.text = "অ্যান্ড্রয়েড ১৩/১৪ বা শাওমি ফোনে অ্যাপ সরাসরি ইনস্টল করায় SMS পারমিশন ব্লক রয়েছে। ওটিপি রিসিভ করতে নিচের 'সেটিংস খুলুন' থেকে 'Allow restricted settings' বা SMS এলাও করুন।"
            tvPermissionDesc.setTextColor(Color.parseColor("#FECACA"))
            llPermissionButtons.visibility = View.VISIBLE
            btnRequestPermissionsDirect.visibility = View.VISIBLE
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "অ্যাপ তথ্যের উপরে ৩টি ডটে (⋮) চেপে 'Allow restricted settings' করুন", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "সেটিংস খুলতে ত্রুটি: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermissionHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📱 SMS পারমিশন যেভাবে এলাও করবেন")
            .setMessage(
                "অ্যান্ড্রয়েড ১৩/১৪ বা শাওমি ফোনে সরাসরি এপিকে (APK) ইনস্টল করলে গুগল নিরাপত্তার স্বার্থে SMS পারমিশন সাময়িকভাবে ব্লক (Restricted) করে রাখে।\n\n" +
                "👉 অ্যান্ড্রয়েড ১৩ ও ১৪ ব্যবহারকারীরা:\n" +
                "১. '⚙️ সেটিংস খুলুন' বাটনে চাপ দিন (অথবা Settings > Apps > UDC SMS Assistant এ যান)।\n" +
                "২. স্ক্রিনের উপরের ডানদিকের ৩টি ডটে (⋮) ট্যাপ করুন।\n" +
                "৩. 'Allow restricted settings' (সীমাবদ্ধ সেটিংস অনুমোদন করুন) চাপুন এবং ফোনের লক পিন বা ফিঙ্গারপ্রিন্ট দিন।\n" +
                "৪. এবার Permissions > SMS এ ক্লিক করে 'Allow' (অনুমোদন করুন) সিলেক্ট করুন।\n\n" +
                "👉 শাওমি / রেডমি / পোকো (MIUI / HyperOS):\n" +
                "১. App info > 'Other permissions' (অন্যান্য অনুমতি) তে যান।\n" +
                "২. 'Service SMS' কে 'Always allow' (সবসময় অনুমোদন দিন) করুন।\n" +
                "৩. 'Start in background' এলাও করুন এবং 'Autostart' চালু রাখুন।\n\n" +
                "👉 স্যামসাং বা অন্য ফোনে:\n" +
                "Permissions > SMS > 'Allow' সিলেক্ট করুন।\n\n" +
                "কাজ শেষ করে এই অ্যাপে ফিরে আসলেই পারমিশন স্বয়ংক্রিয়ভাবে সক্রিয় হয়ে যাবে!"
            )
            .setPositiveButton("⚙️ এখনই অ্যাপ সেটিংস খুলুন") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("বুঝেছি", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkAndRefreshPermissionsUI()
        detectSimSlots()

        val isSmsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (isSmsGranted) {
            Toast.makeText(this, "✅ SMS পারমিশন সফলভাবে চালু হয়েছে! ওটিপি ডিটেক্টর সক্রিয়।", Toast.LENGTH_SHORT).show()
            startForwarderService()
            checkInboxForLatestIvacSms()
        } else {
            Toast.makeText(this, "⚠️ পারমিশন মঞ্জুর হয়নি। 'সমাধান গাইড' দেখে Restricted settings আনলক করুন।", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), PERMISSION_REQ_CODE)
        }
    }

    private fun detectSimSlots() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val subManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subList: List<SubscriptionInfo>? = subManager.activeSubscriptionInfoList

            if (!subList.isNullOrEmpty()) {
                for (info in subList) {
                    val slot = info.simSlotIndex + 1
                    val carrier = info.carrierName ?: "Carrier"
                    if (slot == 1) {
                        tvSim1Status.text = "📶 সিম ১ সক্রিয় ($carrier)"
                    } else if (slot == 2) {
                        tvSim2Status.text = "📶 সিম ২ সক্রিয় ($carrier)"
                    }
                }
            }
        } catch (e: SecurityException) {
            // handle
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "ব্যাটারি অপ্টিমাইজেশন ইতিমধ্যে বন্ধ রয়েছে (Unrestricted)!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startForwarderService() {
        val serviceIntent = Intent(this, OtpForwarderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        tvServiceStatus.text = "UDC SMS Assistant: সার্বক্ষণিক সক্রিয় (Active)"
    }
}
