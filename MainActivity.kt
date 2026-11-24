package com.idm.saturator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvMoSpeed: TextView
    private lateinit var tvMbpsSpeed: TextView
    private lateinit var tvMaxMo: TextView
    private lateinit var tvMaxMbps: TextView
    private lateinit var tvTotalData: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvThreadsLabel: TextView
    private lateinit var seekThreads: SeekBar
    private lateinit var btnToggle: Button

    private val handler = Handler(Looper.getMainLooper())
    private var selectedThreads = 6

    private val uiUpdater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMoSpeed = findViewById(R.id.tvMoSpeed)
        tvMbpsSpeed = findViewById(R.id.tvMbpsSpeed)
        tvMaxMo = findViewById(R.id.tvMaxMo)
        tvMaxMbps = findViewById(R.id.tvMaxMbps)
        tvTotalData = findViewById(R.id.tvTotalData)
        tvElapsed = findViewById(R.id.tvElapsed)
        tvThreadsLabel = findViewById(R.id.tvThreadsLabel)
        seekThreads = findViewById(R.id.seekThreads)
        btnToggle = findViewById(R.id.btnToggle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        seekThreads.max = 14 // 2 to 16 threads
        seekThreads.progress = 4 // default 6
        seekThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedThreads = progress + 2
                tvThreadsLabel.text = "عدد المسارات المتوازية: $selectedThreads مسار"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            if (DownloadForegroundService.isRunning.get()) {
                val stopIntent = Intent(this, DownloadForegroundService::class.java).apply {
                    action = DownloadForegroundService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                val startIntent = Intent(this, DownloadForegroundService::class.java).apply {
                    action = DownloadForegroundService.ACTION_START
                    putExtra(DownloadForegroundService.EXTRA_THREADS, selectedThreads)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiUpdater)
    }

    private fun updateUI() {
        val running = DownloadForegroundService.isRunning.get()
        val bps = DownloadForegroundService.currentSpeedBps.get()
        val peakBps = DownloadForegroundService.peakSpeedBps.get()
        val total = DownloadForegroundService.totalBytes.get()

        val moSec = bps / (1024.0 * 1024.0)
        val mbps = (bps * 8.0) / 1_000_000.0
        val maxMo = peakBps / (1024.0 * 1024.0)
        val maxMbps = (peakBps * 8.0) / 1_000_000.0

        tvMoSpeed.text = String.format("%.2f", moSec)
        tvMbpsSpeed.text = String.format("%.1f", mbps)
        tvMaxMo.text = String.format("أقصى سرعة: %.2f Mo/s", maxMo)
        tvMaxMbps.text = String.format("أقصى سرعة: %.1f Mbps", maxMbps)

        val totalMo = total / (1024.0 * 1024.0)
        val totalGo = total / (1024.0 * 1024.0 * 1024.0)
        tvTotalData.text = if (totalGo >= 1.0) {
            String.format("%.2f Go (%.0f Mo)", totalGo, totalMo)
        } else {
            String.format("%.1f Mo", totalMo)
        }

        if (running) {
            btnToggle.text = "إيقاف التحميل وتحرير الشبكة"
            btnToggle.setBackgroundColor(0xFFF43F5E.toInt())
            val elapsedSec = (System.currentTimeMillis() - DownloadForegroundService.startTimeMs) / 1000
            val h = elapsedSec / 3600
            val m = (elapsedSec % 3600) / 60
            val s = elapsedSec % 60
            tvElapsed.text = String.format("%02d:%02d:%02d", h, m, s)
        } else {
            btnToggle.text = "ابدأ التحميل في الخلفية (En Arrière-Plan)"
            btnToggle.setBackgroundColor(0xFF10B981.toInt())
        }
    }
}
