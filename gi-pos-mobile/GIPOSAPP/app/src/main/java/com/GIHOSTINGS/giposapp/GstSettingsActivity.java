package com.GIHOSTINGS.giposapp;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * GST management. When enabled, the chosen rate/mode/display become the default
 * tax applied to every order line across ordering, KOT, checkout and billing.
 */
public class GstSettingsActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;
  private Switch enableSwitch; private EditText rate; private Spinner modeSpinner,typeSpinner;
  private LinearLayout configPanel;

  // Tax display / type options shown in the dropdown, mapped to stored keys.
  private static final String[] TYPE_KEYS={"cgst_sgst","igst","vat","none"};
  private static final String[] TYPE_LABELS={"CGST + SGST (split equally)","IGST (single line)","VAT (single line)","Single tax line"};

  @Override public void onCreate(Bundle state){super.onCreate(state);db=PosDatabase.get(this);build();}

  private void build(){
    PosDatabase.GstSettings gst=db.gstSettings();
    LinearLayout page=column();page.setPadding(dp(18),dp(16),dp(18),dp(28));page.setBackgroundColor(surface);

    LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
    LinearLayout title=column();title.setPadding(dp(11),0,0,0);
    title.addView(text("GST Management",24,ink,true));
    title.addView(text("Tax configuration applied across billing",13,muted,false));
    head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    page.addView(head);

    // Enable / disable toggle.
    LinearLayout togglePanel=panel("GST");
    LinearLayout toggleRow=row();toggleRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout toggleCopy=column();
    toggleCopy.addView(text("Enable GST",16,ink,true));
    toggleCopy.addView(text("Apply tax to every bill automatically",12,muted,false),top(2));
    toggleRow.addView(toggleCopy,new LinearLayout.LayoutParams(0,-2,1));
    enableSwitch=new Switch(this);enableSwitch.setChecked(gst.enabled);
    toggleRow.addView(enableSwitch);
    togglePanel.addView(toggleRow,top(6));
    page.addView(togglePanel,top(16));

    // Configuration (rate, mode, display) — shown/dimmed based on the toggle.
    configPanel=panel("Tax configuration");
    rate=input("Example: 5",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    rate.setText(trim(gst.rate));
    configPanel.addView(field("GST rate (%)",rate),top(10));

    modeSpinner=spinner(new String[]{"Tax excluded (added on top)","Tax included (in price)"});
    modeSpinner.setSelection("inclusive".equals(gst.mode)?1:0);
    configPanel.addView(labeled("Price mode",modeSpinner),top(12));

    typeSpinner=spinner(TYPE_LABELS);
    int typeIndex=0;for(int i=0;i<TYPE_KEYS.length;i++)if(TYPE_KEYS[i].equals(gst.type))typeIndex=i;
    typeSpinner.setSelection(typeIndex);
    configPanel.addView(labeled("Tax display on bill",typeSpinner),top(12));

    configPanel.addView(text("CGST + SGST splits the rate into two equal halves on the bill. IGST/VAT show one line. Single tax line shows the total tax without a label split.",12,muted,false),top(10));
    page.addView(configPanel,top(14));

    enableSwitch.setOnCheckedChangeListener((b,checked)->applyEnabledState(checked));
    applyEnabledState(gst.enabled);

    Button save=button("Save",red,Color.WHITE);save.setOnClickListener(v->save());
    page.addView(save,topHeight(18,52));

    ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
  }

  private void applyEnabledState(boolean enabled){
    if(configPanel==null)return;
    configPanel.setAlpha(enabled?1f:0.5f);
    setEnabledRecursive(configPanel,enabled);
  }
  private void setEnabledRecursive(View view,boolean enabled){
    if(view instanceof LinearLayout){LinearLayout g=(LinearLayout)view;for(int i=0;i<g.getChildCount();i++)setEnabledRecursive(g.getChildAt(i),enabled);}
    else view.setEnabled(enabled);
  }

  private void save(){
    boolean enabled=enableSwitch.isChecked();
    double value;
    try{value=Double.parseDouble(rate.getText().toString().trim());}
    catch(Exception error){value=0;}
    if(enabled&&(value<=0||value>100)){rate.setError("Enter a rate between 0 and 100");rate.requestFocus();return;}
    String mode=modeSpinner.getSelectedItemPosition()==1?"inclusive":"exclusive";
    String type=TYPE_KEYS[Math.max(0,Math.min(typeSpinner.getSelectedItemPosition(),TYPE_KEYS.length-1))];
    db.saveGstSettings(new PosDatabase.GstSettings(enabled,value,mode,type));
    CloudSyncManager.syncAsync(this);
    Toast.makeText(this,enabled?"GST enabled and saved":"GST disabled",Toast.LENGTH_SHORT).show();
    finish();
  }

  private Spinner spinner(String[] values){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));s.setBackground(shape(Color.rgb(248,250,252),9,1,line));s.setPadding(dp(10),0,dp(8),0);return s;}
  private EditText input(String hint,int type){return FormControls.input(this,hint,type);}
  private LinearLayout field(String label,View input){return labeled(label,input);}
  private LinearLayout labeled(String label,View input){return FormControls.field(this,label,input);}
  private LinearLayout panel(String title){LinearLayout value=column();value.setPadding(dp(15),dp(14),dp(15),dp(16));value.setBackground(shape(Color.WHITE,10,1,line));value.addView(text(title,17,ink,true));return value;}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
  private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
  private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
  private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(shape(fill,9,1,line));return b;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
  private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}
  private LinearLayout.LayoutParams topHeight(int m,int h){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(h));p.topMargin=dp(m);return p;}
  private String trim(double value){if(value==Math.rint(value))return String.valueOf((long)value);return String.valueOf(value);}
  private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
