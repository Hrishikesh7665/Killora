package `in`.hrishikesh7665.killora

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.min

/**
 * Home screen: lists installed apps and the user's Quick List, and drives closing the
 * selected ones (normal force-stop, root, or the Accessibility-driven "deep" force stop).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppListAdapter
    /** The list currently shown in "All apps". Replaced as a whole on every scan. */
    private var apps: List<AppInfo> = emptyList()

    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var quickRecycler: RecyclerView
    private lateinit var quickAdapter: QuickListAdapter
    private var quickEntries: List<QuickAppEntry> = emptyList()

    /** Rows currently shown in the Quick List (after search / "show stopped" filtering). The
     *  Select all button acts on exactly these. */
    private var quickVisible: List<QuickAppEntry> = emptyList()

    /** Packages ticked in the Quick List. Kept apart from [QuickAppEntry.selected] for the same
     *  reason as [selectedPackages]: the entries are rebuilt on every reload, and a tick must
     *  survive that (it used to be lost every time the screen was resumed). */
    private val quickSelectedPackages = mutableSetOf<String>()

    /** Packages saved in the Quick List, used for the pin badge in the main list. */
    private var quickPackageSet: Set<String> = emptySet()

    private var currentSection = HomeTab.APPS

    /** Packages the user has ticked in the main app list. Kept separate from [AppInfo.selected]
     *  so a selection survives list rebuilds (pull-to-refresh, the auto-refresh timer, coming
     *  back to the screen) - it is only ever cleared when the user unticks the app themselves
     *  or the app is actually closed/uninstalled. See [loadApps] and [closeApps]. */
    private val selectedPackages = mutableSetOf<String>()

    private var showSystem = false
    private var showEssential = false
    private var essentialHiddenCount = 0

    /** Current text in each tab's own search box (empty when the box is hidden or untouched).
     *  Filters by label or package name; see [applyAppsFilter] / [applyQuickListFilter]. */
    private var appsQuery: String = ""
    private var quickQuery: String = ""

    /** Mirrors [Prefs.getQuickListShowStopped]. On by default, so nothing saved is hidden
     *  unless the user turns this off from the Quick List tab. */
    private var quickShowStopped = true

    /** Lets the user dismiss the "N essential apps hidden" banner for this session instead of
     *  it sitting there permanently once they've seen it. Reset whenever "Show system apps" is
     *  toggled, since that's when the underlying count becomes relevant again. */
    private var essentialBannerDismissed = false

    /** The exact warning text last dismissed by the user, so the permission banner stays
     *  hidden for that specific issue but still comes back if a *different* problem appears
     *  (e.g. root access was fixed but Accessibility is now the thing missing). */
    private var dismissedPermissionWarning: String? = null

    /** Set right before we send the user to Settings (Accessibility or "display over other
     *  apps") so the deep force-stop they asked for actually resumes when they come back,
     *  instead of silently doing nothing. Cleared once it either resumes or the user picks a
     *  path that no longer needs it, e.g. turns the overlay card off from inside Settings. */
    private var pendingDeepForceStopPackages: List<String>? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshIntervalMs = 4000L
    private val refreshAfterCloseMs = 600L
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (::adapter.isInitialized) {
                loadApps()
                loadQuickList(rebindMainList = false)
            }
            refreshHandler.postDelayed(this, autoRefreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_main)
        EdgeToEdge.setUp(this, findViewById(R.id.mainRoot), R.dimen.content_max_width)
        // Rotating, switching theme or language recreates this screen. Put back what the user
        // was in the middle of (tab, ticks, open search boxes) instead of starting over.
        if (savedInstanceState != null) restoreScreenState(savedInstanceState)

        showSystem = Prefs.getShowSystem(this)
        showEssential = Prefs.getShowEssential(this)

        recycler = findViewById(R.id.recyclerApps)
        EdgeToEdge.configureList(recycler)
        adapter = AppListAdapter(
            isInQuickList = { it in quickPackageSet },
            onOpenSettings = { app -> openAppInfo(app.packageName) },
            onSelectionChanged = { app, checked ->
                if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            }
        )
        recycler.adapter = adapter
        // apps were empty at this point (first launch, nothing scanned yet) - this establishes
        // the correct starting state explicitly instead of relying on the XML default.
        updateAppsLoadingState()

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.accent_blue, R.color.accent_purple)
        swipeRefresh.setOnRefreshListener {
            updateRamStats()
            // Keep the pull-to-refresh spinner up until the background scan actually finishes,
            // instead of it vanishing the instant the pull gesture is released. Its own
            // spinner is already the "loading" cue here, so skip the thin top bar too.
            loadApps(showThinProgress = false, onComplete = { swipeRefresh.isRefreshing = false })
        }

        // Quick List is now its own full-width, full-height section (see setSection()) rather
        // than a horizontal strip squeezed under the RAM card, so it gets a normal vertical list.
        quickRecycler = findViewById(R.id.recyclerQuickList)
        EdgeToEdge.configureList(quickRecycler)
        quickAdapter = QuickListAdapter(
            onRemove = { entry -> onRemoveFromQuickList(entry) },
            onOpenSettings = { entry -> openAppInfo(entry.packageName) },
            onSelectionChanged = { entry, checked ->
                if (checked) quickSelectedPackages.add(entry.packageName)
                else quickSelectedPackages.remove(entry.packageName)
                updateQuickSelectAllButton()
            }
        )
        quickRecycler.adapter = quickAdapter

        findViewById<View>(R.id.btnTabApps).setOnClickListener { setSection(HomeTab.APPS) }
        findViewById<View>(R.id.btnTabQuickList).setOnClickListener { setSection(HomeTab.QUICK_LIST) }
        // A fresh start opens the tab chosen in Settings (All apps unless changed). A screen
        // that is only being recreated keeps the tab it was on.
        setSection(
            savedInstanceState?.getString(STATE_SECTION)
                ?.let { saved -> HomeTab.entries.firstOrNull { it.name == saved } }
                ?: Prefs.getDefaultTab(this)
        )

        findViewById<ImageButton>(R.id.btnRefreshApps).setOnClickListener {
            loadApps()
            updateRamStats()
        }

        setUpSearchRow(
            toggleButtonId = R.id.btnSearchApps,
            rowId = R.id.rowAppSearch,
            editTextId = R.id.etAppSearch,
            closeButtonId = R.id.btnCloseAppSearch,
            onQueryChanged = { query ->
                appsQuery = query
                applyAppsFilter()
            }
        )
        setUpSearchRow(
            toggleButtonId = R.id.btnSearchQuickList,
            rowId = R.id.rowQuickSearch,
            editTextId = R.id.etQuickSearch,
            closeButtonId = R.id.btnCloseQuickSearch,
            onQueryChanged = { query ->
                quickQuery = query
                applyQuickListFilter()
            }
        )

        if (savedInstanceState?.getBoolean(STATE_APPS_SEARCH_OPEN) == true) {
            findViewById<View>(R.id.rowAppSearch).isVisible = true
        }
        if (savedInstanceState?.getBoolean(STATE_QUICK_SEARCH_OPEN) == true) {
            findViewById<View>(R.id.rowQuickSearch).isVisible = true
        }
        findViewById<ImageButton>(R.id.btnQuickMore).setOnClickListener { showQuickListMenu(it) }

        quickShowStopped = Prefs.getQuickListShowStopped(this)
        findViewById<SwitchMaterial>(R.id.switchQuickShowStopped).apply {
            isChecked = quickShowStopped
            setOnCheckedChangeListener { _, checked ->
                quickShowStopped = checked
                Prefs.setQuickListShowStopped(this@MainActivity, checked)
                applyQuickListFilter()
            }
        }

        findViewById<SwitchMaterial>(R.id.switchShowSystem).apply {
            isChecked = showSystem
            setOnCheckedChangeListener { _, checked ->
                showSystem = checked
                Prefs.setShowSystem(this@MainActivity, checked)
                if (!checked) {
                    // Essential-apps visibility only makes sense alongside system apps.
                    showEssential = false
                    Prefs.setShowEssential(this@MainActivity, false)
                }
                essentialBannerDismissed = false
                loadApps()
            }
        }

        findViewById<ImageButton>(R.id.btnDismissEssentialHidden).setOnClickListener {
            essentialBannerDismissed = true
            updateEssentialRow()
        }

        findViewById<ImageButton>(R.id.btnDismissPermissionWarning).setOnClickListener {
            dismissedPermissionWarning = findViewById<TextView>(R.id.tvPermissionWarning).text.toString()
            findViewById<View>(R.id.rowPermissionWarning).isVisible = false
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnCloseSelected).setOnClickListener { view ->
            val selected = tickedApps()
            if (selected.isEmpty()) {
                Notice.show(this, getString(R.string.close_select_first))
            } else {
                hapticTap(view)
                closeApps(selected)
            }
        }

        findViewById<MaterialButton>(R.id.btnSaveQuickList)
            .setOnClickListener { onAddSelectedToQuickList() }
        findViewById<MaterialButton>(R.id.btnAddApps).setOnClickListener { showAddAppsDialog() }
        findViewById<MaterialButton>(R.id.btnClearQuickList).setOnClickListener { confirmClearQuickList() }
        findViewById<MaterialButton>(R.id.btnQuickSelectAll).setOnClickListener { toggleQuickSelectAll() }

        findViewById<MaterialButton>(R.id.btnCloseQuickList).setOnClickListener { view ->
            val selected = tickedQuickApps()
            if (selected.isEmpty()) {
                Notice.show(this, getString(R.string.quick_close_select_first))
            } else {
                hapticTap(view)
                // Ticks are kept while closing. Once the whole run is over, the apps that
                // really stopped get unticked and the ones still running stay ticked.
                // See CloseTracker and pruneClosedSelections().
                closeApps(selected)
            }
        }

        loadApps()
        loadQuickList()
        updateRamStats()
        requestNotificationPermissionIfNeeded()
        if (savedInstanceState == null) handleManualHandOff(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, currentSection.name)
        outState.putStringArrayList(STATE_SELECTED_APPS, ArrayList(selectedPackages))
        outState.putStringArrayList(STATE_SELECTED_QUICK, ArrayList(quickSelectedPackages))
        outState.putBoolean(STATE_APPS_SEARCH_OPEN, findViewById<View>(R.id.rowAppSearch).isVisible)
        outState.putBoolean(STATE_QUICK_SEARCH_OPEN, findViewById<View>(R.id.rowQuickSearch).isVisible)
        outState.putBoolean(STATE_ESSENTIAL_DISMISSED, essentialBannerDismissed)
        outState.putString(STATE_DISMISSED_PERMISSION, dismissedPermissionWarning)
    }

    /** Restores the plain fields saved by [onSaveInstanceState]. The tab and the search boxes
     *  are restored where onCreate wires them up. The typed search text itself is restored by
     *  Android, and the list filters pick it up through the existing text watchers. */
    private fun restoreScreenState(state: Bundle) {
        state.getStringArrayList(STATE_SELECTED_APPS)?.let { selectedPackages.addAll(it) }
        state.getStringArrayList(STATE_SELECTED_QUICK)?.let { quickSelectedPackages.addAll(it) }
        essentialBannerDismissed = state.getBoolean(STATE_ESSENTIAL_DISMISSED)
        dismissedPermissionWarning = state.getString(STATE_DISMISSED_PERMISSION)
    }

    /** So the closing-progress notification (and, by extension, the live progress it shows
     *  even when the floating card is switched off) actually appears on Android 13+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /**
     * Wires one tab's search icon/box pair: tapping [toggleButtonId] reveals [rowId] and focuses
     * [editTextId]'s keyboard; tapping [closeButtonId] clears the text, hides the row again, and
     * dismisses the keyboard. [onQueryChanged] fires on every keystroke (including the clear) so
     * the caller can re-filter immediately - search is local, in-memory filtering, so there is no
     * need to wait for a "submit".
     */
    private fun setUpSearchRow(
        toggleButtonId: Int,
        rowId: Int,
        editTextId: Int,
        closeButtonId: Int,
        onQueryChanged: (String) -> Unit
    ) {
        val row = findViewById<View>(rowId)
        val editText = findViewById<EditText>(editTextId)
        val insetsController = WindowCompat.getInsetsController(window, editText)

        findViewById<ImageButton>(toggleButtonId).setOnClickListener {
            if (row.isVisible) {
                row.isVisible = false
                editText.setText("")
                insetsController.hide(WindowInsetsCompat.Type.ime())
            } else {
                row.isVisible = true
                editText.requestFocus()
                insetsController.show(WindowInsetsCompat.Type.ime())
            }
        }
        findViewById<ImageButton>(closeButtonId).setOnClickListener {
            editText.setText("")
            row.isVisible = false
            insetsController.hide(WindowInsetsCompat.Type.ime())
        }
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
        })
    }

    override fun onResume() {
        super.onResume()

        // "Show system apps" / "Show essential system apps" can also be changed from the
        // Settings screen now (so dismissing the in-list banner never costs the user their
        // only way back to the setting) - pick up any change made there since we were paused.
        syncShowSystemAndEssentialFromPrefs()

        if (::adapter.isInitialized) loadApps()

        updateRamStats()
        loadQuickList()
        resumePendingDeepForceStopIfReady()
        refreshPermissionStatus()

        // Keep the running/stopped state current while the screen is visible, since Android
        // gives no callback for "some other app changed state in the background".
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        refreshHandler.postDelayed(autoRefreshRunnable, autoRefreshIntervalMs)

        // When a close request ends while we're visible, untick what stopped right away.
        CloseTracker.onFinished = {
            if (::adapter.isInitialized && !isFinishing && !isDestroyed) {
                loadApps()
                loadQuickList(rebindMainList = false)
            }
        }

        // Manual mode: coming back from an App info screen opens the next app.
        ManualCloser.onResume(this)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        CloseTracker.onFinished = null
        ManualCloser.onPause()
    }

    /** The widget / shortcut hands its apps over here when manual mode is on, because manual
     *  mode needs a visible screen of its own (no Accessibility Service involved). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleManualHandOff(intent)
    }

    private fun handleManualHandOff(intent: Intent?) {
        val packages = intent?.getStringArrayListExtra(ManualCloser.EXTRA_MANUAL_PACKAGES) ?: return
        intent.removeExtra(ManualCloser.EXTRA_MANUAL_PACKAGES)
        if (packages.isEmpty()) return
        CloseTracker.begin(packages)
        // Wait for the first frame so the message has a window to appear in.
        window.decorView.post { ManualCloser.start(this, packages) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Catches the case where the app was merely covered (split-screen, a system dialog,
        // the recents switcher) and regains focus without a full onResume.
        if (hasFocus && ::adapter.isInitialized) {
            loadApps()
            updateRamStats()
        }
    }

    /** Called every time the user comes back to the app, including right after they grant
     *  the Accessibility Service or "display over other apps" permission from Settings. If a
     *  deep force-stop was waiting on exactly that permission, pick it back up here instead
     *  of leaving the user stuck on the plain "closed in background" toast forever. */
    private fun resumePendingDeepForceStopIfReady() {
        val packages = pendingDeepForceStopPackages ?: return
        if (!isAccessibilityServiceEnabled()) return
        pendingDeepForceStopPackages = null
        runDeepForceStop(packages)
    }

    private fun updateRamStats() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / 1e9
        val availGb = info.availMem / 1e9
        val usedGb = totalGb - availGb
        val percent = if (totalGb > 0) ((usedGb / totalGb) * 100).toInt() else 0

        findViewById<RamGaugeView>(R.id.ramGauge).percent = percent.toFloat()
        findViewById<TextView>(R.id.tvRamPercent).text = getString(R.string.percent_format, percent)
        findViewById<ProgressBar>(R.id.progressMemory).progress = percent
        findViewById<TextView>(R.id.tvAvailableMemory).text = getString(R.string.ram_free_gb, availGb)
        val running = runningAppCount(am)
        findViewById<TextView>(R.id.tvLaunchedApps).text =
            resources.getQuantityString(R.plurals.apps_running, running, running)
    }

    // ---------- Section switching (Apps vs Quick List) ----------

    /** Shows exactly one of the two sections at a time instead of stacking the full app list
     *  and the Quick List card on the same screen, which is what made the main screen feel
     *  cluttered. Switching is driven by the two tab buttons (or anything else that calls
     *  this, e.g. after saving a Quick List). */
    private fun setSection(section: HomeTab) {
        currentSection = section
        val showApps = section == HomeTab.APPS
        findViewById<View>(R.id.sectionApps).isVisible = showApps
        findViewById<View>(R.id.sectionQuickList).isVisible = !showApps

        val tabApps = findViewById<MaterialButton>(R.id.btnTabApps)
        val tabQuickList = findViewById<MaterialButton>(R.id.btnTabQuickList)
        tabApps.isChecked = showApps
        tabQuickList.isChecked = !showApps
    }

    /** Keeps the main screen's own "Show system apps" switch (and the showSystem/showEssential
     *  fields that [loadApps] actually filters by) in sync with Prefs, since both can now also
     *  be changed from the Settings screen's "App list display" card. A no-op when nothing
     *  changed, so this is cheap to call on every onResume(). */
    private fun syncShowSystemAndEssentialFromPrefs() {
        val latestShowSystem = Prefs.getShowSystem(this)
        val latestShowEssential = Prefs.getShowEssential(this)
        if (latestShowSystem == showSystem && latestShowEssential == showEssential) return

        showSystem = latestShowSystem
        showEssential = latestShowEssential
        essentialBannerDismissed = false

        val switchSystem = findViewById<SwitchMaterial>(R.id.switchShowSystem)
        if (switchSystem.isChecked != showSystem) {
            // Setting isChecked fires the listener below, which already handles writing
            // back to Prefs (a harmless no-op here, since the value already matches) and
            // calling loadApps() - so nothing further is needed after this.
            switchSystem.isChecked = showSystem
        }
    }

    private fun runningAppCount(am: ActivityManager): Int = try {
        am.runningAppProcesses?.size ?: 0
    } catch (_: Exception) {
        0
    }

    // ---------- Main app list ----------

    /** True while a background [loadApps] enumeration is in flight. Guards against the 4s
     *  auto-refresh timer (or a manual refresh) piling up a second scan on top of one that's
     *  still running - enumerating every installed app, reading each FLAG_STOPPED bit and
     *  resolving every icon/label/version is real I/O and can take a noticeable moment on a
     *  phone with a few hundred apps installed, so this is the one place in the app that's
     *  genuinely worth moving off the main thread. */
    private var isLoadingApps = false

    /** Set once the very first [loadApps] call (from onCreate) has finished - see Part 5's
     *  full-screen "Loading your apps…" state, which only shows before this flips true. */
    private var hasLoadedAppsOnce = false

    private data class AppScanResult(val apps: List<AppInfo>, val essentialHiddenCount: Int)

    /** @param showThinProgress Shows the thin top progress bar while the scan runs. Pass
     *    false when the caller already has its own indicator up (pull-to-refresh's own
     *    spinner) so the user never sees two overlapping "loading" cues at once.
     *  @param onComplete Called on the UI thread once the list has actually been applied -
     *    lets a caller (like pull-to-refresh) keep its own spinner up for the real duration
     *    of the scan instead of stopping it the instant the call returns. */
    private fun loadApps(showThinProgress: Boolean = true, onComplete: (() -> Unit)? = null) {
        if (isLoadingApps) {
            onComplete?.invoke()
            return
        }
        isLoadingApps = true

        // A small, non-destructive progress bar at the top of the list - never a blocking
        // spinner, never clears the current selection or scroll position. Shown for every
        // refresh (auto-interval, manual button); the one-time full-screen loading state for
        // the very first launch is handled separately.
        if (showThinProgress) findViewById<View>(R.id.progressListRefresh).isVisible = true

        val pm = packageManager
        val snapshotShowSystem = showSystem
        val snapshotShowEssential = showEssential

        Background.io {
            val result = scanInstalledApps(pm, snapshotShowSystem, snapshotShowEssential)
            Background.main {
                if (isFinishing || isDestroyed) return@main
                applyAppScanResult(result)
                isLoadingApps = false
                hasLoadedAppsOnce = true
                findViewById<View>(R.id.progressListRefresh).isVisible = false
                onComplete?.invoke()
            }
        }
    }

    /** Pure, background-thread-safe scan: reads PackageManager state and returns plain data,
     *  touching no view and no field this Activity's UI thread is also reading/writing (apps,
     *  selectedPackages, adapter, etc. are only ever touched back on the UI thread, in
     *  [applyAppScanResult]). */
    private fun scanInstalledApps(
        pm: PackageManager,
        snapshotShowSystem: Boolean,
        snapshotShowEssential: Boolean
    ): AppScanResult {
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = mutableListOf<AppInfo>()
        var essentialHidden = 0

        for (ai in installed) {
            if (ai.packageName == packageName) continue
            // Fresh ApplicationInfo so FLAG_STOPPED is up to date after a force-stop.
            val fresh = try {
                pm.getApplicationInfo(ai.packageName, 0)
            } catch (_: Exception) { ai }

            val isSystem = (fresh.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isEssential = isSystem && EssentialApps.isEssential(this, fresh.packageName, fresh)

            if (isSystem) {
                if (!snapshotShowSystem) continue
                if (isEssential) {
                    essentialHidden++
                    if (!snapshotShowEssential) continue
                }
            }

            // FLAG_STOPPED is the one authoritative, public signal Android gives for "this
            // app was force-stopped and hasn't been launched since" - it is set by a real
            // Force stop and cleared the moment the app runs again.
            val isStopped = (fresh.flags and ApplicationInfo.FLAG_STOPPED) != 0
            val isRunning = !isStopped

            val label = pm.getApplicationLabel(fresh).toString()
            val icon = pm.getApplicationIcon(fresh)
            val versionName = versionNameFor(pm, fresh.packageName)
            // selected is resolved against the *current* selectedPackages back on the UI
            // thread (applyAppScanResult), not here - avoids a race with the user ticking a
            // box while this background scan is still running.
            result.add(AppInfo(fresh.packageName, label, icon, isSystem, isEssential, isRunning, versionName))
        }

        result.sortWith(compareByDescending<AppInfo> { it.isRunning }.thenBy { it.label.lowercase() })
        return AppScanResult(result, essentialHidden)
    }

    /** Main-thread only: applies a finished background scan to the live list/adapter. */
    private fun applyAppScanResult(result: AppScanResult) {
        pruneClosedSelections()
        essentialHiddenCount = result.essentialHiddenCount
        apps = result.apps.map { it.copy(selected = selectedPackages.contains(it.packageName)) }

        // Drop selections for apps that are no longer installed. Anything still installed
        // keeps its selection across this rebuild even if it's temporarily not running -
        // selections are only cleared by the user unchecking them or by actually closing
        // the app (see closeApps()), never just by a refresh.
        selectedPackages.retainAll(apps.map { it.packageName }.toSet())

        applyAppsFilter()
        updateEssentialRow()
        updateAppsLoadingState()

        val runningCount = apps.count { it.isRunning }
        findViewById<TextView>(R.id.tvAppListHeader).text =
            getString(R.string.app_list_header, runningCount, apps.size)
    }

    /** Re-applies [appsQuery] to the current [apps] list and submits the result to the adapter.
     *  Called after every scan and on every keystroke in the search box - cheap, in-memory
     *  filtering, so there is no need to re-scan installed apps just to search them. */
    private fun applyAppsFilter() {
        val query = appsQuery.trim()
        val filtered = if (query.isEmpty()) {
            apps
        } else {
            apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
        findViewById<View>(R.id.tvAppsSearchNoResults).isVisible = query.isNotEmpty() && filtered.isEmpty()
    }

    /** Shows the full-screen "Preparing your apps…" spinner in place of the (empty) list
     *  whenever there's genuinely nothing to show yet - the very first launch, before
     *  [loadApps] has ever finished once, and any later scan that happens to come back empty
     *  (e.g. everything filtered out). Hides the moment the list actually has something in it.
     *  Deliberately independent of [isLoadingApps]: a first launch that's slow to scan should
     *  keep showing this the whole time, not just for the duration of the background thread. */
    private fun updateAppsLoadingState() {
        findViewById<View>(R.id.loadingAppsState).isVisible = apps.isEmpty()
    }

    private fun updateEssentialRow() {
        val row = findViewById<View>(R.id.rowEssentialHidden)
        val text = findViewById<TextView>(R.id.tvEssentialHidden)
        if (showSystem && essentialHiddenCount > 0 && !showEssential && !essentialBannerDismissed) {
            row.isVisible = true
            text.text = resources.getQuantityString(
                R.plurals.essential_hidden_banner, essentialHiddenCount, essentialHiddenCount
            )
            row.setOnClickListener { promptShowEssential() }
        } else {
            row.isVisible = false
            row.setOnClickListener(null)
        }
    }

    private fun promptShowEssential() {
        AlertDialog.Builder(this)
            .setTitle(R.string.essential_dialog_title)
            .setMessage(R.string.essential_dialog_message)
            .setPositiveButton(R.string.essential_dialog_confirm) { _, _ ->
                showEssential = true
                Prefs.setShowEssential(this, true)
                loadApps()
            }
            .setNegativeButton(R.string.quick_cancel, null)
            .show()
    }

    /** Best-effort versionName lookup, used so every row can always show "package • vX.Y"
     *  instead of only sometimes having a version to display. Returns null (never crashes
     *  the list) if the OS can't resolve one for this package. */
    private fun versionNameFor(pm: PackageManager, packageName: String): String? = try {
        pm.getPackageInfo(packageName, 0).versionName
    } catch (_: Exception) {
        null
    }

    /** Opens system App Info for a single package, so the user can check or change its
     *  permissions directly. Used by the per-app settings shortcut and the Quick List. */
    private fun openAppInfo(packageName: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:$packageName".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            Notice.show(this, getString(R.string.open_app_info_failed))
        }
    }

    // ---------- Quick List section ----------

    /** @param rebindMainList rebinds the main list so its pin badges match the Quick List. Only
     *    needed when the saved list itself changed, so the periodic refresh passes false. */
    private fun loadQuickList(rebindMainList: Boolean = true) {
        pruneClosedSelections()
        val pm = packageManager
        val packages = QuickList.load(this)
        quickPackageSet = packages.toSet()
        // Forget ticks for apps that were removed from the Quick List.
        quickSelectedPackages.retainAll(quickPackageSet)
        quickEntries = packages.map { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                QuickAppEntry(
                    pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), versionNameFor(pm, pkg),
                    isRunning = (ai.flags and ApplicationInfo.FLAG_STOPPED) == 0,
                    selected = pkg in quickSelectedPackages
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // Uninstalled since it was added. It stays listed (dimmed) so reinstalling it
                // just works, and the user decides when to remove it.
                QuickAppEntry(pkg, pkg, pm.defaultActivityIcon, installed = false)
            }
        }
        applyQuickListFilter()

        val isEmpty = quickEntries.isEmpty()
        findViewById<View>(R.id.tvQuickListEmpty).isVisible = isEmpty
        findViewById<View>(R.id.recyclerQuickList).isVisible = !isEmpty
        findViewById<View>(R.id.btnCloseQuickList).isVisible = !isEmpty
        findViewById<View>(R.id.btnClearQuickList).isVisible = !isEmpty
        findViewById<View>(R.id.rowQuickShowStopped).isVisible = !isEmpty
        findViewById<View>(R.id.btnQuickSelectAll).isVisible = !isEmpty
        findViewById<TextView>(R.id.tvQuickListCount).text = if (isEmpty) {
            ""
        } else {
            resources.getQuantityString(R.plurals.quick_saved_count, quickEntries.size, quickEntries.size)
        }

        // The pin badges in the main list depend on the Quick List, so rebind its rows.
        if (rebindMainList) adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    /** Re-applies [quickQuery] and [quickShowStopped] to the current [quickEntries] and submits
     *  the result to the adapter. Called after every reload and on every keystroke/toggle. */
    private fun applyQuickListFilter() {
        val query = quickQuery.trim()
        var filtered = quickEntries
        if (!quickShowStopped) filtered = filtered.filter { it.isRunning }
        if (query.isNotEmpty()) {
            filtered = filtered.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
        quickVisible = filtered
        quickAdapter.submitList(filtered)
        val noResults = quickEntries.isNotEmpty() && filtered.isEmpty()
        findViewById<View>(R.id.tvQuickSearchNoResults).isVisible = noResults
        updateQuickSelectAllButton()
    }

    /** Ticks every installed row currently shown, or - when they are all already ticked -
     *  unticks them. One button, two directions, so it doubles as "Unselect all". */
    private fun toggleQuickSelectAll() {
        val targets = quickVisible.filter { it.installed }.map { it.packageName }.toSet()
        if (targets.isEmpty()) return
        val selectAll = !targets.all { it in quickSelectedPackages }
        if (selectAll) quickSelectedPackages.addAll(targets) else quickSelectedPackages.removeAll(targets)
        // New entry instances (not in-place mutation) so DiffUtil sees the change and rebinds.
        quickEntries = quickEntries.map {
            if (it.packageName in targets) it.copy(selected = selectAll) else it
        }
        applyQuickListFilter()
    }

    private fun updateQuickSelectAllButton() {
        val button = findViewById<MaterialButton>(R.id.btnQuickSelectAll)
        val targets = quickVisible.filter { it.installed }
        val allSelected = targets.isNotEmpty() && targets.all { it.packageName in quickSelectedPackages }
        button.isEnabled = targets.isNotEmpty()
        button.setText(if (allSelected) R.string.quick_unselect_all else R.string.quick_select_all)
    }

    /**
     * The apps ticked in the main list, read from [selectedPackages] - the single source of truth.
     * Reading the adapter's rows instead is unreliable: a row's own `selected` flag can be stale
     * for a moment after a list refresh (the new list is applied asynchronously, so a tick made
     * during that window never reaches it), which made "Close selected" claim nothing was ticked.
     */
    private fun tickedApps(): List<String> =
        apps.map { it.packageName }.filter { it in selectedPackages }

    /** Same idea for the Quick List: ticked, installed entries, in list order. */
    private fun tickedQuickApps(): List<String> =
        quickEntries.filter { it.installed && it.packageName in quickSelectedPackages }.map { it.packageName }

    /** Adds the apps ticked in the main list. Merges into the saved list, never replaces it. */
    private fun onAddSelectedToQuickList() {
        val selected = tickedApps()
        if (selected.isEmpty()) {
            Notice.show(this, getString(R.string.quick_select_first))
            return
        }
        addToQuickList(selected)
    }

    private fun addToQuickList(packages: List<String>) {
        val result = QuickList.add(this, packages)
        loadQuickList()
        val message = when {
            result.added == 0 -> getString(R.string.quick_already_all)
            result.alreadyPresent == 0 ->
                resources.getQuantityString(R.plurals.quick_added, result.added, result.added)
            else -> getString(R.string.quick_added_some, result.added, result.alreadyPresent)
        }
        if (currentSection == HomeTab.APPS) {
            // Offer a jump to the list instead of silently leaving the user on the Apps tab.
            Notice.show(this, message, actionLabel = getString(R.string.quick_view)) {
                setSection(HomeTab.QUICK_LIST)
            }
        } else {
            Notice.show(this, message)
        }
    }

    private fun onRemoveFromQuickList(entry: QuickAppEntry) {
        val before = QuickList.load(this)
        QuickList.remove(this, entry.packageName)
        loadQuickList()
        Notice.show(this, getString(R.string.quick_removed, entry.label), actionLabel = getString(R.string.quick_undo)) {
            QuickList.replaceAll(this, before)
            loadQuickList()
        }
    }

    private fun confirmClearQuickList() {
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_clear_title)
            .setMessage(R.string.quick_clear_message)
            .setPositiveButton(R.string.quick_clear_confirm) { _, _ ->
                val before = QuickList.load(this)
                QuickList.clear(this)
                loadQuickList()
                Notice.show(this, getString(R.string.quick_cleared), actionLabel = getString(R.string.quick_undo)) {
                    QuickList.replaceAll(this, before)
                    loadQuickList()
                }
            }
            .setNegativeButton(R.string.quick_cancel, null)
            .show()
    }

    // ---------- Quick List import / export ----------

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) writeQuickListExport(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) readQuickListImport(uri)
        }

    private fun showQuickListMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EXPORT, 0, R.string.quick_menu_export)
            menu.add(0, MENU_IMPORT, 1, R.string.quick_menu_import)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EXPORT -> startQuickListExport()
                    MENU_IMPORT -> startQuickListImport()
                }
                true
            }
            show()
        }
    }

    /** Opens the system "save as" screen. Nothing is written until the user picks a place. */
    private fun startQuickListExport() {
        if (QuickList.load(this).isEmpty()) {
            Notice.show(this, getString(R.string.quick_export_empty))
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = QuickListBackup.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, QuickListBackup.suggestedFileName())
        }
        launchFilePicker(exportLauncher, intent)
    }

    private fun writeQuickListExport(uri: Uri) {
        // Labels are only a courtesy for whoever opens the file; apps that are no longer
        // installed have no label of their own, so they are saved by package name alone.
        val labels = quickEntries.filter { it.installed }.associate { it.packageName to it.label }
        val entries = QuickList.load(this).map { QuickListBackup.Entry(it, labels[it]) }
        val json = QuickListBackup.toJson(entries)
        val resolver = contentResolver
        Background.io {
            val saved = QuickListBackup.write(resolver, uri, json)
            Background.main {
                if (isFinishing || isDestroyed) return@main
                if (saved) {
                    Notice.show(
                        this,
                        resources.getQuantityString(R.plurals.quick_export_done, entries.size, entries.size)
                    )
                } else {
                    Notice.show(this, getString(R.string.quick_export_failed), persistent = true)
                }
            }
        }
    }

    private fun startQuickListImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, QuickListBackup.IMPORT_MIME_TYPES)
        }
        launchFilePicker(importLauncher, intent)
    }

    private fun launchFilePicker(launcher: ActivityResultLauncher<Intent>, intent: Intent) {
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Notice.show(this, getString(R.string.quick_file_picker_missing), persistent = true)
        }
    }

    private fun readQuickListImport(uri: Uri) {
        val resolver = contentResolver
        val ownPackage = packageName
        Background.io {
            val text = QuickListBackup.read(resolver, uri)
            val parsed = text?.let { QuickListBackup.parse(it, ownPackage) }
            Background.main {
                if (isFinishing || isDestroyed) return@main
                when (parsed) {
                    null -> Notice.show(this, getString(R.string.quick_import_failed_read), persistent = true)
                    is QuickListBackup.ParseResult.NotAQuickList ->
                        Notice.show(this, getString(R.string.quick_import_bad_file), persistent = true)
                    is QuickListBackup.ParseResult.Success ->
                        if (parsed.packages.isEmpty()) {
                            Notice.show(this, getString(R.string.quick_import_nothing))
                        } else {
                            importIntoQuickList(parsed.packages, parsed.invalid)
                        }
                }
            }
        }
    }

    /** Merges an imported list into the saved one. Like every other way of adding apps, it only
     *  ever adds: nothing saved is replaced or reordered. Apps that are not installed on this
     *  phone are kept too, so restoring a backup onto a new phone works before the apps are
     *  reinstalled. Undo puts the list back exactly as it was. */
    private fun importIntoQuickList(packages: List<String>, invalid: Int) {
        val before = QuickList.load(this)
        val result = QuickList.add(this, packages)
        loadQuickList()
        var message = when {
            result.added == 0 -> getString(R.string.quick_import_already_all)
            result.alreadyPresent == 0 ->
                resources.getQuantityString(R.plurals.quick_imported, result.added, result.added)
            else -> getString(R.string.quick_imported_some, result.added, result.alreadyPresent)
        }
        if (invalid > 0) {
            message += getString(R.string.close_summary_separator) +
                resources.getQuantityString(R.plurals.quick_import_invalid, invalid, invalid)
        }
        if (result.added == 0) {
            Notice.show(this, message)
        } else {
            Notice.show(this, message, actionLabel = getString(R.string.quick_undo)) {
                QuickList.replaceAll(this, before)
                loadQuickList()
            }
        }
    }

    /**
     * Searchable multi-select picker for apps that are not in the Quick List yet. It draws from
     * the same list as the "All apps" tab, so the "Show system apps" settings apply here too.
     */
    // A dialog body has no parent ViewGroup to inflate into.
    @SuppressLint("InflateParams")
    private fun showAddAppsDialog() {
        if (apps.isEmpty()) {
            Notice.show(this, getString(R.string.quick_add_still_loading))
            return
        }
        val candidates = apps.filter { it.packageName !in quickPackageSet }
        if (candidates.isEmpty()) {
            Notice.show(this, getString(R.string.quick_add_none_available))
            return
        }

        val content = layoutInflater.inflate(R.layout.dialog_add_apps, null)
        val list = content.findViewById<RecyclerView>(R.id.recyclerPickApps)
        val emptyView = content.findViewById<View>(R.id.tvPickEmpty)
        val search = content.findViewById<EditText>(R.id.etSearchApps)
        val chosen = mutableSetOf<String>()

        // The list has a comfortable fixed height, but never more than 40% of the window, so the
        // dialog still fits (with its buttons) in landscape, split-screen and small windows.
        val density = resources.displayMetrics.density
        val listFrame = content.findViewById<View>(R.id.pickListFrame)
        val maxListHeight = (resources.configuration.screenHeightDp * density * 0.4f).toInt()
            .coerceAtLeast((120 * density).toInt())
        listFrame.layoutParams = listFrame.layoutParams.apply { height = min(height, maxListHeight) }

        lateinit var dialog: AlertDialog
        fun refreshConfirmButton() {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE) ?: return
            button.isEnabled = chosen.isNotEmpty()
            button.text = if (chosen.isEmpty()) {
                getString(R.string.quick_add_confirm)
            } else {
                getString(R.string.quick_add_confirm_count, chosen.size)
            }
        }

        val pickAdapter = AppPickerAdapter(chosen) { refreshConfirmButton() }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = pickAdapter
        pickAdapter.submitList(candidates)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                val filtered = if (query.isEmpty()) {
                    candidates
                } else {
                    candidates.filter {
                        it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
                }
                pickAdapter.submitList(filtered)
                emptyView.isVisible = filtered.isEmpty()
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.quick_add_title)
            .setView(content)
            .setPositiveButton(R.string.quick_add_confirm) { _, _ -> addToQuickList(chosen.toList()) }
            .setNegativeButton(R.string.quick_cancel, null)
            .create()
        dialog.show()
        refreshConfirmButton()
    }

    // ---------- Permission / root status ----------

    /** Re-checks root and automation permissions every time the user opens or returns to
     *  the app, and surfaces anything that needs attention as a dismissible banner instead
     *  of failing silently later when the user tries to close an app. Root checks do
     *  blocking I/O, so they run off the main thread. */
    private fun refreshPermissionStatus() {
        val appContext = applicationContext
        Background.io {
            val rootManagerInstalled = RootUtils.isRootManagerInstalled(appContext)
            val rootCapable = rootManagerInstalled || RootUtils.isDeviceRooted()
            val rootGranted = rootCapable && RootUtils.isRootGranted()

            Background.main {
                if (!isFinishing && !isDestroyed) updatePermissionBanner(rootManagerInstalled, rootGranted)
            }
        }
    }

    private fun updatePermissionBanner(rootManagerInstalled: Boolean, rootGranted: Boolean) {
        val row = findViewById<View>(R.id.rowPermissionWarning)
        val text = findViewById<TextView>(R.id.tvPermissionWarning)

        // Manual mode works without the Accessibility Service, so don't nag about it there.
        // Root (like manual mode) doesn't need the Accessibility Service, so don't nag about it.
        val accessibilityMissing =
            !Prefs.getManualMode(this) && !rootGranted && !isAccessibilityServiceEnabled()

        val message = when {
            rootManagerInstalled && !rootGranted -> getString(R.string.banner_root_denied)
            accessibilityMissing -> getString(R.string.banner_accessibility_missing)
            else -> null
        }

        if (message == null || message == dismissedPermissionWarning) {
            row.isVisible = false
            row.setOnClickListener(null)
            return
        }

        row.isVisible = true
        text.text = message
        row.setOnClickListener {
            if (rootManagerInstalled && !rootGranted) requestRootAccess()
            else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestRootAccess() {
        Background.io {
            val granted = RootUtils.isRootGranted()
            Background.main {
                if (isFinishing || isDestroyed) return@main
                Notice.show(this, getString(if (granted) R.string.root_granted else R.string.root_not_granted))
                refreshPermissionStatus()
            }
        }
    }

    // ---------- Closing apps (shared by "Close selected" and "Close Quick List") ----------

    /**
     * Closes [packages] off the main thread (root commands block) and then shows one summary of
     * what happened. See [AppCloser.closeNow] for the strategy.
     */
    private fun closeApps(packages: List<String>) {
        // Ticks stay while closing. When the whole request is over, pruneClosedSelections()
        // unticks the apps that really stopped and leaves the still-running ones ticked, in both
        // the main list and the Quick List.
        CloseTracker.begin(packages)

        val appContext = applicationContext
        Background.io {
            val report = AppCloser.closeNow(appContext, packages)
            Background.main {
                if (isFinishing || isDestroyed) return@main
                onCloseFinished(report)
            }
        }
    }

    /**
     * Unticks every app from the last close request that is no longer running, in both lists.
     * Apps that are still running (or came straight back) keep their tick so the user can see
     * what is left and try again. Does nothing while the request is still in flight.
     */
    private fun pruneClosedSelections() {
        for (pkg in CloseTracker.drainIfIdle()) {
            if (!AppCloser.isRunning(this, pkg)) {
                selectedPackages.remove(pkg)
                quickSelectedPackages.remove(pkg)
            }
        }
    }

    private fun onCloseFinished(report: CloseReport) {
        loadApps()
        loadQuickList(rebindMainList = false)
        updateRamStats()
        // Killed processes take a moment to disappear from the list.
        recycler.postDelayed({ updateRamStats(); loadApps() }, refreshAfterCloseMs)

        // Second pass, always on: drive the real Settings Force stop button for apps still
        // holding a foreground service open. Its own overlay is the feedback, so the summary
        // message is skipped in that case.
        if (report.deepCandidates.isNotEmpty()) {
            runDeepForceStop(report.deepCandidates)
            return
        }
        // Nothing more to do: the request is over, so untick whatever stopped.
        CloseTracker.finish()
        Notice.show(this, report.summary(this), persistent = report.hasProblems)
    }

    private fun runDeepForceStop(packages: List<String>) {
        // Manual mode never needs the Accessibility Service, so it never asks for it either.
        if (Prefs.getManualMode(this)) {
            ManualCloser.start(this, packages)
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            pendingDeepForceStopPackages = packages
            // No run is happening right now; the apps simply stay ticked until one is.
            CloseTracker.finish()
            promptEnableAccessibility()
            return
        }
        if (shouldPromptBatteryException()) {
            promptBatteryException(packages)
            return
        }
        startOverlayService(packages)
    }

    private fun startOverlayService(packages: List<String>) {
        // Re-arm tracking (it may have been released by a permission prompt). OverlayService
        // calls CloseTracker.finish() when the run ends.
        CloseTracker.begin(packages)
        val intent = Intent(this, OverlayService::class.java)
        intent.putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: RuntimeException) {
            // The service never started, so nothing will call CloseTracker.finish() for us.
            KLog.w("Could not start the closing service: ${e.message}")
            CloseTracker.finish()
        }
    }

    /** Only relevant on OEM skins known to kill backgrounded accessibility work (MIUI,
     *  ColorOS/realme UI, Funtouch OS, EMUI, etc.) - stock Android and most of One UI
     *  don't need this, so there's nothing to interrupt there. Asked once; the user can
     *  revisit it from Settings → "Run reliably in the background" any time. */
    private fun shouldPromptBatteryException(): Boolean {
        if (!OemAutostartHelper.hasAggressiveBackgroundRestrictions()) return false
        if (Prefs.getBatteryPromptShown(this)) return false
        return !OemAutostartHelper.isIgnoringBatteryOptimizations(this)
    }

    private fun promptBatteryException(packages: List<String>) {
        Prefs.setBatteryPromptShown(this, true)
        val skin = OemAutostartHelper.skinName(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_dialog_title, skin))
            .setMessage(getString(R.string.battery_dialog_message, skin))
            .setPositiveButton(R.string.battery_dialog_allow) { _, _ ->
                OemAutostartHelper.openBatteryOptimizationSettings(this)
                startOverlayService(packages)
            }
            .setNegativeButton(R.string.battery_dialog_skip) { _, _ -> startOverlayService(packages) }
            .setOnCancelListener { CloseTracker.finish() }
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        AccessibilityUtils.isForceCloseServiceEnabled(this)

    private fun promptEnableAccessibility() {
        AlertDialog.Builder(this)
            .setTitle(R.string.a11y_disclosure_title)
            .setMessage(R.string.a11y_disclosure_message)
            .setPositiveButton(R.string.a11y_disclosure_agree) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.a11y_disclosure_decline) { _, _ -> pendingDeepForceStopPackages = null }
            .setOnCancelListener { pendingDeepForceStopPackages = null }
            .show()
    }

    /** A small, friendly confirmation buzz on key actions - works on every API level this
     *  app supports (HapticFeedbackConstants.CONFIRM needs API 30, so this uses the widely
     *  supported constant instead and asks the view to honor it even if a system setting
     *  would normally suppress virtual-key-style feedback). */
    private fun hapticTap(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    private companion object {
        const val MENU_EXPORT = 1
        const val MENU_IMPORT = 2

        // Keys for what survives the screen being recreated (rotation, theme or language change).
        const val STATE_SECTION = "state_section"
        const val STATE_SELECTED_APPS = "state_selected_apps"
        const val STATE_SELECTED_QUICK = "state_selected_quick"
        const val STATE_APPS_SEARCH_OPEN = "state_apps_search_open"
        const val STATE_QUICK_SEARCH_OPEN = "state_quick_search_open"
        const val STATE_ESSENTIAL_DISMISSED = "state_essential_dismissed"
        const val STATE_DISMISSED_PERMISSION = "state_dismissed_permission"
    }

}
