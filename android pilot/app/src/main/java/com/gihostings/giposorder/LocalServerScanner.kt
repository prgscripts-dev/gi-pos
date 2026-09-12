package com.gihostings.giposorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Actively scans the phone's local Wi-Fi subnet for the GI POS Main PC by probing
 * `http://<ip>:<port>/api/health` on every candidate host in parallel. The first host that
 * responds with a GI POS health payload wins. Reliable fallback for mDNS.
 */
class LocalServerScanner(context: Context) {
  private val appContext = context.applicationContext
  private val done = AtomicBoolean(false)
  private val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE) { runnable ->
    Thread(runnable, "gipos-scan").apply { isDaemon = true }
  }

  interface Callback {
    fun onFound(endpoint: String)
    fun onError(message: String)
  }

  fun cancel() {
    if (done.compareAndSet(false, true)) {
      pool.shutdownNow()
    }
  }

  fun scan(callback: Callback) {
    val subnet = localSubnet()
    if (subnet == null) {
      finishError(callback, "Connect this phone to the same Wi-Fi as the Main PC, then try again.")
      return
    }

    val hosts = hostsForSubnet(subnet.first, subnet.second)
    if (hosts.isEmpty()) {
      finishError(callback, "Could not read this Wi-Fi network. Enter the address manually.")
      return
    }

    val remaining = AtomicInteger(hosts.size * CANDIDATE_PORTS.size)
    for (host in hosts) {
      for (port in CANDIDATE_PORTS) {
        pool.execute {
          if (done.get()) {
            remaining.decrementAndGet()
            return@execute
          }
          val endpoint = probe(host, port)
          if (endpoint != null && done.compareAndSet(false, true)) {
            pool.shutdownNow()
            callback.onFound(endpoint)
            return@execute
          }
          if (remaining.decrementAndGet() <= 0 && !done.get()) {
            finishError(callback, "No GI POS Main PC found on this Wi-Fi. Make sure Local POS Server is on.")
          }
        }
      }
    }
  }

  private fun finishError(callback: Callback, message: String) {
    if (done.compareAndSet(false, true)) {
      pool.shutdownNow()
      callback.onError(message)
    }
  }

  private fun probe(host: String, port: Int): String? {
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL("http://$host:$port/api/health").openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        useCaches = false
        setRequestProperty("Accept", "application/json")
      }
      if (conn.responseCode != 200) return null
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      if (looksLikeGiPos(body)) "http://$host:$port" else null
    } catch (_: Exception) {
      null
    } finally {
      conn?.disconnect()
    }
  }

  private fun looksLikeGiPos(body: String?): Boolean {
    if (body.isNullOrBlank()) return false
    val b = body.replace(" ", "").lowercase()
    return b.contains("\"ok\":true") && (b.contains("\"server\"") || b.contains("appname") || b.contains("primaryurl"))
  }

  /** @return Pair(ipInt, prefixLength) for the active Wi-Fi IPv4, or null. */
  private fun localSubnet(): Pair<Int, Int>? {
    // Preferred: ConnectivityManager LinkProperties for an accurate prefix length.
    runCatching {
      val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val network = cm?.activeNetwork
      val lp = if (network != null) cm.getLinkProperties(network) else null
      lp?.linkAddresses?.forEach { la ->
        val addr = la.address
        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
          val raw = addr.address
          val ip = (raw[0].toInt() and 0xFF shl 24) or
            (raw[1].toInt() and 0xFF shl 16) or
            (raw[2].toInt() and 0xFF shl 8) or
            (raw[3].toInt() and 0xFF)
          return Pair(ip, la.prefixLength)
        }
      }
    }

    // Fallback: WifiManager DHCP info (assumes /24).
    runCatching {
      val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      @Suppress("DEPRECATION")
      val raw = wifi?.dhcpInfo?.ipAddress ?: 0
      if (raw != 0) {
        // DhcpInfo stores IPv4 little-endian; convert to big-endian int a.b.c.d.
        val a = raw and 0xFF
        val b = raw shr 8 and 0xFF
        val c = raw shr 16 and 0xFF
        val d = raw shr 24 and 0xFF
        val ip = (a shl 24) or (b shl 16) or (c shl 8) or d
        return Pair(ip, 24)
      }
    }

    return null
  }

  private fun hostsForSubnet(ip: Int, prefixLenRaw: Int): List<String> {
    val prefixLen = maxOf(prefixLenRaw, 24) // cap sweep to a /24 for responsiveness
    if (prefixLen >= 32) return emptyList()
    val selfIp = ip.toLong() and 0xFFFFFFFFL
    val hostBits = 32 - prefixLen
    val mask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
    val network = selfIp and mask
    val broadcast = network or (mask.inv() and 0xFFFFFFFFL)

    val hosts = ArrayList<String>()
    var addr = network + 1
    while (addr < broadcast && hosts.size < MAX_HOSTS) {
      if (addr != selfIp) {
        val a = (addr shr 24 and 0xFF).toInt()
        val b = (addr shr 16 and 0xFF).toInt()
        val c = (addr shr 8 and 0xFF).toInt()
        val d = (addr and 0xFF).toInt()
        hosts.add("$a.$b.$c.$d")
      }
      addr++
    }
    return hosts
  }

  private companion object {
    val CANDIDATE_PORTS = intArrayOf(8090, 8091, 8092, 8093, 8080)
    const val CONNECT_TIMEOUT_MS = 600
    const val READ_TIMEOUT_MS = 900
    const val MAX_HOSTS = 512
    const val THREAD_POOL_SIZE = 48
  }
}
