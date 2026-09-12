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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CustomerActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236);
  private final ArrayList<PosDatabase.CustomerInfo> customers=new ArrayList<>();private PosDatabase db;private ListView list;private EditText search;
  @Override public void onCreate(Bundle state){super.onCreate(state);if(!requireFeature("customers"))return;db=PosDatabase.get(this);build();}
  @Override protected void onResume(){super.onResume();load();}
  private void build(){LinearLayout root=column();root.setPadding(dp(16),dp(14),dp(16),dp(14));root.setBackgroundColor(Color.rgb(244,247,250));LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout title=column();title.setPadding(dp(10),0,0,0);title.addView(text("Customers",24,ink,true));title.addView(text("Search customers and review their bills",13,muted,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button add=button("+ New",red,Color.WHITE);add.setOnClickListener(v->showEditor(null));head.addView(add,new LinearLayout.LayoutParams(dp(92),dp(44)));root.addView(head);search=input("Search name or phone",InputType.TYPE_CLASS_TEXT);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){load();}public void afterTextChanged(Editable e){}});root.addView(search,topHeight(14,56));list=new ListView(this);list.setDivider(null);list.setDividerHeight(dp(8));list.setPadding(0,dp(10),0,0);list.setClipToPadding(false);list.setAdapter(new CustomerAdapter());list.setOnItemClickListener((p,v,pos,id)->showDetails(customers.get(pos)));root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);}
  private void load(){String query=search==null?"":search.getText().toString();new Thread(()->{List<PosDatabase.CustomerInfo> result=db.customers(query);runOnUiThread(()->{customers.clear();customers.addAll(result);((BaseAdapter)list.getAdapter()).notifyDataSetChanged();});}).start();}
  private void showDetails(PosDatabase.CustomerInfo customer){LinearLayout content=column();content.setPadding(dp(18),dp(2),dp(18),dp(8));content.addView(pair("Phone",customer.phone.isEmpty()?"-":customer.phone));if(!customer.address.isEmpty())content.addView(pair("Address",customer.address),top(8));content.addView(pair("Due","Rs. "+money(customer.dueBalance)),top(8));content.addView(text("Bills",13,muted,true),top(18));List<PosDatabase.OrderListItem> bills=db.customerOrders(customer.id);if(bills.isEmpty())content.addView(text("No bills found",14,muted,false),top(9));for(PosDatabase.OrderListItem bill:bills){TextView row=text("Bill #"+bill.billNumber+"  /  "+bill.tableName+"\n"+bill.status.toUpperCase(Locale.US)+"  /  Rs. "+money(bill.total),14,ink,true);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(shape(Color.rgb(248,250,252),9,1,line));row.setOnClickListener(v->{Intent intent=new Intent(this,SaleSuccessActivity.class);intent.putExtra("orderId",bill.id);startActivity(intent);});content.addView(row,top(7));}ScrollView scroll=new ScrollView(this);scroll.addView(content);new AlertDialog.Builder(this).setTitle(customer.name).setView(scroll).setNegativeButton("Close",null).setNeutralButton("Edit",(d,w)->showEditor(customer)).show();}
  private void showEditor(PosDatabase.CustomerInfo current){LinearLayout form=FormControls.dialogForm(this);EditText name=input("Enter customer name",InputType.TYPE_CLASS_TEXT),phone=input("Enter phone number",InputType.TYPE_CLASS_PHONE),address=FormControls.multiline(this,"Enter address (optional)");if(current!=null){name.setText(current.name);phone.setText(current.phone);address.setText(current.address);}form.addView(FormControls.field(this,"Customer name",name));form.addView(FormControls.field(this,"Phone number",phone),top(13));form.addView(FormControls.field(this,"Address",address),top(13));new AlertDialog.Builder(this).setTitle(current==null?"New customer":"Edit customer").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{try{db.saveCustomer(name.getText().toString(),phone.getText().toString(),address.getText().toString());CloudSyncManager.syncAsync(this);Toast.makeText(this,"Customer saved",Toast.LENGTH_SHORT).show();load();}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}).show();}
  private final class CustomerAdapter extends BaseAdapter {public int getCount(){return customers.size();}public Object getItem(int p){return customers.get(p);}public long getItemId(int p){return p;}public View getView(int position,View old,ViewGroup parent){PosDatabase.CustomerInfo item=customers.get(position);LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(14),dp(13),dp(12),dp(13));card.setBackground(shape(Color.WHITE,11,1,line));LinearLayout copy=column();copy.addView(text(item.name,16,ink,true));copy.addView(text(item.phone.isEmpty()?"No phone":item.phone,12,muted,false),top(4));card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));LinearLayout dueBox=column();dueBox.setGravity(Gravity.RIGHT);dueBox.addView(text("Due",11,muted,true));dueBox.addView(text("Rs. "+money(item.dueBalance),15,item.dueBalance>0?red:teal,true),top(3));card.addView(dueBox);return card;}}
  private LinearLayout pair(String label,String value){LinearLayout row=row();row.setGravity(Gravity.TOP);row.addView(text(label,13,muted,false),new LinearLayout.LayoutParams(dp(74),-2));TextView content=text(value,14,ink,true);content.setMaxLines(3);row.addView(content,new LinearLayout.LayoutParams(0,-2,1));return row;}private EditText input(String hint,int type){return FormControls.input(this,hint,type);}private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}private LinearLayout row(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);return value;}private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);return view;}private Button button(String value,int fill,int color){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(color);view.setTypeface(Typeface.DEFAULT_BOLD);view.setBackground(shape(fill,9,1,line));return view;}private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}private LinearLayout.LayoutParams topHeight(int margin,int height){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,dp(height));value.topMargin=dp(margin);return value;}private String money(double value){return String.format(Locale.US,"%.2f",value);}private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
}
