package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actively scans the phone's local Wi-Fi subnet to locate the GI POS Main PC by probing
 * {@code http://<ip>:<port>/api/health} on every candidate host in parallel. The first host that
 * responds with a GI POS health payload ({@code {"ok":true, ...}}) wins.
 *
 * <p>This is the reliable fallback for {@link LocalServerDiscovery} (mDNS), which is unavailable or
 * flaky on some Android devices and Wi-Fi networks.
 */
public final class LocalServerScanner {
  /** Ports the Main PC LAN server may listen on (default 8090, then EADDRINUSE fall-throughs). */
  private static final int[] CANDIDATE_PORTS = {8090, 8091, 8092, 8093};
  private static final int CONNECT_TIMEOUT_MS = 600;
  private static final int READ_TIMEOUT_MS = 900;
  private static final int MAX_HOSTS = 512;
  private static final int THREAD_POOL_SIZE = 48;
  private static final long OVERALL_TIMEOUT_MS = 12000;

  public interface Callback {
    void onFound(String endpoint);
    void onError(String message);
  }

  private LocalServerScanner() {}

  public static void scan(Context context, Callback callback) {
    final Handler handler = new Handler(Looper.getMainLooper());
    final Context appContext = context.getApplicationContext();

    final int[] prefix = localSubnet(appContext);
    if (prefix == null) {
      handler.post(() -> callback.onError("Connect this phone to the same Wi-Fi as the Main PC, then try again."));
      return;
    }

    final List<String> hosts = hostsForSubnet(prefix[0], prefix[1], prefix[2], prefix[3], prefix[4]);
    if (hosts.isEmpty()) {
      handler.post(() -> callback.onError("Could not read this Wi-Fi network. Enter the address manually."));
      return;
    }

    final AtomicBoolean done = new AtomicBoolean(false);
    final AtomicInteger remaining = new AtomicInteger(hosts.size() * CANDIDATE_PORTS.length);
    final ThreadFactory factory = new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger(1);
      @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "gipos-scan-" + n.getAndIncrement());
        t.setDaemon(true);
        return t;
      }
    };
    final ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE, factory);

    final Runnable finishNotFound = () -> {
      if (!done.compareAndSet(false, true)) return;
      pool.shutdownNow();
      handler.post(() -> callback.onError("No GI POS Main PC found on this Wi-Fi. Make sure the Local POS Server is switched on."));
    };

    // Overall safety timeout so the button never hangs forever.
    handler.postDelayed(() -> {
      if (done.get()) return;
      finishNotFound.run();
    }, OVERALL_TIMEOUT_MS);

    for (final String host : hosts) {
      for (final int port : CANDIDATE_PORTS) {
        pool.execute(() -> {
          if (done.get()) { remaining.decrementAndGet(); return; }
          String endpoint = probe(host, port);
          if (endpoint != null && done.compareAndSet(false, true)) {
            pool.shutdownNow();
            handler.post(() -> callback.onFound(endpoint));
            return;
          }
          if (remaining.decrementAndGet() <= 0 && !done.get()) {
            finishNotFound.run();
          }
        });
      }
    }
  }

  /** Returns {@code http://host:port} when the host answers /api/health like a GI POS server. */
  private static String probe(String host, int port) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL("http://" + host + ":" + port + "/api/health");
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setRequestMethod("GET");
      conn.setUseCaches(false);
      conn.setRequestProperty("Accept", "application/json");
      int code = conn.getResponseCode();
      if (code != 200) return null;
      String body = read(conn.getInputStream());
      if (looksLikeGiPos(body)) {
        return "http://" + host + ":" + port;
      }
    } catch (Exception ignored) {
      // Host unreachable / not our server / timed out — expected for most addresses.
    } finally {
      if (conn != null) conn.disconnect();
    }
    return null;
  }

  private static boolean looksLikeGiPos(String body) {
    if (body == null) return false;
    String b = body.replace(" ", "").toLowerCase();
    // Health payload is { ok: true, server: { appName, port, ... } }.
    return b.contains("\"ok\":true") && (b.contains("\"server\"") || b.contains("appname") || b.contains("primaryurl"));
  }

  private static String read(InputStream in) throws Exception {
    if (in == null) return null;
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[2048];
    int n;
    int total = 0;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
      total += n;
      if (total > 65536) break; // health payload is tiny; guard against junk
    }
    in.close();
    return out.toString("UTF-8");
  }

  /**
   * Resolves the active Wi-Fi IPv4 address and prefix length.
   * @return int[]{a,b,c,d,prefixLength} of the local IPv4, or null when not on a usable network.
   */
  private static int[] localSubnet(Context context) {
    // Preferred: ConnectivityManager LinkProperties (accurate prefix length, API 21+).
    try {
      ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm != null) {
        Network active = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? cm.getActiveNetwork() : null;
        Network[] networks = active != null ? new Network[]{active} : cm.getAllNetworks();
        for (Network net : networks) {
          LinkProperties lp = cm.getLinkProperties(net);
          if (lp == null) continue;
          for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
              byte[] raw = addr.getAddress();
              int prefixLen = la.getPrefixLength();
              return new int[]{raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF, prefixLen};
            }
          }
        }
      }
    } catch (Exception ignored) {}

    // Fallback: WifiManager DHCP info (older behaviour, assumes /24).
    try {
      WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
      if (wifi != null) {
        @SuppressWarnings("deprecation")
        int ip = wifi.getDhcpInfo() != null ? wifi.getDhcpInfo().ipAddress : 0;
        if (ip == 0 && wifi.getConnectionInfo() != null) ip = wifi.getConnectionInfo().getIpAddress();
        if (ip != 0) {
          int a = ip & 0xFF, b = (ip >> 8) & 0xFF, c = (ip >> 16) & 0xFF, d = (ip >> 24) & 0xFF;
          return new int[]{a, b, c, d, 24};
        }
      }
    } catch (Exception ignored) {}

    return null;
  }

  /** Builds the list of host IPs to probe for the given IPv4 + prefix length (excludes self, net, broadcast). */
  private static List<String> hostsForSubnet(int a, int b, int c, int d, int prefixLen) {
    List<String> hosts = new ArrayList<>();
    // Clamp to at most a /24-sized sweep for responsiveness; wider subnets probe the local /24 only.
    int effectivePrefix = Math.max(prefixLen, 24);
    if (effectivePrefix >= 32) return hosts;

    long selfIp = ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | (d & 0xFF);
    int hostBits = 32 - effectivePrefix;
    long mask = 0xFFFFFFFFL << hostBits;
    long network = selfIp & mask;
    long broadcast = network | (~mask & 0xFFFFFFFFL);

    for (long ip = network + 1; ip < broadcast && hosts.size() < MAX_HOSTS; ip++) {
      if (ip == selfIp) continue;
      int ha = (int) ((ip >> 24) & 0xFF);
      int hb = (int) ((ip >> 16) & 0xFF);
      int hc = (int) ((ip >> 8) & 0xFF);
      int hd = (int) (ip & 0xFF);
      hosts.add(ha + "." + hb + "." + hc + "." + hd);
    }
    return hosts;
  }
}
