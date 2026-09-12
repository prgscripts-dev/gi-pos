package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SaleSuccessActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),page=Color.rgb(244,247,250);
  private String orderId;private PosDatabase db;

  private boolean viewOnly=false;
  @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!requireFeature("billing"))return;db=PosDatabase.get(this);orderId=getIntent().getStringExtra("orderId");viewOnly=getIntent().getBooleanExtra("viewOnly",false);getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){goBack();}});build();}
  // When opened just to view a bill (e.g. from Settings > Orders) return to the
  // caller; otherwise go to the dashboard as the post-sale home.
  private void goBack(){if(viewOnly)finish();else openHome();}

  private void build(){
    PosDatabase.OrderInfo order=db.orderInfo(orderId);if(order==null){openHome();return;}List<PosDatabase.OrderLine> lines=db.orderLines(orderId);PosDatabase.BusinessSettings business=db.businessSettings();
    LinearLayout root=column();root.setBackgroundColor(page);
    LinearLayout header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(16),dp(13),dp(16),dp(10));header.addView(AppBackButton.create(this,v->goBack()),new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout title=column();title.setPadding(dp(11),0,0,0);title.addView(text("Bill #"+order.billNumber,24,ink,true));title.addView(text("cancelled".equals(order.status)?"Bill cancelled":order.due>0?"Saved with due":"Payment complete",13,"cancelled".equals(order.status)?red:order.due>0?red:teal,true),top(3));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);

    ScrollView scroll=new ScrollView(this);LinearLayout content=column();content.setPadding(dp(16),0,dp(16),dp(24));
    LinearLayout receipt=column();receipt.setPadding(dp(15),dp(18),dp(15),dp(18));receipt.setBackground(shape(Color.WHITE,11,1,line));
    TextView businessName=text(business.name,21,ink,true);businessName.setGravity(Gravity.CENTER);receipt.addView(businessName);if(!business.address.isEmpty()){TextView address=text(business.address,12,muted,false);address.setGravity(Gravity.CENTER);receipt.addView(address,top(4));}if(!business.phone.isEmpty()){TextView phone=text("Phone: "+business.phone,12,muted,false);phone.setGravity(Gravity.CENTER);receipt.addView(phone,top(3));}if(!business.taxId.isEmpty()){TextView taxId=text("Tax ID: "+business.taxId,12,muted,false);taxId.setGravity(Gravity.CENTER);receipt.addView(taxId,top(3));}
    receipt.addView(rule(),top(12));
    LinearLayout numbers=row();numbers.addView(infoCell("Bill #",String.valueOf(order.billNumber),Gravity.START),new LinearLayout.LayoutParams(0,-2,1));numbers.addView(infoCell("KOT #",String.valueOf(order.kotNumber),Gravity.END),new LinearLayout.LayoutParams(0,-2,1));receipt.addView(numbers,top(10));
    String orderLabel;
    if("delivery".equals(order.orderType))orderLabel="Delivery";
    else if("takeaway".equals(order.orderType))orderLabel="Takeaway";
    else orderLabel=order.tableName.isEmpty()?"Direct sale":"Dining / "+order.tableName;
    LinearLayout service=row();service.addView(infoCell("Order",orderLabel,Gravity.START),new LinearLayout.LayoutParams(0,-2,1));service.addView(infoCell("Date",receiptDate(order.receiptSnapshot),Gravity.END),new LinearLayout.LayoutParams(0,-2,1));receipt.addView(service,top(10));if(!order.customerName.isEmpty())receipt.addView(pair("Customer",order.customerName),top(10));
    if("delivery".equals(order.orderType)){
      if(!order.deliveryName.isEmpty())receipt.addView(pair("Deliver to",order.deliveryName),top(10));
      if(!order.deliveryPhone.isEmpty())receipt.addView(pair("Phone",order.deliveryPhone),top(8));
      if(!order.deliveryAddress.isEmpty())receipt.addView(pair("Address",order.deliveryAddress),top(8));
    }
    if(!order.orderNote.isEmpty())receipt.addView(pair("Note",order.orderNote),top(8));
    receipt.addView(rule(),top(12));receipt.addView(billColumns("Item","Qty","Price","Amount"),top(9));receipt.addView(rule(),top(8));
    double totalQuantity=0;for(PosDatabase.OrderLine item:lines){totalQuantity+=item.quantity;receipt.addView(billLine(item),top(9));}
    receipt.addView(rule(),top(11));receipt.addView(pair("Total Qty",quantity(totalQuantity)),top(9));receipt.addView(pair("Sub Total","Rs. "+money(order.subtotal)),top(8));if(order.tax>0)receipt.addView(pair("Tax","Rs. "+money(order.tax)),top(8));if(order.discount>0)receipt.addView(pair("Discount","- Rs. "+money(order.discount)),top(8));receipt.addView(rule(),top(11));LinearLayout grand=pair("GRAND TOTAL","Rs. "+money(order.total));((TextView)grand.getChildAt(0)).setTextSize(18);((TextView)grand.getChildAt(1)).setTextSize(20);((TextView)grand.getChildAt(1)).setTextColor(red);receipt.addView(grand,top(10));if(order.due>0)receipt.addView(pair("Due","Rs. "+money(order.due)),top(9));if(order.balance>0)receipt.addView(pair("Balance","Rs. "+money(order.balance)),top(9));if(!business.footer.isEmpty()){receipt.addView(rule(),top(12));TextView footer=text(business.footer,13,muted,true);footer.setGravity(Gravity.CENTER);receipt.addView(footer,top(10));}
    content.addView(receipt);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

    LinearLayout actions=row();actions.setPadding(dp(16),dp(10),dp(16),dp(14));actions.setBackground(shape(Color.WHITE,0,1,line));
    Button home=button(viewOnly?"Back":"Home",Color.rgb(241,245,249),ink);home.setOnClickListener(v->goBack());actions.addView(home,new LinearLayout.LayoutParams(0,dp(52),1));
    // Reprint Bill: always reprints the current bill directly, no popup.
    Button reprint=button("Reprint Bill",red,Color.WHITE);reprint.setOnClickListener(v->reprintOrder(orderId,"this bill"));LinearLayout.LayoutParams reprintParams=new LinearLayout.LayoutParams(0,dp(52),1);reprintParams.leftMargin=dp(10);actions.addView(reprint,reprintParams);root.addView(actions);setContentView(root);
  }

  // Reprint menu: reprint the bill shown here, or choose a previous bill to reprint.
  private void showReprintOptions(){
    new AlertDialog.Builder(this)
      .setTitle("Reprint Bill")
      .setItems(new String[]{"Reprint this bill","Reprint a previous bill…"},(d,which)->{
        if(which==0)reprintOrder(orderId,"this bill");
        else showPreviousBills();
      })
      .setNegativeButton("Cancel",null)
      .show();
  }
  // Queue a fresh receipt print for the given finalized order (repeatable).
  private void reprintOrder(String targetOrderId,String label){
    boolean ok=db.reprintReceipt(targetOrderId);
    if(ok)PrintDispatcher.processAsync(this);
    Toast.makeText(this,ok?("Reprint queued for "+label):"This bill has no printable receipt yet",ok?Toast.LENGTH_SHORT:Toast.LENGTH_LONG).show();
  }
  // List recent finalized bills (last 30 days) so any of them can be reprinted.
  private void showPreviousBills(){
    long now=System.currentTimeMillis();
    List<PosDatabase.OrderListItem> bills=db.orderHistory(now-30L*24*60*60*1000,now+24L*60*60*1000);
    // Keep only bills that actually have a bill number (finalized), current one first is fine.
    java.util.ArrayList<PosDatabase.OrderListItem> printable=new java.util.ArrayList<>();
    for(PosDatabase.OrderListItem b:bills)if(b.billNumber!=null)printable.add(b);
    if(printable.isEmpty()){Toast.makeText(this,"No previous bills to reprint",Toast.LENGTH_SHORT).show();return;}
    String[] labels=new String[printable.size()];
    for(int i=0;i<printable.size();i++){
      PosDatabase.OrderListItem b=printable.get(i);
      String svc=PosDatabase.orderTypeLabel(b.orderType);
      String when=new SimpleDateFormat("dd/MM hh:mm a",Locale.US).format(new Date(b.updatedAt));
      String status="cancelled".equals(b.status)?"  (cancelled)":"";
      labels[i]="Bill #"+b.billNumber+"  •  Rs. "+money(b.total)+"  •  "+svc+status+"\n"+when;
    }
    new AlertDialog.Builder(this)
      .setTitle("Reprint a previous bill")
      .setItems(labels,(d,which)->{PosDatabase.OrderListItem b=printable.get(which);reprintOrder(b.id,"Bill #"+b.billNumber);})
      .setNegativeButton("Cancel",null)
      .show();
  }

  private LinearLayout billLine(PosDatabase.OrderLine item){LinearLayout line=row();line.setGravity(Gravity.TOP);String name=item.itemName+(item.variantName.isEmpty()?"":" / "+item.variantName);TextView itemName=text(name,13,ink,false);itemName.setMaxLines(3);line.addView(itemName,new LinearLayout.LayoutParams(0,-2,1));line.addView(cell(quantity(item.quantity),dp(38),Gravity.END));line.addView(cell(money(item.unitPrice),dp(65),Gravity.END));line.addView(cell(money(item.lineTotal),dp(72),Gravity.END));return line;}
  private LinearLayout billColumns(String item,String qty,String price,String amount){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.addView(text(item,12,muted,true),new LinearLayout.LayoutParams(0,-2,1));line.addView(cell(qty,dp(38),Gravity.END));line.addView(cell(price,dp(65),Gravity.END));line.addView(cell(amount,dp(72),Gravity.END));return line;}
  private TextView cell(String value,int width,int gravity){TextView text=text(value,12,ink,true);text.setGravity(gravity);text.setSingleLine(true);text.setPadding(dp(3),0,0,0);text.setLayoutParams(new LinearLayout.LayoutParams(width,-2));return text;}
  private LinearLayout infoCell(String label,String value,int gravity){LinearLayout cell=column();TextView heading=text(label,11,muted,true);heading.setGravity(gravity);cell.addView(heading);TextView detail=text(value,14,ink,true);detail.setGravity(gravity);detail.setMaxLines(2);cell.addView(detail,top(4));return cell;}
  private LinearLayout pair(String label,String value){LinearLayout row=row();row.setGravity(Gravity.CENTER_VERTICAL);row.addView(text(label,13,muted,false),new LinearLayout.LayoutParams(0,-2,1));TextView amount=text(value,14,ink,true);amount.setGravity(Gravity.END);row.addView(amount);return row;}
  private View rule(){View value=new View(this);value.setBackgroundColor(Color.rgb(180,191,203));value.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return value;}
  private String receiptDate(String snapshot){long value=System.currentTimeMillis();try{value=new JSONObject(snapshot).optLong("createdAt",value);}catch(Exception ignored){}return new SimpleDateFormat("dd/MM/yy\nhh:mm a",Locale.US).format(new Date(value));}
  private void openHome(){Intent intent=new Intent(this,DashboardActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);finish();}
  private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}private LinearLayout row(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);return value;}private TextView text(String value,int size,int color,boolean bold){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);if(bold)text.setTypeface(Typeface.DEFAULT_BOLD);return text;}private Button button(String value,int fill,int color){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setTextColor(color);button.setTypeface(Typeface.DEFAULT_BOLD);button.setBackground(shape(fill,10,1,line));return button;}private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}private String money(double value){return String.format(Locale.US,"%.2f",value);}private String quantity(double value){return Math.rint(value)==value?String.valueOf((long)value):String.format(Locale.US,"%.2f",value);}private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
}
