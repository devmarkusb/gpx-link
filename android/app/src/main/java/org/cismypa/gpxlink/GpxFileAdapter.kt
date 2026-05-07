package org.cismypa.gpxlink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GpxFileAdapter(
    private val items: MutableList<GpxListItem>,
    private val onChanged: () -> Unit,
    private val onItemLongPress: (anchor: View, position: Int) -> Unit,
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
        holder.check.isChecked = item.checked
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val rowItem = items.getOrNull(pos) ?: return@setOnClickListener
            rowItem.checked = !rowItem.checked
            holder.check.isChecked = rowItem.checked
            onChanged()
        }
        val rowLongPress = View.OnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemLongPress(holder.itemView, pos)
            }
            true
        }
        holder.itemView.setOnLongClickListener(rowLongPress)
    }

    override fun getItemCount(): Int = items.size
}

data class GpxListItem(
    val displayName: String,
    val cachePath: String,
    var checked: Boolean = true,
)
