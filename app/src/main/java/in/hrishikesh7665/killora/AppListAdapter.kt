package `in`.hrishikesh7665.killora

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
 * The main "All apps" list.
 *
 * @param isInQuickList tells a row whether to show the pin badge.
 * @param onSelectionChanged reports ticks so the screen can keep them across list refreshes.
 */
class AppListAdapter(
    private val isInQuickList: (String) -> Boolean,
    private val onOpenSettings: (AppInfo) -> Unit,
    private val onSelectionChanged: (AppInfo, Boolean) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val label: TextView = view.findViewById(R.id.tvLabel)
        val packageVersion: TextView = view.findViewById(R.id.tvPackageVersion)
        val status: TextView = view.findViewById(R.id.tvStatus)
        val badge: TextView = view.findViewById(R.id.tvBadge)
        val quickBadge: ImageView = view.findViewById(R.id.imgQuickBadge)
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val btnAppSettings: ImageButton = view.findViewById(R.id.btnAppSettings)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        val ctx = holder.itemView.context
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.packageVersion.text = if (app.versionName.isNullOrBlank()) {
            app.packageName
        } else {
            ctx.getString(R.string.app_package_version, app.packageName, app.versionName)
        }

        // Every row stays fully opaque and interactive, running or not. A status indicator
        // shows the state instead of greying the row out. Apps that are not running can still be
        // ticked, which is useful for building a Quick List ahead of time.
        if (app.isRunning) {
            holder.status.setText(R.string.app_status_running)
            holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
        } else {
            holder.status.setText(R.string.app_status_not_running)
            holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }

        when {
            app.isEssential -> {
                holder.badge.isVisible = true
                holder.badge.setText(R.string.app_badge_essential)
                holder.badge.setBackgroundResource(R.drawable.bg_pill_essential)
                holder.badge.setTextColor(ContextCompat.getColor(ctx, R.color.danger_red))
            }
            app.isSystemApp -> {
                holder.badge.isVisible = true
                holder.badge.setText(R.string.app_badge_system)
                holder.badge.setBackgroundResource(R.drawable.bg_pill_system)
                holder.badge.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            else -> holder.badge.isVisible = false
        }

        holder.quickBadge.isVisible = isInQuickList(app.packageName)

        // Detach the listener first, otherwise a recycled row would report a phantom change.
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = app.selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            app.selected = checked
            onSelectionChanged(app, checked)
        }
        holder.itemView.setOnClickListener { holder.checkbox.toggle() }
        holder.btnAppSettings.setOnClickListener { onOpenSettings(app) }
    }

    /**
     * Every ticked app, running or not. Closing an app that is not running is harmless: the close
     * engine skips it and reports it as already stopped.
     */
    fun selectedPackages(): List<String> =
        currentList.filter { it.selected }.map { it.packageName }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.packageName == newItem.packageName

            // The icon is deliberately left out: every scan produces a new Drawable instance,
            // which would make every row look changed on every refresh.
            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
                oldItem.label == newItem.label &&
                    oldItem.versionName == newItem.versionName &&
                    oldItem.isSystemApp == newItem.isSystemApp &&
                    oldItem.isEssential == newItem.isEssential &&
                    oldItem.isRunning == newItem.isRunning &&
                    oldItem.selected == newItem.selected
        }
    }
}
