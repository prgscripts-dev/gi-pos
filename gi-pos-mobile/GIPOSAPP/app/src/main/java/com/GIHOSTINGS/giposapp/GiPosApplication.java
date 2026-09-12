package com.GIHOSTINGS.giposapp;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * App-wide real-time sync coordinator.
 *
 * <p>While any activity is in the foreground the app keeps a live Server-Sent
 * Events channel open ({@link RealtimeSync}) so remote changes and forced
 * sign-outs are delivered instantly. A slow background poll runs alongside it
 * as a safety net so data still syncs even if the real-time channel drops (poor
 * network, proxy buffering, etc.). Together they keep all data — orders,
 * billing, customers, tables, menu, settings — consistent across devices in
 * real time and reliably.
 */
public final class GiPosApplication extends Application {

    // Foreground auto-sync interval. The SSE stream still delivers remote changes
    // instantly; this steady 5-second poll guarantees every device refreshes and
    // stays in sync even if the real-time stream is briefly down.
    private static final long FALLBACK_POLL_MS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // Count started (not just resumed) activities. Using onStart/onStop keeps a
    // count > 0 across an in-app navigation (the next activity starts before the
    // previous one stops), so we never tear down the cloud link mid-navigation.
    private int started = 0;

    private final Runnable syncTick = new Runnable() {
        @Override
        public void run() {
            if (started > 0) {
                CloudSyncManager.syncAsync(GiPosApplication.this);
                handler.postDelayed(this, FALLBACK_POLL_MS);
            }
        }
    };

    // Delay stopping the realtime channel so a quick screen change (which briefly
    // hits zero) doesn't disconnect/reconnect and flash the connection banner.
    private final Runnable stopRealtime = new Runnable() {
        @Override
        public void run() {
            if (started == 0) {
                handler.removeCallbacks(syncTick);
                RealtimeSync.stop();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                handler.removeCallbacks(stopRealtime); // cancel any pending teardown
                started++;
                if (started == 1) {
                    // App came to the foreground — open the real-time channel and
                    // start the fallback poll.
                    RealtimeSync.start(GiPosApplication.this);
                    handler.removeCallbacks(syncTick);
                    handler.post(syncTick);
                }
            }
            @Override public void onActivityStopped(Activity activity) {
                started = Math.max(0, started - 1);
                if (started == 0) {
                    // Might just be a navigation; wait briefly before tearing down.
                    handler.removeCallbacks(stopRealtime);
                    handler.postDelayed(stopRealtime, 1500);
                }
            }
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
