package com.exitreminder.gpstester

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import com.google.gson.Gson

class FullscreenMapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LINES_JSON = "lines_json"
        const val RESULT_LINES_JSON = "result_lines_json"
    }

    private lateinit var mapView: MapView
    private lateinit var tvInfo: TextView
    private lateinit var tvSelected: TextView
    private lateinit var tvRotation: TextView
    private lateinit var tvLength: TextView
    private lateinit var seekRotation: SeekBar
    private lateinit var seekLength: SeekBar
    private lateinit var btnAdd: Button
    private lateinit var btnRemove: Button
    private lateinit var btnConfirm: Button

    private var lineSet = TriggerLineSet()
    private var overlay: MultiLineOverlay? = null
    private var selectedLine: TriggerLine? = null
    private var centerLat = 0.0
    private var centerLng = 0.0
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_fullscreen_map)

        centerLat = intent.getDoubleExtra(EXTRA_LAT, 47.0)
        centerLng = intent.getDoubleExtra(EXTRA_LNG, 8.0)

        intent.getStringExtra(EXTRA_LINES_JSON)?.let {
            lineSet = gson.fromJson(it, TriggerLineSet::class.java)
        } ?: run {
            lineSet = TriggerLineSet(targetLat = centerLat, targetLng = centerLng)
            lineSet.addLine(centerLat, centerLng)
        }

        initViews()
        setupMap()
        setupSliders()
        updateUI()
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        tvInfo = findViewById(R.id.tvInfo)
        tvSelected = findViewById(R.id.tvSelected)
        tvRotation = findViewById(R.id.tvRotation)
        tvLength = findViewById(R.id.tvLength)
        seekRotation = findViewById(R.id.seekRotation)
        seekLength = findViewById(R.id.seekLength)
        btnAdd = findViewById(R.id.btnAdd)
        btnRemove = findViewById(R.id.btnRemove)
        btnConfirm = findViewById(R.id.btnConfirm)

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnAdd.setOnClickListener { addLine() }
        btnRemove.setOnClickListener { removeLine() }
        btnConfirm.setOnClickListener { confirm() }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(centerLat, centerLng))

        overlay = MultiLineOverlay(
            lineSet = lineSet,
            onChanged = { lineSet = it; updateUI() },
            onSelected = { selectedLine = it; updateSelectedUI() }
        )
        mapView.overlays.add(overlay)
    }

    private fun setupSliders() {
        seekRotation.max = 360
        seekRotation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    selectedLine?.rotationDegrees = (progress - 180).toFloat()
                    overlay?.update(lineSet)
                    mapView.invalidate()
                    updateUI()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekLength.min = 10
        seekLength.max = 100
        seekLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    selectedLine?.lengthMeters = progress.toFloat()
                    overlay?.update(lineSet)
                    mapView.invalidate()
                    updateUI()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun addLine() {
        if (lineSet.lines.size >= TriggerLineSet.MAX_LINES) {
            Toast.makeText(this, "Max ${TriggerLineSet.MAX_LINES} Linien", Toast.LENGTH_SHORT).show()
            return
        }
        val offset = lineSet.lines.size * 0.0001
        lineSet.addLine(centerLat + offset, centerLng + offset, 90f * lineSet.lines.size)
        overlay?.update(lineSet)
        mapView.invalidate()
        updateUI()
        Toast.makeText(this, "Linie hinzugefügt", Toast.LENGTH_SHORT).show()
    }

    private fun removeLine() {
        val line = selectedLine ?: return
        if (lineSet.lines.size <= 1) {
            Toast.makeText(this, "Mind. 1 Linie", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Linie ${line.id} löschen?")
            .setPositiveButton("Ja") { _, _ ->
                lineSet.removeLine(line.id)
                selectedLine = null
                overlay?.update(lineSet)
                overlay?.setSelected(null)
                mapView.invalidate()
                updateUI()
                updateSelectedUI()
            }
            .setNegativeButton("Nein", null)
            .show()
    }

    private fun confirm() {
        val json = gson.toJson(lineSet)
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_LINES_JSON, json))
        finish()
    }

    private fun updateUI() {
        tvInfo.text = "${lineSet.lines.size} von ${TriggerLineSet.MAX_LINES} Linien"
        btnAdd.isEnabled = lineSet.lines.size < TriggerLineSet.MAX_LINES
    }

    private fun updateSelectedUI() {
        val line = selectedLine
        if (line == null) {
            tvSelected.text = "Keine Linie ausgewählt"
            seekRotation.isEnabled = false
            seekLength.isEnabled = false
            btnRemove.isEnabled = false
            return
        }

        tvSelected.text = "Linie ${line.id}"
        seekRotation.isEnabled = true
        seekLength.isEnabled = true
        btnRemove.isEnabled = lineSet.lines.size > 1

        seekRotation.progress = (line.rotationDegrees.toInt() + 180).coerceIn(0, 360)
        seekLength.progress = line.lengthMeters.toInt().coerceIn(10, 100)
        tvRotation.text = "${line.rotationDegrees.toInt()}°"
        tvLength.text = "${line.lengthMeters.toInt()}m"
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
}
