package com.GIHOSTINGS.giposapp;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReportActivity extends InsetActivity {
  private static final int EXPORT_CSV=401,EXPORT_PDF=402;
  // Report types the user can switch between.
  private static final String TYPE_SUMMARY="summary",TYPE_PAYMENTS="payments",TYPE_ITEMS="items",TYPE_ORDERS="orders";
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;
  private LocalDate selectedFrom=PosDatabase.currentBusinessDay(),selectedTo=PosDatabase.currentBusinessDay();
  private PosDatabase.ReportSummary report;
  private String reportType=TYPE_SUMMARY;
  // Active payment filter. filterMethod: all|cash|upi|card|due|gateway. filterGatewayId set when a specific gateway chip is chosen.
  private String filterMethod="all",filterGatewayId="";
  private List<PosDatabase.PaymentGateway> gateways=new ArrayList<>();

  @Override public void onCreate(Bundle state){super.onCreate(state);if(!requireFeature("reports"))return;db=PosDatabase.get(this);load();}
  @Override protected void onResume(){super.onResume();if(db!=null)load();}

  private void load(){
    gateways=db.paymentGateways(false);
    long from=PosDatabase.businessDayStart(selectedFrom);
    long to=PosDatabase.businessDayEnd(selectedTo);
    final String method=filterMethod,gatewayId=filterGatewayId;
    new Thread(()->{PosDatabase.ReportSummary value=db.report(from,to,method,gatewayId);runOnUiThread(()->{report=value;render();});}).start();
  }

  private void render(){
    LinearLayout root=column();root.setPadding(dp(16),dp(16),dp(16),dp(26));root.setBackgroundColor(surface);

    LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout title=column();title.addView(text("Reports",25,ink,true));title.addView(text(periodLabel()+"  ·  "+report.filterLabel,13,muted,false));
    head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button today=button("Today",Color.WHITE,ink);today.setOnClickListener(v->{selectedFrom=PosDatabase.currentBusinessDay();selectedTo=PosDatabase.currentBusinessDay();load();});head.addView(today,new LinearLayout.LayoutParams(dp(78),dp(42)));
    root.addView(head);

    // Date range
    LinearLayout dates=row();
    Button from=button("From\n"+shortDate(selectedFrom),Color.WHITE,ink);from.setOnClickListener(v->pickDate(true));dates.addView(from,new LinearLayout.LayoutParams(0,dp(58),1));
    dates.addView(gap());
    Button to=button("To\n"+shortDate(selectedTo),Color.WHITE,ink);to.setOnClickListener(v->pickDate(false));dates.addView(to,new LinearLayout.LayoutParams(0,dp(58),1));
    root.addView(dates,top(14));

    // Quick date presets
    // Quick-range preset chips (Today / 7 days / 30 days / This month) removed per
    // request; use the From/To date pickers (and the Today button) to set the range.

    // Report-type tabs
    HorizontalScrollView typeScroll=new HorizontalScrollView(this);typeScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout tabs=row();
    tabs.addView(typeTab("Summary",TYPE_SUMMARY),chip());
    tabs.addView(typeTab("Payments",TYPE_PAYMENTS),chip());
    tabs.addView(typeTab("Items",TYPE_ITEMS),chip());
    tabs.addView(typeTab("Orders",TYPE_ORDERS),chip());
    typeScroll.addView(tabs);
    root.addView(typeScroll,top(12));

    // Payment filter chips
    HorizontalScrollView filterScroll=new HorizontalScrollView(this);filterScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout filters=row();
    filters.addView(filterChip("All","all",""),chip());
    filters.addView(filterChip("Cash","cash",""),chip());
    filters.addView(filterChip("UPI","upi",""),chip());
    filters.addView(filterChip("Card","card",""),chip());
    filters.addView(filterChip("Due","due",""),chip());
    for(PosDatabase.PaymentGateway gateway:gateways)filters.addView(filterChip(gateway.name,"gateway",gateway.id),chip());
    filterScroll.addView(filters);
    root.addView(filterScroll,top(8));

    // Export toolbar
    LinearLayout toolbar=row();
    Button print=button("Print",Color.rgb(229,247,249),teal);print.setOnClickListener(v->print());toolbar.addView(print,new LinearLayout.LayoutParams(0,dp(46),1));
    toolbar.addView(gap());
    Button pdf=button("PDF",red,Color.WHITE);pdf.setOnClickListener(v->exportPdf());toolbar.addView(pdf,new LinearLayout.LayoutParams(0,dp(46),1));
    toolbar.addView(gap());
    Button csv=button("CSV",Color.WHITE,ink);csv.setOnClickListener(v->exportCsv());toolbar.addView(csv,new LinearLayout.LayoutParams(0,dp(46),1));
    root.addView(toolbar,top(12));

    // Hero total (always shown)
    LinearLayout hero=column();hero.setPadding(dp(18),dp(17),dp(18),dp(17));hero.setBackground(shape(red,14,0,red));
    hero.addView(text("all".equals(filterMethod)?"Total sales":report.filterLabel,13,Color.rgb(255,220,227),true));
    hero.addView(text("Rs. "+money(report.sales),30,Color.WHITE,true),top(3));
    hero.addView(text(report.bills+" bill(s)  ·  "+qty(report.totalQuantity)+" item(s)",13,Color.WHITE,false),top(5));
    root.addView(hero,top(14));

    // Body varies by report type
    if(TYPE_SUMMARY.equals(reportType))renderSummary(root);
    else if(TYPE_PAYMENTS.equals(reportType))renderPayments(root);
    else if(TYPE_ITEMS.equals(reportType))renderItems(root);
    else renderOrders(root);

    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);
    setContentView(MobileBottomNavigation.wrap(this,scroll,MobileBottomNavigation.Destination.REPORT));
  }

  private void renderSummary(LinearLayout root){
    LinearLayout summary=panel("Sales details");
    summary.addView(pair("Tax collected",report.tax));
    summary.addView(pair("Discount",report.discount),top(9));
    summary.addView(pair("Average bill",report.bills==0?0:report.sales/report.bills),top(9));
    if(report.due>0)summary.addView(pair("Outstanding due",report.due),top(9));
    root.addView(summary,top(12));

    LinearLayout payments=panel("Payment mix");
    if(report.methodTotals.isEmpty())payments.addView(text("No payments recorded for this period.",14,muted,false),top(12));
    else for(int i=0;i<report.methodTotals.size();i++){PosDatabase.PaymentTotal item=report.methodTotals.get(i);payments.addView(paymentRow(item),top(i==0?12:9));}
    root.addView(payments,top(12));
  }

  private void renderPayments(LinearLayout root){
    LinearLayout methods=panel("By payment method");
    if(report.methodTotals.isEmpty())methods.addView(text("No payments recorded for this period.",14,muted,false),top(12));
    else for(int i=0;i<report.methodTotals.size();i++){PosDatabase.PaymentTotal item=report.methodTotals.get(i);methods.addView(paymentRow(item),top(i==0?12:9));}
    root.addView(methods,top(12));

    LinearLayout gatewayPanel=panel("By payment gateway");
    if(report.gatewayTotals.isEmpty())gatewayPanel.addView(text("No custom gateway payments in this period. Create gateways in Settings › Payment gateways.",13,muted,false),top(12));
    else for(int i=0;i<report.gatewayTotals.size();i++){PosDatabase.PaymentTotal item=report.gatewayTotals.get(i);gatewayPanel.addView(paymentRow(item),top(i==0?12:9));}
    root.addView(gatewayPanel,top(12));
  }

  private void renderItems(LinearLayout root){
    LinearLayout sold=panel("Items sold");
    if(report.items.isEmpty())sold.addView(text("No completed sales for this selection.",14,muted,false),top(12));
    else{
      LinearLayout labels=row();labels.addView(text("Item",12,muted,true),new LinearLayout.LayoutParams(0,-2,1));labels.addView(right("Qty",12,muted,true,dp(58)));labels.addView(right("Amount",12,muted,true,dp(92)));sold.addView(labels,top(12));
      for(PosDatabase.ReportItem item:report.items){LinearLayout itemRow=row();itemRow.setGravity(Gravity.CENTER_VERTICAL);TextView name=text(item.name,14,ink,false);name.setMaxLines(2);itemRow.addView(name,new LinearLayout.LayoutParams(0,-2,1));itemRow.addView(right(qty(item.quantity),14,ink,true,dp(58)));itemRow.addView(right(money(item.amount),14,ink,true,dp(92)));sold.addView(itemRow,top(11));}
    }
    root.addView(sold,top(12));
  }

  private void renderOrders(LinearLayout root){
    long from=PosDatabase.businessDayStart(selectedFrom);
    long to=PosDatabase.businessDayEnd(selectedTo);
    List<PosDatabase.OrderListItem> orders=db.completedOrders(from,to);
    LinearLayout panel=panel("Bills ("+orders.size()+")");
    if(orders.isEmpty())panel.addView(text("No bills for this date range.",14,muted,false),top(12));
    else for(PosDatabase.OrderListItem order:orders){
      LinearLayout orderRow=row();orderRow.setGravity(Gravity.CENTER_VERTICAL);orderRow.setPadding(dp(12),dp(10),dp(12),dp(10));orderRow.setBackground(shape(Color.rgb(248,250,252),9,1,line));
      LinearLayout copy=column();
      String label=(order.billNumber==null?"No bill":"Bill #"+order.billNumber)+"  ·  "+order.tableName;
      copy.addView(text(label,14,ink,true));
      copy.addView(text(order.itemCount+" item(s)  ·  "+("due".equals(order.status)?"Due":"Paid"),12,muted,false),top(3));
      orderRow.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
      orderRow.addView(text("Rs. "+money(order.total),15,ink,true));
      panel.addView(orderRow,top(9));
    }
    root.addView(panel,top(12));
  }

  private View paymentRow(PosDatabase.PaymentTotal item){
    LinearLayout rowView=row();rowView.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout copy=column();copy.addView(text(item.label,14,ink,false));if(item.count>0)copy.addView(text(item.count+" payment(s)",11,muted,false),top(2));
    rowView.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
    rowView.addView(text("Rs. "+money(item.amount),15,ink,true));
    return rowView;
  }

  // ---- tabs / chips ----
  private Button typeTab(String label,String type){boolean active=reportType.equals(type);Button b=button(label,active?ink:Color.WHITE,active?Color.WHITE:ink);b.setOnClickListener(v->{reportType=type;render();});return b;}
  private Button filterChip(String label,String method,String gatewayId){boolean active="gateway".equals(method)&&!gatewayId.isEmpty()?(filterGatewayId.equals(gatewayId)):(filterMethod.equals(method)&&filterGatewayId.isEmpty());Button b=button(label,active?teal:Color.WHITE,active?Color.WHITE:ink);b.setOnClickListener(v->{filterMethod=method;filterGatewayId="gateway".equals(method)?gatewayId:"";load();});return b;}
  private Button presetChip(String label,int days){Button b=button(label,Color.WHITE,ink);b.setOnClickListener(v->{applyPreset(days);load();});return b;}
  private void applyPreset(int days){LocalDate now=PosDatabase.currentBusinessDay();if(days<0){selectedFrom=now.withDayOfMonth(1);selectedTo=now;}else{selectedTo=now;selectedFrom=now.minusDays(days);}}

  private void pickDate(boolean fromDate){LocalDate current=fromDate?selectedFrom:selectedTo;DatePickerDialog dialog=new DatePickerDialog(this,(picker,year,month,day)->{LocalDate value=LocalDate.of(year,month+1,day);if(fromDate){selectedFrom=value;if(selectedTo.isBefore(value))selectedTo=value;}else if(value.isBefore(selectedFrom)){Toast.makeText(this,"To date cannot be before From date",Toast.LENGTH_SHORT).show();return;}else selectedTo=value;load();},current.getYear(),current.getMonthValue()-1,current.getDayOfMonth());dialog.getDatePicker().setMaxDate(System.currentTimeMillis());dialog.show();}
  private void print(){if(report==null)return;boolean queued=db.queueReportPrint(report);PrintDispatcher.processAsync(this);Toast.makeText(this,queued?"Report queued for printing":"Report is already queued",Toast.LENGTH_SHORT).show();}
  private void exportPdf(){if(report==null)return;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/pdf");intent.putExtra(Intent.EXTRA_TITLE,"gi-pos-report-"+selectedFrom+"-to-"+selectedTo+".pdf");startActivityForResult(intent,EXPORT_PDF);}
  private void exportCsv(){if(report==null)return;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/csv");intent.putExtra(Intent.EXTRA_TITLE,"gi-pos-report-"+selectedFrom+"-to-"+selectedTo+".csv");startActivityForResult(intent,EXPORT_CSV);}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if((request!=EXPORT_CSV&&request!=EXPORT_PDF)||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();new Thread(()->{try(OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new IllegalStateException("Cannot open selected file");if(request==EXPORT_PDF)new PdfReportWriter().write(output);else output.write(csv().getBytes(StandardCharsets.UTF_8));runOnUiThread(()->Toast.makeText(this,request==EXPORT_PDF?"PDF report created":"CSV report created",Toast.LENGTH_SHORT).show());}catch(Exception error){runOnUiThread(()->Toast.makeText(this,"Export failed: "+error.getMessage(),Toast.LENGTH_LONG).show());}}).start();}
  private String csv(){
    StringBuilder out=new StringBuilder("GI POS Sales Report,").append(selectedFrom).append(" to ").append(selectedTo).append("\nFilter,").append(escape(report.filterLabel)).append("\n\nSummary,Amount\nTotal sales,").append(money(report.sales)).append("\nBills,").append(report.bills).append("\nTotal quantity,").append(qty(report.totalQuantity)).append("\nTax,").append(money(report.tax)).append("\nDiscount,").append(money(report.discount)).append("\nDue,").append(money(report.due)).append("\n\nPayment method,Amount,Count\n");
    for(PosDatabase.PaymentTotal item:report.methodTotals)out.append(escape(item.label)).append(',').append(money(item.amount)).append(',').append(item.count).append('\n');
    out.append("\nPayment gateway,Amount,Count\n");
    if(report.gatewayTotals.isEmpty())out.append("(none),0,0\n");
    else for(PosDatabase.PaymentTotal item:report.gatewayTotals)out.append(escape(item.label)).append(',').append(money(item.amount)).append(',').append(item.count).append('\n');
    out.append("\nItem,Quantity,Amount\n");
    for(PosDatabase.ReportItem item:report.items)out.append(escape(item.name)).append(',').append(qty(item.quantity)).append(',').append(money(item.amount)).append('\n');
    return out.toString();
  }
  private String escape(String value){String clean=value==null?"":value;return '"'+clean.replace("\"","\"\"")+'"';}
  private String periodLabel(){return selectedFrom.equals(selectedTo)?selectedFrom.format(DateTimeFormatter.ofPattern("dd MMM yyyy")):selectedFrom.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))+" - "+selectedTo.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));}
  private String shortDate(LocalDate value){return value.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));}

  private final class PdfReportWriter {
    private static final int WIDTH=595,HEIGHT=842,MARGIN=38;
    private final PdfDocument document=new PdfDocument();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private PdfDocument.Page page;private Canvas canvas;private int y,pageNumber;private boolean itemMode;

    void write(OutputStream output)throws Exception{
      startPage();
      section("Summary");
      pair("Total sales","Rs. "+money(report.sales));
      pair("Filter",report.filterLabel);
      pair("Paid bills",String.valueOf(report.bills));
      pair("Total quantity",qty(report.totalQuantity));
      if(report.tax>0)pair("Tax collected","Rs. "+money(report.tax));
      if(report.discount>0)pair("Discount","Rs. "+money(report.discount));
      if(report.due>0)pair("Due","Rs. "+money(report.due));
      space(8);
      section("Payment methods");
      if(report.methodTotals.isEmpty())pair("No payments","-");
      else for(PosDatabase.PaymentTotal item:report.methodTotals)pair(item.label,"Rs. "+money(item.amount));
      space(6);
      section("Payment gateways");
      if(report.gatewayTotals.isEmpty())pair("No gateway payments","-");
      else for(PosDatabase.PaymentTotal item:report.gatewayTotals)pair(item.label,"Rs. "+money(item.amount));
      space(10);
      itemHeader();
      for(PosDatabase.ReportItem item:report.items)item(item);
      finishPage();document.writeTo(output);document.close();
    }
    private void startPage(){pageNumber++;page=document.startPage(new PdfDocument.PageInfo.Builder(WIDTH,HEIGHT,pageNumber).create());canvas=page.getCanvas();y=44;paint.setColor(ink);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(20);canvas.drawText(db.businessSettings().name,MARGIN,y,paint);y+=23;paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(11);paint.setColor(muted);canvas.drawText("SALES REPORT  |  "+periodLabel(),MARGIN,y,paint);paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText("Page "+pageNumber,WIDTH-MARGIN,y,paint);paint.setTextAlign(Paint.Align.LEFT);y+=18;rule();}
    private void finishPage(){if(page!=null){document.finishPage(page);page=null;}}
    private void ensure(int required){if(y+required<=HEIGHT-45)return;finishPage();startPage();if(itemMode)drawItemHeader();}
    private void section(String value){ensure(34);y+=8;paint.setColor(ink);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(14);canvas.drawText(value,MARGIN,y,paint);y+=16;}
    private void pair(String label,String value){ensure(22);paint.setTextSize(11);paint.setTypeface(Typeface.DEFAULT);paint.setColor(muted);canvas.drawText(fit(label,320),MARGIN,y,paint);paint.setTextAlign(Paint.Align.RIGHT);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setColor(ink);canvas.drawText(value,WIDTH-MARGIN,y,paint);paint.setTextAlign(Paint.Align.LEFT);y+=20;}
    private void itemHeader(){ensure(30);itemMode=true;drawItemHeader();}
    private void drawItemHeader(){paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(12);paint.setColor(ink);canvas.drawText("Item",MARGIN,y,paint);paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText("Qty",445,y,paint);canvas.drawText("Amount",WIDTH-MARGIN,y,paint);paint.setTextAlign(Paint.Align.LEFT);y+=9;rule();y+=13;}
    private void item(PosDatabase.ReportItem item){ensure(23);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(11);paint.setColor(ink);canvas.drawText(fit(item.name,330),MARGIN,y,paint);paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText(qty(item.quantity),445,y,paint);canvas.drawText("Rs. "+money(item.amount),WIDTH-MARGIN,y,paint);paint.setTextAlign(Paint.Align.LEFT);y+=21;}
    private String fit(String value,float width){String clean=value==null?"":value;while(clean.length()>1&&paint.measureText(clean+"…")>width)clean=clean.substring(0,clean.length()-1);return clean.equals(value)?clean:clean+"…";}
    private void rule(){paint.setColor(line);paint.setStrokeWidth(1);canvas.drawLine(MARGIN,y,WIDTH-MARGIN,y,paint);y+=10;}
    private void space(int amount){y+=amount;}
  }

  private LinearLayout panel(String title){LinearLayout value=column();value.setPadding(dp(16),dp(15),dp(16),dp(16));value.setBackground(shape(Color.WHITE,13,1,line));value.addView(text(title,17,ink,true));return value;}
  private LinearLayout pair(String label,double amount){LinearLayout value=row();value.setGravity(Gravity.CENTER_VERTICAL);value.addView(text(label,14,muted,false),new LinearLayout.LayoutParams(0,-2,1));value.addView(text("Rs. "+money(amount),15,ink,true));return value;}
  private TextView right(String value,int size,int color,boolean bold,int width){TextView view=text(value,size,color,bold);view.setGravity(Gravity.RIGHT);view.setLayoutParams(new LinearLayout.LayoutParams(width,-2));return view;}
  private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}
  private LinearLayout row(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);return value;}
  private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);return view;}
  private Button button(String value,int fill,int color){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(color);view.setTypeface(Typeface.DEFAULT_BOLD);view.setBackground(shape(fill,9,1,line));view.setPadding(dp(14),0,dp(14),0);return view;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}
  private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}
  private LinearLayout.LayoutParams chip(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(40));p.rightMargin=dp(8);return p;}
  private View gap(){View value=new View(this);value.setLayoutParams(new LinearLayout.LayoutParams(dp(7),1));return value;}
  private String money(double value){return String.format(Locale.US,"%.2f",value);}
  private String qty(double value){return Math.rint(value)==value?String.valueOf((long)value):String.format(Locale.US,"%.2f",value);}
  private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
}
