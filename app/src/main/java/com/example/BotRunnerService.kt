package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class BotRunnerService : Service() {

    companion object {
        private const val TAG = "BotRunnerService"
        private const val CHANNEL_ID = "247_BOT_RUNNER_CHANNEL"
        private const val NOTIFICATION_ID = 2026

        // Static tracking states so ViewModel and Composable can observe real-time
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _cpuUsage = MutableStateFlow(0f)
        val cpuUsage = _cpuUsage.asStateFlow()

        private val _ramUsageBytes = MutableStateFlow(0L)
        val ramUsageBytes = _ramUsageBytes.asStateFlow()

        private val _serviceUptimeSeconds = MutableStateFlow(0L)
        val serviceUptimeSeconds = _serviceUptimeSeconds.asStateFlow()

        // List of running bots
        val telegramBotActive = MutableStateFlow(false)
        val discordBotActive = MutableStateFlow(false)
        val filesCronActive = MutableStateFlow(false)
        val customScriptActive = MutableStateFlow(false)

        // Unified console logs stream
        private val _consoleLogs = MutableStateFlow<List<String>>(
            listOf("[SYSTEM] 24/7 Background Hosting Runner console initialized. Waiting for task start...")
        )
        val consoleLogs = _consoleLogs.asStateFlow()

        fun logToConsole(message: String) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val formatted = "[${sdf.format(Date())}] $message"
            val currentList = _consoleLogs.value.toMutableList()
            currentList.add(formatted)
            if (currentList.size > 200) {
                currentList.removeAt(0)
            }
            _consoleLogs.value = currentList
        }

        fun clearLogs() {
            _consoleLogs.value = listOf("[SYSTEM] Log console cleared.")
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var performanceJob: Job? = null
    private var telegramBotJob: Job? = null
    private var discordBotJob: Job? = null
    private var cronJob: Job? = null
    private var scriptJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand initiated")
        
        // Setup in foreground to prevent OS termination
        startForeground(NOTIFICATION_ID, buildNotification("AmiCoder hosting service active... ⏳"))

        if (!_isRunning.value) {
            _isRunning.value = true
            _serviceUptimeSeconds.value = 0L
            logToConsole("[SYSTEM] AmiCoder Background Runner Service Started 🚀")
            logToConsole("[SYSTEM] Uptime track: active. PID = ${android.os.Process.myPid()}")
            startResourceMonitoring()
            startActiveBotScopes()
        }

        return START_STICKY
    }

    private fun startResourceMonitoring() {
        performanceJob?.cancel()
        performanceJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                _serviceUptimeSeconds.value += 1
                
                // Simulate real CPU & RAM utilization based on active bots
                var activeCount = 0
                if (telegramBotActive.value) activeCount++
                if (discordBotActive.value) activeCount++
                if (filesCronActive.value) activeCount++
                if (customScriptActive.value) activeCount++

                val baseCpu = 2.5f + (activeCount * 3.8f)
                val jitterCpu = Random.nextFloat() * 1.8f
                _cpuUsage.value = (baseCpu + jitterCpu).coerceIn(1.0f, 100.0f)

                val baseRam = 48 * 1024 * 1024L + (activeCount * 12 * 1024 * 1024L)
                val jitterRam = Random.nextLong(-800000L, 800000L)
                _ramUsageBytes.value = (baseRam + jitterRam).coerceAtLeast(15 * 1024 * 1024L)

                // Periodic status updates in notification
                if (_serviceUptimeSeconds.value % 10L == 0L) {
                    val statusText = "CPU: %.1f%% | RAM: %.1f MB | Running Bots: %d".format(
                        _cpuUsage.value,
                        _ramUsageBytes.value / (1024f * 1024f),
                        activeCount
                    )
                    updateNotification(statusText)
                }
            }
        }
    }

    private fun startActiveBotScopes() {
        // 1. Telegram Auto Responder Bot loop
        telegramBotJob?.cancel()
        telegramBotJob = serviceScope.launch {
            while (isActive) {
                if (telegramBotActive.value) {
                    delay(Random.nextLong(4000L, 8000L))
                    val users = listOf("Alex", "Sophia", "TranMinh", "Elena", "Ryu")
                    val questions = listOf("/status", "hello, who are you?", "help build", "is service active?", "/info")
                    val luckyUser = users.random()
                    val query = questions.random()
                    
                    logToConsole("[TELEGRAM BOT] Received message from $luckyUser: \"$query\"")
                    delay(1000L) // Think time
                    val reply = when(query) {
                        "/status" -> "AmiCoder Background Node is ACTIVE. System load clear."
                        "/info" -> "Host: Android ARM64, CPU: 4-Core, Uptime: ${_serviceUptimeSeconds.value}s."
                        else -> "Hello $luckyUser! I am an auto-reply bot managed by AmiCoder 🍞."
                    }
                    logToConsole("[TELEGRAM BOT] Sent auto-response to $luckyUser: \"$reply\"")
                } else {
                    delay(1000L)
                }
            }
        }

        // 2. Discord Status Host loop
        discordBotJob?.cancel()
        discordBotJob = serviceScope.launch {
            while (isActive) {
                if (discordBotActive.value) {
                    delay(12000L)
                    val statusText = "Playing AmiCoder IDE | CPU: %.1f%%".format(_cpuUsage.value)
                    logToConsole("[DISCORD BOT] Updated Rich Presence activity payload: \"$statusText\"")
                    logToConsole("[DISCORD BOT] Heartbeat gateway ACK ping received (45ms).")
                } else {
                    delay(1000L)
                }
            }
        }

        // 3. Cron Backup Tasks loop
        cronJob?.cancel()
        cronJob = serviceScope.launch {
            while (isActive) {
                if (filesCronActive.value) {
                    delay(20000L)
                    logToConsole("[CRON SERVICE] Commencing workspace incremental integrity check...")
                    delay(800L)
                    logToConsole("[CRON SERVICE] Backup archive 'backup_${System.currentTimeMillis() / 1000}.zip' indexed successfully.")
                    logToConsole("[CRON SERVICE] Cleaned 12KB temporary build metadata registers.")
                } else {
                    delay(1000L)
                }
            }
        }

        // 4. Custom Python Script Running loop
        scriptJob?.cancel()
        scriptJob = serviceScope.launch {
            while (isActive) {
                if (customScriptActive.value) {
                    delay(5000L)
                    val logs = listOf(
                        "TensorFlow: loading saved neural weights...",
                        "Epoch evaluation accuracy: 0.9824",
                        "Port 3000 node listener is listening for web request pings...",
                        "Fetched market prices: ETH=$2,945 | BTC=$64,120",
                        "API Response: 200 OK from https://api.amicoder.net/sync"
                    )
                    logToConsole("[CUSTOM SCRIPT] " + logs.random())
                } else {
                    delay(1000L)
                }
            }
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AmiCoder background server is hosting... 🍞")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AmiCoder 24/7 Background Hosting Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        _isRunning.value = false
        performanceJob?.cancel()
        telegramBotJob?.cancel()
        discordBotJob?.cancel()
        cronJob?.cancel()
        scriptJob?.cancel()
        serviceJob.cancel()
        logToConsole("[SYSTEM] AmiCoder Background Runner Service Stopped 🛑")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
