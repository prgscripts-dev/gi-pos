package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/** Resolves the Main PC's current address through DNS-SD/mDNS on the local network. */
public final class LocalServerDiscovery {
  private static final String SERVICE_TYPE = "_gipos._tcp.";

  public interface Callback {
    void onFound(String serviceName, String endpoint);
    void onError(String message);
  }

  private LocalServerDiscovery() {}

  @SuppressWarnings("deprecation")
  public static void find(Context context, String preferredName, Callback callback) {
    NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("gipos-main-pc-discovery");
    if (lock != null) {
      lock.setReferenceCounted(false);
      lock.acquire();
    }

    Handler handler = new Handler(Looper.getMainLooper());
    AtomicBoolean finished = new AtomicBoolean(false);
    String preferred = preferredName == null ? "" : preferredName.trim();
    final NsdManager.DiscoveryListener[] listenerHolder = new NsdManager.DiscoveryListener[1];

    Runnable stop = () -> {
      if (!finished.compareAndSet(false, true)) return;
      try { manager.stopServiceDiscovery(listenerHolder[0]); } catch (Exception ignored) {}
      if (lock != null && lock.isHeld()) lock.release();
    };

    NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
      @Override public void onDiscoveryStarted(String serviceType) {}

      @Override public void onServiceFound(NsdServiceInfo service) {
        if (finished.get() || !service.getServiceType().equalsIgnoreCase(SERVICE_TYPE)) return;
        if (!preferred.isBlank() && !service.getServiceName().equalsIgnoreCase(preferred)) return;
        manager.resolveService(service, new NsdManager.ResolveListener() {
          @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}

          @Override public void onServiceResolved(NsdServiceInfo resolved) {
            if (finished.get() || resolved.getHost() == null || resolved.getPort() <= 0) return;
            String endpoint = "http://" + resolved.getHost().getHostAddress() + ":" + resolved.getPort();
            String name = resolved.getServiceName();
            stop.run();
            handler.post(() -> callback.onFound(name, endpoint));
          }
        });
      }

      @Override public void onServiceLost(NsdServiceInfo service) {}

      @Override public void onDiscoveryStopped(String serviceType) {}

      @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        stop.run();
        handler.post(() -> callback.onError("Could not start Main PC discovery."));
      }

      @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
    };
    listenerHolder[0] = listener;

    handler.postDelayed(() -> {
      if (finished.get()) return;
      stop.run();
      callback.onError(preferred.isBlank()
        ? "No GI POS Main PC was found on this network."
        : "Main PC '" + preferred + "' was not found on this network.");
    }, 7000);

    try {
      manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
    } catch (Exception error) {
      stop.run();
      handler.post(() -> callback.onError("Main PC discovery is unavailable on this network."));
    }
  }
}
