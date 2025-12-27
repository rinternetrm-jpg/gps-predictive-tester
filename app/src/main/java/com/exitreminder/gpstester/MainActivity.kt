package com.exitreminder.gpstester

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etAddress: EditText
    private lateinit var seekRadius: SeekBar
    private lateinit var tvRadius: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvNextCheck: TextView
    private lateinit var tvCheckCount: TextView
    private lateinit var tvETA: TextView
    private lateinit var rvLog: RecyclerView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val logAdapter = LogAdapter()
    private var locationManager: PredictiveLocationManager? = null
    private var service: ForegroundLocationService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ForegroundLocationService.LocalBinder
            service = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkPermissions()
    }

    private fun initViews() {
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etAddress = findViewById(R.id.etAddress)
        seekRadius = findViewById(R.id.seekRadius)
        tvRadius = findViewById(R.id.tvRadius)
        tvDistance = findViewById(R.id.tvDistance)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvMode = findViewById(R.id.tvMode)
        tvNextCheck = findViewById(R.id.tvNextCheck)
        tvCheckCount = findViewById(R.id.tvCheckCount)
        tvETA = findViewById(R.id.tvETA)
        rvLog = findViewById(R.id.rvLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        rvLog.layoutManager = LinearLayoutManager(this)
        rvLog.adapter = logAdapter

        // Default: Breitenstein 17, Walchwil (Beispiel)
        etLat.setText("47.1189")
        etLng.setText("8.5160")
    }

    private fun setupListeners() {
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvRadius.text = "${progress}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            searchAddress()
        }

        btnStart.setOnClickListener { startTracking() }
        btnStop.setOnClickListener { stopTracking() }
    }

    @Suppress("DEPRECATION")
    private fun searchAddress() {
        val address = etAddress.text.toString()
        if (address.isBlank()) return

        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val results = geocoder.getFromLocationName(address, 1)
            if (!results.isNullOrEmpty()) {
                val location = results[0]
                etLat.setText(location.latitude.toString())
                etLng.setText(location.longitude.toString())
                Toast.makeText(this, "\uD83D\uDCCD ${location.getAddressLine(0)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Adresse nicht gefunden", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTracking() {
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        val radius = seekRadius.progress.toFloat()

        if (lat == null || lng == null) {
            Toast.makeText(this, "Ungültige Koordinaten", Toast.LENGTH_SHORT).show()
            return
        }

        // Start foreground service
        val serviceIntent = Intent(this, ForegroundLocationService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        // Create location manager
        locationManager = PredictiveLocationManager(
            context = this,
            targetLat = lat,
            targetLng = lng,
            triggerRadius = radius,
            onStateUpdate = { state -> runOnUiThread { updateUI(state) } },
            onLogEntry = { entry -> runOnUiThread { logAdapter.addEntry(entry) } },
            onTrigger = { distance, accuracy -> runOnUiThread { onTrigger(distance, accuracy) } }
        )

        locationManager?.startTracking()

        btnStart.isEnabled = false
        btnStop.isEnabled = true

        logAdapter.clear()
        Toast.makeText(this, "\uD83D\uDE80 Tracking gestartet", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        locationManager?.stopTracking()
        locationManager = null

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, ForegroundLocationService::class.java))

        btnStart.isEnabled = true
        btnStop.isEnabled = false

        Toast.makeText(this, "\u23F9\uFE0F Tracking gestoppt", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI(state: PredictiveState) {
        tvDistance.text = Utils.formatDistance(state.distanceToTarget)
        tvAccuracy.text = "\u00B1${state.accuracy.toInt()}m"
        tvSpeed.text = "${state.speedKmh.toInt()} km/h"
        tvMode.text = state.precisionMode.label
        tvNextCheck.text = Utils.formatDuration(state.nextCheckSeconds)
        tvCheckCount.text = state.checkCount.toString()

        if (state.etaSeconds < Float.MAX_VALUE) {
            tvETA.text = Utils.formatDuration(state.etaSeconds)
        } else {
            tvETA.text = "\u221E"
        }

        // Mode farbe
        val modeColor = when (state.precisionMode) {
            PrecisionMode.LOW_POWER -> 0xFF22c55e.toInt()
            PrecisionMode.BALANCED -> 0xFFeab308.toInt()
            PrecisionMode.HIGH_ACCURACY -> 0xFFf97316.toInt()
            PrecisionMode.MAXIMUM_PRECISION -> 0xFFef4444.toInt()
        }
        tvMode.setTextColor(modeColor)

        // Accuracy farbe
        val accColor = when {
            state.accuracy <= 5 -> 0xFF22c55e.toInt()
            state.accuracy <= 10 -> 0xFF84cc16.toInt()
            state.accuracy <= 20 -> 0xFFeab308.toInt()
            else -> 0xFFef4444.toInt()
        }
        tvAccuracy.setTextColor(accColor)

        // Update notification
        service?.updateNotification(
            state.distanceToTarget,
            state.nextCheckSeconds,
            state.precisionMode.label
        )
    }

    private fun onTrigger(distanceToCenter: Float, accuracy: Float) {
        // Vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1))

        // Trigger-Radius aus SeekBar
        val triggerRadius = seekRadius.progress

        // Bewertung der Genauigkeit
        val (rating, ratingColor) = when {
            distanceToCenter <= 5 -> "PERFEKT!" to "#22c55e"
            distanceToCenter <= 10 -> "Sehr gut" to "#84cc16"
            distanceToCenter <= 15 -> "Gut" to "#eab308"
            else -> "OK" to "#f97316"
        }

        // Custom Dialog Layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
            setBackgroundColor(Color.parseColor("#1c1c26"))
        }

        // Emoji
        val emojiView = TextView(this).apply {
            text = "\uD83C\uDFAF"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        layout.addView(emojiView)

        // Titel
        val titleView = TextView(this).apply {
            text = "TRIGGER!"
            textSize = 28f
            setTextColor(Color.parseColor("#22c55e"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 30)
        }
        layout.addView(titleView)

        // Distanz zum Ziel-Zentrum (Hauptinfo)
        val distanceLabel = TextView(this).apply {
            text = "Distanz zum Ziel-Zentrum:"
            textSize = 14f
            setTextColor(Color.parseColor("#9898a8"))
            gravity = Gravity.CENTER
        }
        layout.addView(distanceLabel)

        val distanceValue = TextView(this).apply {
            text = "${distanceToCenter.toInt()} Meter"
            textSize = 36f
            setTextColor(Color.parseColor("#3b82f6"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }
        layout.addView(distanceValue)

        // Bewertung
        val ratingView = TextView(this).apply {
            text = rating
            textSize = 20f
            setTextColor(Color.parseColor(ratingColor))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        layout.addView(ratingView)

        // Details Box
        val detailsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#22222e"))
            setPadding(40, 30, 40, 30)
        }

        val detail1 = TextView(this).apply {
            text = "Trigger-Radius: ${triggerRadius}m"
            textSize = 14f
            setTextColor(Color.parseColor("#9898a8"))
        }
        detailsBox.addView(detail1)

        val detail2 = TextView(this).apply {
            text = "GPS-Accuracy: \u00B1${accuracy.toInt()}m"
            textSize = 14f
            setTextColor(Color.parseColor("#9898a8"))
            setPadding(0, 10, 0, 0)
        }
        detailsBox.addView(detail2)

        val detail3 = TextView(this).apply {
            text = "Abweichung: ${(distanceToCenter - 0).toInt()}m"
            textSize = 14f
            setTextColor(Color.parseColor("#9898a8"))
            setPadding(0, 10, 0, 0)
        }
        detailsBox.addView(detail3)

        layout.addView(detailsBox)

        // Dialog anzeigen
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(layout)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }
}
