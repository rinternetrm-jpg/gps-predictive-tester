package com.exitreminder.gpstester

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class MultiLineOverlay(
    private var lineSet: TriggerLineSet,
    private val onChanged: (TriggerLineSet) -> Unit,
    private val onSelected: (TriggerLine?) -> Unit
) : Overlay() {

    private val colors = listOf(
        Color.parseColor("#a855f7"),
        Color.parseColor("#3b82f6"),
        Color.parseColor("#22c55e"),
        Color.parseColor("#f97316"),
        Color.parseColor("#ec4899")
    )

    private var selectedId: Int? = null
    private var dragMode = DragMode.NONE
    private var dragLineId: Int? = null

    private enum class DragMode { NONE, CENTER, ENDPOINT }

    private val linePaint = Paint().apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection

        lineSet.lines.forEachIndexed { idx, line ->
            val isSelected = line.id == selectedId
            val (p1, p2) = line.getEndpoints()

            val sp1 = proj.toPixels(p1, null)
            val sp2 = proj.toPixels(p2, null)
            val sc = proj.toPixels(GeoPoint(line.centerLat, line.centerLng), null)

            // Linie
            linePaint.color = colors[idx % colors.size]
            linePaint.strokeWidth = if (isSelected) 12f else 8f
            linePaint.alpha = if (line.isActive) 255 else 100
            canvas.drawLine(sp1.x.toFloat(), sp1.y.toFloat(), sp2.x.toFloat(), sp2.y.toFloat(), linePaint)

            // Handles nur bei ausgewählter Linie
            if (isSelected && line.isActive) {
                // Endpunkte (Rotation)
                canvas.drawCircle(sp1.x.toFloat(), sp1.y.toFloat(), 30f, handlePaint)
                canvas.drawCircle(sp2.x.toFloat(), sp2.y.toFloat(), 30f, handlePaint)
                canvas.drawText("↻", sp1.x.toFloat(), sp1.y.toFloat() + 10, textPaint)
                canvas.drawText("↻", sp2.x.toFloat(), sp2.y.toFloat() + 10, textPaint)

                // Mitte (Verschieben)
                handlePaint.color = Color.parseColor("#f97316")
                canvas.drawCircle(sc.x.toFloat(), sc.y.toFloat(), 25f, handlePaint)
                canvas.drawText("✥", sc.x.toFloat(), sc.y.toFloat() + 10, textPaint)
                handlePaint.color = Color.WHITE
            }

            // Linien-Nummer
            val numPaint = Paint(textPaint).apply { color = colors[idx % colors.size]; textSize = 32f }
            canvas.drawText("${line.id}", sc.x.toFloat(), sc.y.toFloat() - 45, numPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val touchGeo = proj.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                for (line in lineSet.lines.filter { it.isActive }) {
                    val (p1, p2) = line.getEndpoints()
                    val sp1 = proj.toPixels(p1, null)
                    val sp2 = proj.toPixels(p2, null)
                    val sc = proj.toPixels(GeoPoint(line.centerLat, line.centerLng), null)

                    // Mitte getroffen?
                    if (dist(event.x, event.y, sc.x.toFloat(), sc.y.toFloat()) < 50) {
                        selectedId = line.id
                        dragMode = DragMode.CENTER
                        dragLineId = line.id
                        onSelected(line)
                        return true
                    }

                    // Endpunkte nur bei ausgewählter Linie
                    if (line.id == selectedId) {
                        if (dist(event.x, event.y, sp1.x.toFloat(), sp1.y.toFloat()) < 50 ||
                            dist(event.x, event.y, sp2.x.toFloat(), sp2.y.toFloat()) < 50) {
                            dragMode = DragMode.ENDPOINT
                            dragLineId = line.id
                            return true
                        }
                    }
                }
                selectedId = null
                onSelected(null)
            }

            MotionEvent.ACTION_MOVE -> {
                val line = lineSet.lines.find { it.id == dragLineId } ?: return false
                when (dragMode) {
                    DragMode.CENTER -> {
                        line.moveTo(touchGeo.latitude, touchGeo.longitude)
                        onChanged(lineSet)
                        mapView.invalidate()
                        return true
                    }
                    DragMode.ENDPOINT -> {
                        line.rotationDegrees = line.rotateFromPoint(
                            GeoPoint(line.centerLat, line.centerLng), touchGeo
                        )
                        onChanged(lineSet)
                        mapView.invalidate()
                        return true
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragMode != DragMode.NONE) {
                    dragMode = DragMode.NONE
                    dragLineId = null
                    onChanged(lineSet)
                    return true
                }
            }
        }
        return false
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        kotlin.math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1))

    fun update(newSet: TriggerLineSet) { lineSet = newSet }
    fun setSelected(id: Int?) { selectedId = id }
}
