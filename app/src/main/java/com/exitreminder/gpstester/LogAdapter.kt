package com.exitreminder.gpstester

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private val entries = mutableListOf<LogEntry>()

    fun addEntry(entry: LogEntry) {
        entries.add(0, entry)
        if (entries.size > 200) {
            entries.removeAt(entries.lastIndex)
        }
        notifyItemInserted(0)
    }

    fun clear() {
        entries.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvDistance: TextView = view.findViewById(R.id.tvDistance)
        private val tvAccuracy: TextView = view.findViewById(R.id.tvAccuracy)
        private val tvSpeed: TextView = view.findViewById(R.id.tvSpeed)
        private val tvMode: TextView = view.findViewById(R.id.tvMode)
        private val tvNext: TextView = view.findViewById(R.id.tvNext)
        private val tvEvent: TextView = view.findViewById(R.id.tvEvent)

        fun bind(entry: LogEntry) {
            tvTime.text = Utils.formatTime(entry.timestamp)
            tvDistance.text = "${entry.distance.toInt()}m"
            tvAccuracy.text = "\u00B1${entry.accuracy.toInt()}m"
            tvSpeed.text = "${entry.speedKmh.toInt()} km/h ${entry.category.emoji}"
            tvMode.text = entry.mode.label
            tvNext.text = Utils.formatDuration(entry.nextCheckSec)

            if (entry.event.isNotEmpty()) {
                tvEvent.visibility = View.VISIBLE
                tvEvent.text = entry.event
            } else {
                tvEvent.visibility = View.GONE
            }

            // Farben basierend auf Mode
            val modeColor = when (entry.mode) {
                PrecisionMode.LOW_POWER -> Color.parseColor("#22c55e")       // Grün
                PrecisionMode.BALANCED -> Color.parseColor("#eab308")        // Gelb
                PrecisionMode.HIGH_ACCURACY -> Color.parseColor("#f97316")   // Orange
                PrecisionMode.MAXIMUM_PRECISION -> Color.parseColor("#ef4444") // Rot
            }
            tvMode.setTextColor(modeColor)

            // Accuracy Farbe
            val accColor = when {
                entry.accuracy <= 5 -> Color.parseColor("#22c55e")   // Super
                entry.accuracy <= 10 -> Color.parseColor("#84cc16")  // Gut
                entry.accuracy <= 20 -> Color.parseColor("#eab308")  // OK
                else -> Color.parseColor("#ef4444")                   // Schlecht
            }
            tvAccuracy.setTextColor(accColor)
        }
    }
}
