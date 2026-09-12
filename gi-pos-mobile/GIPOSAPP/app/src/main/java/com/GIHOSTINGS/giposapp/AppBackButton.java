package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageButton;

/** Shared back control used by every secondary screen. */
public final class AppBackButton {
  private AppBackButton() {}

  public static ImageButton create(Context context, View.OnClickListener listener) {
    ImageButton button = new ImageButton(context);
    button.setImageDrawable(new ArrowDrawable(context));
    button.setContentDescription("Back");
    button.setPadding(dp(context, 11), dp(context, 11), dp(context, 11), dp(context, 11));
    button.setBackground(background(context));
    button.setOnClickListener(listener);
    button.setScaleType(ImageButton.ScaleType.CENTER);
    button.setElevation(dp(context, 1));
    return button;
  }

  private static Drawable background(Context context) {
    GradientDrawable value = new GradientDrawable();
    value.setColor(Color.WHITE);
    value.setCornerRadius(dp(context, 11));
    value.setStroke(dp(context, 1), Color.rgb(217, 226, 236));
    return value;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private static final class ArrowDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int inset;

    ArrowDrawable(Context context) {
      inset = dp(context, 3);
      paint.setColor(Color.rgb(18, 32, 51));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(context, 2));
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
      float left = getBounds().left + inset;
      float right = getBounds().right - inset;
      float middle = getBounds().exactCenterY();
      float wing = Math.min(getBounds().width(), getBounds().height()) * .3f;
      canvas.drawLine(left, middle, right, middle, paint);
      canvas.drawLine(left, middle, left + wing, middle - wing, paint);
      canvas.drawLine(left, middle, left + wing, middle + wing, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return inset * 7; }
    @Override public int getIntrinsicHeight() { return inset * 7; }
  }
}
