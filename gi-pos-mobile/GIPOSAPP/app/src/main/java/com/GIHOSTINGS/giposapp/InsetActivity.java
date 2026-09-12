package com.GIHOSTINGS.giposapp;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Keeps every screen clear of the Android status and navigation bars. */
public abstract class InsetActivity extends AppCompatActivity {
  // While any screen is visible, listen for a forced sign-out (this device was
  // logged out because the account signed in elsewhere) and route to login.
  private final CloudSyncManager.SignedOutListener forcedSignOutListener =
      reason -> handleForcedSignOut(reason);

  // App-wide cloud connection banner shown at the top of every screen when the
  // cloud link is down or erroring; hidden when connected.
  private TextView connectionBanner;
  private final CloudSyncManager.ConnectionListener connectionListener =
      (state, message) -> renderConnectionBanner(state, message);

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.rgb(18, 32, 51));
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightStatusBars(true);
  }

  @Override protected void onResume() {
    super.onResume();
    CloudSyncManager.addSignedOutListener(forcedSignOutListener);
    // addConnectionListener immediately posts the current state so the banner
    // reflects reality as soon as the screen appears.
    CloudSyncManager.addConnectionListener(connectionListener);
  }

  @Override protected void onPause() {
    CloudSyncManager.removeSignedOutListener(forcedSignOutListener);
    CloudSyncManager.removeConnectionListener(connectionListener);
    super.onPause();
  }

  /**
   * Send the user back to the login screen after a secure, forced sign-out.
   * Subclasses that already are the login screen (MainActivity) override this
   * to simply re-render the login form instead of relaunching themselves.
   */
  protected void handleForcedSignOut(String reason) {
    if (isFinishing()) {
      return;
    }
    Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show();
    Intent intent = new Intent(this, MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity(intent);
    finish();
  }

  @Override public void setContentView(View view) {
    super.setContentView(wrapWithBanner(view));
    applySystemInsets(view);
  }

  @Override public void setContentView(View view, ViewGroup.LayoutParams params) {
    // The wrapper always fills the screen; the original layout params apply to
    // the content beneath the banner.
    View wrapped = wrapWithBanner(view);
    view.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
    super.setContentView(wrapped);
    applySystemInsets(view);
  }

  // Wrap the screen content in a vertical container with a thin connection
  // banner on top. The banner is hidden by default and shown when the cloud
  // link is down; content sits below it (weight fills the rest).
  private View wrapWithBanner(View content) {
    LinearLayout wrapper = new LinearLayout(this);
    wrapper.setOrientation(LinearLayout.VERTICAL);
    connectionBanner = new TextView(this);
    connectionBanner.setVisibility(View.GONE);
    connectionBanner.setGravity(Gravity.CENTER);
    connectionBanner.setTextColor(Color.WHITE);
    connectionBanner.setTypeface(Typeface.DEFAULT_BOLD);
    connectionBanner.setTextSize(13);
    int padV = (int) (7 * getResources().getDisplayMetrics().density);
    connectionBanner.setPadding(padV, padV, padV, padV);
    wrapper.addView(connectionBanner, new LinearLayout.LayoutParams(-1, -2));
    wrapper.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
    // Reflect whatever the current state is right away.
    renderConnectionBanner(CloudSyncManager.connectionState(), CloudSyncManager.connectionMessage());
    return wrapper;
  }

  private final android.os.Handler bannerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable hideBanner = () -> { if (connectionBanner != null) connectionBanner.setVisibility(View.GONE); };

  // Show/update or hide the connection banner. The banner deliberately does NOT
  // show for CONNECTING or normal CONNECTED — background (re)connection is
  // silent. It only shows a red "Connection lost" while genuinely down, and a
  // brief green "Cloud connected" when the link is restored, then hides.
  protected void renderConnectionBanner(CloudSyncManager.ConnectionState state, String message) {
    if (connectionBanner == null) return;
    bannerHandler.removeCallbacks(hideBanner);
    boolean cloud = CloudSyncManager.usesCloud(this);
    if (!cloud) { connectionBanner.setVisibility(View.GONE); return; }

    if (state == CloudSyncManager.ConnectionState.DISCONNECTED
        || state == CloudSyncManager.ConnectionState.ERROR) {
      // Genuinely down — show a persistent, clear warning.
      connectionBanner.setText("Connection lost");
      connectionBanner.setBackgroundColor(Color.rgb(190, 30, 45)); // red
      connectionBanner.setVisibility(View.VISIBLE);
      return;
    }

    if (state == CloudSyncManager.ConnectionState.RECONNECTED) {
      // Just came back after being down — flash a brief green confirmation.
      connectionBanner.setText("Cloud connected");
      connectionBanner.setBackgroundColor(Color.rgb(22, 140, 74)); // green
      connectionBanner.setVisibility(View.VISIBLE);
      bannerHandler.postDelayed(hideBanner, 2500);
      return;
    }

    // CONNECTING and CONNECTED are silent (no banner, no page changes).
    connectionBanner.setVisibility(View.GONE);
  }

  private void applySystemInsets(View root) {
    int initialLeft = root.getPaddingLeft();
    int initialTop = root.getPaddingTop();
    int initialRight = root.getPaddingRight();
    int initialBottom = root.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      // Content always keeps the status-bar top inset so it is never hidden
      // under the status bar. The banner (when shown) also clears the status
      // bar via its own top padding.
      if (connectionBanner != null) {
        int p = (int) (7 * getResources().getDisplayMetrics().density);
        connectionBanner.setPadding(connectionBanner.getPaddingLeft(), bars.top + p, connectionBanner.getPaddingRight(), p);
      }
      view.setPadding(
          initialLeft + bars.left,
          initialTop + bars.top,
          initialRight + bars.right,
          initialBottom + bars.bottom
      );
      return windowInsets;
    });
    ViewCompat.requestApplyInsets(root);
  }

  protected boolean requireFeature(String... features) {
    SecureStore.Session session = SecureStore.load(this);
    if (session == null || features == null || features.length == 0) {
      return true;
    }
    for (String feature : features) {
      if (session.hasFeature(feature)) {
        return true;
      }
    }
    Toast.makeText(this, "This feature is not included in the active plan.", Toast.LENGTH_LONG).show();
    finish();
    return false;
  }
}
