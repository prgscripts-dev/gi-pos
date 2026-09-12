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
 * Settings > Unclosed orders: every currently open/unbilled order (dine-in,
 * delivery and takeaway). Each order can be viewed (full details) or cancelled
 * so it is no longer active.
 */
public class UnclosedOrdersActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;
  private final List<PosDatabase.OrderListItem> orders=new ArrayList<>();
  private LinearLayout listContainer;private TextView summary;

  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing())load();}};
  @Override public void onCreate(Bundle state){super.onCreate(state);if(!requireFeature("billing"))return;db=PosDatabase.get(this);build();}
  @Override protected void onResume(){super.onResume();load();CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}

  private void build(){
    LinearLayout root=column();root.setPadding(dp(16),dp(12),dp(16),dp(16));root.setBackgroundColor(surface);
    // Compact, ~2-line header: small back button + two tidy text lines.
    LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(38),dp(38)));
    LinearLayout title=column();title.setPadding(dp(10),0,0,0);
    title.addView(text("Unclosed orders",18,ink,true));
    title.addView(text("All open orders · view or cancel",12,muted,false),top(1));
    header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    root.addView(header);

    summary=text("Loading",12,muted,true);summary.setPadding(dp(12),dp(9),dp(12),dp(9));summary.setBackground(shape(Color.WHITE,9,1,line));
    root.addView(summary,top(12));

    listContainer=column();
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(listContainer);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);
  }

  private void load(){
    if(listContainer==null)return;
    new Thread(()->{
      List<PosDatabase.OrderListItem> result=db.activeOrders();
      double total=0;for(PosDatabase.OrderListItem item:result)total+=item.total;
      final double amount=total;
      runOnUiThread(()->{orders.clear();orders.addAll(result);renderList();summary.setText(result.size()+" open order(s)  ·  Rs. "+money(amount));});
    }).start();
  }

  private void renderList(){
    listContainer.removeAllViews();
    if(orders.isEmpty()){TextView empty=text("No unclosed orders. Everything is billed.",16,muted,true);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(48),dp(20),dp(48));listContainer.addView(empty);return;}
    for(PosDatabase.OrderListItem order:orders)listContainer.addView(orderCard(order),top(9));
  }

  private View orderCard(PosDatabase.OrderListItem order){
    LinearLayout card=column();card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackground(shape(Color.WHITE,11,1,line));
    LinearLayout headRow=row();headRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout copy=column();
    String number=order.billNumber!=null?"Bill #"+order.billNumber:order.kotNumber!=null?"KOT #"+order.kotNumber:"New order";
    copy.addView(text(order.tableName+"  ·  "+number,16,ink,true));
    copy.addView(text(order.itemCount+" item(s)  ·  "+time(order.updatedAt),12,muted,false),top(3));
    headRow.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
    headRow.addView(typeBadge(order.orderType));
    card.addView(headRow);

    LinearLayout meta=row();meta.setGravity(Gravity.CENTER_VERTICAL);
    meta.addView(text(order.status.toUpperCase(Locale.US),11,teal,true),new LinearLayout.LayoutParams(0,-2,1));
    TextView amount=text("Rs. "+money(order.total),16,ink,true);meta.addView(amount);
    card.addView(meta,top(8));

    LinearLayout actions=row();
    Button view=button("View",Color.rgb(229,247,249),teal);view.setOnClickListener(v->openOrder(order));
    actions.addView(view,new LinearLayout.LayoutParams(0,dp(44),1));
    actions.addView(gap());
    Button cancel=button("Cancel",Color.rgb(255,241,243),red);cancel.setOnClickListener(v->confirmCancel(order));
    actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(44),1));
    card.addView(actions,top(11));
    return card;
  }

  private TextView typeBadge(String orderType){
    String label=PosDatabase.orderTypeLabel(orderType);
    int color="delivery".equals(orderType)?Color.rgb(180,83,9):"takeaway".equals(orderType)?Color.rgb(29,78,216):teal;
    int fill="delivery".equals(orderType)?Color.rgb(255,247,224):"takeaway".equals(orderType)?Color.rgb(232,240,254):Color.rgb(229,247,249);
    TextView badge=text(label,11,color,true);badge.setPadding(dp(10),dp(6),dp(10),dp(6));badge.setBackground(shape(fill,8,0,color));
    return badge;
  }

  private void openOrder(PosDatabase.OrderListItem order){
    Intent intent=new Intent(this,OrderActivity.class);
    intent.putExtra("orderId",order.id);
    intent.putExtra("tableName",order.tableName);
    startActivity(intent);
  }

  private void confirmCancel(PosDatabase.OrderListItem order){
    EditText reason=FormControls.input(this,"Reason (optional)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(text("Cancelling this order removes it from active service. This cannot be undone.",13,muted,false));
    form.addView(FormControls.field(this,"Cancellation reason",reason),top(12));
    new AlertDialog.Builder(this)
      .setTitle("Cancel order?")
      .setView(form)
      .setNegativeButton("Keep order",null)
      .setPositiveButton("Cancel order",(dialog,which)->doCancel(order,reason.getText().toString()))
      .show();
  }

  private void doCancel(PosDatabase.OrderListItem order,String reason){
    new Thread(()->{
      try{db.cancelActiveOrder(order.id,reason);CloudSyncManager.syncAsync(this);runOnUiThread(()->{Toast.makeText(this,"Order cancelled",Toast.LENGTH_SHORT).show();load();});}
      catch(Exception error){runOnUiThread(()->Toast.makeText(this,error.getMessage()==null?"Could not cancel":error.getMessage(),Toast.LENGTH_LONG).show());}
    }).start();
  }

  private String time(long value){return DateTimeFormatter.ofPattern("dd MMM, hh:mm a",Locale.US).format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()));}
  private String money(double value){return String.format(Locale.US,"%.2f",value);}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
  private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
  private TextView text(String s,int z,int c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(b)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
  private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(shape(fill,9,1,line));return b;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
  private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}
  private View gap(){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(dp(8),1));return v;}
  private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
