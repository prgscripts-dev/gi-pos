package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

public class DashboardActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  // Foreground sync + real-time updates are driven app-wide by GiPosApplication
  // (RealtimeSync stream + fallback poll). This screen only listens for applied
  // updates via syncListener and refreshes its summary.
  private TextView openOrdersValue,openOrdersDetail,todayValue,todayDetail,tableSummary;
  private LinearLayout floorBar;
  private GridLayout tableGrid;
  private PosDatabase database;
  private String selectedFloor=null;

  @Override public void onCreate(Bundle savedInstanceState){super.onCreate(savedInstanceState);showDashboard();}

  private void showDashboard(){
    SecureStore.Session session=SecureStore.load(this);
    if(session==null||!session.isActive()){
      SecureStore.clear(this);
      startActivity(new Intent(this,MainActivity.class));
      finish();
      return;
    }
    database=PosDatabase.get(this);
    database.getWritableDatabase();
    database.seedDefaultTables();
    PosDatabase.BusinessSettings business=database.businessSettings();

    LinearLayout content=column();
    content.setPadding(dp(16),dp(14),dp(16),dp(22));
    content.setBackgroundColor(surface);
    content.addView(appHeader(business,session));

    LinearLayout summary=row();
    if(session.hasFeature("reports")){
      LinearLayout today=metric("Today","Rs. 0.00","0 bills / 0 items");
      todayValue=(TextView)today.getChildAt(1);
      todayDetail=(TextView)today.getChildAt(2);
      today.setOnClickListener(v->startActivity(new Intent(this,ReportActivity.class)));
      summary.addView(today,weightHeight(88));
    }
    if(session.hasFeature("billing")){
      if(summary.getChildCount()>0)summary.addView(spacer(10));
      LinearLayout openOrders=metric("Open orders","0","No active orders");
      openOrdersValue=(TextView)openOrders.getChildAt(1);
      openOrdersDetail=(TextView)openOrders.getChildAt(2);
      openOrders.setOnClickListener(v->startActivity(new Intent(this,OrdersActivity.class)));
      summary.addView(openOrders,weightHeight(88));
    }
    if(summary.getChildCount()>0)content.addView(summary,top(16));

    if(session.hasFeature("billing")){
      LinearLayout quickHeading=row();
      quickHeading.addView(text("New order",20,ink,true),new LinearLayout.LayoutParams(0,-2,1));
      content.addView(quickHeading,top(20));
      LinearLayout quickRow=row();
      Button delivery=serviceButton("Delivery","Home delivery order");
      delivery.setOnClickListener(v->startDelivery());
      quickRow.addView(delivery,weightHeight(58));
      quickRow.addView(spacer(10));
      Button takeaway=serviceButton("Takeaway","Parcel / pickup order");
      takeaway.setOnClickListener(v->startTakeaway());
      quickRow.addView(takeaway,weightHeight(58));
      content.addView(quickRow,top(10));
    }

    LinearLayout tableHeading=row();
    tableHeading.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout tableCopy=column();
    tableCopy.addView(text("Tables",20,ink,true));
    tableSummary=text("",12,muted,false);
    tableCopy.addView(tableSummary,top(2));
    tableHeading.addView(tableCopy,new LinearLayout.LayoutParams(0,-2,1));
    TextView hint=text("Tap a table to order",11,teal,true);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(dp(9),dp(6),dp(9),dp(6));
    hint.setBackground(shape(Color.rgb(229,247,249),9,0,teal));
    tableHeading.addView(hint);
    content.addView(tableHeading,top(20));

    HorizontalScrollView floorScroll=new HorizontalScrollView(this);
    floorScroll.setHorizontalScrollBarEnabled(false);
    floorBar=row();
    floorScroll.addView(floorBar);
    content.addView(floorScroll,top(10));

    tableGrid=new GridLayout(this);
    tableGrid.setColumnCount(tableColumns());
    content.addView(tableGrid,top(7));

    ScrollView scroll=new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(surface);
    scroll.addView(content);
    setContentView(MobileBottomNavigation.wrap(this,scroll,MobileBottomNavigation.Destination.HOME));
    refreshTables();
  }

  private View appHeader(PosDatabase.BusinessSettings business,SecureStore.Session session){
    LinearLayout top=row();
    top.setGravity(Gravity.CENTER_VERTICAL);
    ImageView brand=new ImageView(this);
    brand.setImageResource(R.drawable.app_logo);
    brand.setScaleType(ImageView.ScaleType.CENTER_CROP);
    brand.setContentDescription("GI POS logo");
    brand.setBackground(shape(Color.rgb(16,21,24),12,0,Color.TRANSPARENT));
    brand.setClipToOutline(true);
    top.addView(brand,new LinearLayout.LayoutParams(dp(46),dp(46)));
    LinearLayout heading=column();
    heading.setPadding(dp(11),0,0,0);
    TextView businessName=text(business.name.isBlank()?"GI POS Mobile":business.name,19,ink,true);
    businessName.setSingleLine(true);
    businessName.setEllipsize(TextUtils.TruncateAt.END);
    heading.addView(businessName);
    TextView operator=text(session.ownerName.isBlank()?"Ready for service":session.ownerName,12,muted,false);
    operator.setSingleLine(true);
    operator.setEllipsize(TextUtils.TruncateAt.END);
    heading.addView(operator,top(2));
    top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    return top;
  }

  private void refreshTables(){
    if(database==null||floorBar==null||tableGrid==null)return;
    floorBar.removeAllViews();
    addFloorChip("All",null);
    for(PosDatabase.FloorInfo floor:database.floors())addFloorChip(floor.name,floor.id);
    List<PosDatabase.TableInfo> tables=database.tables(selectedFloor);
    tableGrid.removeAllViews();
    int active=0;
    for(PosDatabase.TableInfo table:tables){
      if(!"available".equals(table.status))active++;
      tableGrid.addView(tableCard(table),cellParams());
    }
    tableSummary.setText(tables.size()+" tables  •  "+active+" active");
  }

  private void addFloorChip(String label,String id){
    boolean selected=(selectedFloor==null&&id==null)||(selectedFloor!=null&&selectedFloor.equals(id));
    Button chip=button(label,selected?red:Color.WHITE,selected?Color.WHITE:ink);
    chip.setOnClickListener(v->{selectedFloor=id;refreshTables();});
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(40));
    params.rightMargin=dp(8);
    floorBar.addView(chip,params);
  }

  private View tableCard(PosDatabase.TableInfo table){
    int fill,border;
    if("available".equals(table.status)){fill=Color.WHITE;border=Color.rgb(205,216,227);}
    else if("held".equals(table.status)){fill=Color.rgb(255,247,220);border=Color.rgb(236,179,55);}
    else{fill=Color.rgb(229,247,249);border=teal;}
    LinearLayout card=column();
    card.setGravity(Gravity.CENTER);
    card.setPadding(dp(6),dp(11),dp(6),dp(10));
    card.setBackground(shape(fill,13,2,border));
    TextView name=text(table.name,19,ink,true);
    name.setGravity(Gravity.CENTER);
    name.setMaxLines(1);
    name.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(name,new LinearLayout.LayoutParams(-1,-2));
    String state="available".equals(table.status)?table.seats+" seats":table.status.toUpperCase(Locale.US);
    TextView stateView=text(state,11,"available".equals(table.status)?muted:border,true);
    stateView.setGravity(Gravity.CENTER);
    card.addView(stateView,centerTop(5));
    card.setOnClickListener(v->openOrder(table));
    // Long-press a table to duplicate it for another customer (or remove a duplicate).
    card.setOnLongClickListener(v->{showTableActions(table);return true;});
    return card;
  }

  // Long-press menu: duplicate a table so the same table can serve another
  // customer in parallel. The new table starts empty and is auto-removed once its
  // bill is finalized at checkout. Duplicates can also be removed manually here.
  private void showTableActions(PosDatabase.TableInfo table){
    String[] options=table.isDuplicate
      ? new String[]{"Open order","Remove this duplicate"}
      : new String[]{"Open order","Duplicate this table"};
    new android.app.AlertDialog.Builder(this).setTitle(table.name).setItems(options,(d,which)->{
      if(which==0){openOrder(table);return;}
      try{
        if(table.isDuplicate){
          database.removeDuplicateTable(table.id);
          CloudSyncManager.syncAsync(this);
          refreshTables();
          android.widget.Toast.makeText(this,"Duplicate table removed",android.widget.Toast.LENGTH_SHORT).show();
        }else{
          database.duplicateTable(table.id);
          CloudSyncManager.syncAsync(this);
          refreshTables();
          android.widget.Toast.makeText(this,"Table duplicated \u2014 ready for the next customer",android.widget.Toast.LENGTH_SHORT).show();
        }
      }catch(Exception error){
        android.widget.Toast.makeText(this,error.getMessage()==null?"Could not update table":error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
      }
    }).setNegativeButton("Cancel",null).show();
  }

  private void openOrder(PosDatabase.TableInfo table){
    String orderId=database.openOrCreateOrder(table.id);
    Intent intent=new Intent(this,OrderActivity.class);
    intent.putExtra("orderId",orderId);
    intent.putExtra("tableName",table.name);
    startActivity(intent);
  }

  private Button serviceButton(String label,String description){
    Button button=new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(16);
    button.setTextColor(Color.WHITE);
    button.setTypeface(Typeface.DEFAULT_BOLD);
    button.setContentDescription(description);
    button.setBackground(shape(teal,12,0,teal));
    return button;
  }

  // Delivery: collect the delivery details first, then open the ordering flow.
  // Delivery: go straight to the ordering screen. Customer/delivery details are
  // added there via the customer icon next to the Note button (no upfront form).
  private void startDelivery(){
    String orderId=database.createTypedOrder("delivery","","","","");
    openTypedOrder(orderId,"Delivery");
  }

  // Takeaway: start immediately, no address needed.
  private void startTakeaway(){
    String orderId=database.createTypedOrder("takeaway","","","","");
    openTypedOrder(orderId,"Takeaway");
  }

  private void openTypedOrder(String orderId,String label){
    Intent intent=new Intent(this,OrderActivity.class);
    intent.putExtra("orderId",orderId);
    intent.putExtra("tableName",label);
    startActivity(intent);
  }

  private EditText input(String hint,int type){return FormControls.input(this,hint,type);}
  private ScrollView wrapScroll(View view){ScrollView scroll=new ScrollView(this);scroll.addView(view);return scroll;}

  private LinearLayout metric(String label,String value,String detail){
    LinearLayout card=column();
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(dp(13),dp(11),dp(13),dp(10));
    card.setBackground(shape(Color.WHITE,12,1,line));
    card.addView(text(label,12,muted,true));
    TextView amount=text(value,18,ink,true);
    amount.setSingleLine(true);
    amount.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(amount,top(3));
    TextView supporting=text(detail,10,muted,false);
    supporting.setSingleLine(true);
    supporting.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(supporting,top(2));
    return card;
  }

  private void refreshSummary(){
    new Thread(()->{
      LocalDate day=PosDatabase.currentBusinessDay();
      long from=PosDatabase.businessDayStart(day);
      long to=PosDatabase.businessDayEnd(day);
      PosDatabase.ReportSummary report=database.report(from,to);
      int open=database.activeOrderCount();
      runOnUiThread(()->{
        if(openOrdersValue!=null)openOrdersValue.setText(String.valueOf(open));
        if(openOrdersDetail!=null)openOrdersDetail.setText(open==0?"No active orders":open+" waiting");
        if(todayValue!=null)todayValue.setText("Rs. "+String.format(Locale.US,"%.2f",report.sales));
        if(todayDetail!=null)todayDetail.setText(report.bills+" bills / "+formatQty(report.totalQuantity)+" items");
        refreshTables();
      });
    }).start();
  }

  private int tableColumns(){return getResources().getConfiguration().screenWidthDp>=600?5:3;}
  private GridLayout.LayoutParams cellParams(){GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(100);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(4),dp(4),dp(4),dp(4));return p;}
  private LinearLayout.LayoutParams centerTop(int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);return p;}
  private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
  private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);return view;}
  private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);return view;}
  private Button button(String value,int fill,int color){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(color);view.setTypeface(Typeface.DEFAULT_BOLD);view.setMinWidth(0);view.setMinimumWidth(0);view.setPadding(dp(14),0,dp(14),0);view.setBackground(shape(fill,10,1,line));return view;}
  private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}
  private LinearLayout.LayoutParams weightHeight(int height){return new LinearLayout.LayoutParams(0,dp(height),1);}
  private View spacer(int size){View view=new View(this);view.setLayoutParams(new LinearLayout.LayoutParams(dp(size),1));return view;}
  private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}
  private String formatQty(double value){return Math.rint(value)==value?String.valueOf((long)value):String.format(Locale.US,"%.2f",value);}
  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing()&&database!=null)refreshSummary();}};
  @Override protected void onResume(){super.onResume();PrintDispatcher.processAsync(this);if(database!=null)refreshSummary();CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}
}
