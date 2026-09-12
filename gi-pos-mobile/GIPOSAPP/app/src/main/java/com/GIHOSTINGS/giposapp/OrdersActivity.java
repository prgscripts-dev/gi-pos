package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
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
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OrdersActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236);
  private final ArrayList<PosDatabase.OrderListItem> orders=new ArrayList<>();
  // Full loaded list; `orders` holds the currently displayed (search-filtered) subset.
  private final ArrayList<PosDatabase.OrderListItem> allOrders=new ArrayList<>();
  private PosDatabase db; private ListView list; private TextView summary,empty; private Button activeButton,completedButton; private boolean active=false;
  private LinearLayout typeBar; private String typeFilter="all"; private String searchText=""; // all|dine_in|delivery|takeaway

  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing())load();}};
  @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!requireFeature("billing"))return;db=PosDatabase.get(this);build();}
  @Override protected void onResume(){super.onResume();load();CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}

  private void build(){
    LinearLayout root=column();root.setPadding(dp(16),dp(14),dp(16),dp(14));root.setBackgroundColor(Color.rgb(244,247,250));
    LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout title=column();title.setPadding(dp(10),0,0,0);title.addView(text("Orders",24,ink,true));title.addView(text("Today's bills · long-press to manage",13,muted,false));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
    LinearLayout filters=row();activeButton=button("Unclosed",red,Color.WHITE);completedButton=button("Today's orders",Color.WHITE,ink);activeButton.setOnClickListener(v->{active=true;styleFilters();load();});completedButton.setOnClickListener(v->{active=false;styleFilters();load();});filters.addView(activeButton,new LinearLayout.LayoutParams(0,dp(46),1));filters.addView(gap());filters.addView(completedButton,new LinearLayout.LayoutParams(0,dp(46),1));root.addView(filters,top(16));
    // Order-type filter chips (All / Dining / Delivery / Takeaway) applied to whichever tab is active.
    HorizontalScrollView typeScroll=new HorizontalScrollView(this);typeScroll.setHorizontalScrollBarEnabled(false);typeBar=row();typeScroll.addView(typeBar);root.addView(typeScroll,top(10));refreshTypeChips();
    summary=text("Loading",13,muted,true);summary.setPadding(dp(13),dp(11),dp(13),dp(11));summary.setBackground(shape(Color.WHITE,10,1,line));root.addView(summary,top(10));
    // Search bar: filter the listed orders by bill number or KOT number.
    EditText search=FormControls.input(this,"Search by bill # or KOT #",InputType.TYPE_CLASS_NUMBER);
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){searchText=s==null?"":s.toString().trim();applySearch();}public void afterTextChanged(Editable e){}});
    root.addView(search,top(10));
    list=new ListView(this);list.setDivider(null);list.setDividerHeight(dp(8));list.setPadding(0,dp(10),0,0);list.setClipToPadding(false);list.setAdapter(new OrderAdapter());list.setOnItemClickListener((parent,view,position,id)->open(orders.get(position)));list.setOnItemLongClickListener((parent,view,position,id)->{manage(orders.get(position));return true;});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    empty=text("No unclosed orders",17,muted,true);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(40),dp(20),dp(40));list.setEmptyView(empty);root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);styleFilters();
  }

  private void load(){
    final boolean requestedActive=active;
    new Thread(()->{List<PosDatabase.OrderListItem> raw;if(requestedActive)raw=db.activeOrders();else{LocalDate day=PosDatabase.currentBusinessDay();long from=PosDatabase.businessDayStart(day);long to=PosDatabase.businessDayEnd(day);raw=db.completedOrders(from,to);}List<PosDatabase.OrderListItem> result=new ArrayList<>();double total=0;for(PosDatabase.OrderListItem item:raw){if(!matchesType(item))continue;result.add(item);total+=item.total;}final double amount=total;runOnUiThread(()->{if(requestedActive!=active)return;allOrders.clear();allOrders.addAll(result);summary.setText(result.size()+" order(s)  /  Rs. "+money(amount));String typeSuffix="all".equals(typeFilter)?"":" · "+(("dine_in".equals(typeFilter))?"Dining":"delivery".equals(typeFilter)?"Delivery":"Takeaway");empty.setText((requestedActive?"No unclosed orders":"No orders today")+typeSuffix);applySearch();});}).start();
  }

  // Filter the loaded orders by bill number / KOT number (digits) into the list.
  private void applySearch(){
    if(list==null)return;
    String q=searchText==null?"":searchText.replaceAll("[^0-9]","");
    orders.clear();
    for(PosDatabase.OrderListItem item:allOrders){
      if(q.isEmpty()){orders.add(item);continue;}
      boolean billMatch=item.billNumber!=null&&String.valueOf(item.billNumber).contains(q);
      boolean kotMatch=item.kotNumber!=null&&String.valueOf(item.kotNumber).contains(q);
      if(billMatch||kotMatch)orders.add(item);
    }
    ((BaseAdapter)list.getAdapter()).notifyDataSetChanged();
    if(!q.isEmpty()&&empty!=null)empty.setText("No bill or KOT matches \""+searchText+"\".");
  }

  private void open(PosDatabase.OrderListItem item){Intent intent;if("paid".equals(item.status)||"due".equals(item.status)){intent=new Intent(this,SaleSuccessActivity.class);intent.putExtra("orderId",item.id);intent.putExtra("viewOnly",true);}else{intent=new Intent(this,OrderActivity.class);intent.putExtra("orderId",item.id);intent.putExtra("tableName",item.tableName);}startActivity(intent);}

  // Long-press action sheet: view/resume the order or cancel it. Cancelling a
  // completed bill removes it from sales totals; cancelling an open order voids
  // it. The list + total refresh right after.
  private void manage(PosDatabase.OrderListItem item){
    boolean completed="paid".equals(item.status)||"due".equals(item.status);
    String number=item.billNumber!=null?"Bill #"+item.billNumber:item.kotNumber!=null?"KOT #"+item.kotNumber:"Order";
    String[] options=completed?new String[]{"View bill","Cancel order"}:new String[]{"Resume order","Cancel order"};
    new AlertDialog.Builder(this)
      .setTitle(item.tableName+" · "+number)
      .setItems(options,(d,which)->{
        if(which==0)open(item);
        else confirmCancel(item,completed);
      })
      .setNegativeButton("Close",null)
      .show();
  }

  private void confirmCancel(PosDatabase.OrderListItem item,boolean completed){
    EditText reason=FormControls.input(this,"Reason (optional)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    LinearLayout form=FormControls.dialogForm(this);
    String note=completed
      ?"Cancelling this bill removes it from today's total and sales reports."+("due".equals(item.status)?" Any customer due is reversed.":"")
      :"Cancelling this open order voids it and frees the table.";
    form.addView(text(note+" This cannot be undone.",13,muted,false));
    form.addView(FormControls.field(this,"Reason",reason),top(12));
    new AlertDialog.Builder(this)
      .setTitle("Cancel order?")
      .setView(form)
      .setNegativeButton("Keep",null)
      .setPositiveButton("Cancel order",(d,w)->doCancel(item,completed,reason.getText().toString()))
      .show();
  }

  private void doCancel(PosDatabase.OrderListItem item,boolean completed,String reason){
    new Thread(()->{
      try{
        if(completed)db.cancelOrder(item.id,reason);
        else db.cancelActiveOrder(item.id,reason);
        CloudSyncManager.syncAsync(this);
        runOnUiThread(()->{Toast.makeText(this,"Order cancelled",Toast.LENGTH_SHORT).show();load();});
      }catch(Exception error){runOnUiThread(()->Toast.makeText(this,error.getMessage()==null?"Could not cancel":error.getMessage(),Toast.LENGTH_LONG).show());}
    }).start();
  }
  private void styleFilters(){activeButton.setBackground(shape(active?red:Color.WHITE,9,1,active?red:line));activeButton.setTextColor(active?Color.WHITE:ink);completedButton.setBackground(shape(active?Color.WHITE:red,9,1,active?line:red));completedButton.setTextColor(active?ink:Color.WHITE);}
  private void refreshTypeChips(){if(typeBar==null)return;typeBar.removeAllViews();String[][] chips={{"all","All"},{"dine_in","Dining"},{"delivery","Delivery"},{"takeaway","Takeaway"}};for(String[] chip:chips){typeBar.addView(typeChip(chip[1],chip[0]));}}
  private Button typeChip(String label,String value){boolean selected=typeFilter.equals(value);Button b=button(label,selected?teal:Color.WHITE,selected?Color.WHITE:ink);b.setOnClickListener(v->{typeFilter=value;refreshTypeChips();load();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(40));p.rightMargin=dp(8);b.setLayoutParams(p);return b;}
  private boolean matchesType(PosDatabase.OrderListItem item){if("all".equals(typeFilter))return true;String type=item.orderType==null||item.orderType.isEmpty()?"dine_in":item.orderType;return typeFilter.equals(type);}

  private final class OrderAdapter extends BaseAdapter {
    public int getCount(){return orders.size();}public Object getItem(int p){return orders.get(p);}public long getItemId(int p){return p;}
    public View getView(int position,View old,ViewGroup parent){PosDatabase.OrderListItem item=orders.get(position);LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(14),dp(13),dp(12),dp(13));int fill="held".equals(item.status)?Color.rgb(255,248,220):"unclosed".equals(item.status)||"kot".equals(item.status)?Color.rgb(235,248,250):Color.WHITE;card.setBackground(shape(fill,11,1,line));LinearLayout main=column();String number=item.billNumber!=null?"Bill #"+item.billNumber:item.kotNumber!=null?"KOT #"+item.kotNumber:"New order";main.addView(text(item.tableName+"  /  "+number,16,ink,true));main.addView(text(item.itemCount+" item(s)  /  "+date(item.updatedAt),12,muted,false),top(4));card.addView(main,new LinearLayout.LayoutParams(0,-2,1));LinearLayout right=column();right.setGravity(Gravity.END);right.addView(text(item.status.toUpperCase(Locale.US),11,"held".equals(item.status)?Color.rgb(154,96,0):teal,true));right.addView(text("Rs. "+money(item.total),16,ink,true),top(5));card.addView(right);return card;}
  }

  private String date(long value){return DateTimeFormatter.ofPattern("hh:mm a",Locale.US).format(java.time.Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()));}
  private String money(double value){return String.format(Locale.US,"%.2f",value);}private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}private TextView text(String s,int z,int c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(b)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(shape(fill,9,1,line));return b;}private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}private View gap(){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(dp(8),1));return v;}private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
