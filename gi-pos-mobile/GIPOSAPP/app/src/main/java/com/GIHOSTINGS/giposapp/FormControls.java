package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared form controls keep touch targets and text legible throughout the app. */
final class FormControls {
  private static final int INK=Color.rgb(18,32,51);
  private static final int MUTED=Color.rgb(99,115,138);
  private static final int LINE=Color.rgb(199,211,223);
  private static final int FOCUS=Color.rgb(8,127,140);
  private static final int FILL=Color.rgb(250,252,253);

  private FormControls() {}

  static EditText input(Context context,String hint,int type){
    EditText value=new EditText(context);
    value.setHint(hint);
    value.setHintTextColor(ColorStateList.valueOf(MUTED));
    value.setTextColor(ColorStateList.valueOf(INK));
    value.setLinkTextColor(FOCUS);
    value.setTextSize(15);
    value.setInputType(type);
    value.setSingleLine(true);
    value.setMinHeight(dp(context,56));
    value.setGravity(Gravity.CENTER_VERTICAL);
    value.setPadding(dp(context,16),0,dp(context,16),0);
    value.setSelectAllOnFocus(false);
    value.setIncludeFontPadding(false);
    value.setImeOptions(EditorInfo.IME_ACTION_NEXT|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    value.setBackground(background(context,false));
    value.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(context,56)));
    value.setOnFocusChangeListener((view,focused)->value.setBackground(background(context,focused)));
    return value;
  }

  static LinearLayout field(Context context,String label,View control){
    LinearLayout box=new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    TextView title=new TextView(context);
    title.setText(label);
    title.setTextSize(13);
    title.setTextColor(INK);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setIncludeFontPadding(false);
    box.addView(title,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    int height=dp(context,56);
    ViewGroup.LayoutParams existing=control.getLayoutParams();
    if(existing!=null&&existing.height!=0)height=existing.height;
    LinearLayout.LayoutParams controlParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,height);
    controlParams.topMargin=dp(context,7);
    box.addView(control,controlParams);
    return box;
  }

  static LinearLayout dialogForm(Context context){
    LinearLayout form=new LinearLayout(context);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(context,22),dp(context,8),dp(context,22),dp(context,6));
    return form;
  }

  static void submitOnDone(EditText value,Runnable action){
    value.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    value.setOnEditorActionListener((view,actionId,event)->{
      if(actionId!=EditorInfo.IME_ACTION_DONE)return false;
      action.run();
      return true;
    });
  }

  static EditText multiline(Context context,String hint){
    EditText value=input(context,hint,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    value.setSingleLine(false);
    value.setMinLines(3);
    value.setGravity(Gravity.TOP|Gravity.START);
    value.setPadding(dp(context,16),dp(context,14),dp(context,16),dp(context,14));
    value.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    return value;
  }

  private static GradientDrawable background(Context context,boolean focused){
    GradientDrawable value=new GradientDrawable();
    value.setColor(FILL);
    value.setCornerRadius(dp(context,8));
    value.setStroke(dp(context,focused?2:1),focused?FOCUS:LINE);
    return value;
  }

  private static int dp(Context context,int value){return (int)(value*context.getResources().getDisplayMetrics().density);}
}
