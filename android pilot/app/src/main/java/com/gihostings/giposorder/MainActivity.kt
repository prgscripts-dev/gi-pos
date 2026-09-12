package com.gihostings.giposorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem as AndroidMenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
  private val ink = Color.rgb(18, 32, 51)
  private val red = Color.rgb(199, 22, 55)
  private val teal = Color.rgb(8, 127, 140)
  private val muted = Color.rgb(99, 115, 138)
  private val line = Color.rgb(217, 226, 236)
  private val surface = Color.rgb(244, 247, 250)
  private val soft = Color.rgb(240, 247, 249)

  private var bootstrap = Bootstrap()
  private var sessionToken = ""
  private var currentUser: StaffUser? = null
  private var selectedTable: TableInfo? = null
  private var activeOrder: OpenOrder? = null
  private var selectedCategoryId: String? = null
  private var searchText = ""
  private var selectedPrinterId = ""
  private val cart = linkedMapOf<String, CartLine>()

  private lateinit var root: FrameLayout
  private lateinit var shell: LinearLayout
  private lateinit var content: LinearLayout
  private lateinit var statusView: TextView
  private lateinit var titleView: TextView
  private lateinit var subtitleView: TextView
  private lateinit var connectedPill: TextView

  // Sliding navigation drawer.
  private var drawerScrim: View? = null
  private var drawerPanel: LinearLayout? = null
  private var drawerOpen = false

  // Bottom cart bar + slide-up cart sheet (shown on the order screen).
  private var cartBar: LinearLayout? = null
  private var cartSheetScrim: View? = null
  private var cartSheet: LinearLayout? = null
  private var cartSheetList: LinearLayout? = null
  private var cartSheetTotal: TextView? = null
  private var onOrderScreen = false

  // Persistent bottom navigation (Report / Home / Settings) on the main table view.
  private var bottomNav: View? = null

  private val green = Color.rgb(22, 163, 74)
  private val amber = Color.rgb(180, 83, 9)
  private var autoSyncEnabled = true
  private val liveSync = android.os.Handler(android.os.Looper.getMainLooper())
  private var liveSyncScheduled = false
  // Near real-time: refresh the Main PC state every ~2.5s so mobile <-> software stay in sync.
  private val liveSyncIntervalMs = 2_500L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = "GI Captain"
    window.statusBarColor = surface
    window.navigationBarColor = ink

    when {
      // First run: no Main PC saved yet -> connect screen.
      ServerStore.get(this).isBlank() -> openServerSetup()
      // Already connected and a staff session is saved -> go straight into the POS.
      ServerStore.getSession(this).isNotBlank() -> {
        buildShell()
        restoreSavedSession()
      }
      // Connected but not signed in yet -> one-time QR scan.
      else -> {
        buildShell()
        renderScanPrompt()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Resume live sync when returning to the app while signed in.
    if (sessionToken.isNotBlank()) scheduleLiveSync()
  }

  override fun onPause() {
    stopLiveSync()
    super.onPause()
  }

  override fun onDestroy() {
    stopLiveSync()
    super.onDestroy()
  }

  private fun restoreSavedSession() {
    sessionToken = ServerStore.getSession(this)
    currentUser = runCatching { StaffUser.from(JSONObject(ServerStore.getSessionUser(this))) }.getOrNull()
    if (sessionToken.isBlank() || currentUser == null) {
      ServerStore.clearSession(this)
      renderScanPrompt()
      return
    }
    loadBootstrap()
  }

  private fun buildShell() {
    root = FrameLayout(this)

    shell = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(surface)
    }

    // ---- Top app bar: hamburger (left) + app name + connected pill (right) ----
    val header = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(12.dp, 16.dp, 16.dp, 12.dp)
      setBackgroundColor(Color.WHITE)
      elevation = 3.dp.toFloat()
    }
    val menuButton = TextView(this).apply {
      text = "\u2261" // ≡ hamburger
      textSize = 26f
      gravity = Gravity.CENTER
      setTextColor(ink)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(soft, 12.dp, 1.dp, line)
      setOnClickListener { openDrawer() }
    }
    header.addView(menuButton, LinearLayout.LayoutParams(46.dp, 46.dp))
    val titleBlock = column().apply { setPadding(12.dp, 0, 0, 0) }
    titleView = text("GI Captain", 22f, ink, true)
    subtitleView = text("Connecting\u2026", 12f, muted, false)
    titleBlock.addView(titleView)
    titleBlock.addView(subtitleView)
    header.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    connectedPill = TextView(this).apply {
      textSize = 12f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(12.dp, 7.dp, 12.dp, 7.dp)
      visibility = View.GONE
    }
    header.addView(connectedPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    shell.addView(header)

    statusView = text("", 13f, muted, true).apply {
      visibility = View.GONE
      setPadding(12.dp, 10.dp, 12.dp, 10.dp)
    }
    val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    statusParams.setMargins(18.dp, 12.dp, 18.dp, 0)
    shell.addView(statusView, statusParams)

    content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(16.dp, 12.dp, 16.dp, 18.dp)
    }
    val scroller = ScrollView(this).apply { isFillViewport = true }
    scroller.addView(content)
    shell.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
  }

  // ---- Sliding navigation drawer ----
  private fun openDrawer() {
    if (drawerOpen) return
    drawerOpen = true

    val scrim = View(this).apply {
      setBackgroundColor(Color.argb(120, 0, 0, 0))
      alpha = 0f
      setOnClickListener { closeDrawer() }
    }
    drawerScrim = scrim
    root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    scrim.animate().alpha(1f).setDuration(160).start()

    val panel = buildDrawerPanel()
    drawerPanel = panel
    val widthPx = (resources.displayMetrics.widthPixels * 0.82f).toInt().coerceAtMost(340.dp)
    root.addView(panel, FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    panel.translationX = -widthPx.toFloat()
    panel.animate().translationX(0f).setDuration(200).start()
  }

  private fun closeDrawer() {
    if (!drawerOpen) return
    drawerOpen = false
    val panel = drawerPanel
    val scrim = drawerScrim
    scrim?.animate()?.alpha(0f)?.setDuration(160)?.withEndAction { scrim.let { root.removeView(it) } }?.start()
    if (panel != null) {
      panel.animate().translationX(-panel.width.toFloat()).setDuration(180).withEndAction { root.removeView(panel) }.start()
    }
    drawerPanel = null
    drawerScrim = null
  }

  private fun buildDrawerPanel(): LinearLayout {
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.WHITE)
      elevation = 8.dp.toFloat()
      setPadding(0, 0, 0, 12.dp)
    }

    // Header block with app identity.
    val head = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(20.dp, 28.dp, 20.dp, 18.dp)
      setBackgroundColor(ink)
    }
    head.addView(TextView(this).apply {
      text = "Captain"
      textSize = 22f
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
    })
    head.addView(text(currentUser?.name ?: "Not signed in", 13f, Color.rgb(197, 208, 222), false).withTop(4.dp))
    panel.addView(head)

    // Scanner-connected status row.
    val connected = sessionToken.isNotBlank() && currentUser != null
    val statusRow = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(20.dp, 16.dp, 20.dp, 16.dp)
    }
    statusRow.addView(TextView(this).apply {
      text = "\u25CF"
      textSize = 14f
      setTextColor(if (connected) green else amber)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 10.dp })
    val statusText = column()
    statusText.addView(text(if (connected) "Scanner connected" else "Not connected", 15f, ink, true))
    statusText.addView(text(if (connected) (ServerStore.get(this).removeSuffix("/")) else "Connect to a Main PC", 12f, muted, false))
    statusRow.addView(statusText)
    panel.addView(statusRow)
    panel.addView(divider())

    // Dashboard / Home (the main table view).
    panel.addView(drawerItem("Dashboard", "Tables, today and open orders") {
      closeDrawer(); if (currentUser != null) { selectedTable = null; renderTables() } else renderScanPrompt()
    })

    panel.addView(divider())

    // Find Server.
    panel.addView(drawerItem("Find Server", "Auto-detect the Main PC on Wi-Fi") {
      closeDrawer(); openServerSetup()
    })

    // Printer Settings.
    panel.addView(drawerItem("Printer Settings", "IP, Bluetooth or Wi-Fi printer") {
      closeDrawer(); if (currentUser != null) renderPrinterSettings() else renderScanPrompt()
    })

    // Auto-sync toggle.
    panel.addView(drawerToggle("Auto-sync", if (autoSyncEnabled) "Live table updates on" else "Live table updates off", autoSyncEnabled) { enabled ->
      autoSyncEnabled = enabled
      if (enabled) {
        scheduleLiveSync()
        if (sessionToken.isNotBlank()) loadBootstrap(silent = true)
      } else {
        stopLiveSync()
      }
    })

    // Refresh now.
    panel.addView(drawerItem("Refresh now", "Reload tables and menu") {
      closeDrawer(); if (currentUser == null) renderScanPrompt() else loadBootstrap()
    })

    panel.addView(divider())

    // Logout.
    panel.addView(drawerItem("Logout", "Sign out this staff member", danger = true) {
      closeDrawer(); doLogout()
    })

    return panel
  }

  private fun drawerItem(title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(20.dp, 14.dp, 20.dp, 14.dp)
      isClickable = true
      setOnClickListener { onClick() }
      background = rippleBackground()
    }
    row.addView(text(title, 16f, if (danger) Color.rgb(190, 24, 54) else ink, true))
    row.addView(text(subtitle, 12f, muted, false).withTop(2.dp))
    return row
  }

  private fun drawerToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val row = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(20.dp, 14.dp, 20.dp, 14.dp)
    }
    val labels = column()
    labels.addView(text(title, 16f, ink, true))
    val sub = text(subtitle, 12f, muted, false).withTop(2.dp)
    labels.addView(sub)
    row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val toggle = android.widget.Switch(this).apply {
      isChecked = checked
      setOnCheckedChangeListener { _, value ->
        sub.text = if (title == "Auto-sync") (if (value) "Live table updates on" else "Live table updates off") else subtitle
        onChange(value)
      }
    }
    row.addView(toggle)
    return row
  }

  private fun divider(): View = View(this).apply {
    setBackgroundColor(line)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
      topMargin = 4.dp
      bottomMargin = 4.dp
    }
  }

  private fun rippleBackground(): GradientDrawable = GradientDrawable().apply { setColor(Color.WHITE) }

  private fun doLogout() {
    val token = sessionToken
    currentUser = null
    sessionToken = ""
    ServerStore.clearSession(this)
    Thread { runCatching { postJson("/api/mobile/logout", JSONObject(), token) } }.start()
    renderScanPrompt()
  }

  private fun renderScanPrompt() {
    if (!::content.isInitialized) return
    stopLiveSync()
    onOrderScreen = false
    refreshCartBar()
    hideBottomNav()
    currentUser = null
    sessionToken = ""
    if (::connectedPill.isInitialized) connectedPill.visibility = View.GONE
    titleView.text = "Staff Login"
    subtitleView.text = "Connected to Main PC"
    content.removeAllViews()
    val card = card()
    card.addView(text("Scan your staff QR", 22f, ink, true))
    card.addView(text("Scan the QR code from the client portal for your staff account. Only your assigned permissions will apply.", 15f, muted, false).withTop(8.dp))
    card.addView(primaryButton("Scan QR Code").apply { setOnClickListener { openQrScan() } }.withTop(20.dp).withHeight(54.dp))
    card.addView(headerButton("Change Server").apply { setOnClickListener { openServerSetup() } }.withTop(10.dp).withHeight(48.dp))
    content.addView(card)
    showStatus("Tap Scan QR Code and point the camera at the staff QR.", false)
  }

  private fun openQrScan() {
    startActivityForResult(Intent(this, ScanQrActivity::class.java), REQUEST_SCAN)
  }

  private fun loadBootstrap(silent: Boolean = false) {
    if (!::content.isInitialized || sessionToken.isBlank()) return
    if (!silent) showLoading("Loading POS data...")
    Thread {
      val result = runCatching { getJson("/api/mobile/bootstrap", sessionToken) }
      runOnUiThread {
        result.onSuccess {
          bootstrap = Bootstrap.from(it)
          selectedPrinterId = bootstrap.printers.firstOrNull { printer -> printer.isBillPrinter }?.id
            ?: bootstrap.printers.firstOrNull()?.id.orEmpty()
          // Re-apply the staff member's live permissions from the server on every refresh.
          val serverUser = it.optJSONObject("currentUser")
          if (serverUser != null) {
            currentUser = StaffUser.from(serverUser)
            ServerStore.saveSession(this@MainActivity, sessionToken, serverUser.toString())
          }
          setConnected(true)
          scheduleLiveSync()
          // A silent refresh should not disturb the screen the user is on (e.g. an open order).
          if (!silent || selectedTable == null) {
            renderTables()
          }
        }.onFailure { error ->
          val isAuthRejection = error is ApiException && (error.statusCode == 401 || error.statusCode == 403)
          when {
            // A real server auth rejection means this staff account was disabled or lost access.
            // Only in this case do we end the session and return to the QR scan screen.
            isAuthRejection -> {
              stopLiveSync()
              sessionToken = ""
              currentUser = null
              ServerStore.clearSession(this@MainActivity)
              setConnected(false)
              showStatus("Your access was ended by the admin. Scan your staff QR to sign in again.", true)
              renderScanPrompt()
            }
            // Any other failure (network drop, timeout, server unreachable) keeps the session.
            // Show the professional Connection Lost screen with Reconnect.
            else -> {
              setConnected(false)
              if (silent) {
                // Background refresh failed: keep retrying quietly, keep the current screen.
                scheduleLiveSync()
              } else {
                renderConnectionLost()
              }
            }
          }
        }
      }
    }.start()
  }

  private fun setConnected(connected: Boolean) {
    if (!::connectedPill.isInitialized) return
    connectedPill.visibility = View.VISIBLE
    if (connected) {
      connectedPill.text = "\u25CF Connected"
      connectedPill.setTextColor(Color.WHITE)
      connectedPill.background = shape(green, 20)
    } else {
      connectedPill.text = "\u25CF Reconnecting"
      connectedPill.setTextColor(Color.WHITE)
      connectedPill.background = shape(amber, 20)
    }
  }

  // Keeps the app in sync with the Main PC while connected: refreshes free tables / open orders and
  // re-applies the staff member's permissions on a short interval.
  private fun scheduleLiveSync() {
    if (!autoSyncEnabled || liveSyncScheduled || sessionToken.isBlank()) return
    liveSyncScheduled = true
    liveSync.postDelayed(object : Runnable {
      override fun run() {
        liveSyncScheduled = false
        if (sessionToken.isBlank()) return
        loadBootstrap(silent = true)
      }
    }, liveSyncIntervalMs)
  }

  private fun stopLiveSync() {
    liveSyncScheduled = false
    liveSync.removeCallbacksAndMessages(null)
  }

  // Professional full-screen "Connection lost" state. The session is preserved; tapping Reconnect
  // retries the Main PC and, on success, returns to the POS with the green Connected indicator.
  private var reconnecting = false

  private fun renderConnectionLost() {
    if (!::content.isInitialized) return
    stopLiveSync()
    onOrderScreen = false
    refreshCartBar()
    hideBottomNav()
    statusView.visibility = View.GONE
    titleView.text = "GI Captain"
    subtitleView.text = currentUser?.name ?: "Offline"
    content.removeAllViews()

    // Centered, polished offline state.
    val wrap = column().apply {
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(20.dp, 36.dp, 20.dp, 24.dp)
    }

    // Circular icon badge with a drawn Wi-Fi-off glyph.
    val badge = FrameLayout(this).apply {
      background = shape(Color.rgb(254, 226, 226), 60.dp)
    }
    badge.addView(WifiOffIconView(this, Color.rgb(220, 38, 38)), FrameLayout.LayoutParams(60.dp, 60.dp, Gravity.CENTER))
    wrap.addView(badge, LinearLayout.LayoutParams(112.dp, 112.dp))

    wrap.addView(text("Network Connection Lost", 22f, ink, true).apply { gravity = Gravity.CENTER }.withTop(20.dp))
    wrap.addView(text("We can't reach the Main PC right now. Check that this device is on the restaurant Wi-Fi and the Local POS Server is running.", 14f, muted, false).apply {
      gravity = Gravity.CENTER
      setLineSpacing(4f, 1f)
    }.withTop(10.dp))

    val card = wrap

    val reconnectBtn = Button(this).apply {
      text = "Reconnect"
      isAllCaps = false
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      textSize = 16f
      background = shape(teal, 14.dp)
    }
    reconnectBtn.setOnClickListener {
      if (reconnecting) return@setOnClickListener
      reconnecting = true
      reconnectBtn.text = "Reconnecting\u2026"
      reconnectBtn.isEnabled = false
      Thread {
        // Prefer a lightweight health probe, then a full bootstrap.
        val ok = runCatching { getJson("/api/health") }.map { it.optBoolean("ok", false) }.getOrDefault(false)
        runOnUiThread {
          reconnecting = false
          if (ok && sessionToken.isNotBlank()) {
            setConnected(true)
            loadBootstrap()
          } else {
            reconnectBtn.text = "Reconnect"
            reconnectBtn.isEnabled = true
            showStatus("Still can't reach the Main PC. Please try again.", true)
          }
        }
      }.start()
    }
    card.addView(reconnectBtn.withTop(26.dp).withHeight(54.dp))

    // A separate escape hatch to change the server (does NOT log the staff member out).
    card.addView(Button(this).apply {
      text = "Change server"
      isAllCaps = false
      setTextColor(ink)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(Color.WHITE, 14.dp, 1.dp, line)
      setOnClickListener { openServerSetup() }
    }.withTop(10.dp).withHeight(50.dp))

    content.addView(card)
  }

  private fun onQrLoginResult(token: String, userJson: String) {
    sessionToken = token
    currentUser = runCatching { StaffUser.from(JSONObject(userJson)) }.getOrNull()
    if (sessionToken.isBlank() || currentUser == null) {
      showStatus("QR login did not complete. Scan again.", true)
      renderScanPrompt()
      return
    }
    // One-time setup: remember this session so the app stays signed in until logout/reset.
    ServerStore.saveSession(this, token, userJson)
    loadBootstrap()
  }

  private var selectedFloor: String? = null

  // A clean summary dashboard reachable from the drawer.
  private fun renderTables() {
    onOrderScreen = false
    refreshCartBar()
    content.removeAllViews()
    // The app-bar header already shows "GI Captain" + business/user; here we mirror the reference
    // GI POS Mobile home: Today + Open orders cards, Tables heading + pill, area chips, seat cards.
    titleView.text = bootstrap.businessName
    subtitleView.text = currentUser?.name ?: "Ready for service"
    statusView.visibility = View.GONE

    // Enforce per-user table access from the Windows User Manage: only permitted tables are shown.
    val user = currentUser
    val allowedTables = bootstrap.tables.filter { user == null || user.canAccessTable(it.name) }
    val occupied = bootstrap.openOrderList.size
    val openTotal = bootstrap.openOrderList.sumOf { it.total }

    // ----- Summary cards: Today + Open orders -----
    val summary = row()
    val today = metricCard(
      "Today",
      "Rs. ${money(bootstrap.paidTotal)}",
      "${bootstrap.paidBills} bills / ${formatQty(bootstrap.paidItems)} items",
    )
    summary.addView(today, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10.dp })
    val openOrdersCard = metricCard(
      "Open orders",
      occupied.toString(),
      if (occupied == 0) "No active orders" else "Rs. ${money(openTotal)} active",
    )
    summary.addView(openOrdersCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    content.addView(summary.withTop(2.dp))

    // ----- Tables heading + "Tap a table to order" pill -----
    val tablesHead = row().apply { gravity = Gravity.CENTER_VERTICAL }
    val headCopy = column()
    headCopy.addView(text("Tables", 20f, ink, true))
    headCopy.addView(text("${allowedTables.size} tables  \u2022  $occupied active", 12f, muted, false).withTop(2.dp))
    tablesHead.addView(headCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    tablesHead.addView(TextView(this).apply {
      text = "Tap a table to order"
      textSize = 11f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(teal)
      setPadding(10.dp, 7.dp, 10.dp, 7.dp)
      background = shape(Color.rgb(229, 247, 249), 9.dp)
    })
    content.addView(tablesHead.withTop(20.dp))

    // ----- Area chips (only areas that contain at least one permitted table) -----
    val floorBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val floors = linkedSetOf("All")
    allowedTables.mapTo(floors) { it.floor.ifBlank { "Main" } }
    floors.forEach { floor ->
      val isActive = (floor == "All" && selectedFloor == null) || floor == selectedFloor
      floorBar.addView(chip(floor, isActive) {
        selectedFloor = if (floor == "All") null else floor
        renderTables()
      }, chipParams())
    }
    val floorScroll = HorizontalScrollView(this).apply {
      isHorizontalScrollBarEnabled = false
      addView(floorBar)
    }
    content.addView(floorScroll.withTop(12.dp))

    // ----- 3-column table cards -----
    val grid = GridView(this).apply {
      numColumns = 3
      horizontalSpacing = 12.dp
      verticalSpacing = 12.dp
      stretchMode = GridView.STRETCH_COLUMN_WIDTH
      isVerticalScrollBarEnabled = false
      adapter = TableAdapter(allowedTables.filter { selectedFloor == null || it.floor == selectedFloor })
    }
    content.addView(grid.apply { withTop(12.dp) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // Leave room for the bottom navigation bar.
    content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90.dp))
    showBottomNav("home")
  }

  // Reference-style metric card (label / big value / detail).
  private fun metricCard(label: String, value: String, detail: String): LinearLayout {
    val card = column().apply {
      setPadding(14.dp, 13.dp, 14.dp, 13.dp)
      background = shape(Color.WHITE, 14.dp, 1.dp, line)
    }
    card.addView(text(label, 12f, muted, true))
    card.addView(text(value, 22f, ink, true).apply { setSingleLine(true) }.withTop(4.dp))
    card.addView(text(detail, 12f, muted, false).withTop(2.dp))
    return card
  }

  // ---- Bottom navigation: Report / Home (raised) / Settings ----
  private fun showBottomNav(active: String) {
    hideBottomNav()
    val holder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }

    val rail = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(12.dp, 5.dp, 12.dp, 4.dp)
      background = shape(Color.WHITE, 0, 1, line)
    }
    rail.addView(navItem("\u25A4", "Bills", active == "bills") { renderTotalBills() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    rail.addView(View(this), LinearLayout.LayoutParams(92.dp, 1))
    rail.addView(navItem("\u2699", "Settings", active == "settings") { openDrawer() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    holder.addView(rail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64.dp, Gravity.BOTTOM))

    val home = TextView(this).apply {
      text = "\u2302\nHome"
      textSize = 12f
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setLineSpacing(0f, 0.9f)
      background = shape(red, 20.dp)
      elevation = 8.dp.toFloat()
      setOnClickListener { if (currentUser != null) { selectedTable = null; renderTables() } }
    }
    holder.addView(home, FrameLayout.LayoutParams(70.dp, 70.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

    bottomNav = holder
    root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 82.dp, Gravity.BOTTOM))
  }

  private fun hideBottomNav() {
    bottomNav?.let { root.removeView(it) }
    bottomNav = null
  }

  private fun navItem(icon: String, label: String, active: Boolean, onClick: () -> Unit): LinearLayout {
    val color = if (active) red else muted
    val item = column().apply {
      gravity = Gravity.CENTER
      setPadding(6.dp, 4.dp, 6.dp, 3.dp)
      setOnClickListener { onClick() }
    }
    item.addView(text(icon, 18f, color, true).apply { gravity = Gravity.CENTER })
    item.addView(text(label, 11f, color, active).apply { gravity = Gravity.CENTER })
    return item
  }

  // Total Bills: total number of bills today + bills handled by the logged-in waiter.
  private fun renderTotalBills() {
    if (!::content.isInitialized || currentUser == null) return
    onOrderScreen = false
    refreshCartBar()
    content.removeAllViews()
    titleView.text = "Total Bills"
    subtitleView.text = currentUser?.name ?: bootstrap.businessName
    statusView.visibility = View.GONE

    val totalCard = card()
    totalCard.addView(text("Total bills", 13f, muted, true))
    totalCard.addView(text(bootstrap.paidBills.toString(), 34f, ink, true).withTop(4.dp))
    totalCard.addView(text("Rs. ${money(bootstrap.paidTotal)}  \u2022  ${formatQty(bootstrap.paidItems)} items", 13f, muted, false).withTop(2.dp))
    content.addView(totalCard)

    val mineCard = card().withTop(12.dp)
    mineCard.addView(text("My bills (${currentUser?.name ?: "You"})", 13f, muted, true))
    mineCard.addView(text(bootstrap.myBills.toString(), 34f, teal, true).withTop(4.dp))
    mineCard.addView(text("Rs. ${money(bootstrap.myBillsTotal)} handled by you today", 13f, muted, false).withTop(2.dp))
    content.addView(mineCard)

    val openCard = card().withTop(12.dp)
    openCard.addView(text("Open orders", 13f, muted, true))
    openCard.addView(text(bootstrap.openOrders.toString(), 34f, ink, true).withTop(4.dp))
    openCard.addView(text("Rs. ${money(bootstrap.openOrderList.sumOf { it.total })} active", 13f, muted, false).withTop(2.dp))
    content.addView(openCard)

    content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90.dp))
    showBottomNav("bills")
  }

  // Printer Settings: choose IP / Bluetooth / Wi-Fi and enter the address, persisted locally.
  private var printerTypeDraft = "ip"

  private fun renderPrinterSettings() {
    if (!::content.isInitialized || currentUser == null) return
    onOrderScreen = false
    refreshCartBar()
    hideBottomNav()
    content.removeAllViews()
    titleView.text = "Printer Settings"
    subtitleView.text = "Receipt & KOT printer"
    statusView.visibility = View.GONE

    printerTypeDraft = ServerStore.getPrinterType(this)

    val card = card()
    card.addView(text("Printer type", 15f, ink, true))

    val addressField = input(printerAddressHint(printerTypeDraft), InputType.TYPE_CLASS_TEXT)
    addressField.setText(ServerStore.getPrinterAddress(this))

    // Three selectable option rows.
    val options = listOf(
      Triple("ip", "IP address", "Network printer by IP, e.g. 192.168.1.50"),
      Triple("bluetooth", "Bluetooth printer", "Paired Bluetooth thermal printer"),
      Triple("wifi", "Wi-Fi printer", "Wi-Fi / network printer name or host"),
    )
    val optionViews = mutableListOf<LinearLayout>()
    options.forEach { (type, title, desc) ->
      val optRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        setOnClickListener {
          printerTypeDraft = type
          optionViews.forEachIndexed { i, v -> styleprinterOption(v, options[i].first == type) }
          addressField.hint = printerAddressHint(type)
        }
      }
      val radio = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
      optRow.addView(radio, LinearLayout.LayoutParams(28.dp, ViewGroup.LayoutParams.WRAP_CONTENT))
      val copy = column()
      copy.addView(text(title, 15f, ink, true))
      copy.addView(text(desc, 12f, muted, false).withTop(2.dp))
      optRow.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      optRow.tag = radio
      optionViews.add(optRow)
      card.addView(optRow.withTop(8.dp))
    }
    optionViews.forEachIndexed { i, v -> styleprinterOption(v, options[i].first == printerTypeDraft) }

    card.addView(text("Printer address / name", 13f, muted, true).withTop(14.dp))
    card.addView(addressField.withTop(6.dp).withHeight(52.dp))

    card.addView(primaryButton("Save Printer").apply {
      setOnClickListener {
        ServerStore.savePrinter(this@MainActivity, printerTypeDraft, addressField.text.toString())
        Toast.makeText(this@MainActivity, "Printer settings saved", Toast.LENGTH_SHORT).show()
        renderTables()
      }
    }.withTop(16.dp).withHeight(54.dp))

    content.addView(card)
    content.addView(text("The printer is used for KOT and bill printing from this device.", 12f, muted, false).apply { gravity = Gravity.CENTER }.withTop(12.dp))
  }

  private fun printerAddressHint(type: String): String = when (type) {
    "bluetooth" -> "Bluetooth device name"
    "wifi" -> "Wi-Fi printer host or name"
    else -> "192.168.1.50:9100"
  }

  private fun styleprinterOption(row: LinearLayout, selected: Boolean) {
    row.background = shape(if (selected) Color.rgb(229, 247, 249) else Color.WHITE, 12.dp, if (selected) 2.dp else 1.dp, if (selected) teal else line)
    (row.tag as? TextView)?.apply {
      text = if (selected) "\u25C9" else "\u25CB"
      setTextColor(if (selected) teal else muted)
    }
  }

  private fun openTable(table: TableInfo) {
    if (currentUser?.canAccessTable(table.name) == false) {
      showStatus("You do not have access to ${table.name}.", true)
      return
    }
    selectedTable = table
    val open = bootstrap.openOrderList.firstOrNull { order ->
      order.orderType == "Dining" && order.tables.any { it.equals(table.name, ignoreCase = true) }
    }
    activeOrder = open
    cart.clear()
    open?.cart?.forEach { cart[it.itemId] = it.copy() }
    selectedCategoryId = null
    searchText = ""
    renderOrder()
  }

  private fun renderOrder() {
    hideBottomNav()
    content.removeAllViews()
    val table = selectedTable
    titleView.text = table?.name ?: "New Order"
    subtitleView.text = currentUser?.name ?: "Staff"

    val search = input("Search item", InputType.TYPE_CLASS_TEXT)
    search.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        searchText = s?.toString().orEmpty()
        renderMenuArea()
      }
      override fun afterTextChanged(s: Editable?) {}
    })
    content.addView(search.withHeight(52.dp))

    val categoryScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    categoryRow.addView(chip("All", selectedCategoryId == null) {
      selectedCategoryId = null
      renderMenuArea()
    }, chipParams())
    bootstrap.categories.forEach { category ->
      categoryRow.addView(chip(category.name, category.id == selectedCategoryId) {
        selectedCategoryId = category.id
        renderMenuArea()
      }, chipParams())
    }
    categoryScroll.addView(categoryRow)
    content.addView(categoryScroll.withTop(12.dp))

    val menuHolder = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      tag = "menuHolder"
      // Leave room at the bottom so the cart bar never covers the last items.
      setPadding(0, 0, 0, 76.dp)
    }
    content.addView(menuHolder.withTop(10.dp))

    onOrderScreen = true
    renderMenuArea()
    refreshCartBar()
  }

  private fun renderMenuArea() {
    val holder = content.findViewWithTag<LinearLayout>("menuHolder") ?: return
    holder.removeAllViews()
    val items = bootstrap.menuItems.filter {
      (selectedCategoryId == null || it.category == selectedCategoryId) &&
        (searchText.isBlank() || it.name.contains(searchText, ignoreCase = true))
    }
    val grid = GridView(this).apply {
      numColumns = if (resources.configuration.screenWidthDp >= 600) 4 else 2
      horizontalSpacing = 8.dp
      verticalSpacing = 8.dp
      stretchMode = GridView.STRETCH_COLUMN_WIDTH
      adapter = ItemAdapter(items)
    }
    holder.addView(grid)
  }

  // ---- Bottom cart bar (persistent) ----
  private fun refreshCartBar() {
    // Remove the bar when leaving the order screen or when the cart is empty.
    if (!onOrderScreen || cart.isEmpty()) {
      cartBar?.let { root.removeView(it) }
      cartBar = null
      return
    }
    val totalQty = cart.values.sumOf { it.qty }
    val total = cart.values.sumOf { it.price * it.qty }

    val bar = cartBar ?: LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(18.dp, 12.dp, 18.dp, 12.dp)
      background = shape(teal, 0)
      elevation = 10.dp.toFloat()
      setOnClickListener { openCartSheet() }
      val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
      root.addView(this, lp)
      cartBar = this
    }
    bar.removeAllViews()
    val info = column()
    info.addView(text("${formatQty(totalQty)} item(s)", 12f, Color.rgb(200, 236, 240), true))
    info.addView(text("Rs. ${money(total)}", 20f, Color.WHITE, true))
    bar.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    bar.addView(TextView(this).apply {
      text = "View Cart  \u25B2"
      textSize = 15f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      setPadding(14.dp, 8.dp, 14.dp, 8.dp)
      background = shape(Color.rgb(6, 105, 116), 10.dp)
    })
  }

  // ---- Slide-up cart sheet ----
  private fun openCartSheet() {
    if (cartSheet != null || cart.isEmpty()) return
    val scrim = View(this).apply {
      setBackgroundColor(Color.argb(120, 0, 0, 0))
      alpha = 0f
      setOnClickListener { closeCartSheet() }
    }
    cartSheetScrim = scrim
    root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    scrim.animate().alpha(1f).setDuration(150).start()

    val sheet = buildCartSheet()
    cartSheet = sheet
    root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    sheet.post {
      sheet.translationY = sheet.height.toFloat()
      sheet.animate().translationY(0f).setDuration(200).start()
    }
  }

  private fun closeCartSheet() {
    val sheet = cartSheet
    val scrim = cartSheetScrim
    scrim?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { root.removeView(scrim) }?.start()
    if (sheet != null) {
      sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).withEndAction { root.removeView(sheet) }.start()
    }
    cartSheet = null
    cartSheetScrim = null
    cartSheetList = null
    cartSheetTotal = null
  }

  private fun buildCartSheet(): LinearLayout {
    val sheet = column().apply {
      setBackgroundColor(Color.WHITE)
      background = shapeTop(Color.WHITE, 20.dp)
      elevation = 16.dp.toFloat()
      setPadding(0, 8.dp, 0, 0)
    }
    // Grab handle + header (tap to close).
    val handle = View(this).apply { background = shape(Color.rgb(203, 213, 225), 3.dp) }
    val handleRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setOnClickListener { closeCartSheet() } }
    handleRow.addView(handle, LinearLayout.LayoutParams(44.dp, 5.dp))
    sheet.addView(handleRow)

    val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(18.dp, 10.dp, 18.dp, 6.dp) }
    val headText = column()
    headText.addView(text(selectedTable?.name ?: "Current Order", 18f, ink, true))
    headText.addView(text("${cart.size} line(s)", 12f, muted, true))
    head.addView(headText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(TextView(this).apply {
      text = "\u2715"
      textSize = 18f
      setTextColor(muted)
      setPadding(10.dp, 6.dp, 6.dp, 6.dp)
      setOnClickListener { closeCartSheet() }
    })
    sheet.addView(head)

    // Scrollable line-item list (capped height so the sheet stays a sheet).
    val listScroll = ScrollView(this)
    val list = column().apply { setPadding(18.dp, 4.dp, 18.dp, 4.dp) }
    cartSheetList = list
    cart.values.forEach { line -> list.addView(cartLineView(line).withTop(8.dp)) }
    listScroll.addView(list)
    val maxListHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
    sheet.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { })
    listScroll.post { if (listScroll.height > maxListHeight) listScroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight) }

    // Total + Next.
    val footer = column().apply {
      setPadding(18.dp, 12.dp, 18.dp, 16.dp)
      background = shape(Color.rgb(248, 250, 252), 0)
    }
    val totalRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
    totalRow.addView(text("Total", 15f, muted, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val totalView = text("Rs. ${money(cart.values.sumOf { it.price * it.qty })}", 22f, ink, true)
    cartSheetTotal = totalView
    totalRow.addView(totalView)
    footer.addView(totalRow)
    footer.addView(primaryButton("Next").apply {
      setOnClickListener { closeCartSheet(); openOrderPreview() }
    }.withTop(12.dp).withHeight(54.dp))
    sheet.addView(footer)
    return sheet
  }

  // ---- Order preview with all print/KOT/bill actions ----
  private fun openOrderPreview() {
    val table = selectedTable
    if (table == null || cart.isEmpty()) {
      showStatus("Add items first.", true)
      return
    }
    onOrderScreen = false
    refreshCartBar()
    hideBottomNav()
    content.removeAllViews()
    titleView.text = "Order Preview"
    subtitleView.text = table.name
    statusView.visibility = View.GONE

    // Items summary card.
    val itemsCard = card()
    itemsCard.addView(text("Items", 16f, ink, true))
    cart.values.forEach { line ->
      val r = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8.dp, 0, 0) }
      r.addView(text("${line.name}  x${formatQty(line.qty)}", 14f, ink, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      r.addView(text("Rs. ${money(line.price * line.qty)}", 14f, ink, true))
      itemsCard.addView(r)
    }
    val totalRow = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 12.dp, 0, 0) }
    totalRow.addView(text("Total", 16f, muted, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    totalRow.addView(text("Rs. ${money(cart.values.sumOf { it.price * it.qty })}", 22f, red, true))
    itemsCard.addView(totalRow)
    content.addView(itemsCard)

    // Actions: exactly three — KOT, KOT Print, Save & Print. Printer is chosen in Printer Settings.
    // Gated by the POS Billing permission from the Windows User Manage.
    val kotCard = card().withTop(12.dp)
    kotCard.addView(text("Kitchen & Bill", 15f, ink, true))
    if (currentUser?.can("pos_access") != false) {
      val kotRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
      kotRow.addView(actionButton("KOT", Color.rgb(255, 247, 220), ink) { sendKot(print = false) }, weight())
      kotRow.addView(gap())
      kotRow.addView(actionButton("KOT Print", teal, Color.WHITE) { sendKot(print = true) }, weight())
      kotCard.addView(kotRow.withTop(10.dp))
      kotCard.addView(actionButton("Save & Print", red, Color.WHITE) { saveAndPrint() }.withTop(8.dp).withHeight(52.dp))
    } else {
      kotCard.addView(text("Your account does not have POS billing permission.", 13f, muted, false).withTop(8.dp))
    }
    content.addView(kotCard)

    content.addView(headerButton("Back to items").apply { setOnClickListener { onOrderScreen = true; renderOrder() } }.withTop(14.dp).withHeight(48.dp))
  }

  private fun addItem(item: MenuItem) {
    val existing = cart[item.id]
    if (existing == null) {
      cart[item.id] = CartLine(item.id, item.name, item.price, 1.0, item.taxRate)
    } else {
      existing.qty += 1.0
    }
    renderMenuArea()
    refreshCartBar()
  }

  private fun changeQty(itemId: String, delta: Double) {
    val line = cart[itemId] ?: return
    line.qty += delta
    if (line.qty <= 0.0) cart.remove(itemId)
    renderMenuArea()
    refreshCartBar()
    // Keep the open cart sheet in sync in place (no flicker/close-reopen).
    if (cartSheet != null) {
      if (cart.isEmpty()) {
        closeCartSheet()
      } else {
        cartSheetList?.let { list ->
          list.removeAllViews()
          cart.values.forEach { l -> list.addView(cartLineView(l).withTop(8.dp)) }
        }
        cartSheetTotal?.text = "Rs. ${money(cart.values.sumOf { it.price * it.qty })}"
      }
    }
  }

  // KOT: save the order and (optionally) print the kitchen ticket. addTo just re-sends the order to
  // the kitchen (Normal KOT / Add to KOT). All variants mark the order kotPrinted (table -> yellow).
  private fun sendKot(print: Boolean, addTo: Boolean = false) {
    val table = selectedTable
    if (table == null || cart.isEmpty()) {
      showStatus("Add items first.", true)
      return
    }
    showStatus(if (print) "Printing KOT\u2026" else "Sending KOT\u2026", false)
    Thread {
      val body = buildOrderPayload("unclosed", table).put("printerProfileId", selectedPrinterId).put("kotPrinted", true)
      // "KOT" (no print) still records the kitchen ticket; print + Add-to-KOT hit the print route.
      val path = if (print || addTo) "/api/mobile/kot/print" else "/api/mobile/orders/save"
      val result = runCatching { postJson(path, body, sessionToken) }
      runOnUiThread {
        result.onSuccess {
          Toast.makeText(this, if (addTo) "Added to KOT" else "KOT sent", Toast.LENGTH_SHORT).show()
          finishOrderAndReturn()
        }.onFailure { showStatus(it.message ?: "KOT failed", true) }
      }
    }.start()
  }

  // Save & Print: finalize the order (settle) and print the bill.
  private fun saveAndPrint() {
    val table = selectedTable
    if (table == null || cart.isEmpty()) {
      showStatus("Add items first.", true)
      return
    }
    showStatus("Saving and printing\u2026", false)
    Thread {
      runCatching { postJson("/api/mobile/orders/save", buildOrderPayload("unclosed", table).put("billPrinted", true), sessionToken) }
      val body = buildOrderPayload("unclosed", table).put("billPrinted", true).put("printerProfileId", selectedPrinterId)
      val result = runCatching { postJson("/api/mobile/kot/print", body, sessionToken) }
      runOnUiThread {
        result.onSuccess {
          Toast.makeText(this, "Saved & printed", Toast.LENGTH_SHORT).show()
          finishOrderAndReturn()
        }.onFailure { showStatus(it.message ?: "Save & print failed", true) }
      }
    }.start()
  }

  // Clears the current order and returns to the (refreshed) table view.
  private fun finishOrderAndReturn() {
    onOrderScreen = false
    selectedTable = null
    activeOrder = null
    cart.clear()
    refreshCartBar()
    loadBootstrap()
  }

  private fun buildOrderPayload(status: String, table: TableInfo): JSONObject {
    val lines = JSONArray()
    cart.values.forEach {
      lines.put(
        JSONObject()
          .put("itemId", it.itemId)
          .put("name", it.name)
          .put("price", it.price)
          .put("qty", it.qty)
          .put("taxRate", it.taxRate),
      )
    }
    return JSONObject()
      .put("orderId", activeOrder?.id.orEmpty())
      .put("billNo", activeOrder?.billNo.orEmpty())
      .put("expectedUpdatedAt", activeOrder?.updatedAt.orEmpty())
      .put("status", status)
      .put("orderType", "Dining")
      .put("seatingMode", "individual")
      .put("table", table.name)
      .put("tables", JSONArray().put(table.name))
      .put("diningGroupName", "")
      .put("cart", lines)
  }

  private fun getJson(path: String, token: String = ""): JSONObject = request("GET", path, null, token)

  private fun postJson(path: String, body: JSONObject, token: String = ""): JSONObject = request("POST", path, body, token)

  private fun request(method: String, path: String, body: JSONObject?, token: String): JSONObject {
    val baseUrl = ServerStore.get(this).removeSuffix("/")
    val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
    connection.connectTimeout = 7000
    connection.readTimeout = 10000
    connection.requestMethod = method
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("X-GI-Device-Type", "mobile")
    connection.setRequestProperty("X-GI-Device-Name", android.os.Build.MODEL)
    if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
    if (body != null) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }
    val statusCode = connection.responseCode
    val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
    val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    val json = runCatching { JSONObject(responseText) }.getOrElse {
      throw ApiException(statusCode, "Main PC returned an invalid response")
    }
    if (!json.optBoolean("ok", false)) {
      throw ApiException(
        statusCode,
        json.optString("error", "Request failed"),
        json.optString("code"),
        json.optBoolean("retryable", false),
      )
    }
    return json
  }

  private inner class TableAdapter(private val tables: List<TableInfo>) : BaseAdapter() {
    override fun getCount() = tables.size
    override fun getItem(position: Int) = tables[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, old: View?, parent: ViewGroup?): View {
      val table = tables[position]
      val open = bootstrap.openOrderList.firstOrNull { it.tables.any { name -> name.equals(table.name, true) } }

      // Match the Windows app status colours:
      //   bill printed (Save & Print) -> green, KOT printed -> yellow,
      //   other open order -> running (slate), free -> default white.
      val fill: Int; val border: Int; val accent: Int; val label: String
      when {
        open == null -> {
          fill = Color.WHITE; border = Color.rgb(205, 216, 227); accent = muted; label = "Free"
        }
        open.billPrinted -> {
          fill = Color.rgb(214, 247, 226); border = Color.rgb(47, 174, 99); accent = Color.rgb(11, 90, 47); label = "Bill Printed"
        }
        open.kotPrinted -> {
          fill = Color.rgb(255, 244, 184); border = Color.rgb(230, 184, 0); accent = Color.rgb(107, 78, 5); label = if (open.kotNo.isNotBlank()) "KOT #${open.kotNo}" else "KOT Printed"
        }
        else -> {
          fill = Color.rgb(238, 242, 247); border = Color.rgb(148, 163, 184); accent = Color.rgb(51, 65, 85); label = "Running"
        }
      }

      val card = column().apply {
        setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        background = shape(fill, 16.dp, 2.dp, border)
        setOnClickListener { openTable(table) }
      }
      val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
      top.addView(text(table.name, 22f, ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      top.addView(TextView(this@MainActivity).apply {
        text = "\u25CF"
        textSize = 13f
        setTextColor(border)
      })
      card.addView(top)
      card.addView(text(label, 12f, accent, true).withTop(8.dp))
      val detail = if (open != null) "Rs. ${money(open.total)}" else "${table.seats} seats"
      card.addView(text(detail, 13f, muted, false).withTop(3.dp))
      return card
    }
  }

  private inner class ItemAdapter(private val items: List<MenuItem>) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, old: View?, parent: ViewGroup?): View {
      val item = items[position]
      val active = cart[item.id]
      val card = column().apply {
        setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        background = shape(if (active == null) Color.WHITE else Color.rgb(229, 247, 249), 12.dp, if (active == null) 1.dp else 2.dp, if (active == null) line else teal)
        setOnClickListener { addItem(item) }
      }
      card.addView(text(item.name, 16f, ink, true).apply { maxLines = 2 })
      card.addView(text("Rs. ${money(item.price)}", 13f, if (active == null) muted else teal, true).withTop(5.dp))
      if (active == null) {
        card.addView(text("+ Add", 12f, teal, true).apply {
          gravity = Gravity.CENTER
          setPadding(0, 7.dp, 0, 7.dp)
          background = shape(Color.rgb(240, 249, 250), 8.dp, 1.dp, Color.rgb(190, 224, 228))
        }.withTop(10.dp))
      } else {
        card.addView(quantityControls(active).withTop(10.dp))
      }
      return card
    }
  }

  private fun cartLineView(lineItem: CartLine): View {
    val row = row().apply {
      gravity = Gravity.CENTER_VERTICAL
      setPadding(10.dp, 9.dp, 10.dp, 9.dp)
      background = shape(soft, 10.dp, 1.dp, line)
    }
    val copy = column()
    copy.addView(text(lineItem.name, 15f, ink, true))
    copy.addView(text("Rs. ${money(lineItem.price)} x ${formatQty(lineItem.qty)} = Rs. ${money(lineItem.price * lineItem.qty)}", 12f, muted, true))
    row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(quantityControls(lineItem), LinearLayout.LayoutParams(126.dp, 38.dp))
    return row
  }

  private fun quantityControls(lineItem: CartLine): View {
    val controls = row().apply { gravity = Gravity.CENTER_VERTICAL }
    controls.addView(compactButton("-") { changeQty(lineItem.itemId, -1.0) }, LinearLayout.LayoutParams(38.dp, 38.dp))
    controls.addView(text(formatQty(lineItem.qty), 15f, ink, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, 38.dp, 1f))
    controls.addView(compactButton("+") { changeQty(lineItem.itemId, 1.0) }, LinearLayout.LayoutParams(38.dp, 38.dp))
    return controls
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    when {
      requestCode == REQUEST_SERVER && resultCode == RESULT_OK -> {
        buildShell()
        renderScanPrompt()
      }
      requestCode == REQUEST_SCAN && resultCode == RESULT_OK && data != null -> {
        onQrLoginResult(
          data.getStringExtra(ScanQrActivity.EXTRA_SESSION_TOKEN).orEmpty(),
          data.getStringExtra(ScanQrActivity.EXTRA_USER_JSON).orEmpty(),
        )
      }
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    when {
      cartSheet != null -> closeCartSheet()
      drawerOpen -> closeDrawer()
      // On the order-preview screen, go back to item selection.
      selectedTable != null && !onOrderScreen && cart.isNotEmpty() -> {
        onOrderScreen = true
        renderOrder()
      }
      selectedTable != null -> {
        selectedTable = null
        activeOrder = null
        cart.clear()
        onOrderScreen = false
        refreshCartBar()
        renderTables()
      }
      currentUser != null -> renderScanPrompt()
      else -> super.onBackPressed()
    }
  }

  private fun openServerSetup() {
    startActivityForResult(Intent(this, ServerSetupActivity::class.java), REQUEST_SERVER)
  }

  private fun showLoading(message: String) {
    content.removeAllViews()
    val progress = ProgressBar(this)
    val box = column().apply {
      gravity = Gravity.CENTER
      addView(progress, LinearLayout.LayoutParams(54.dp, 54.dp))
      addView(text(message, 15f, muted, true).withTop(14.dp))
    }
    content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360.dp))
  }

  private fun showStatus(message: String, error: Boolean) {
    statusView.visibility = View.VISIBLE
    statusView.text = message
    statusView.setTextColor(if (error) Color.rgb(185, 28, 28) else teal)
    statusView.background = shape(if (error) Color.rgb(255, 241, 242) else Color.rgb(232, 249, 244), 9.dp, 1.dp, if (error) Color.rgb(254, 205, 211) else Color.rgb(134, 239, 172))
  }

  private fun card(): LinearLayout = column().apply {
    setPadding(18.dp, 18.dp, 18.dp, 18.dp)
    background = shape(Color.WHITE, 16.dp, 1.dp, line)
    elevation = 2.dp.toFloat()
  }

  private fun emptyBox(value: String): TextView = text(value, 15f, muted, true).apply {
    gravity = Gravity.CENTER
    setPadding(12.dp, 22.dp, 12.dp, 22.dp)
    background = shape(soft, 10.dp, 1.dp, line)
  }

  private fun input(hintText: String, inputTypeValue: Int): EditText = EditText(this).apply {
    hint = hintText
    inputType = inputTypeValue
    setSingleLine(true)
    setTextColor(ink)
    setHintTextColor(Color.rgb(126, 144, 166))
    textSize = 16f
    typeface = Typeface.DEFAULT_BOLD
    setPadding(14.dp, 0, 14.dp, 0)
    background = shape(Color.WHITE, 11.dp, 1.dp, Color.rgb(192, 207, 220))
  }

  private fun headerButton(label: String): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(ink)
    typeface = Typeface.DEFAULT_BOLD
    background = shape(Color.WHITE, 10.dp, 1.dp, line)
  }

  private fun primaryButton(label: String): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(Color.WHITE)
    typeface = Typeface.DEFAULT_BOLD
    background = shape(red, 12.dp)
  }

  private fun actionButton(label: String, fill: Int, color: Int, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(color)
    typeface = Typeface.DEFAULT_BOLD
    background = shape(fill, 10.dp, 1.dp, if (fill == Color.WHITE) line else fill)
    setOnClickListener { onClick() }
  }

  private fun chip(label: String, active: Boolean, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(if (active) Color.WHITE else ink)
    typeface = Typeface.DEFAULT_BOLD
    background = shape(if (active) red else Color.WHITE, 10.dp, 1.dp, if (active) red else line)
    setOnClickListener { onClick() }
  }

  private fun compactButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    textSize = 18f
    isAllCaps = false
    setTextColor(if (label == "+") Color.WHITE else ink)
    typeface = Typeface.DEFAULT_BOLD
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    setPadding(0, 0, 0, 0)
    background = shape(if (label == "+") teal else Color.WHITE, 9.dp, 1.dp, line)
    setOnClickListener { onClick() }
  }

  private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
  }
  private fun shape(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = fill) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius.toFloat()
    if (stroke > 0) setStroke(stroke, strokeColor)
  }
  private fun shapeTop(fill: Int, radius: Int) = GradientDrawable().apply {
    setColor(fill)
    val r = radius.toFloat()
    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
  }
  private fun LinearLayout.withTop(top: Int): LinearLayout {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    return this
  }
  private fun <T : View> T.withTop(top: Int): T {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    return this
  }
  private fun <T : View> T.withHeight(height: Int): T {
    layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)).apply { this.height = height }
    return this
  }
  private fun chipParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 42.dp).apply { rightMargin = 8.dp }
  private fun weight() = LinearLayout.LayoutParams(0, 50.dp, 1f)
  private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(8.dp, 1) }
  private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
  private fun formatQty(value: Double) = if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
  private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

  private companion object {
    const val REQUEST_SERVER = 700
    const val REQUEST_SCAN = 703
  }
}

private class ApiException(
  val statusCode: Int,
  override val message: String,
  val code: String = "",
  val retryable: Boolean = false,
) : IllegalStateException(message)

/** A canvas-drawn Wi-Fi icon with a slash, for the "Network Connection Lost" screen. */
private class WifiOffIconView(context: android.content.Context, private val tint: Int) : View(context) {
  private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE
    strokeCap = android.graphics.Paint.Cap.ROUND
    color = tint
  }
  private val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.FILL
    color = tint
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    val cx = w / 2f
    val baseY = h * 0.72f
    paint.strokeWidth = w * 0.06f
    // Three Wi-Fi arcs of increasing radius.
    val radii = floatArrayOf(w * 0.34f, w * 0.24f, w * 0.14f)
    for (r in radii) {
      val rect = android.graphics.RectF(cx - r, baseY - r, cx + r, baseY + r)
      canvas.drawArc(rect, 210f, 120f, false, paint)
    }
    // Signal dot.
    canvas.drawCircle(cx, baseY, w * 0.045f, dotPaint)
    // Diagonal slash to convey "off / lost".
    canvas.drawLine(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f, paint)
  }
}

data class Bootstrap(
  val version: String = "",
  val businessName: String = "Restaurant",
  val categories: List<CategoryInfo> = emptyList(),
  val tables: List<TableInfo> = emptyList(),
  val menuItems: List<MenuItem> = emptyList(),
  val staff: List<StaffUser> = emptyList(),
  val printers: List<PrinterInfo> = emptyList(),
  val openOrderList: List<OpenOrder> = emptyList(),
  val openOrders: Int = 0,
  val paidBills: Int = 0,
  val paidTotal: Double = 0.0,
  val paidItems: Double = 0.0,
  val myBills: Int = 0,
  val myBillsTotal: Double = 0.0,
) {
  companion object {
    fun from(json: JSONObject): Bootstrap {
      val groups = json.optJSONArray("diningTableGroups") ?: JSONArray()
      val tables = mutableListOf<TableInfo>()
      for (i in 0 until groups.length()) {
        val group = groups.optJSONObject(i) ?: continue
        val floor = group.optString("label", "Main")
        val groupTables = group.optJSONArray("tables") ?: JSONArray()
        for (j in 0 until groupTables.length()) {
          val raw = groupTables.opt(j)
          val table = if (raw is JSONObject) raw.optString("name") else raw.toString()
          if (table.isNotBlank()) tables.add(TableInfo(table, floor, 4))
        }
      }
      return Bootstrap(
        version = json.optString("version"),
        businessName = json.optJSONObject("businessProfile")?.optString("businessName")?.ifBlank { null }
          ?: json.optJSONObject("businessProfile")?.optString("ownerName")?.ifBlank { null }
          ?: "Restaurant",
        categories = json.optJSONArray("categories").toCategoryList(),
        tables = tables,
        menuItems = json.optJSONArray("menuItems").toMenuList(),
        staff = json.optJSONArray("staffUsers").toStaffList(),
        printers = json.optJSONArray("printerProfiles").toPrinterList(),
        openOrderList = json.optJSONArray("openOrderList").toOpenOrderList(),
        openOrders = json.optInt("openOrders", 0),
        paidBills = json.optInt("paidBills", 0),
        paidTotal = json.optDouble("paidTotal", 0.0),
        paidItems = json.optDouble("paidItems", 0.0),
        myBills = json.optInt("myBills", 0),
        myBillsTotal = json.optDouble("myBillsTotal", 0.0),
      )
    }
  }
}

data class CategoryInfo(val id: String, val name: String)
data class TableInfo(val name: String, val floor: String, val seats: Int)
data class MenuItem(val id: String, val name: String, val category: String, val price: Double, val taxRate: Double)
data class StaffUser(
  val id: String,
  val name: String,
  val active: Boolean,
  val permissions: List<String> = emptyList(),
  val tableAccess: List<String> = emptyList(),
) {
  fun can(permission: String) = permissions.contains(permission)

  // Empty tableAccess means the user can serve all tables; otherwise only the ticked ones.
  fun canAccessTable(tableName: String) =
    tableAccess.isEmpty() || tableAccess.any { it.equals(tableName, ignoreCase = true) }

  companion object {
    fun from(json: JSONObject): StaffUser {
      val perms = json.optJSONArray("permissions")
      val list = if (perms == null) emptyList() else (0 until perms.length()).mapNotNull { perms.optString(it).takeIf(String::isNotBlank) }
      val tables = json.optJSONArray("tableAccess")
      val tableList = if (tables == null) emptyList() else (0 until tables.length()).mapNotNull { tables.optString(it).takeIf(String::isNotBlank) }
      return StaffUser(json.optString("id"), json.optString("name", "User"), json.optBoolean("active", true), list, tableList)
    }
  }
}
data class PrinterInfo(val id: String, val name: String, val isBillPrinter: Boolean)
data class CartLine(val itemId: String, val name: String, val price: Double, var qty: Double, val taxRate: Double) {
  fun copy() = CartLine(itemId, name, price, qty, taxRate)
}
data class OpenOrder(
  val id: String,
  val billNo: String,
  val status: String,
  val orderType: String,
  val tables: List<String>,
  val cart: List<CartLine>,
  val total: Double,
  val updatedAt: String,
  val kotPrinted: Boolean = false,
  val kotNo: String = "",
  val billPrinted: Boolean = false,
) {
  companion object {
    fun from(json: JSONObject): OpenOrder {
      return OpenOrder(
        json.optString("id"),
        json.optString("billNo"),
        json.optString("status"),
        json.optString("orderType", "Dining"),
        json.optJSONArray("tables").toStringList(),
        json.optJSONArray("cart").toCartList(),
        json.optJSONObject("totals")?.optDouble("total", 0.0) ?: 0.0,
        json.optString("updatedAt"),
        json.optBoolean("kotPrinted", false),
        json.optString("kotNo"),
        json.optBoolean("billPrinted", false),
      )
    }
  }
}

private fun JSONArray?.toCategoryList(): List<CategoryInfo> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull {
    val item = optJSONObject(it) ?: return@mapNotNull null
    CategoryInfo(item.optString("id"), item.optString("label", item.optString("name"))).takeIf { value -> value.id.isNotBlank() && value.name.isNotBlank() }
  }
}

private fun JSONArray?.toMenuList(): List<MenuItem> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull {
    val item = optJSONObject(it) ?: return@mapNotNull null
    if (!item.optBoolean("available", true)) return@mapNotNull null
    MenuItem(
      item.optString("id"),
      item.optString("name"),
      item.optString("category"),
      max(0.0, item.optDouble("price", 0.0)),
      max(0.0, item.optDouble("taxRate", 0.0)),
    ).takeIf { value -> value.id.isNotBlank() && value.name.isNotBlank() }
  }
}

private fun JSONArray?.toStaffList(): List<StaffUser> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { optJSONObject(it)?.let(StaffUser::from) }.filter { it.id.isNotBlank() }
}

private fun JSONArray?.toPrinterList(): List<PrinterInfo> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull {
    val item = optJSONObject(it) ?: return@mapNotNull null
    PrinterInfo(item.optString("id"), item.optString("name"), item.optBoolean("isBillPrinter")).takeIf { value -> value.id.isNotBlank() }
  }
}

private fun JSONArray?.toOpenOrderList(): List<OpenOrder> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { optJSONObject(it)?.let(OpenOrder::from) }
}

private fun JSONArray?.toStringList(): List<String> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}

private fun JSONArray?.toCartList(): List<CartLine> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull {
    val item = optJSONObject(it) ?: return@mapNotNull null
    CartLine(
      item.optString("itemId"),
      item.optString("name"),
      item.optDouble("price", 0.0),
      item.optDouble("qty", 0.0),
      item.optDouble("taxRate", 0.0),
    ).takeIf { value -> value.itemId.isNotBlank() && value.qty > 0 }
  }
}
