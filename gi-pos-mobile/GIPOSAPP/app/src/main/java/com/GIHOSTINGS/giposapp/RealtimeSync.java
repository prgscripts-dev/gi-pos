package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time cloud sync channel over Server-Sent Events (SSE).
 *
 * <p>The app keeps a single long-lived connection to {@code /api/v1/sync/stream}
 * open while it is in the foreground. The server pushes:
 * <ul>
 *   <li>{@code changed} — another device wrote data; pull immediately.</li>
 *   <li>{@code heartbeat} / {@code ready} — keep-alive; also a good moment to
 *       run a quick sync so nothing is ever missed.</li>
 *   <li>{@code session-invalidated} — this device was logged out (the account
 *       signed in elsewhere); sign out securely and return to login.</li>
 * </ul>
 *
 * <p>The stream is best-effort. If it drops or is unavailable, the app still
 * syncs through the slower background poll, so data reliability never depends on
 * the socket staying connected. This class auto-reconnects with a short backoff
 * while the app remains started.
 */
public final class RealtimeSync {
  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
  private static volatile Thread worker;
  private static volatile HttpURLConnection activeConnection;

  private RealtimeSync() {}

  /** Start the real-time channel if it isn't already running. Safe to call repeatedly. */
  public static void start(Context context) {
    Context app = context.getApplicationContext();
    if (!RUNNING.compareAndSet(false, true)) return;
    worker = new Thread(() -> runLoop(app), "gi-pos-realtime");
    worker.setDaemon(true);
    worker.start();
  }

  /** Stop the real-time channel and close any open connection. */
  public static void stop() {
    RUNNING.set(false);
    HttpURLConnection connection = activeConnection;
    if (connection != null) {
      try { connection.disconnect(); } catch (Exception ignored) {}
    }
    Thread current = worker;
    if (current != null) current.interrupt();
  }

  // Report a genuine outage ("Connection lost") only after this many consecutive
  // failed attempts, so a momentary blip that recovers on the first retry stays
  // silent and never flashes the banner.
  private static final int FAILURES_BEFORE_WARNING = 2;

  private static void runLoop(Context app) {
    long backoffMs = 2_000L;
    int consecutiveFailures = 0;
    while (RUNNING.get()) {
      SecureStore.Session session = SecureStore.load(app);
      if (!connectable(app, session)) {
        // No internet is a definite disconnect for a cloud account — report it.
        // Missing session / not-cloud accounts stay silent.
        if (CloudSyncManager.usesCloud(app) && !online(app)) {
          CloudSyncManager.setConnectionState(
              CloudSyncManager.ConnectionState.DISCONNECTED, "No internet connection");
        }
        sleep(5_000L);
        continue;
      }
      // Background (re)connect is silent — CONNECTING never shows a banner.
      CloudSyncManager.setConnectionState(CloudSyncManager.ConnectionState.CONNECTING, "");
      try {
        boolean cleanClose = streamOnce(app, session);
        // A clean close means we WERE connected; reset failure tracking.
        consecutiveFailures = 0;
        backoffMs = cleanClose ? 2_000L : Math.min(backoffMs * 2, 30_000L);
      } catch (CloudClient.DeviceAuthException authError) {
        CloudSyncManager.forceSignOut(app, message(authError));
        return;
      } catch (Exception ignored) {
        consecutiveFailures++;
        backoffMs = Math.min(backoffMs * 2, 30_000L);
        if (CloudSyncManager.usesCloud(app)) {
          if (!online(app)) {
            CloudSyncManager.setConnectionState(
                CloudSyncManager.ConnectionState.DISCONNECTED, "No internet connection");
          } else if (consecutiveFailures >= FAILURES_BEFORE_WARNING) {
            // Server unreachable for a sustained period — show "Connection lost".
            CloudSyncManager.setConnectionState(
                CloudSyncManager.ConnectionState.ERROR, "Cloud connection error");
          }
          // First transient failure: stay silent (CONNECTING) and just retry.
        }
      }
      if (!RUNNING.get()) break;
      sleep(backoffMs);
    }
  }

  /**
   * Open the SSE stream once and process events until it closes.
   *
   * @return true if the stream ended normally (server closed / EOF), false if it
   *         ended due to a transient error.
   */
  private static boolean streamOnce(Context app, SecureStore.Session session) throws Exception {
    String endpoint = session.serverUrl + "/api/v1/sync/stream";
    HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(12_000);
    // No read timeout: this is a long-lived stream kept alive by heartbeats.
    connection.setReadTimeout(0);
    connection.setRequestProperty("Accept", "text/event-stream");
    connection.setRequestProperty("x-restaurant-id", session.restaurantId);
    connection.setRequestProperty("x-device-id", session.deviceId);
    connection.setRequestProperty("x-api-key", session.apiKey);
    activeConnection = connection;

    int code;
    try {
      code = connection.getResponseCode();
    } catch (Exception error) {
      connection.disconnect();
      activeConnection = null;
      throw error;
    }

    if (code == 401 || code == 403) {
      connection.disconnect();
      activeConnection = null;
      throw new CloudClient.DeviceAuthException("This device was signed out.");
    }
    if (code < 200 || code >= 300) {
      connection.disconnect();
      activeConnection = null;
      return false;
    }

    // Channel is live: mark connected and run a sync so we start fully up to date.
    CloudSyncManager.setConnectionState(CloudSyncManager.ConnectionState.CONNECTED, "");
    CloudSyncManager.syncAsync(app);

    try (InputStream in = connection.getInputStream();
         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      String currentEvent = "message";
      while (RUNNING.get() && (line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          currentEvent = "message"; // event boundary
          continue;
        }
        if (line.startsWith("event:")) {
          currentEvent = line.substring(6).trim();
          continue;
        }
        if (line.startsWith("data:")) {
          handleEvent(app, currentEvent);
        }
      }
      return true;
    } finally {
      try { connection.disconnect(); } catch (Exception ignored) {}
      activeConnection = null;
    }
  }

  private static void handleEvent(Context app, String event) {
    switch (event) {
      case "session-invalidated":
        CloudSyncManager.forceSignOut(app, "You were signed out because this account was used on another device.");
        stop();
        break;
      case "changed":
      case "ready":
      case "heartbeat":
        // New data (or a good moment to verify): pull immediately.
        CloudSyncManager.syncAsync(app);
        break;
      default:
        break;
    }
  }

  private static boolean connectable(Context app, SecureStore.Session session) {
    if (session == null || !session.isActive()) return false;
    if (session.deviceId == null || session.deviceId.isEmpty()) return false;
    if (session.apiKey == null || session.apiKey.isEmpty()) return false;
    if (session.serverUrl == null || session.serverUrl.isEmpty()) return false;
    if (!session.hasFeature("cloudSync")) return false;
    if (SecureStore.isSignedOut(app)) return false;
    return online(app);
  }

  private static boolean online(Context context) {
    ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (manager == null) return false;
    Network network = manager.getActiveNetwork();
    NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  private static void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
  }

  private static String message(Throwable error) {
    String text = error.getMessage();
    return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
  }
}
