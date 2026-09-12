package com.GIHOSTINGS.giposapp;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class BusinessSettingsActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250),teal=Color.rgb(8,127,140);
  private PosDatabase db; private EditText name,address,phone,taxId,footer,billNext,kotNext; private CheckBox upi,card,due,split;
  private ImageView logoPreview; private String logoUri="";
  private String selectedTemplate="classic"; private LinearLayout templateBar;
  private final ActivityResultLauncher<String[]> logoPicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri==null)return;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}logoUri=uri.toString();showLogo();});
  @Override public void onCreate(Bundle state){super.onCreate(state);db=PosDatabase.get(this);build();}
  private void build(){PosDatabase.BusinessSettings current=db.businessSettings();logoUri=current.logoUri;selectedTemplate=current.template;PosDatabase.NumberingSettings numbering=db.numberingSettings();LinearLayout page=column();page.setPadding(dp(18),dp(16),dp(18),dp(28));page.setBackgroundColor(surface);LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout title=column();title.setPadding(dp(11),0,0,0);title.addView(text("Business & Billing",24,ink,true));title.addView(text("Receipt identity, numbering and payments",13,muted,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
    LinearLayout receipt=panel("Receipt details");name=input("Business name",InputType.TYPE_CLASS_TEXT);address=input("Address",InputType.TYPE_CLASS_TEXT);phone=input("Phone",InputType.TYPE_CLASS_PHONE);taxId=input("GSTIN / VAT number",InputType.TYPE_CLASS_TEXT);footer=input("Receipt footer",InputType.TYPE_CLASS_TEXT);name.setText(current.name);address.setText(current.address);phone.setText(current.phone);taxId.setText(current.taxId);footer.setText(current.footer);receipt.addView(field("Business name",name));receipt.addView(field("Address",address),top(11));receipt.addView(field("Phone",phone),top(11));receipt.addView(field("Tax ID",taxId),top(11));receipt.addView(field("Receipt footer",footer),top(11));
    // Business logo (printed at the top of the bill).
    receipt.addView(text("Business logo",13,ink,true),top(14));
    receipt.addView(text("Shown at the top of printed bills. A clear black-on-white logo prints best.",12,muted,false),top(4));
    logoPreview=new ImageView(this);logoPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);logoPreview.setBackground(shape(Color.rgb(241,245,249),10,1,line));receipt.addView(logoPreview,topHeight(10,120));
    LinearLayout logoActions=row();Button chooseLogo=button("Choose logo",Color.rgb(229,247,249),teal);chooseLogo.setOnClickListener(v->logoPicker.launch(new String[]{"image/*"}));logoActions.addView(chooseLogo,new LinearLayout.LayoutParams(0,dp(46),1));Button removeLogo=button("Remove",Color.rgb(255,241,242),red);removeLogo.setOnClickListener(v->{logoUri="";showLogo();});LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(dp(100),dp(46));rl.leftMargin=dp(8);logoActions.addView(removeLogo,rl);receipt.addView(logoActions,top(10));
    page.addView(receipt,top(18));showLogo();
    LinearLayout numbers=panel("Bill & KOT numbering");numbers.addView(text("Bill numbers reset each financial year. KOT numbers reset every day.",13,muted,false),top(4));billNext=input("Next bill number",InputType.TYPE_CLASS_NUMBER);kotNext=input("Next KOT number",InputType.TYPE_CLASS_NUMBER);billNext.setText(String.valueOf(numbering.nextBillNumber));kotNext.setText(String.valueOf(numbering.nextKotNumber));numbers.addView(field("Next bill number  |  FY "+numbering.billPeriod,billNext),top(12));numbers.addView(field("Next KOT number  |  "+numbering.kotPeriod,kotNext),top(11));numbers.addView(text("Only future numbers change. Existing bills and KOTs remain unchanged.",12,muted,false),top(9));page.addView(numbers,top(14));
    LinearLayout payments=panel("Payment methods");payments.addView(text("Cash is always available. Enable only the methods used at this counter.",13,muted,false),top(4));upi=check("UPI",current.upi);card=check("Card",current.card);due=check("Customer due",current.due);split=check("Split payment",current.split);payments.addView(upi,top(10));payments.addView(card);payments.addView(due);payments.addView(split);page.addView(payments,top(14));
    // Print template selector: choose the bill layout style.
    LinearLayout templates=panel("Print template");templates.addView(text("Choose the printed bill layout. Logo prints centered at the top in every style.",13,muted,false),top(4));templateBar=column();templates.addView(templateBar,top(10));renderTemplateOptions();page.addView(templates,top(14));
    Button save=button("Save",red,Color.WHITE);save.setOnClickListener(v->save());page.addView(save,topHeight(18,52));ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);}
  private void save(){String business=name.getText().toString().trim();if(business.isEmpty()){name.setError("Business name is required");name.requestFocus();return;}int nextBill,nextKot;try{nextBill=Integer.parseInt(billNext.getText().toString().trim());}catch(Exception error){billNext.setError("Enter a valid next bill number");billNext.requestFocus();return;}try{nextKot=Integer.parseInt(kotNext.getText().toString().trim());}catch(Exception error){kotNext.setError("Enter a valid next KOT number");kotNext.requestFocus();return;}try{db.saveNumberingSettings(nextBill,nextKot);}catch(IllegalArgumentException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();return;}db.saveBusinessSettings(new PosDatabase.BusinessSettings(business,address.getText().toString(),phone.getText().toString(),taxId.getText().toString(),footer.getText().toString(),upi.isChecked(),card.isChecked(),due.isChecked(),split.isChecked(),logoUri,selectedTemplate));CloudSyncManager.syncAsync(this);Toast.makeText(this,"Billing settings saved",Toast.LENGTH_SHORT).show();finish();}
  private void renderTemplateOptions(){
    if(templateBar==null)return;
    templateBar.removeAllViews();
    String[][] options={{"classic","Classic","Standard clean layout with a big Grand Total"},{"compact","Compact","Dense, saves paper, minimal separators"},{"elegant","Elegant","Framed, spaced, prominent name and total"}};
    for(int i=0;i<options.length;i++){
      String key=options[i][0],title=options[i][1],desc=options[i][2];
      boolean selected=selectedTemplate.equals(key);
      LinearLayout opt=column();opt.setPadding(dp(13),dp(11),dp(13),dp(11));
      opt.setBackground(shape(selected?Color.rgb(229,247,249):Color.WHITE,10,selected?2:1,selected?teal:line));
      opt.addView(text((selected?"● ":"○ ")+title,15,selected?teal:ink,true));
      opt.addView(text(desc,12,muted,false),top(3));
      opt.setOnClickListener(v->{selectedTemplate=key;renderTemplateOptions();});
      templateBar.addView(opt,i==0?new LinearLayout.LayoutParams(-1,-2):top(8));
    }
  }
  private void showLogo(){
    if(logoPreview==null)return;
    if(logoUri==null||logoUri.isEmpty()){logoPreview.setImageDrawable(null);logoPreview.setContentDescription("No logo selected");return;}
    // Load the bitmap ourselves inside a try/catch. setImageURI defers decoding to
    // the ImageView's measure pass, so a lost/expired URI permission would throw an
    // uncaught SecurityException during layout and crash (auto-close) the page.
    // Decoding here keeps any failure contained and clears the stale logo cleanly.
    android.graphics.Bitmap bitmap=null;
    try(java.io.InputStream input=getContentResolver().openInputStream(Uri.parse(logoUri))){
      if(input!=null)bitmap=android.graphics.BitmapFactory.decodeStream(input);
    }catch(Throwable ignored){bitmap=null;}
    if(bitmap!=null){
      logoPreview.setImageBitmap(bitmap);
      logoPreview.setContentDescription("Selected business logo");
    }else{
      // Permission lost or file gone: forget the logo so the page renders and the
      // user can pick a new one. (Saved only if they press Save.)
      logoUri="";
      logoPreview.setImageDrawable(null);
      logoPreview.setContentDescription("No logo selected");
    }
  }
  private CheckBox check(String label,boolean checked){CheckBox value=new CheckBox(this);value.setText(label);value.setTextSize(15);value.setTextColor(ink);value.setChecked(checked);value.setMinHeight(dp(44));return value;}private LinearLayout field(String label,EditText input){return FormControls.field(this,label,input);}private EditText input(String hint,int type){return FormControls.input(this,hint,type);}private LinearLayout panel(String title){LinearLayout value=column();value.setPadding(dp(15),dp(14),dp(15),dp(16));value.setBackground(shape(Color.WHITE,10,1,line));value.addView(text(title,17,ink,true));return value;}private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}private LinearLayout row(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);return value;}private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);return view;}private Button button(String value,int fill,int color){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(color);view.setTypeface(Typeface.DEFAULT_BOLD);view.setBackground(shape(fill,9,1,line));return view;}private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}private LinearLayout.LayoutParams topHeight(int margin,int height){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,dp(height));value.topMargin=dp(margin);return value;}private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
}
