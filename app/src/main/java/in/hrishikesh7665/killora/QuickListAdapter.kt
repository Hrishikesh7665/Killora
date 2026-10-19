package `in`.hrishikesh7665.killora

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * One saved Quick List app. [installed] is false when the app has been uninstalled since it was
 * added; it stays listed (dimmed) so the user decides whether to keep it. [isRunning] is false
 * for an uninstalled app too, since there is nothing running to report.
 */
data class QuickAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val versionName: String? = null,
    val installed: Boolean = true,
    val isRunning: Boolean = false,
    /** Ticked via the row checkbox so "Close selected" can act on just a subset of the saved
     *  list instead of always closing everything. Mutable for the same reason as
     *  [AppInfo.selected]: it needs to survive list rebinds without being treated as a content
     *  change by [DiffUtil] item identity. */
    var selected: Boolean = false
)

/** Full-width vertical Quick List: each row has a checkbox, an app-settings shortcut and a
 *  remove button. [onSelectionChanged] reports ticks so the screen can close just the checked
 *  apps instead of the whole saved list. */
class QuickListAdapter(
    private val onRemove: (QuickAppEntry) -> Unit,
    private val onOpenSettings: (QuickAppEntry) -> Unit,
    private val onSelectionChanged: (QuickAppEntry, Boolean) -> Unit
) : ListAdapter<QuickAppEntry, QuickListAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgQuickIcon)
        val label: TextView = view.findViewById(R.id.tvQuickLabel)
        val packageLabel: TextView = view.findViewById(R.id.tvQuickPackage)
        val status: TextView = view.findViewById(R.id.tvQuickStatus)
        val checkbox: CheckBox = view.findViewById(R.id.checkboxQuick)
        val settings: ImageButton = view.findViewById(R.id.btnQuickSettings)
        val remove: ImageButton = view.findViewById(R.id.btnQuickRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_quick_app, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val context = holder.itemView.context
        holder.icon.setImageDrawable(entry.icon)
        holder.label.text = entry.label
        holder.packageLabel.text = when {
            !entry.installed -> context.getString(R.string.quick_not_installed)
            entry.versionName.isNullOrBlank() -> entry.packageName
            else -> context.getString(R.string.app_package_version, entry.packageName, entry.versionName)
        }
        // An uninstalled app stays visible but dimmed, with no app-settings shortcut or
        // checkbox to offer - there's nothing running to close.
        holder.itemView.alpha = if (entry.installed) 1f else DIMMED_ALPHA
        holder.settings.isVisible = entry.installed
        holder.checkbox.isVisible = entry.installed

        if (!entry.installed) {
            holder.status.isVisible = false
        } else if (entry.isRunning) {
            holder.status.isVisible = true
            holder.status.setText(R.string.app_status_running)
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
        } else {
            holder.status.isVisible = true
            holder.status.setText(R.string.app_status_not_running)
            holder.status.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        // Detach the listener first, otherwise a recycled row would report a phantom change.
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = entry.selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            entry.selected = checked
            onSelectionChanged(entry, checked)
        }

        holder.remove.setOnClickListener { onRemove(entry) }
        holder.settings.setOnClickListener { onOpenSettings(entry) }
    }

    /** Every ticked, installed app. Mirrors [AppListAdapter.selectedPackages]. */
    fun selectedPackages(): List<String> =
        currentList.filter { it.installed && it.selected }.map { it.packageName }

    private companion object {
        const val DIMMED_ALPHA = 0.55f

        val DIFF = object : DiffUtil.ItemCallback<QuickAppEntry>() {
            override fun areItemsTheSame(oldItem: QuickAppEntry, newItem: QuickAppEntry) =
                oldItem.packageName == newItem.packageName

            // The icon is deliberately left out: every scan produces a new Drawable instance.
            override fun areContentsTheSame(oldItem: QuickAppEntry, newItem: QuickAppEntry) =
                oldItem.label == newItem.label &&
                    oldItem.versionName == newItem.versionName &&
                    oldItem.installed == newItem.installed &&
                    oldItem.isRunning == newItem.isRunning &&
                    oldItem.selected == newItem.selected
        }
    }
}
