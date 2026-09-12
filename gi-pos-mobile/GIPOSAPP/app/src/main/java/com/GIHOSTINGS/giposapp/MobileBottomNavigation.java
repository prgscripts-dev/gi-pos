package com.GIHOSTINGS.giposapp;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared three-destination navigation for the mobile app's main screens. */
public final class MobileBottomNavigation {
  public enum Destination { REPORT, HOME, SETTINGS }

  private static final int INK = Color.rgb(18, 32, 51);
  private static final int RED = Color.rgb(199, 22, 55);
  private static final int MUTED = Color.rgb(99, 115, 138);
  private static final int LINE = Color.rgb(217, 226, 236);
  private static final int SURFACE = Color.rgb(244, 247, 250);

  private MobileBottomNavigation() {}

  public static View wrap(Activity activity, View content, Destination active) {
    LinearLayout screen = new LinearLayout(activity);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(SURFACE);
    screen.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    screen.addView(create(activity, active), new LinearLayout.LayoutParams(-1, dp(activity, 82)));
    return screen;
  }

  private static View create(Activity activity, Destination active) {
    FrameLayout holder = new FrameLayout(activity);
    holder.setClipChildren(false);
    holder.setClipToPadding(false);

    LinearLayout rail = new LinearLayout(activity);
    rail.setGravity(Gravity.CENTER_VERTICAL);
    rail.setPadding(dp(activity, 12), dp(activity, 5), dp(activity, 12), dp(activity, 4));
    rail.setBackground(shape(activity, Color.WHITE, 18, 1, LINE));
    FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(-1, dp(activity, 64), Gravity.BOTTOM);
    holder.addView(rail, railParams);

    rail.addView(side(activity, "▥", "Report", Destination.REPORT, active), weight());
    rail.addView(new View(activity), new LinearLayout.LayoutParams(dp(activity, 92), 1));
    rail.addView(side(activity, "⚙", "Settings", Destination.SETTINGS, active), weight());

    TextView home = new TextView(activity);
    home.setText("⌂\nHome");
    home.setTextSize(12);
    home.setTextColor(Color.WHITE);
    home.setTypeface(Typeface.DEFAULT_BOLD);
    home.setGravity(Gravity.CENTER);
    home.setLineSpacing(0, 0.88f);
    home.setBackground(shape(activity, RED, 20, 0, RED));
    home.setElevation(dp(activity, 8));
    home.setContentDescription("Home");
    home.setOnClickListener(v -> open(activity, DashboardActivity.class, active != Destination.HOME));
    FrameLayout.LayoutParams homeParams = new FrameLayout.LayoutParams(dp(activity, 70), dp(activity, 70), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    holder.addView(home, homeParams);
    return holder;
  }

  private static View side(Activity activity, String icon, String label, Destination destination, Destination active) {
    LinearLayout item = new LinearLayout(activity);
    item.setOrientation(LinearLayout.VERTICAL);
    item.setGravity(Gravity.CENTER);
    item.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 3));
    int color = active == destination ? RED : MUTED;
    TextView iconView = text(activity, icon, 19, color, true);
    iconView.setGravity(Gravity.CENTER);
    TextView labelView = text(activity, label, 11, color, active == destination);
    labelView.setGravity(Gravity.CENTER);
    item.addView(iconView);
    item.addView(labelView);
    item.setContentDescription(label);
    Class<?> target = destination == Destination.REPORT ? ReportActivity.class : SettingsActivity.class;
    boolean animate=!(active==Destination.REPORT&&destination==Destination.SETTINGS);
    item.setOnClickListener(v -> open(activity, target, active != destination,animate));
    return item;
  }

  private static void open(Activity activity, Class<?> target, boolean shouldOpen) {
    open(activity,target,shouldOpen,true);
  }

  private static void open(Activity activity, Class<?> target, boolean shouldOpen,boolean animate) {
    if (!shouldOpen) return;
    Intent intent = new Intent(activity, target);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if(!animate){
      activity.startActivity(intent);
      activity.overridePendingTransition(0,0);
      return;
    }
    ActivityOptions options=ActivityOptions.makeCustomAnimation(activity,R.anim.nav_enter,R.anim.nav_exit);
    activity.startActivity(intent,options.toBundle());
  }

  private static LinearLayout.LayoutParams weight() {
    return new LinearLayout.LayoutParams(0, -1, 1);
  }

  private static TextView text(Activity activity, String value, int size, int color, boolean bold) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
    return view;
  }

  private static GradientDrawable shape(Activity activity, int fill, int radius, int stroke, int strokeColor) {
    GradientDrawable value = new GradientDrawable();
    value.setColor(fill);
    value.setCornerRadius(dp(activity, radius));
    if (stroke > 0) value.setStroke(dp(activity, stroke), strokeColor);
    return value;
  }

  private static int dp(Activity activity, int value) {
    return (int) (value * activity.getResources().getDisplayMetrics().density);
  }
}
