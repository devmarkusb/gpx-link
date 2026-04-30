package io.github.gpxlink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GpxFileAdapter(
    private val items: MutableList<GpxListItem>,
    private val onChanged: () -> Unit,
) : RecyclerView.Adapter<GpxFileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.checkbox)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gpx_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.displayName
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.checked
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (item.checked != checked) {
                item.checked = checked
                onChanged()
            }
        }
    }

    override fun getItemCount(): Int = items.size
}

data class GpxListItem(
    val displayName: String,
    val cachePath: String,
    var checked: Boolean = true,
)
