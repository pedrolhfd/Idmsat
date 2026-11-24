package com.idm.saturator

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "idm_saturator_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_THREADS = "EXTRA_THREADS"

        val isRunning = AtomicBoolean(false)
        val totalBytes = AtomicLong(0L)
        val currentSpeedBps = AtomicLong(0L)
        val peakSpeedBps = AtomicLong(0L)
        var startTimeMs: Long = 0L
        var activeThreadsCount: Int = 6
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val servers = listOf(
        "https://speed.cloudflare.com/__down?bytes=100000000",
        "https://proof.ovh.net/files/100Mb.dat",
        "https://speedtest.tele2.net/100MB.zip",
        "https://cachefly.cachefly.net/100mb.test",
        "https://ash-speed.hetzner.com/100MB.bin"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSaturation()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                activeThreadsCount = intent.getIntExtra(EXTRA_THREADS, 6)
                if (!isRunning.get()) {
                    startSaturation()
                }
            }
        }
        return START_STICKY
    }

    private fun startSaturation() {
        isRunning.set(true)
        totalBytes.set(0L)
        currentSpeedBps.set(0L)
        peakSpeedBps.set(0L)
        startTimeMs = System.currentTimeMillis()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IDMSaturator::DownloadLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        startForeground(NOTIF_ID, buildNotification("جاري بدء السحب بأقصى سرعة...", "0.00 Mo/s | 0.0 Mbps"))

        val pool = Executors.newCachedThreadPool()
        for (i in 0 until activeThreadsCount) {
            pool.execute { workerLoop(i) }
        }

        // Speed & Notification updater thread
        pool.execute {
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            while (isRunning.get()) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
                if (!isRunning.get()) break

                val now = System.currentTimeMillis()
                val currentTotal = totalBytes.get()
                val diffBytes = currentTotal - lastBytes
                val diffSec = (now - lastTime) / 1000.0

                if (diffSec > 0) {
                    val bytesPerSec = (diffBytes / diffSec).toLong()
                    currentSpeedBps.set(bytesPerSec)
                    if (bytesPerSec > peakSpeedBps.get()) {
                        peakSpeedBps.set(bytesPerSec)
                    }

                    val moPerSec = bytesPerSec / (1024.0 * 1024.0)
                    val mbps = (bytesPerSec * 8.0) / 1_000_000.0
                    val totalGo = currentTotal / (1024.0 * 1024.0 * 1024.0)
                    val totalMo = currentTotal / (1024.0 * 1024.0)

                    val consumedStr = if (totalGo >= 1.0) {
                        String.format("%.2f Go", totalGo)
                    } else {
                        String.format("%.1f Mo", totalMo)
                    }

                    val title = String.format("السرعة: %.2f Mo/s (%.1f Mbps)", moPerSec, mbps)
                    val text = "الاستهلاك المطابق للروتر: $consumedStr • المسارات: $activeThreadsCount"
                    nm.notify(NOTIF_ID, buildNotification(title, text))
                }

                lastBytes = currentTotal
                lastTime = now
            }
        }
    }

    private fun workerLoop(workerIndex: Int) {
        var serverIdx = workerIndex % servers.size
        val buffer = ByteArray(65536) // 64KB chunk zero-RAM discard

        while (isRunning.get()) {
            val targetUrl = servers[serverIdx] + (if (servers[serverIdx].contains("?")) "&r=" else "?r=") + System.nanoTime()
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            try {
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-store, no-cache")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "IDM-Saturator-Android/1.0")
                }
                conn.connect()
                if (conn.responseCode in 200..299) {
                    input = conn.inputStream
                    while (isRunning.get()) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalBytes.addAndGet(read.toLong())
                    }
                } else {
                    serverIdx = (serverIdx + 1) % servers.size
                    Thread.sleep(300)
                }
            } catch (e: Exception) {
                serverIdx = (serverIdx + 1) % servers.size
                try { Thread.sleep(300) } catch (_: Exception) {}
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun stopSaturation() {
        isRunning.set(false)
        currentSpeedBps.set(0L)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف التحميل", pendingStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IDM Background Saturator",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار استمرار التحميل بأقصى سرعة في الخلفية"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSaturation()
        super.onDestroy()
    }
}
