package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Orders: date-wise bill history. Shows today's completed bills by
 * default, with a date range filter on top. Each order opens a full bill view
 * and can be cancelled. Cancelled bills remain listed (marked cancelled) but
 * are excluded from sales reports.
 */
public class OrderHistoryActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;
  // Default to the current business day (03:00 -> 03:00).
  private LocalDate selectedFrom=PosDatabase.currentBusinessDay(),selectedTo=PosDatabase.currentBusinessDay();
  private final List<PosDatabase.OrderListItem> orders=new ArrayList<>();
  private LinearLayout listContainer;private TextView summary;private Button fromButton,toButton;private String searchText="";

  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing())load();}};
  @Override public void onCreate(Bundle state){super.onCreate(state);if(!requireFeature("billing"))return;db=PosDatabase.get(this);build();}
  @Override protected void onResume(){super.onResume();load();CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}

  private void build(){
    LinearLayout root=column();root.setPadding(dp(16),dp(14),dp(16),dp(16));root.setBackgroundColor(surface);

    LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
    LinearLayout title=column();title.setPadding(dp(10),0,0,0);
    title.addView(text("Orders",24,ink,true));
    title.addView(text("Bill history · view or cancel orders",13,muted,false));
    header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button today=button("Today",Color.WHITE,ink);today.setOnClickListener(v->{selectedFrom=PosDatabase.currentBusinessDay();selectedTo=PosDatabase.currentBusinessDay();syncDateButtons();load();});
    header.addView(today,new LinearLayout.LayoutParams(dp(76),dp(42)));
    root.addView(header);

    LinearLayout dates=row();
    fromButton=button("From\n"+shortDate(selectedFrom),Color.WHITE,ink);fromButton.setOnClickListener(v->pickDate(true));dates.addView(fromButton,new LinearLayout.LayoutParams(0,dp(58),1));
    dates.addView(gap());
    toButton=button("To\n"+shortDate(selectedTo),Color.WHITE,ink);toButton.setOnClickListener(v->pickDate(false));dates.addView(toButton,new LinearLayout.LayoutParams(0,dp(58),1));
    root.addView(dates,top(14));

    summary=text("Loading",13,muted,true);summary.setPadding(dp(13),dp(11),dp(13),dp(11));summary.setBackground(shape(Color.WHITE,10,1,line));
    root.addView(summary,top(12));

    // Search bar: filter the listed bills by bill number or KOT number.
    EditText search=FormControls.input(this,"Search by bill # or KOT #",InputType.TYPE_CLASS_NUMBER);
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){searchText=s==null?"":s.toString().trim();renderList();}public void afterTextChanged(Editable e){}});
    root.addView(search,top(10));

    listContainer=column();
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(listContainer);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

    setContentView(root);
  }

  private void load(){
    if(listContainer==null)return;
    long from=PosDatabase.businessDayStart(selectedFrom);
    long to=PosDatabase.businessDayEnd(selectedTo);
    new Thread(()->{
      List<PosDatabase.OrderListItem> result=db.orderHistory(from,to);
      double sales=0;int active=0;
      for(PosDatabase.OrderListItem item:result)if(!"cancelled".equals(item.status)){sales+=item.total;active++;}
      final double totalSales=sales;final int counted=active;
      runOnUiThread(()->{orders.clear();orders.addAll(result);renderList();summary.setText(counted+" bill(s)  ·  Rs. "+money(totalSales)+(result.size()>counted?"   ("+(result.size()-counted)+" cancelled)":""));});
    }).start();
  }

  private void renderList(){
    listContainer.removeAllViews();
    // Apply the bill#/KOT# search filter (digits only).
    String q=searchText==null?"":searchText.replaceAll("[^0-9]","");
    List<PosDatabase.OrderListItem> shown=new ArrayList<>();
    for(PosDatabase.OrderListItem order:orders){
      if(q.isEmpty()){shown.add(order);continue;}
      boolean billMatch=order.billNumber!=null&&String.valueOf(order.billNumber).contains(q);
      boolean kotMatch=order.kotNumber!=null&&String.valueOf(order.kotNumber).contains(q);
      if(billMatch||kotMatch)shown.add(order);
    }
    if(shown.isEmpty()){
      String msg=q.isEmpty()?"No bills for the selected dates.":"No bill or KOT matches \""+searchText+"\".";
      TextView empty=text(msg,16,muted,true);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(48),dp(20),dp(48));listContainer.addView(empty);return;
    }
    for(PosDatabase.OrderListItem order:shown)listContainer.addView(orderCard(order),top(9));
  }

  private View orderCard(PosDatabase.OrderListItem order){
    boolean cancelled="cancelled".equals(order.status);
    LinearLayout card=column();card.setPadding(dp(14),dp(12),dp(14),dp(12));
    card.setBackground(shape(cancelled?Color.rgb(253,246,246):Color.WHITE,11,1,cancelled?Color.rgb(248,205,205):line));

    LinearLayout headRow=row();headRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout copy=column();
    String number=order.billNumber!=null?"Bill #"+order.billNumber:order.kotNumber!=null?"KOT #"+order.kotNumber:"Order";
    copy.addView(text(number+"  ·  "+order.tableName,16,ink,true));
    copy.addView(text(order.itemCount+" item(s)  ·  "+time(order.updatedAt),12,muted,false),top(3));
    headRow.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
    LinearLayout amountBox=column();amountBox.setGravity(Gravity.END);
    amountBox.addView(text(cancelled?"CANCELLED":("due".equals(order.status)?"DUE":"PAID"),11,cancelled?red:teal,true));
    TextView amount=text("Rs. "+money(order.total),16,ink,true);amount.setGravity(Gravity.END);amountBox.addView(amount,top(4));
    headRow.addView(amountBox);
    card.addView(headRow);

    LinearLayout actions=row();
    Button view=button("View bill",Color.rgb(229,247,249),teal);view.setOnClickListener(v->openBill(order));
    actions.addView(view,new LinearLayout.LayoutParams(0,dp(44),1));
    if(!cancelled){
      actions.addView(gap());
      Button cancel=button("Cancel",Color.rgb(255,241,243),red);cancel.setOnClickListener(v->confirmCancel(order));
      actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(44),1));
    }
    card.addView(actions,top(11));
    return card;
  }

  private void openBill(PosDatabase.OrderListItem order){
    Intent intent=new Intent(this,SaleSuccessActivity.class);
    intent.putExtra("orderId",order.id);
    intent.putExtra("viewOnly",true);
    startActivity(intent);
  }

  private void confirmCancel(PosDatabase.OrderListItem order){
    EditText reason=FormControls.input(this,"Reason (optional)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    LinearLayout form=FormControls.dialogForm(this);
    String number=order.billNumber!=null?"Bill #"+order.billNumber:"this order";
    form.addView(text("Cancelling "+number+" removes it from sales reports"+(order.status.equals("due")?" and reverses the customer due":"")+". This cannot be undone.",13,muted,false));
    form.addView(FormControls.field(this,"Cancellation reason",reason),top(12));
    new AlertDialog.Builder(this)
      .setTitle("Cancel bill?")
      .setView(form)
      .setNegativeButton("Keep bill",null)
      .setPositiveButton("Cancel bill",(dialog,which)->doCancel(order,reason.getText().toString()))
      .show();
  }

  private void doCancel(PosDatabase.OrderListItem order,String reason){
    new Thread(()->{
      try{db.cancelOrder(order.id,reason);CloudSyncManager.syncAsync(this);runOnUiThread(()->{Toast.makeText(this,"Bill cancelled",Toast.LENGTH_SHORT).show();load();});}
      catch(Exception error){runOnUiThread(()->Toast.makeText(this,error.getMessage()==null?"Could not cancel":error.getMessage(),Toast.LENGTH_LONG).show());}
    }).start();
  }

  private void pickDate(boolean fromDate){
    LocalDate current=fromDate?selectedFrom:selectedTo;
    DatePickerDialog dialog=new DatePickerDialog(this,(picker,year,month,day)->{
      LocalDate value=LocalDate.of(year,month+1,day);
      if(fromDate){selectedFrom=value;if(selectedTo.isBefore(value))selectedTo=value;}
      else if(value.isBefore(selectedFrom)){Toast.makeText(this,"To date cannot be before From date",Toast.LENGTH_SHORT).show();return;}
      else selectedTo=value;
      syncDateButtons();load();
    },current.getYear(),current.getMonthValue()-1,current.getDayOfMonth());
    dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
    dialog.show();
  }
  private void syncDateButtons(){if(fromButton!=null)fromButton.setText("From\n"+shortDate(selectedFrom));if(toButton!=null)toButton.setText("To\n"+shortDate(selectedTo));}

  private String shortDate(LocalDate value){return value.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));}
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
