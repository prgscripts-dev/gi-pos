package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class TableActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140);
  private PosDatabase database; private LinearLayout floorBar; private GridLayout grid; private TextView summary; private String selectedFloor=null; private boolean editMode=false,layoutOnly=false;

  @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!requireFeature("tables"))return;layoutOnly=getIntent().getBooleanExtra("layoutOnly",false);editMode=layoutOnly;database=PosDatabase.get(this);database.seedDefaultTables();build();}
  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing()&&grid!=null)refresh();}};
  @Override protected void onResume(){super.onResume();if(grid!=null)refresh();CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}

  private void build(){
    LinearLayout root=column();root.setBackgroundColor(Color.rgb(244,247,250));
    LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(18),dp(16),dp(18),dp(12));
    head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
    LinearLayout title=column();title.setPadding(dp(12),0,0,0);title.addView(text(layoutOnly?"Table Layout":"Select Table",24,ink,true));summary=text("",13,muted,false);title.addView(summary);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    if(!layoutOnly){Button edit=button("Edit layout",Color.WHITE,ink);edit.setOnClickListener(v->{editMode=!editMode;edit.setText(editMode?"Done":"Edit layout");refresh();});head.addView(edit,new LinearLayout.LayoutParams(-2,dp(44)));}root.addView(head);
    HorizontalScrollView floorScroll=new HorizontalScrollView(this);floorScroll.setHorizontalScrollBarEnabled(false);floorBar=new LinearLayout(this);floorBar.setPadding(dp(14),dp(4),dp(14),dp(10));floorScroll.addView(floorBar);root.addView(floorScroll);
    ScrollView body=new ScrollView(this);grid=new GridLayout(this);grid.setColumnCount(columns());grid.setPadding(dp(14),dp(6),dp(14),dp(24));body.addView(grid);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);refresh();
  }

  private void refresh(){
    floorBar.removeAllViews();addFloorChip("All",null);for(PosDatabase.FloorInfo floor:database.floors())addFloorChip(floor.name,floor.id);if(editMode){Button add=button("+ Floor",Color.WHITE,teal);add.setOnClickListener(v->addFloor());floorBar.addView(add,chipParams());}
    List<PosDatabase.TableInfo> tables=database.tables(selectedFloor);grid.removeAllViews();int occupied=0;for(PosDatabase.TableInfo table:tables){if(!"available".equals(table.status))occupied++;grid.addView(tableCard(table),cellParams());}
    if(editMode){TextView add=text("+\nAdd table",16,teal,true);add.setGravity(Gravity.CENTER);add.setBackground(shape(Color.WHITE,14,1,Color.rgb(148,210,218)));add.setOnClickListener(v->addTable());grid.addView(add,cellParams());}
    summary.setText(layoutOnly?tables.size()+" tables  •  Tap a table to edit":tables.size()+" tables  •  "+occupied+" active");
  }

  private void addFloorChip(String label,String id){boolean selected=(selectedFloor==null&&id==null)||(selectedFloor!=null&&selectedFloor.equals(id));Button chip=button(label,selected?red:Color.WHITE,selected?Color.WHITE:ink);chip.setOnClickListener(v->{selectedFloor=id;refresh();});floorBar.addView(chip,chipParams());}
  private View tableCard(PosDatabase.TableInfo table){int fill,border;if(layoutOnly||"available".equals(table.status)){fill=Color.WHITE;border=Color.rgb(205,216,227);}else if("held".equals(table.status)){fill=Color.rgb(255,247,220);border=Color.rgb(236,179,55);}else{fill=Color.rgb(229,247,249);border=teal;}
    LinearLayout card=column();card.setGravity(Gravity.CENTER);card.setPadding(dp(8),dp(14),dp(8),dp(12));card.setBackground(shape(fill,14,2,border));card.addView(text(table.name,22,ink,true),center());String state=layoutOnly?table.seats+" seats":"available".equals(table.status)?table.seats+" seats":table.status.toUpperCase();card.addView(text(state,12,layoutOnly||"available".equals(table.status)?muted:border,true),centerTop(5));
    card.setOnClickListener(v->{if(editMode)editTable(table);else openOrder(table);});
    // Long-press a table (in ordering mode) to duplicate it for another customer.
    if(!layoutOnly&&!editMode)card.setOnLongClickListener(v->{showTableActions(table);return true;});
    return card;}
  private void openOrder(PosDatabase.TableInfo table){String orderId=database.openOrCreateOrder(table.id);Intent intent=new Intent(this,OrderActivity.class);intent.putExtra("orderId",orderId);intent.putExtra("tableName",table.name);startActivity(intent);}
  // Long-press menu on a table: duplicate it so the same table can serve another
  // customer in parallel. The new table starts empty and is auto-removed once its
  // bill is finalized at checkout.
  private void showTableActions(PosDatabase.TableInfo table){
    String[] options=table.isDuplicate?new String[]{"Open order","Remove this duplicate"}:new String[]{"Open order","Duplicate this table"};
    new AlertDialog.Builder(this).setTitle(table.name).setItems(options,(d,which)->{
      if(which==0){openOrder(table);return;}
      if(table.isDuplicate){database.removeDuplicateTable(table.id);CloudSyncManager.syncAsync(this);refresh();Toast.makeText(this,"Duplicate table removed",Toast.LENGTH_SHORT).show();return;}
      String newId=database.duplicateTable(table.id);CloudSyncManager.syncAsync(this);refresh();Toast.makeText(this,"Table duplicated \u2014 ready for the next customer",Toast.LENGTH_SHORT).show();
    }).setNegativeButton("Cancel",null).show();
  }
  private void addFloor(){EditText name=input("Example: Main Hall",InputType.TYPE_CLASS_TEXT);LinearLayout form=dialogForm();form.addView(FormControls.field(this,"Floor or area name",name));new AlertDialog.Builder(this).setTitle("Add floor / area").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->{if(!name.getText().toString().trim().isEmpty()){selectedFloor=database.addFloor(name.getText().toString());refresh();}}).show();}
  private void addTable(){List<PosDatabase.FloorInfo> floors=database.floors();if(floors.isEmpty()){addFloor();return;}String floor=selectedFloor==null?floors.get(0).id:selectedFloor;LinearLayout form=dialogForm();EditText name=input("Example: T1",InputType.TYPE_CLASS_TEXT);EditText seats=input("Number of seats",InputType.TYPE_CLASS_NUMBER);seats.setText("4");form.addView(FormControls.field(this,"Table name",name));form.addView(FormControls.field(this,"Seats",seats),top(13));new AlertDialog.Builder(this).setTitle("Add table").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->{String value=name.getText().toString().trim();if(!value.isEmpty())database.addTable(floor,value,number(seats,4));refresh();}).show();}
  private void editTable(PosDatabase.TableInfo table){LinearLayout form=dialogForm();EditText name=input("Table name",InputType.TYPE_CLASS_TEXT);name.setText(table.name);EditText seats=input("Number of seats",InputType.TYPE_CLASS_NUMBER);seats.setText(String.valueOf(table.seats));form.addView(FormControls.field(this,"Table name",name));form.addView(FormControls.field(this,"Seats",seats),top(13));new AlertDialog.Builder(this).setTitle("Edit "+table.name).setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{if(!name.getText().toString().trim().isEmpty())database.updateTable(table.id,name.getText().toString(),number(seats,table.seats));refresh();}).show();}
  private LinearLayout dialogForm(){return FormControls.dialogForm(this);}
  private EditText input(String hint,int type){return FormControls.input(this,hint,type);}
  private int number(EditText value,int fallback){try{return Math.max(1,Integer.parseInt(value.getText().toString()));}catch(Exception ignored){return fallback;}}
  private int columns(){return getResources().getConfiguration().screenWidthDp>=600?5:3;}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
  private Button button(String label,int fill,int color){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT_BOLD);v.setBackground(shape(fill,10,1,Color.rgb(217,226,236)));return v;}
  private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
  private LinearLayout.LayoutParams chipParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.rightMargin=dp(8);return p;}
  private GridLayout.LayoutParams cellParams(){GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(112);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);p.setMargins(dp(5),dp(5),dp(5),dp(5));return p;}
  private LinearLayout.LayoutParams center(){return new LinearLayout.LayoutParams(-2,-2);}
  private LinearLayout.LayoutParams centerTop(int margin){LinearLayout.LayoutParams p=center();p.topMargin=dp(margin);return p;}
  private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);return p;}
  private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
}
