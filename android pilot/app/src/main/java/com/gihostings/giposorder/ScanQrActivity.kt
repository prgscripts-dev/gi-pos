package com.gihostings.giposorder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scan QR Code — the second screen. Opens the camera once and keeps a stable preview. When a QR is
 * detected, analysis is paused (the camera stays bound, so no flicker) and the staff member is
 * logged in against the Main PC via POST /api/mobile/login-qr. On success it returns the session
 * token + user JSON to MainActivity, which applies only that staff's permissions.
 *
 * Reliability notes vs the previous version:
 *  - The camera is bound exactly once and never unbound/rebound on failure, so the preview no
 *    longer blinks.
 *  - A single frame result is processed at a time; further frames are ignored while a login is in
 *    flight or a result is shown.
 *  - Failures show the real server message and an explicit "Scan again" button instead of silently
 *    restarting the camera in a loop.
 */
class ScanQrActivity : ComponentActivity() {
  private val teal = Color.rgb(8, 127, 140)

  private lateinit var previewView: PreviewView
  private lateinit var status: TextView
  private lateinit var retryButton: Button

  private val analysisExecutor = Executors.newSingleThreadExecutor()
  private val qrReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
  }

  // Gate that lets exactly one detected code proceed at a time. Re-armed only via "Scan again".
  private val armed = AtomicBoolean(false)
  private var loginInFlight = false

  private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) startCamera() else showError("Camera permission is needed to scan the staff QR. Use manual entry instead.")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = "Scan Staff QR"
    setContentView(buildView())

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCamera()
    } else {
      requestCamera.launch(Manifest.permission.CAMERA)
    }
  }

  override fun onDestroy() {
    // Unbind is only done here, at teardown — never during a scan — to avoid preview flicker.
    runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
    analysisExecutor.shutdown()
    super.onDestroy()
  }

  private fun buildView(): View {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.rgb(11, 18, 32))
      setPadding(20.dp, 24.dp, 20.dp, 24.dp)
    }
    root.addView(text("Scan Staff QR", 24f, Color.WHITE, true))
    root.addView(text("Point the camera at the staff member's QR code from the client portal.", 14f, Color.rgb(197, 208, 222), false).withTop(6.dp))

    previewView = PreviewView(this).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    val frame = FrameLayout(this).apply {
      background = shape(Color.BLACK, 18.dp)
      clipToOutline = true
    }
    frame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 18.dp })

    status = text("Starting camera...", 14f, Color.WHITE, true).apply {
      gravity = Gravity.CENTER
      setPadding(12.dp, 12.dp, 12.dp, 12.dp)
      background = shape(Color.rgb(24, 34, 52), 10.dp)
    }
    root.addView(status.withTop(16.dp))

    retryButton = Button(this).apply {
      text = "Scan again"
      isAllCaps = false
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(teal, 10.dp)
      visibility = View.GONE
      setOnClickListener { rearmScanning() }
    }
    root.addView(retryButton.withTop(12.dp).apply { (layoutParams as LinearLayout.LayoutParams).height = 50.dp })

    val enterManually = Button(this).apply {
      text = "Enter staff code manually"
      isAllCaps = false
      setTextColor(Color.WHITE)
      typeface = Typeface.DEFAULT_BOLD
      background = shape(Color.rgb(24, 34, 52), 10.dp)
      setOnClickListener { promptManualCode() }
    }
    root.addView(enterManually.withTop(10.dp).apply { (layoutParams as LinearLayout.LayoutParams).height = 50.dp })
    return root
  }

  private fun startCamera() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      val provider = runCatching { future.get() }.getOrNull()
      if (provider == null) {
        showError("Camera could not start on this device. Use manual entry instead.")
        return@addListener
      }
      val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
      val resolution = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .build()
      val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(resolution)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(analysisExecutor, ::analyze) }

      runCatching {
        // Bind once. The camera stays bound for the whole screen lifetime.
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
      }.onFailure {
        showError("Camera could not start: ${it.message}. Use manual entry instead.")
        return@addListener
      }
      armed.set(true)
      showInfo("Point the camera at the staff QR code.")
    }, ContextCompat.getMainExecutor(this))
  }

  private fun analyze(proxy: androidx.camera.core.ImageProxy) {
    // Ignore frames unless we're armed and idle. We never unbind here, so the preview keeps running.
    if (!armed.get() || loginInFlight) {
      proxy.close()
      return
    }
    val value = runCatching { decodeQr(proxy) }.getOrNull()
    proxy.close()
    if (value != null && value.isNotBlank() && armed.compareAndSet(true, false)) {
      runOnUiThread { onCodeScanned(value) }
    }
  }

  /** Decode a QR from the camera frame's luminance (Y) plane using ZXing. No native libs needed. */
  private fun decodeQr(proxy: androidx.camera.core.ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val width = proxy.width
    val height = proxy.height
    val rowStride = plane.rowStride
    // PlanarYUVLuminanceSource expects a tightly-packed luminance array; account for row stride.
    val source = if (rowStride == width) {
      PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    } else {
      val packed = ByteArray(width * height)
      for (row in 0 until height) {
        System.arraycopy(data, row * rowStride, packed, row * width, width)
      }
      PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
    }
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
      qrReader.decodeWithState(bitmap).text
    } catch (_: Exception) {
      // ZXing throws NotFoundException when no QR is in the frame — expected, just keep scanning.
      null
    } finally {
      qrReader.reset()
    }
  }

  private fun rearmScanning() {
    retryButton.visibility = View.GONE
    loginInFlight = false
    armed.set(true)
    showInfo("Point the camera at the staff QR code.")
  }

  private fun promptManualCode() {
    val input = android.widget.EditText(this).apply {
      hint = "GS-XXXXXXXX"
      setSingleLine(true)
    }
    android.app.AlertDialog.Builder(this)
      .setTitle("Enter staff code")
      .setView(input)
      .setPositiveButton("Login") { _, _ ->
        val code = input.text.toString().trim()
        if (code.isBlank()) {
          showError("Enter the staff code shown under the QR.")
        } else if (!loginInFlight) {
          armed.set(false)
          onCodeScanned(code)
        }
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private fun onCodeScanned(rawValue: String) {
    loginInFlight = true
    retryButton.visibility = View.GONE
    showInfo("Signing in...")
    Thread {
      val result = runCatching { loginWithCode(rawValue) }
      runOnUiThread {
        loginInFlight = false
        result.onSuccess { json ->
          showInfo("Connected. Loading POS...")
          val data = android.content.Intent()
            .putExtra(EXTRA_SESSION_TOKEN, json.optString("sessionToken"))
            .putExtra(EXTRA_USER_JSON, (json.optJSONObject("user") ?: JSONObject()).toString())
          setResult(RESULT_OK, data)
          finish()
        }.onFailure {
          // Show the real reason (server message or network error). Camera preview keeps running;
          // the user taps "Scan again" to retry — no auto-restart loop, no blinking.
          showError(it.message ?: "Could not sign in. Tap Scan again.")
          retryButton.visibility = View.VISIBLE
        }
      }
    }.start()
  }

  private fun loginWithCode(code: String): JSONObject {
    val baseUrl = ServerStore.get(this).removeSuffix("/")
    if (baseUrl.isBlank()) throw IllegalStateException("Not connected to a Main PC. Go back and connect first.")
    val body = JSONObject()
      .put("staffCode", code)
      .put("deviceType", "mobile")
      .put("deviceName", android.os.Build.MODEL)

    val connection = URL("$baseUrl/api/mobile/login-qr").openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 7000
      connection.readTimeout = 10000
      connection.requestMethod = "POST"
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("X-GI-Device-Type", "mobile")
      connection.setRequestProperty("X-GI-Device-Name", android.os.Build.MODEL)
      connection.doOutput = true
      connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

      val statusCode = connection.responseCode
      val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
      val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      val json = runCatching { JSONObject(responseText) }.getOrNull()

      // A 404 (or a NOT_FOUND body) means this Main PC is running an older POS build that does not
      // have the QR sign-in endpoint yet. Give an actionable message instead of a bare "Not found".
      val notFoundCode = json?.optString("code") == "NOT_FOUND"
      if (statusCode == 404 || notFoundCode) {
        throw IllegalStateException("This Main PC needs updating: staff QR sign-in is not available on it yet. Update the GI POS software on the Main PC, then try again.")
      }

      if (json == null) {
        throw IllegalStateException("Main PC returned an unexpected response (HTTP $statusCode).")
      }
      if (!json.optBoolean("ok", false)) {
        throw IllegalStateException(json.optString("error", "This staff QR could not sign in (HTTP $statusCode)."))
      }
      return json
    } catch (error: java.net.SocketTimeoutException) {
      throw IllegalStateException("Main PC did not respond. Check Wi-Fi and that Local POS Server is on.")
    } catch (error: java.io.IOException) {
      throw IllegalStateException("Cannot reach the Main PC. Check the server address and Wi-Fi.")
    } finally {
      connection.disconnect()
    }
  }

  private fun showInfo(message: String) {
    status.text = message
    status.setTextColor(Color.WHITE)
    status.background = shape(Color.rgb(24, 34, 52), 10.dp)
  }

  private fun showError(message: String) {
    status.text = message
    status.setTextColor(Color.rgb(254, 202, 202))
    status.background = shape(Color.rgb(76, 20, 28), 10.dp)
  }

  private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
  }

  private fun <T : View> T.withTop(top: Int): T {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      topMargin = top
    }
    return this
  }

  private fun shape(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius.toFloat()
  }

  private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

  companion object {
    const val EXTRA_SESSION_TOKEN = "session_token"
    const val EXTRA_USER_JSON = "user_json"
  }
}
