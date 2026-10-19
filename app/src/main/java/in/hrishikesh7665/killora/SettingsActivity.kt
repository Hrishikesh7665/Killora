package `in`.hrishikesh7665.killora

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/** App preferences: theme, close behaviour (manual vs automatic, progress overlay), and
 *  permission/status shortcuts (root, Accessibility Service, home-screen widget shortcut). */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_settings)

        refreshThemeLabel()
        findViewById<View>(R.id.rowTheme).setOnClickListener { showThemeDialog() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnPinShortcut).setOnClickListener { pinQuickCloseShortcut() }
        findViewById<MaterialButton>(R.id.btnAddWidget).setOnClickListener { requestAddWidget() }
        findViewById<MaterialButton>(R.id.btnRequestRoot).setOnClickListener { requestRootAccess() }

        setUpAppListDisplayCard()

        // Deep force stop itself is always on and has no visible toggle in this screen any more
        // (see activity_settings.xml) - only how it behaves (manual vs automatic) and whether
        // its progress overlay shows are exposed here.
        findViewById<SwitchMaterial>(R.id.switchManualMode).apply {
            isChecked = Prefs.getManualMode(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setManualMode(this@SettingsActivity, checked)
            }
        }

        findViewById<SwitchMaterial>(R.id.switchShowOverlay).apply {
            isChecked = Prefs.getShowOverlay(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setShowOverlay(this@SettingsActivity, checked)
            }
        }

        setUpBackgroundReliabilityCard()
    }

    override fun onResume() {
        super.onResume()
        // Root and automation permissions can change at any time from outside the app
        // (Magisk, system Settings), so re-check every time this screen is shown.
        refreshRootAndPermissionStatus()
    }

    /** Checks root and Accessibility status off the main thread (root checks do blocking I/O)
     *  and shows the result in the status card at the top of this screen. */
    private fun refreshRootAndPermissionStatus() {
        findViewById<TextView>(R.id.tvRootStatus).setText(R.string.status_checking)
        val appContext = applicationContext
        Background.io {
            val rootManagerInstalled = RootUtils.isRootManagerInstalled(appContext)
            val rootCapable = rootManagerInstalled || RootUtils.isDeviceRooted()
            val rootGranted = rootCapable && RootUtils.isRootGranted()
            val accessibilityOn = AccessibilityUtils.isForceCloseServiceEnabled(appContext)

            Background.main {
                if (isFinishing || isDestroyed) return@main
                bindRootAndPermissionStatus(rootCapable, rootManagerInstalled, rootGranted, accessibilityOn)
            }
        }
    }

    private fun bindRootAndPermissionStatus(
        rootCapable: Boolean,
        rootManagerInstalled: Boolean,
        rootGranted: Boolean,
        accessibilityOn: Boolean
    ) {
        val rootText = when {
            rootGranted -> R.string.status_root_granted
            rootManagerInstalled -> R.string.status_root_denied
            rootCapable -> R.string.status_root_not_granted
            else -> R.string.status_root_unavailable
        }
        findViewById<TextView>(R.id.tvRootStatus).setText(rootText)
        findViewById<MaterialButton>(R.id.btnRequestRoot).isVisible = rootCapable && !rootGranted

        findViewById<TextView>(R.id.tvAccessibilityStatus).setText(
            if (accessibilityOn) R.string.status_a11y_enabled else R.string.status_a11y_disabled
        )
    }

    private fun requestRootAccess() {
        findViewById<MaterialButton>(R.id.btnRequestRoot).isEnabled = false
        Background.io {
            val granted = RootUtils.isRootGranted()
            Background.main {
                if (isFinishing || isDestroyed) return@main
                findViewById<MaterialButton>(R.id.btnRequestRoot).isEnabled = true
                Notice.show(this, getString(if (granted) R.string.root_granted else R.string.root_not_granted))
                refreshRootAndPermissionStatus()
            }
        }
    }

    /** Mirrors the "Show system apps" / "Show essential system apps" switches that also live
     *  on the main screen. They write to the same [Prefs] keys, so this is here purely so
     *  dismissing the in-list "essential apps hidden" banner never costs the user their only
     *  way to turn the setting back on - it's reachable from Settings regardless. */
    private fun setUpAppListDisplayCard() {
        val switchSystem = findViewById<SwitchMaterial>(R.id.switchShowSystemSettings)
        val switchEssential = findViewById<SwitchMaterial>(R.id.switchShowEssentialSettings)
        val rowEssential = findViewById<View>(R.id.rowShowEssentialSettings)

        fun applyEssentialEnabled(systemOn: Boolean) {
            rowEssential.isEnabled = systemOn
            rowEssential.alpha = if (systemOn) 1f else 0.4f
            switchEssential.isEnabled = systemOn
        }

        switchSystem.isChecked = Prefs.getShowSystem(this)
        switchEssential.isChecked = Prefs.getShowEssential(this)
        applyEssentialEnabled(switchSystem.isChecked)

        switchSystem.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowSystem(this, checked)
            if (!checked) {
                // Essential-apps visibility only makes sense alongside system apps - same
                // rule the main screen's own toggle follows.
                switchEssential.isChecked = false
                Prefs.setShowEssential(this, false)
            }
            applyEssentialEnabled(checked)
        }

        switchEssential.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowEssential(this, checked)
        }
    }

    /** Only shown on OEM skins known to kill backgrounded accessibility work (MIUI,
     *  ColorOS/realme UI, Funtouch OS, EMUI/MagicOS, One UI's own battery manager,
     *  etc.) - on a Pixel or other near-stock device there's nothing useful to offer
     *  here, so the whole card stays hidden. */
    private fun setUpBackgroundReliabilityCard() {
        val card = findViewById<View>(R.id.cardBackgroundReliability)
        if (!OemAutostartHelper.hasAggressiveBackgroundRestrictions()) {
            card.isVisible = false
            return
        }
        card.isVisible = true
        val skin = OemAutostartHelper.skinName()
        findViewById<TextView>(R.id.tvBackgroundReliabilityBody).text =
            getString(R.string.settings_background_body, skin)

        findViewById<MaterialButton>(R.id.btnIgnoreBatteryOptimization).setOnClickListener {
            OemAutostartHelper.openBatteryOptimizationSettings(this)
        }
        findViewById<MaterialButton>(R.id.btnOpenAutostart).setOnClickListener {
            val opened = OemAutostartHelper.openAutostartSettings(this)
            if (!opened) {
                Notice.show(this, getString(R.string.settings_autostart_not_found, skin), persistent = true)
            }
        }
    }

    private fun refreshThemeLabel() {
        val label = when (ThemeManager.getMode(this)) {
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
            ThemeMode.AMOLED -> R.string.theme_amoled
            ThemeMode.SYSTEM -> R.string.theme_system_description
        }
        findViewById<TextView>(R.id.tvThemeValue).setText(label)
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.theme_system_default),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_amoled)
        )
        val modes = arrayOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)
        val current = modes.indexOf(ThemeManager.getMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(options, current) { dialog, which ->
                ThemeManager.setMode(this, modes[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun pinQuickCloseShortcut() {
        val quickList = QuickList.load(this)
        if (quickList.isEmpty()) {
            Notice.show(
                this,
                getString(R.string.settings_pin_needs_quick_list),
                persistent = true
            )
            return
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Notice.show(this, getString(R.string.settings_pin_unsupported), persistent = true)
            return
        }
        val intent = Intent(this, QuickCloseActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        val shortcut = ShortcutInfoCompat.Builder(this, "quick_close")
            .setShortLabel(getString(R.string.shortcut_short_label))
            .setLongLabel(getString(R.string.shortcut_long_label))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_force_stop))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    private fun requestAddWidget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Notice.show(this, getString(R.string.settings_widget_manual), persistent = true)
            return
        }
        val awm = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, RamWidgetProvider::class.java)
        if (awm.isRequestPinAppWidgetSupported) {
            awm.requestPinAppWidget(provider, null, null)
        } else {
            Notice.show(this, getString(R.string.settings_widget_manual), persistent = true)
        }
    }
}
