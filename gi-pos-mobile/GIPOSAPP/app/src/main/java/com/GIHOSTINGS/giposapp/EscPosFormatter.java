package com.GIHOSTINGS.giposapp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class EscPosFormatter {
  private static final Charset ENCODING=Charset.forName("windows-1252");
  private EscPosFormatter(){}
  public static byte[] format(String type,String payload,int paperWidth){try{return "kot".equals(type)?kot(new JSONObject(payload),paperWidth):"report".equals(type)?report(new JSONObject(payload),paperWidth):receipt(new JSONObject(payload),paperWidth);}catch(Exception error){throw new IllegalArgumentException("Invalid print data",error);}}
  public static byte[] testPage(PosDatabase.PrinterProfile printer){Writer out=new Writer(printer.paperWidth);out.init();out.center();out.bold(true);out.size(1);out.text("GI POS MOBILE\n");out.size(0);out.bold(false);out.text("Printer test successful\n");out.text(printer.name+"\n");out.text(printer.connectionType.toUpperCase(Locale.US)+" / POS "+printer.paperWidth+"mm\n");out.rule();out.text("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n0123456789  Rs. 1234.50\n");out.feedAndCut();return out.bytes();}
  // Bill dispatcher: choose one of the professional templates by the payload's
  // "template" field (classic / compact / elegant). Any centered logo is
  // prepended separately by PrintDispatcher before this output.
  private static byte[] receipt(JSONObject data,int width)throws Exception{
    String template=data.optString("template","classic");
    if("compact".equals(template))return receiptCompact(data,width);
    if("elegant".equals(template))return receiptElegant(data,width);
    return receiptClassic(data,width);
  }

  // Shared: emit the item table body for a template. showNotes prints per-item
  // notes indented under each line.
  private static double emitItems(Writer out,JSONArray items)throws Exception{
    double totalQty=0;
    for(int i=0;i<items.length();i++){
      JSONObject item=items.getJSONObject(i);
      String name=item.optString("name");String variant=item.optString("variant");
      if(!variant.isEmpty())name+=" ("+variant+")";
      double qty=item.optDouble("quantity"),price=item.optDouble("unitPrice"),amount=item.optDouble("lineTotal");
      totalQty+=qty;
      out.billItem(name,quantity(qty),money(price),money(amount));
      String note=item.optString("note");
      if(!note.isEmpty())out.leftLine("  * "+note);
    }
    return totalQty;
  }

  // Shared: totals + tax split + grand total. big=true prints Grand Total large.
  private static void emitTotals(Writer out,JSONObject data,double totalQty,boolean bigGrand)throws Exception{
    emitTotals(out,data,totalQty,bigGrand,false);
  }
  // ruleAfterQty draws a separator line right under the Total Qty / Sub Total row.
  private static void emitTotals(Writer out,JSONObject data,double totalQty,boolean bigGrand,boolean ruleAfterQty)throws Exception{
    emitTotals(out,data,totalQty,bigGrand,ruleAfterQty,false);
  }
  // columnAligned=true prints the Total Qty and Sub Total values aligned to the
  // item table's Qty. and Price columns (Classic template).
  private static void emitTotals(Writer out,JSONObject data,double totalQty,boolean bigGrand,boolean ruleAfterQty,boolean columnAligned)throws Exception{
    String taxType="";double taxRate=0;
    JSONArray items=data.getJSONArray("items");
    for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);double r=item.optDouble("taxRate");if(r>taxRate)taxRate=r;String t=item.optString("taxType");if(!t.isEmpty()&&taxType.isEmpty())taxType=t;}
    JSONObject totals=data.getJSONObject("totals");
    double subtotal=totals.optDouble("subtotal"),tax=totals.optDouble("tax"),discount=totals.optDouble("discount"),grand=totals.optDouble("total");
    if(columnAligned){
      // "Total Qty" label with its value under the Qty. column; "Sub Total"
      // with its value under the Amount column, lining up with the table above.
      out.billItem("Total Qty",quantity(totalQty),"","");
      out.billItem("Sub Total","","",money(subtotal));
    }else{
      out.totalsRow("Total Qty: "+quantity(totalQty),"Sub Total","",money(subtotal));
    }
    if(ruleAfterQty)out.rule();
    if(discount>0)out.totalsRow(null,"Discount","","-"+money(discount));
    if(tax>0){
      if("igst".equals(taxType))out.totalsRow(null,"IGST@"+trimRate(taxRate),trimRate(taxRate)+"%",money(tax));
      else if("vat".equals(taxType))out.totalsRow(null,"VAT@"+trimRate(taxRate),trimRate(taxRate)+"%",money(tax));
      else if("none".equals(taxType))out.totalsRow(null,"Tax","",money(tax));
      else{double half=taxRate/2,each=round2(tax/2);out.totalsRow(null,"CGST@"+trimRate(half),trimRate(half)+"%",money(each));out.totalsRow(null,"SGST@"+trimRate(half),trimRate(half)+"%",money(tax-each));}
    }
    double roundOff=round2(grand-(subtotal+tax-discount));
    if(Math.abs(roundOff)>=0.005)out.totalsRow(null,"Round off","",(roundOff<0?"-":"")+money(Math.abs(roundOff)));
    if(bigGrand){out.bold(true);out.size(1);out.bigTwo("Grand Total","Rs."+money(grand));out.size(0);out.bold(false);}
    else{out.bold(true);out.two("GRAND TOTAL","Rs. "+money(grand));out.bold(false);}
    if(totals.optDouble("due")>0)out.totalsRow(null,"Due","",money(totals.optDouble("due")));
    if(totals.optDouble("balance")>0)out.totalsRow(null,"Balance","",money(totals.optDouble("balance")));
  }

  private static void emitOrderNote(Writer out,JSONObject data){
    String note=data.optString("orderNote");
    if(note.isEmpty())return;
    out.leftLine("Note: "+note);
  }

  private static void emitFooter(Writer out,JSONObject data){
    out.center();out.bold(true);
    String footer=data.optString("receiptFooter","THANK YOU VISIT AGAIN!!!");
    if(!footer.isEmpty())out.wrapped(footer);
    out.bold(false);out.left();
  }

  // ---- Template 1: Classic (matches the standard printed bill) -------------
  private static byte[] receiptClassic(JSONObject data,int width)throws Exception{
    Writer out=new Writer(width);out.init();
    // Symmetric 1-char inner margin so the classic bill has equal breathing room
    // on the left and right edges (rules, item table, totals all respect it).
    out.setMargin(1);
    // 58mm paper is narrow (32 cols); add a little vertical spacing between
    // sections so the company name, details, items, totals and footer don't run
    // together. 80mm (48 cols) already reads cleanly, so it gets no extra gaps.
    boolean narrow=out.columns<=32;
    // Professional centered header: small logo (prepended by PrintDispatcher)
    // sits above; leave a little breathing room, then the company name centered
    // and bold, with contact/GST lines centered beneath it.
    out.center();out.text("\n");
    out.bold(true);out.size(1);out.bigWrapped(data.optString("businessName","GI POS"));out.size(0);out.bold(false);
    String cAddr=data.optString("businessAddress");if(!cAddr.isEmpty())out.wrapped(cAddr);
    String cPhone=data.optString("businessPhone");if(!cPhone.isEmpty())out.wrapped(cPhone);
    String cTax=data.optString("businessTaxId");if(!cTax.isEmpty())out.wrapped("GST NO:"+cTax);
    out.left();
    if(narrow)out.text("\n");
    out.rule();
    // Customer block. For delivery/takeaway orders show the customer name, phone
    // and (delivery) address entered on the order; otherwise show the linked
    // customer name (if any).
    String ot=data.optString("orderType");boolean isDelivery="delivery".equals(ot);boolean typed=isDelivery||"takeaway".equals(ot);
    String dName=data.optString("deliveryName"),dPhone=data.optString("deliveryPhone"),dAddr=data.optString("deliveryAddress");
    String custName=typed&&!dName.isEmpty()?dName:data.optString("customer");
    out.leftLine("Name: "+custName);
    if(typed){
      if(!dPhone.isEmpty())out.leftLine("Phone: "+dPhone);
      if(isDelivery&&!dAddr.isEmpty())out.leftLine("Address: "+dAddr);
    }
    out.rule();
    String orderType=kotTypeLabel(data);String table=data.optString("table");
    out.two("Date: "+day2(data.optLong("createdAt")),orderType+(table.isEmpty()?"":": "+table));
    out.leftLine(time2(data.optLong("createdAt")));
    String operator=data.optString("operator");
    out.two("Cashier: "+(operator.isEmpty()?"CASHIER":operator),"Bill No.: "+data.optInt("billNumber"));
    int kot=data.optInt("kotNumber");if(kot>0)out.leftLine("KOT: "+kot);
    out.rule();
    out.billColumns("Item","Qty.","Price","Amount");
    out.rule();
    double totalQty=emitItems(out,data.getJSONArray("items"));
    out.rule();
    emitTotals(out,data,totalQty,true,true,true);
    out.rule();
    emitOrderNote(out,data);
    if(narrow)out.text("\n"); else out.rule();
    emitFooter(out,data);
    out.feedAndCut();return out.bytes();
  }

  // ---- Template 2: Compact (dense, minimal, thermal-friendly) --------------
  private static byte[] receiptCompact(JSONObject data,int width)throws Exception{
    Writer out=new Writer(width);out.init();
    out.center();out.bold(true);out.wrapped(data.optString("businessName","GI POS"));out.bold(false);
    String tax=data.optString("businessTaxId");if(!tax.isEmpty())out.wrapped("GST:"+tax);
    out.left();out.rule();
    String orderType=kotTypeLabel(data);String table=data.optString("table");
    out.two("Bill #"+data.optInt("billNumber"),orderType+(table.isEmpty()?"":" "+table));
    int kot=data.optInt("kotNumber");
    out.two(day2(data.optLong("createdAt"))+" "+time2(data.optLong("createdAt")),kot>0?"KOT "+kot:"");
    String customer=data.optString("customer");if(!customer.isEmpty())out.text("Name: "+customer+"\n");
    out.rule();
    double totalQty=emitItems(out,data.getJSONArray("items"));
    out.rule();
    emitTotals(out,data,totalQty,false);
    emitOrderNote(out,data);
    out.rule();
    emitFooter(out,data);
    out.feedAndCut();return out.bytes();
  }

  // ---- Template 3: Elegant (spaced, framed, prominent name & total) --------
  private static byte[] receiptElegant(JSONObject data,int width)throws Exception{
    Writer out=new Writer(width);out.init();
    out.center();
    out.doubleRule();
    out.bold(true);out.size(1);out.bigWrapped(data.optString("businessName","GI POS"));out.size(0);out.bold(false);
    String address=data.optString("businessAddress");if(!address.isEmpty())out.wrapped(address);
    String phone=data.optString("businessPhone");if(!phone.isEmpty())out.wrapped(phone);
    String tax=data.optString("businessTaxId");if(!tax.isEmpty())out.wrapped("GST NO:"+tax);
    out.doubleRule();
    out.left();
    String orderType=kotTypeLabel(data);String table=data.optString("table");
    out.two("Date : "+day2(data.optLong("createdAt"))+" "+time2(data.optLong("createdAt")),"");
    out.two("Order: "+orderType+(table.isEmpty()?"":" / "+table),"Bill No: "+data.optInt("billNumber"));
    String operator=data.optString("operator");
    out.two("Cashier: "+(operator.isEmpty()?"CASHIER":operator),"");
    int kot=data.optInt("kotNumber");if(kot>0)out.two("KOT: "+kot,"");
    String customer=data.optString("customer");if(!customer.isEmpty())out.two("Customer: "+customer,"");
    out.rule();
    out.bold(true);out.billColumns("Item","Qty.","Price","Amount");out.bold(false);
    out.rule();
    double totalQty=emitItems(out,data.getJSONArray("items"));
    out.rule();
    emitTotals(out,data,totalQty,true);
    out.doubleRule();
    emitOrderNote(out,data);
    out.center();out.bold(true);
    String footer=data.optString("receiptFooter","THANK YOU VISIT AGAIN!!!");
    if(!footer.isEmpty())out.wrapped(footer);
    out.bold(false);
    out.doubleRule();
    out.feedAndCut();return out.bytes();
  }
  private static void businessHeader(Writer out,JSONObject data){out.center();out.bold(true);out.size(1);out.bigWrapped(data.optString("businessName","GI POS"));out.size(0);out.bold(false);String address=data.optString("businessAddress");if(!address.isEmpty())out.wrapped(address);String phone=data.optString("businessPhone");if(!phone.isEmpty())out.wrapped(phone);String tax=data.optString("businessTaxId");if(!tax.isEmpty())out.wrapped("GST NO:"+tax);out.left();}
  private static byte[] kot(JSONObject data,int width)throws Exception{
    Writer out=new Writer(width);out.init();
    // Centered header block: date/time, KOT - number, order type, table.
    out.center();
    out.text(kotDate(data.optLong("createdAt"))+"\n");
    out.text("KOT - "+data.optInt("kotNumber")+"\n");
    out.bold(true);
    out.text(kotTypeLabel(data)+"\n");
    String table=data.optString("table");
    if(!table.isEmpty())out.text("Table No: "+table+"\n");
    out.bold(false);
    out.rule();
    // Biller line.
    out.left();
    String operator=data.optString("operator");
    out.text("Biller: "+(operator.isEmpty()?"CASHIER":operator)+"\n");
    out.rule();
    // Item | Special Note | Qty. table.
    out.kotColumns("Item","Special Note","Qty.");
    JSONArray items=data.getJSONArray("items");
    for(int i=0;i<items.length();i++){
      JSONObject item=items.getJSONObject(i);
      String name=item.optString("name"),variant=item.optString("variant");
      if(!variant.isEmpty())name+=" ("+variant+")";
      String note=item.optString("note");
      if(note.isEmpty())note="--";
      out.bold(true);
      out.kotItem(name,note,quantity(item.optDouble("quantity")));
      out.bold(false);
    }
    // Order-level note prints prominently at the bottom of the KOT.
    String orderNote=data.optString("orderNote");
    if(!orderNote.isEmpty()){out.rule();out.bold(true);out.text("NOTE: ");out.bold(false);out.wrapped(orderNote);}
    out.feedAndCut();
    return out.bytes();
  }
  private static byte[] report(JSONObject data,int width)throws Exception{Writer out=new Writer(width);out.init();out.center();out.bold(true);out.size(1);out.wrapped(data.optString("businessName","GI POS"));out.size(0);out.text("SALES REPORT\n");out.bold(false);out.text(period(data.optLong("from"),data.optLong("to"))+"\n");out.rule();out.left();out.two("Total sales","Rs. "+money(data.optDouble("sales")));out.two("Paid bills",String.valueOf(data.optInt("bills")));out.two("Total quantity",quantity(data.optDouble("totalQuantity")));if(data.optDouble("tax")>0)out.two("Tax","Rs. "+money(data.optDouble("tax")));if(data.optDouble("discount")>0)out.two("Discount","Rs. "+money(data.optDouble("discount")));if(data.optDouble("due")>0)out.two("Due","Rs. "+money(data.optDouble("due")));out.rule();out.bold(true);out.text("PAYMENTS\n");out.bold(false);if(data.optDouble("cash")>0)out.two("Cash","Rs. "+money(data.optDouble("cash")));if(data.optDouble("upi")>0)out.two("UPI","Rs. "+money(data.optDouble("upi")));if(data.optDouble("card")>0)out.two("Card","Rs. "+money(data.optDouble("card")));JSONArray gateways=data.optJSONArray("gateways");if(gateways!=null)for(int gi=0;gi<gateways.length();gi++){JSONObject g=gateways.optJSONObject(gi);if(g==null)continue;double amt=g.optDouble("amount");if(amt<=0)continue;out.two(g.optString("name","Gateway"),"Rs. "+money(amt));}JSONArray items=data.optJSONArray("items");if(items!=null&&items.length()>0){out.rule();out.bold(true);out.columns("Item","Qty","Amount");out.bold(false);out.rule();for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);out.item(item.optString("name"),quantity(item.optDouble("quantity")),money(item.optDouble("amount")));}}out.rule();out.center();out.text("End of report\n");out.feedAndCut();return out.bytes();}
  private static String date(long timestamp){return new SimpleDateFormat("dd/MM/yyyy, hh:mm a",Locale.US).format(new Date(timestamp));}private static String kotDate(long timestamp){return new SimpleDateFormat("dd/MM/yy HH:mm",Locale.US).format(new Date(timestamp));}private static String kotTypeLabel(JSONObject data){String label=data.optString("orderTypeLabel");if(!label.isEmpty())return label;String type=data.optString("orderType");if("delivery".equals(type))return "Delivery";if("takeaway".equals(type))return "Takeaway";return "Dine In";}private static String day(long timestamp){return new SimpleDateFormat("dd MMM yyyy",Locale.US).format(new Date(timestamp));}private static String period(long from,long to){String first=day(from),last=day(Math.max(from,to-1));return first.equals(last)?first:first+" - "+last;}private static String money(double value){return String.format(Locale.US,"%.2f",value);}private static String quantity(double value){return Math.rint(value)==value?String.valueOf((long)value):String.format(Locale.US,"%.2f",value);}
  private static String day2(long timestamp){return new SimpleDateFormat("dd/MM/yy",Locale.US).format(new Date(timestamp));}
  private static String time2(long timestamp){return new SimpleDateFormat("HH:mm",Locale.US).format(new Date(timestamp));}
  private static double round2(double value){return Math.round(value*100.0)/100.0;}
  private static String trimRate(double value){String r=String.format(Locale.US,"%.2f",value).replaceAll("0+$","").replaceAll("\\.$","");return r.isEmpty()?"0":r;}
  private static final class Writer {final int columns;int margin=0;final ByteArrayOutputStream out=new ByteArrayOutputStream();Writer(int width){columns=width<=58?32:48;}void setMargin(int m){margin=Math.max(0,Math.min(m,columns/4));}int inner(){return columns-2*margin;}String lead(){return repeat(' ',margin);}void raw(int...values){for(int value:values)out.write(value);}void init(){raw(27,64);raw(27,116,16);}void left(){raw(27,97,0);}void center(){raw(27,97,1);}void bold(boolean on){raw(27,69,on?1:0);}void size(int scale){raw(29,33,scale==2?34:scale==1?17:0);}void tall(boolean on){raw(29,33,on?1:0);}void text(String value){byte[] bytes=value.getBytes(ENCODING);out.write(bytes,0,bytes.length);}void wrapped(String value){for(String line:wrap(value,columns))text(line+"\n");}void leftLine(String value){left();for(String line:wrap(value,inner()))text(lead()+line+"\n");}void bigWrapped(String value){for(String line:wrap(value,Math.max(1,columns/2)))text(line+"\n");}void rule(){left();text(lead()+repeat('-',inner())+"\n");}void doubleRule(){left();text(lead()+repeat('=',inner())+"\n");}void bigTwo(String left,String right){this.left();int cols=Math.max(6,columns/2);String l=trim(left,Math.max(1,cols-right.length()-1));int pad=Math.max(1,cols-l.length()-right.length());text(l+repeat(' ',pad)+right+"\n");}void two(String left,String right){this.left();int w=inner();int room=Math.max(1,w-right.length()-1);String[] lines=wrap(left,room);for(int i=0;i<lines.length;i++){if(i==lines.length-1){String l=lines[i];text(lead()+l+repeat(' ',Math.max(1,w-l.length()-right.length()))+right+"\n");}else text(lead()+lines[i]+"\n");}}void columns(String name,String qty,String amount){left();int amountWidth=columns<=32?9:12,qtyWidth=5,nameWidth=columns-amountWidth-qtyWidth;String n=trim(name,nameWidth),q=trim(qty,qtyWidth),a=trim(amount,amountWidth);text(padRight(n,nameWidth)+padLeft(q,qtyWidth)+padLeft(a,amountWidth)+"\n");}void item(String name,String qty,String amount){int amountWidth=columns<=32?9:12,qtyWidth=5,nameWidth=columns-amountWidth-qtyWidth;String[] lines=wrap(name,nameWidth);for(int i=0;i<lines.length;i++){String q=i==0?qty:"",a=i==0?amount:"";text(padRight(lines[i],nameWidth)+padLeft(q,qtyWidth)+padLeft(a,amountWidth)+"\n");}}void billColumns(String name,String qty,String price,String amount){billItem(name,qty,price,amount);}void billItem(String name,String qty,String price,String amount){left();int w=inner();int qtyWidth=4,priceWidth=w<=32?7:10,amountWidth=w<=32?8:11,nameWidth=w-qtyWidth-priceWidth-amountWidth;String[] lines=wrap(name,nameWidth);for(int i=0;i<lines.length;i++){String q=i==0?qty:"",p=i==0?price:"",a=i==0?amount:"";text(lead()+padRight(lines[i],nameWidth)+padLeft(q,qtyWidth)+padLeft(p,priceWidth)+padLeft(a,amountWidth)+"\n");}}
    // Totals row that matches the printed bill: an optional left label (e.g.
    // "Total Qty: 3") on the far left, and a right-aligned block made of a
    // label, an optional rate, and an amount. Widths are fixed so the amounts
    // line up in a clean right column.
    void totalsRow(String leftLabel,String label,String rate,String amount){
      left();
      // Compact right-aligned totals group: [label] [rate] [amount], grouped on
      // the right; the optional "Total Qty: N" label floats at the far left.
      int w=inner();
      int amountW=w<=32?9:10, rateW=5, labelW=w<=32?13:16; // right group width
      String group=padRight(trim(label,labelW),labelW)+padLeft(trim(rate,rateW),rateW)+padLeft(trim(amount,amountW),amountW);
      int groupW=group.length();
      String ll=leftLabel==null?"":trim(leftLabel,Math.max(0,w-groupW-1));
      int leftPad=Math.max(0,w-groupW-ll.length());
      text(lead()+ll+repeat(' ',leftPad)+group+"\n");
    }
    // KOT 3-column layout: Item (left) | Special Note (centre) | Qty (right).
    void kotColumns(String name,String note,String qty){left();int qtyWidth=4,noteWidth=columns<=32?11:14,nameWidth=columns-qtyWidth-noteWidth;text(padRight(trim(name,nameWidth),nameWidth)+padRight(trim(note,noteWidth),noteWidth)+padLeft(trim(qty,qtyWidth),qtyWidth)+"\n");}
    void kotItem(String name,String note,String qty){left();int qtyWidth=4,noteWidth=columns<=32?11:14,nameWidth=columns-qtyWidth-noteWidth;String[] nameLines=wrap(name,nameWidth),noteLines=wrap(note,noteWidth);int rows=Math.max(nameLines.length,noteLines.length);for(int i=0;i<rows;i++){String n=i<nameLines.length?nameLines[i]:"",no=i<noteLines.length?noteLines[i]:"",q=i==0?qty:"";text(padRight(n,nameWidth)+padRight(no,noteWidth)+padLeft(q,qtyWidth)+"\n");}}
    void feedAndCut(){text("\n\n\n");raw(29,86,66,0);}byte[] bytes(){return out.toByteArray();}}
  private static String[] wrap(String value,int width){String clean=value==null?"":value.trim();if(clean.isEmpty())return new String[]{""};java.util.ArrayList<String> lines=new java.util.ArrayList<>();while(clean.length()>width){int cut=clean.lastIndexOf(' ',width);if(cut<1)cut=width;lines.add(clean.substring(0,cut).trim());clean=clean.substring(cut).trim();}lines.add(clean);return lines.toArray(new String[0]);}private static String repeat(char c,int count){StringBuilder b=new StringBuilder();for(int i=0;i<count;i++)b.append(c);return b.toString();}private static String padRight(String v,int width){return trim(v,width)+repeat(' ',Math.max(0,width-trim(v,width).length()));}private static String padLeft(String v,int width){String t=trim(v,width);return repeat(' ',Math.max(0,width-t.length()))+t;}private static String trim(String v,int width){return v.length()<=width?v:v.substring(0,width);}
}
