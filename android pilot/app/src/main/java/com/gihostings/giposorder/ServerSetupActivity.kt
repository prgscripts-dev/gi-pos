package com.gihostings.giposorder

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connect to Main Server — the first screen the pilot app shows.
 *
 * Lets the user:
 *  - manually enter the Main PC address,
 *  - tap "Find Server" to auto-detect it on the Wi-Fi (mDNS _gipos._tcp, then an active subnet
 *    scan of /api/health as a reliable fallback),
 *  - forget any saved address.
 *
 * On a successful health check it saves the address and returns RESULT_OK.
 */
class ServerSetupActivity : Activity() {
  private val ink = Color.rgb(18, 32, 51)
  private val teal = Color.rgb(8, 127, 140)
  private val muted = Color.rgb(86, 103, 124)

  private lateinit var serverInput: EditText
  private lateinit var status: TextView
  private lateinit var connectButton: Button
  private lateinit var findButton: Button
  private lateinit var forgetButton: Button
  private lateinit var progress: ProgressBar

  private val ui = Handler(Looper.getMainLooper())
  private var scanner: LocalServerScanner? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = "Connect to Main Server"
    buildView()
  }

  override fun onDestroy() {
    scanner?.cancel()
    scanner = null
    super.onDestroy()
  }

  private fun buildView() {
    val root = ScrollView(this).apply {
      isFillViewport = true
      setBackgroundColor(Color.rgb(242, 246, 249))
    }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(24.dp, 36.dp, 24.dp, 32.dp)
    }

    val brand = TextView(this).apply {
      text = "GI"
      textSize = 22f
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(Color.rgb(200, 22, 55), 14.dp)
    }
    content.addView(brand, LinearLayout.LayoutParams(56.dp, 56.dp))
    content.addView(text("Captain", 30f, true).apply { gravity = Gravity.CENTER }.withTop(16.dp))
    content.addView(text("Connect this device to your restaurant Main PC", 15f, false).apply {
      gravity = Gravity.CENTER
      setTextColor(muted)
    }.withTop(6.dp))

    val card = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(20.dp, 22.dp, 20.dp, 20.dp)
      background = shape(Color.WHITE, 18.dp, Color.rgb(215, 224, 233))
      elevation = 2.dp.toFloat()
    }
    card.addView(text("Connect to Main Server", 21f, true))
    card.addView(text("Both devices must be on the same Wi-Fi. Tap Find Server to detect it automatically.", 14f, false).apply {
      setTextColor(muted)
    }.withTop(6.dp))
    card.addView(text("Main PC address", 13f, true).withTop(22.dp))

    serverInput = EditText(this).apply {
      hint = "http://192.168.1.6:8090"
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      setSingleLine(true)
      setText(ServerStore.get(this@ServerSetupActivity))
      setSelectAllOnFocus(false)
      setPadding(14.dp, 0, 14.dp, 0)
      setTextColor(ink)
      background = shape(Color.rgb(248, 250, 252), 10.dp, Color.rgb(192, 207, 220))
    }
    card.addView(serverInput.withTop(8.dp).withHeight(52.dp))

    findButton = Button(this).apply {
      text = "Find Server"
      isAllCaps = false
      setTextColor(ink)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(Color.rgb(241, 245, 249), 10.dp, Color.rgb(203, 213, 225))
      setOnClickListener { findServer() }
    }
    card.addView(findButton.withTop(10.dp).withHeight(48.dp))

    progress = ProgressBar(this).apply { visibility = View.GONE }
    connectButton = Button(this).apply {
      text = "Connect"
      isAllCaps = false
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(teal, 10.dp)
      setOnClickListener { connect(serverInput.text.toString()) }
    }
    card.addView(connectButton.withTop(14.dp).withHeight(52.dp))
    card.addView(progress.apply { foregroundGravity = Gravity.CENTER_HORIZONTAL }.withTop(12.dp))

    status = text("", 14f, false).apply { visibility = View.GONE }
    card.addView(status.apply { setPadding(12.dp, 10.dp, 12.dp, 10.dp) }.withTop(12.dp))

    forgetButton = Button(this).apply {
      text = "Forget saved address"
      isAllCaps = false
      setTextColor(muted)
      background = shape(Color.WHITE, 10.dp, Color.rgb(215, 224, 233))
      visibility = if (ServerStore.get(this@ServerSetupActivity).isBlank()) View.GONE else View.VISIBLE
      setOnClickListener {
        ServerStore.clear(this@ServerSetupActivity)
        serverInput.text.clear()
        visibility = View.GONE
        showStatus("Saved address cleared.", false)
      }
    }
    card.addView(forgetButton.withTop(8.dp).withHeight(46.dp))

    content.addView(card.withTop(28.dp))
    content.addView(text("Address is shown on the Main PC under Local POS Server.", 12f, false).apply {
      gravity = Gravity.CENTER
      setTextColor(Color.rgb(107, 122, 140))
    }.withTop(18.dp))
    root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
  }

  private fun setBusy(busy: Boolean) {
    connectButton.isEnabled = !busy
    findButton.isEnabled = !busy
    progress.visibility = if (busy) View.VISIBLE else View.GONE
  }

  private fun findServer() {
    setBusy(true)
    findButton.text = "Searching Wi-Fi..."
    showStatus("Looking for the Main PC on this Wi-Fi...", false)

    // Stage 1: mDNS discovery (fast when supported). Stage 2 fallback: active subnet scan.
    discoverViaMdns(
      onFound = { endpoint -> connect(endpoint) },
      onMiss = {
        findButton.text = "Scanning network..."
        showStatus("Scanning the Wi-Fi network for the Main PC...", false)
        scanner?.cancel()
        scanner = LocalServerScanner(this).also { active ->
          active.scan(object : LocalServerScanner.Callback {
            override fun onFound(endpoint: String) {
              ui.post { connect(endpoint) }
            }
            override fun onError(message: String) {
              ui.post {
                resetFindButton()
                showStatus(message, true)
              }
            }
          })
        }
      },
    )
  }

  private fun resetFindButton() {
    setBusy(false)
    findButton.text = "Find Server"
  }

  /** Attempts mDNS resolution of _gipos._tcp with a short timeout, then reports found or miss. */
  private fun discoverViaMdns(onFound: (String) -> Unit, onMiss: () -> Unit) {
    val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager
    if (manager == null) {
      onMiss()
      return
    }

    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = wifi?.createMulticastLock("gipos-pilot-discovery")?.apply {
      setReferenceCounted(false)
      runCatching { acquire() }
    }

    val finished = AtomicBoolean(false)
    var listener: NsdManager.DiscoveryListener? = null

    fun stop() {
      if (!finished.compareAndSet(false, true)) return
      runCatching { listener?.let { manager.stopServiceDiscovery(it) } }
      if (lock?.isHeld == true) runCatching { lock.release() }
    }

    listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) {}
      override fun onDiscoveryStopped(serviceType: String) {}
      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        stop(); ui.post(onMiss)
      }
      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
      override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (finished.get()) return
        resolve(manager, serviceInfo) { endpoint ->
          if (endpoint != null) {
            stop()
            ui.post { onFound(endpoint) }
          }
        }
      }
    }

    runCatching {
      manager.discoverServices("_gipos._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }.onFailure {
      stop(); ui.post(onMiss)
      return
    }

    // Give mDNS a short window, then fall back to the active scan.
    ui.postDelayed({
      if (!finished.get()) {
        stop()
        onMiss()
      }
    }, 3500)
  }

  private fun resolve(manager: NsdManager, service: NsdServiceInfo, done: (String?) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      manager.registerServiceInfoCallback(service, { it.run() }, object : NsdManager.ServiceInfoCallback {
        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = done(null)
        override fun onServiceInfoCallbackUnregistered() {}
        override fun onServiceLost() {}
        override fun onServiceUpdated(info: NsdServiceInfo) {
          runCatching { manager.unregisterServiceInfoCallback(this) }
          done(endpointFrom(info))
        }
      })
    } else {
      @Suppress("DEPRECATION")
      manager.resolveService(service, object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done(null)
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = done(endpointFrom(serviceInfo))
      })
    }
  }

  private fun endpointFrom(info: NsdServiceInfo): String? {
    val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      info.hostAddresses.firstOrNull()?.hostAddress
    } else {
      @Suppress("DEPRECATION")
      info.host?.hostAddress
    }
    val port = info.port
    if (host.isNullOrBlank() || port <= 0) return null
    return "http://$host:$port"
  }

  private fun connect(rawAddress: String) {
    val baseUrl = ServerStore.normalise(rawAddress)
    if (baseUrl == null) {
      resetFindButton()
      showStatus("Enter a valid Main PC address, for example http://192.168.1.6:8090.", true)
      return
    }

    setBusy(true)
    serverInput.setText(baseUrl)
    showStatus("Connecting to Main PC...", false)

    Thread {
      val reachable = runCatching {
        val connection = URL("$baseUrl/api/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.inputStream.use { stream ->
          JSONObject(stream.readBytes().toString(Charsets.UTF_8)).optBoolean("ok", false)
        }
      }.getOrElse { false }

      ui.post {
        resetFindButton()
        if (reachable) {
          ServerStore.save(this, baseUrl)
          showStatus("Connected to Main PC.", false)
          setResult(RESULT_OK)
          finish()
        } else {
          showStatus("Cannot reach the Main PC. Confirm the address, Wi-Fi, and Windows Firewall.", true)
        }
      }
    }.start()
  }

  private fun showStatus(message: String, isError: Boolean) {
    status.visibility = View.VISIBLE
    status.text = message
    status.setTextColor(if (isError) Color.rgb(190, 24, 54) else teal)
    status.background = shape(
      if (isError) Color.rgb(255, 239, 241) else Color.rgb(232, 249, 244),
      8.dp,
      if (isError) Color.rgb(253, 164, 175) else Color.rgb(134, 239, 172),
    )
  }

  private fun text(value: String, size: Float, strong: Boolean): TextView = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(ink)
    if (strong) typeface = Typeface.DEFAULT_BOLD
  }

  private fun <T : View> T.withTop(top: Int): T {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      topMargin = top
    }
    return this
  }

  private fun <T : View> T.withHeight(height: Int): T {
    layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      height,
    )).apply {
      this.height = height
    }
    return this
  }

  private fun shape(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius.toFloat()
    stroke?.let { setStroke(1.dp, it) }
  }

  private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}

object ServerStore {
  private const val PREFS = "gi_pos_order"
  private const val KEY_SERVER = "server_url"
  private const val KEY_SESSION = "session_token"
  private const val KEY_USER = "session_user"

  fun get(activity: Activity): String = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_SERVER, "") ?: ""

  fun save(activity: Activity, server: String) {
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY_SERVER, server).apply()
  }

  fun clear(activity: Activity) {
    // Clearing the server also ends the saved session, since the session belongs to that Main PC.
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
      .remove(KEY_SERVER)
      .remove(KEY_SESSION)
      .remove(KEY_USER)
      .apply()
  }

  // Persisted staff session so the QR scan is a one-time setup. The user stays connected across
  // app restarts until they log out or reset the server.
  fun saveSession(activity: Activity, token: String, userJson: String) {
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
      .putString(KEY_SESSION, token)
      .putString(KEY_USER, userJson)
      .apply()
  }

  fun getSession(activity: Activity): String =
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_SESSION, "") ?: ""

  fun getSessionUser(activity: Activity): String =
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_USER, "") ?: ""

  fun clearSession(activity: Activity) {
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
      .remove(KEY_SESSION)
      .remove(KEY_USER)
      .apply()
  }

  // Printer settings: type is "ip" | "bluetooth" | "wifi"; address is the IP/host or device name.
  private const val KEY_PRINTER_TYPE = "printer_type"
  private const val KEY_PRINTER_ADDRESS = "printer_address"

  fun getPrinterType(activity: Activity): String =
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_PRINTER_TYPE, "ip") ?: "ip"

  fun getPrinterAddress(activity: Activity): String =
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(KEY_PRINTER_ADDRESS, "") ?: ""

  fun savePrinter(activity: Activity, type: String, address: String) {
    activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
      .putString(KEY_PRINTER_TYPE, type)
      .putString(KEY_PRINTER_ADDRESS, address.trim())
      .apply()
  }

  fun normalise(input: String): String? {
    val value = input.trim().removeSuffix("/")
    if (value.isEmpty()) return null
    val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
    return runCatching {
      val uri = URI(withScheme)
      if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) null else "${uri.scheme}://${uri.authority}"
    }.getOrNull()
  }
}
