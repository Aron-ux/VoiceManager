package com.company.vehiclevoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var rmsText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var peakRms = 0.0

    private val statusListener: (VoiceStatusSnapshot) -> Unit = { snapshot ->
        renderStatus(snapshot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())

        appendLog("M2 debug page ready. AudioRecord RMS is wired; KWS/VAD/ASR/TTS are not wired yet.")
    }

    override fun onStart() {
        super.onStart()
        VoiceStatusBus.setListener(statusListener)
    }

    override fun onStop() {
        VoiceStatusBus.setListener(null)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS_CODE) {
            return
        }

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendLog("Required permissions granted.")
            startVoiceService()
        } else {
            appendLog("Permission denied. Voice service was not started.")
        }
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFFF7F5EF.toInt())
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(0xFF1D2A22.toInt())
            gravity = Gravity.START
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val subtitle = TextView(this).apply {
            text = getString(R.string.m1_subtitle)
            textSize = 14f
            setTextColor(0xFF53645A.toInt())
            setPadding(0, dp(6), 0, dp(18))
        }
        root.addView(subtitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        statusText = TextView(this).apply {
            text = getString(R.string.current_state_template, VoiceForegroundService.STATE_STOPPED)
            textSize = 18f
            setTextColor(0xFF223127.toInt())
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        rmsText = TextView(this).apply {
            text = getString(R.string.rms_template, 0.0, 0.0, 0L, 16_000)
            textSize = 16f
            setTextColor(0xFF365144.toInt())
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(rmsText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(buttonRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        buttonRow.addView(createButton(getString(R.string.start_service)) {
            ensurePermissionsThenStart()
        }, buttonLayoutParams())

        buttonRow.addView(createButton(getString(R.string.stop_service)) {
            appendLog("Stop service requested.")
            stopService(Intent(this, VoiceForegroundService::class.java))
            statusText.text = getString(R.string.current_state_template, VoiceForegroundService.STATE_STOPPING)
        }, buttonLayoutParams())

        buttonRow.addView(createButton(getString(R.string.clear_log)) {
            logText.text = ""
            appendLog("Log cleared.")
        }, buttonLayoutParams())

        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF243029.toInt())
            setLineSpacing(0f, 1.12f)
        }

        logScroll = ScrollView(this).apply {
            setPadding(0, dp(18), 0, 0)
            addView(logText)
        }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        return root
    }

    private fun createButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
    }

    private fun ensurePermissionsThenStart() {
        val missingPermissions = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startVoiceService()
        } else {
            appendLog("Requesting microphone/notification permissions.")
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun startVoiceService() {
        appendLog("Start foreground voice service requested.")
        val intent = VoiceForegroundService.createStartIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun renderStatus(snapshot: VoiceStatusSnapshot) {
        statusText.text = getString(R.string.current_state_template, snapshot.state)
        if (snapshot.rms != null) {
            peakRms = maxOf(peakRms, snapshot.rms)
            rmsText.text = getString(
                R.string.rms_template,
                snapshot.rms,
                peakRms,
                snapshot.frameSequence ?: 0L,
                snapshot.sampleRateHz ?: 0,
            )
        }

        if (snapshot.message != null) {
            appendLog(snapshot.message)
        }
    }

    private fun appendLog(message: String) {
        val line = "${timeFormatter.format(Date())}  $message"
        val currentText = logText.text?.toString().orEmpty()
        logText.text = if (currentText.isBlank()) line else "$currentText\n$line"
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 1001
        private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    }
}
