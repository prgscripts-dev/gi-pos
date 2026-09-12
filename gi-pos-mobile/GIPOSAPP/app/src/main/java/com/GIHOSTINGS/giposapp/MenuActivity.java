package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;

public class MenuActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236);
  private PosDatabase database; private LinearLayout categoryBar,filterBar; private ListView list; private TextView count; private EditText search;
  private String categoryId=null; private Boolean availability=null; private int loadVersion=0; private final ArrayList<PosDatabase.ProductInfo> rows=new ArrayList<>();

  @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!requireFeature("menuManagement"))return;database=PosDatabase.get(this);build();}
  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing()&&list!=null){refreshCategories();loadItems();}}};
  @Override protected void onResume(){super.onResume();if(list!=null){refreshCategories();loadItems();}CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}

  private void build(){
    LinearLayout root=column();root.setBackgroundColor(Color.rgb(244,247,250));
    LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(16),dp(14),dp(16),dp(10));
    head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
    LinearLayout title=column();title.setPadding(dp(12),0,0,0);title.addView(text("Menu & Items",24,ink,true));count=text("0 items",13,muted,false);title.addView(count);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button add=button("+ Add Item",red,Color.WHITE);add.setOnClickListener(v->openEditor(null));head.addView(add,new LinearLayout.LayoutParams(-2,dp(44)));root.addView(head);

    search=FormControls.input(this,"Search item name or alias",android.text.InputType.TYPE_CLASS_TEXT);LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,dp(56));searchParams.setMargins(dp(16),dp(8),dp(16),0);root.addView(search,searchParams);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){loadItems();}public void afterTextChanged(Editable e){}});

    HorizontalScrollView categoryScroll=new HorizontalScrollView(this);categoryScroll.setHorizontalScrollBarEnabled(false);categoryBar=row();categoryBar.setPadding(dp(16),dp(10),dp(8),dp(5));categoryScroll.addView(categoryBar);root.addView(categoryScroll);
    filterBar=row();filterBar.setPadding(dp(16),dp(3),dp(16),dp(10));root.addView(filterBar);refreshFilters();

    list=new ListView(this);list.setDividerHeight(0);list.setPadding(dp(12),0,dp(12),dp(14));list.setClipToPadding(false);list.setAdapter(new ProductAdapter());root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);refreshCategories();loadItems();
  }
  private void refreshCategories(){categoryBar.removeAllViews();categoryBar.addView(categoryButton("All",null),chip());for(PosDatabase.CategoryInfo category:database.categories())categoryBar.addView(categoryButton(category.name,category.id),chip());Button add=button("+ Category",Color.WHITE,teal);add.setOnClickListener(v->addCategory());categoryBar.addView(add,chip());}
  private Button categoryButton(String label,String id){boolean selected=(id==null&&categoryId==null)||(id!=null&&id.equals(categoryId));Button b=button(label,selected?red:Color.WHITE,selected?Color.WHITE:ink);b.setOnClickListener(v->{categoryId=id;refreshCategories();loadItems();});return b;}
  private void refreshFilters(){filterBar.removeAllViews();filterBar.addView(filterButton("All",null),chip());filterBar.addView(filterButton("Available",true),chip());filterBar.addView(filterButton("Unavailable",false),chip());}
  private Button filterButton(String label,Boolean value){boolean selected=availability==value||(availability!=null&&availability.equals(value));Button b=button(label,selected?teal:Color.WHITE,selected?Color.WHITE:ink);b.setOnClickListener(v->{availability=value;refreshFilters();loadItems();});return b;}
  private void loadItems(){final int version=++loadVersion;final String category=categoryId;final String query=search==null?"":search.getText().toString();final Boolean available=availability;new Thread(()->{List<PosDatabase.ProductInfo> loaded=database.products(category,query,available);runOnUiThread(()->{if(version!=loadVersion)return;rows.clear();rows.addAll(loaded);count.setText(rows.size()+" item"+(rows.size()==1?"":"s"));((BaseAdapter)list.getAdapter()).notifyDataSetChanged();});}).start();}
  private void addCategory(){EditText name=FormControls.input(this,"Example: Fresh Juice",android.text.InputType.TYPE_CLASS_TEXT);LinearLayout form=FormControls.dialogForm(this);form.addView(FormControls.field(this,"Category name",name));new AlertDialog.Builder(this).setTitle("New category").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->{String value=name.getText().toString().trim();if(!value.isEmpty()){categoryId=database.addCategory(value);refreshCategories();loadItems();}}).show();}
  private void openEditor(String id){Intent intent=new Intent(this,ItemEditorActivity.class);if(id!=null)intent.putExtra("productId",id);startActivity(intent);}

  private final class ProductAdapter extends BaseAdapter {
    public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
    public View getView(int position,View convert,ViewGroup parent){PosDatabase.ProductInfo item=rows.get(position);LinearLayout card=column();card.setPadding(dp(15),dp(12),dp(15),dp(12));card.setBackground(shape(Color.WHITE,11,1,line));LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);ImageView image=new ImageView(MenuActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(Color.rgb(241,245,249),9,0,line));image.setClipToOutline(true);LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(58),dp(58));imageParams.rightMargin=dp(12);top.addView(image,imageParams);ItemImageLoader.load(MenuActivity.this,image,item.imageUri,dp(128));LinearLayout info=column();info.addView(text(item.name,17,ink,true));String detail=item.categoryName.isEmpty()?"Uncategorized":item.categoryName;if(!item.alias.isEmpty())detail+="  •  "+item.alias;info.addView(text(detail,12,muted,false),top(3));top.addView(info,new LinearLayout.LayoutParams(0,-2,1));String price="variants".equals(item.priceMode)?"Variants":"manual".equals(item.priceMode)?"Ask price":"Rs. "+money(item.price);TextView amount=text(price,15,item.available?teal:muted,true);top.addView(amount);card.addView(top);LinearLayout bottom=row();bottom.setGravity(Gravity.CENTER_VERTICAL);TextView status=text(item.available?"Available":"Unavailable",12,item.available?Color.rgb(5,116,100):red,true);bottom.addView(status,new LinearLayout.LayoutParams(0,-2,1));Button edit=button("Edit",Color.rgb(241,245,249),ink);edit.setOnClickListener(v->openEditor(item.id));bottom.addView(edit,new LinearLayout.LayoutParams(dp(76),dp(40)));card.addView(bottom,top(8));LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,-2);outer.setMargins(dp(4),dp(5),dp(4),dp(5));card.setLayoutParams(outer);card.setOnClickListener(v->openEditor(item.id));return card;}
  }
  private String money(double value){return String.format(java.util.Locale.US,"%.2f",value);}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
  private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
  private Button button(String label,int fill,int color){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT_BOLD);v.setBackground(shape(fill,9,1,line));return v;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
  private LinearLayout.LayoutParams chip(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.rightMargin=dp(8);return p;}private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
