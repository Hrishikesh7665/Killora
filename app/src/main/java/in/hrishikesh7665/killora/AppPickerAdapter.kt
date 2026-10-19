package `in`.hrishikesh7665.killora

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Multi-select list used by the "Add apps" dialog.
 *
 * The chosen packages live in [chosen], owned by the dialog, so a selection survives searching
 * (rows leave and re-enter the visible list) and view recycling.
 */
class AppPickerAdapter(
    private val chosen: MutableSet<String>,
    private val onChanged: () -> Unit
) : ListAdapter<AppInfo, AppPickerAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgPickIcon)
        val label: TextView = view.findViewById(R.id.tvPickLabel)
        val packageName: TextView = view.findViewById(R.id.tvPickPackage)
        val check: CheckBox = view.findViewById(R.id.checkPick)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pick_app, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.packageName.text = app.packageName
        holder.check.isChecked = app.packageName in chosen
        // The whole row is the tap target; the checkbox is only an indicator.
        holder.itemView.setOnClickListener {
            val nowChecked = chosen.add(app.packageName)
            if (!nowChecked) chosen.remove(app.packageName)
            holder.check.isChecked = nowChecked
            onChanged()
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.label == newItem.label
        }
    }
}
