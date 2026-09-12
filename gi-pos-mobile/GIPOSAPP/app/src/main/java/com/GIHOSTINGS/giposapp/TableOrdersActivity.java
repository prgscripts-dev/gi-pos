package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.content.Intent;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Table orders: dine-in tables that currently have an open bill.
 * Clean one-card-per-table layout for quick scanning; each card can view the
 * order details or remove (cancel) the order to free the table.
 */
public class TableOrdersActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;
  private final List<PosDatabase.OrderListItem> orders=new ArrayList<>();
  private LinearLayout listContainer;private TextView summary;

  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing())load();}};
  @Override public void onCreate(Bundle state){super.onCreate(state);if(!requireFeature("billing"))return;db=PosDatabase.get(this);build();}
  @Override protected void onResume(){super.onResume();load();CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}

  private void build(){
    LinearLayout root=column();root.setPadding(dp(16),dp(14),dp(16),dp(16));root.setBackgroundColor(surface);
    LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
    LinearLayout title=column();title.setPadding(dp(10),0,0,0);
    title.addView(text("Table orders",23,ink,true));
    title.addView(text("Tables with an open bill",13,muted,false));
    header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    root.addView(header);

    summary=text("Loading",13,muted,true);summary.setPadding(dp(13),dp(11),dp(13),dp(11));summary.setBackground(shape(Color.WHITE,10,1,line));
    root.addView(summary,top(14));

    listContainer=column();
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(listContainer);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);
  }

  private void load(){
    if(listContainer==null)return;
    new Thread(()->{
      List<PosDatabase.OrderListItem> result=db.activeTableOrders();
      double total=0;for(PosDatabase.OrderListItem item:result)total+=item.total;
      final double amount=total;
      runOnUiThread(()->{orders.clear();orders.addAll(result);renderList();summary.setText(result.size()+" table(s) occupied  ·  Rs. "+money(amount));});
    }).start();
  }

  private void renderList(){
    listContainer.removeAllViews();
    if(orders.isEmpty()){TextView empty=text("No tables have an open bill right now.",16,muted,true);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(48),dp(20),dp(48));listContainer.addView(empty);return;}
    for(PosDatabase.OrderListItem order:orders)listContainer.addView(tableCard(order),top(9));
  }

  private View tableCard(PosDatabase.OrderListItem order){
    boolean held="held".equals(order.status);
    LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(14),dp(13),dp(14),dp(13));
    card.setBackground(shape(held?Color.rgb(255,248,224):Color.WHITE,12,1,held?Color.rgb(236,179,55):line));

    // Table token
    TextView token=text(order.tableName,17,ink,true);token.setGravity(Gravity.CENTER);token.setMinWidth(dp(56));token.setPadding(dp(10),dp(12),dp(10),dp(12));token.setBackground(shape(Color.rgb(244,247,250),10,1,line));
    card.addView(token);

    LinearLayout copy=column();copy.setPadding(dp(12),0,dp(8),0);
    String number=order.billNumber!=null?"Bill #"+order.billNumber:order.kotNumber!=null?"KOT #"+order.kotNumber:"Open";
    copy.addView(text(number+"  ·  "+order.itemCount+" item(s)",15,ink,true));
    copy.addView(text((held?"HELD · ":"")+"Rs. "+money(order.total)+"  ·  "+time(order.updatedAt),12,muted,false),top(3));
    card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));

    LinearLayout actions=column();
    Button view=button("View",Color.rgb(229,247,249),teal);view.setOnClickListener(v->openOrder(order));
    actions.addView(view,new LinearLayout.LayoutParams(dp(92),dp(40)));
    Button remove=button("Remove",Color.rgb(255,241,243),red);remove.setOnClickListener(v->confirmRemove(order));
    LinearLayout.LayoutParams removeParams=new LinearLayout.LayoutParams(dp(92),dp(40));removeParams.topMargin=dp(7);
    actions.addView(remove,removeParams);
    card.addView(actions);
    return card;
  }

  private void openOrder(PosDatabase.OrderListItem order){
    Intent intent=new Intent(this,OrderActivity.class);
    intent.putExtra("orderId",order.id);
    intent.putExtra("tableName",order.tableName);
    startActivity(intent);
  }

  private void confirmRemove(PosDatabase.OrderListItem order){
    EditText reason=FormControls.input(this,"Reason (optional)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(text("Removing the order for "+order.tableName+" frees the table. This cannot be undone.",13,muted,false));
    form.addView(FormControls.field(this,"Reason",reason),top(12));
    new AlertDialog.Builder(this)
      .setTitle("Remove order?")
      .setView(form)
      .setNegativeButton("Keep",null)
      .setPositiveButton("Remove order",(dialog,which)->doRemove(order,reason.getText().toString()))
      .show();
  }

  private void doRemove(PosDatabase.OrderListItem order,String reason){
    new Thread(()->{
      try{db.cancelActiveOrder(order.id,reason);CloudSyncManager.syncAsync(this);runOnUiThread(()->{Toast.makeText(this,order.tableName+" freed",Toast.LENGTH_SHORT).show();load();});}
      catch(Exception error){runOnUiThread(()->Toast.makeText(this,error.getMessage()==null?"Could not remove":error.getMessage(),Toast.LENGTH_LONG).show());}
    }).start();
  }

  private String time(long value){return DateTimeFormatter.ofPattern("hh:mm a",Locale.US).format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()));}
  private String money(double value){return String.format(Locale.US,"%.2f",value);}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
  private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
  private TextView text(String s,int z,int c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(b)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
  private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setMinWidth(0);b.setPadding(dp(6),0,dp(6),0);b.setBackground(shape(fill,9,1,line));return b;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
  private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}
  private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
