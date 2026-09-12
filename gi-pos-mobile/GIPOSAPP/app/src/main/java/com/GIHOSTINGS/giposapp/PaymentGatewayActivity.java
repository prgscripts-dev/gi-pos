package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Settings page for custom payment gateways. Users create a gateway by simply
 * entering a name. Active gateways then appear as payment options at checkout
 * and as gateway-wise filters on the reports page.
 */
public class PaymentGatewayActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;
  private LinearLayout listContainer;

  @Override public void onCreate(Bundle state){super.onCreate(state);if(!requireFeature("billing"))return;db=PosDatabase.get(this);build();}
  @Override protected void onResume(){super.onResume();if(listContainer!=null)renderList();}

  private void build(){
    LinearLayout root=column();
    root.setPadding(dp(18),dp(18),dp(18),dp(28));
    root.setBackgroundColor(surface);

    LinearLayout head=row();
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
    LinearLayout title=column();
    title.setPadding(dp(11),0,0,0);
    title.addView(text("Payment gateways",24,ink,true));
    title.addView(text("Create custom payment methods for billing and reports",13,muted,false),top(3));
    head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    root.addView(head);

    LinearLayout create=panel("Create payment gateway");
    create.addView(text("Add a gateway by entering a name (for example Paytm, PhonePe, Razorpay, Swiggy). It becomes available at checkout and as a report filter.",13,muted,false),top(6));
    Button add=button("+ Create payment gateway",red,Color.WHITE);
    add.setOnClickListener(v->showCreate());
    create.addView(add,top(13));
    root.addView(create,top(16));

    listContainer=panel("Your payment gateways");
    root.addView(listContainer,top(16));

    ScrollView scroll=new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(root);
    setContentView(scroll);
    renderList();
  }

  private void renderList(){
    // Rebuild the list section, keeping the panel heading (first child).
    while(listContainer.getChildCount()>1)listContainer.removeViewAt(1);
    List<PosDatabase.PaymentGateway> gateways=db.paymentGateways(false);
    if(gateways.isEmpty()){
      listContainer.addView(text("No payment gateways yet. Create one above to see it at checkout.",14,muted,false),top(12));
      return;
    }
    for(PosDatabase.PaymentGateway gateway:gateways){
      LinearLayout rowView=row();
      rowView.setGravity(Gravity.CENTER_VERTICAL);
      rowView.setPadding(dp(12),dp(11),dp(12),dp(11));
      rowView.setBackground(shape(gateway.active?Color.WHITE:Color.rgb(248,250,252),9,1,line));

      LinearLayout copy=column();
      copy.addView(text(gateway.name,16,ink,true));
      copy.addView(text(gateway.active?"Active · shown at checkout":"Disabled · hidden at checkout",12,gateway.active?teal:muted,false),top(3));
      rowView.addView(copy,new LinearLayout.LayoutParams(0,-2,1));

      Button toggle=button(gateway.active?"Disable":"Enable",gateway.active?Color.rgb(255,247,220):Color.rgb(229,247,249),gateway.active?ink:teal);
      toggle.setOnClickListener(v->{db.setGatewayActive(gateway.id,!gateway.active);CloudSyncManager.syncAsync(this);renderList();});
      rowView.addView(toggle,new LinearLayout.LayoutParams(dp(96),dp(42)));

      Button rename=button("Edit",Color.WHITE,ink);
      LinearLayout.LayoutParams renameParams=new LinearLayout.LayoutParams(dp(70),dp(42));renameParams.leftMargin=dp(8);
      rename.setOnClickListener(v->showRename(gateway));
      rowView.addView(rename,renameParams);

      listContainer.addView(rowView,top(9));
    }
  }

  private void showCreate(){
    EditText name=FormControls.input(this,"Gateway name",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Payment gateway name",name));
    AlertDialog dialog=new AlertDialog.Builder(this)
      .setTitle("Create payment gateway")
      .setView(form)
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Create",null)
      .create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
      String value=name.getText().toString().trim();
      if(value.isEmpty()){name.setError("Enter a name");name.requestFocus();return;}
      try{db.saveGateway(value);CloudSyncManager.syncAsync(this);dialog.dismiss();renderList();Toast.makeText(this,"Payment gateway created",Toast.LENGTH_SHORT).show();}
      catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Could not create gateway":error.getMessage(),Toast.LENGTH_LONG).show();}
    }));
    dialog.show();
  }

  private void showRename(PosDatabase.PaymentGateway gateway){
    EditText name=FormControls.input(this,"Gateway name",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    name.setText(gateway.name);
    name.setSelection(name.getText().length());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Payment gateway name",name));
    AlertDialog dialog=new AlertDialog.Builder(this)
      .setTitle("Edit payment gateway")
      .setView(form)
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Save",null)
      .create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
      String value=name.getText().toString().trim();
      if(value.isEmpty()){name.setError("Enter a name");name.requestFocus();return;}
      try{db.renameGateway(gateway.id,value);CloudSyncManager.syncAsync(this);dialog.dismiss();renderList();}
      catch(Exception error){Toast.makeText(this,error.getMessage()==null?"Could not save":error.getMessage(),Toast.LENGTH_LONG).show();}
    }));
    dialog.show();
  }

  private LinearLayout panel(String title){LinearLayout value=column();value.setPadding(dp(16),dp(15),dp(16),dp(16));value.setBackground(shape(Color.WHITE,13,1,line));value.addView(text(title,17,ink,true));return value;}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
  private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
  private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
  private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(shape(fill,9,1,line));return b;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
  private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}
  private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
