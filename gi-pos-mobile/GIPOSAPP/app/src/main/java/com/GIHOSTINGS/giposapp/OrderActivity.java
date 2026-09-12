package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.content.Intent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OrderActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236);
  private PosDatabase db; private String orderId,tableName,categoryId=null; private EditText search; private LinearLayout categories; private HorizontalScrollView categoryScroller; private GridView grid; private TextView total,selection; private ImageButton twoColumnButton,threeColumnButton,groupButton;
  private final ArrayList<PosDatabase.ProductInfo> products=new ArrayList<>(); private final ArrayList<PosDatabase.CategoryInfo> menuCategories=new ArrayList<>(); private final ArrayList<PosDatabase.OrderLine> lines=new ArrayList<>(); private final Map<String,List<PosDatabase.OrderLine>> selected=new HashMap<>(); private int loadVersion=0,itemColumns=2; private boolean categoryFirst=false,categoryLanding=false;
  private Button customerButton; // delivery customer-details icon (turns green when set)

  @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!requireFeature("billing"))return;db=PosDatabase.get(this);PosDatabase.OrderingPreferences preferences=db.orderingPreferences();categoryFirst="categories".equals(preferences.browseMode);categoryLanding=categoryFirst;itemColumns=preferences.columns;orderId=getIntent().getStringExtra("orderId");tableName=getIntent().getStringExtra("tableName");PosDatabase.OrderInfo info=orderId==null?null:db.orderInfo(orderId);if(info==null){finish();return;}if("paid".equals(info.status)||"due".equals(info.status)){Intent done=new Intent(this,SaleSuccessActivity.class);done.putExtra("orderId",orderId);startActivity(done);finish();return;}build();}
  private final CloudSyncManager.UpdateListener syncListener=new CloudSyncManager.UpdateListener(){@Override public void onSyncUpdated(){if(!isFinishing()&&grid!=null)loadMenu();}};
  @Override protected void onResume(){super.onResume();if(grid!=null){loadMenu();refreshCart();}CloudSyncManager.addUpdateListener(syncListener);}
  @Override protected void onPause(){CloudSyncManager.removeUpdateListener(syncListener);super.onPause();}
  private void build(){
    LinearLayout root=column();root.setBackgroundColor(Color.rgb(244,247,250));
    LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(14),dp(12),dp(14),dp(8));head.addView(AppBackButton.create(this,v->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout title=column();title.setPadding(dp(10),0,0,0);title.addView(text(tableName==null?"New Order":tableName,23,ink,true));selection=text("Choose items",12,muted,false);title.addView(selection);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));PosDatabase.OrderInfo current=db.orderInfo(orderId);String statusLabel=current.kotNumber==null?current.status.toUpperCase(Locale.US):"KOT #"+current.kotNumber;TextView status=text(statusLabel,12,teal,true);status.setPadding(dp(10),dp(8),dp(10),dp(8));status.setBackground(shape(Color.rgb(229,247,249),8,1,Color.rgb(148,210,218)));head.addView(status);root.addView(head);
    LinearLayout browseTools=row();browseTools.setGravity(Gravity.CENTER_VERTICAL);browseTools.setPadding(dp(14),0,dp(14),0);search=FormControls.input(this,categoryFirst?"Search all menu items":"Search menu",InputType.TYPE_CLASS_TEXT);browseTools.addView(search,new LinearLayout.LayoutParams(0,dp(56),1));search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){loadMenu();}public void afterTextChanged(Editable e){}});LinearLayout densityGroup=row();densityGroup.setPadding(dp(2),dp(2),dp(2),dp(2));densityGroup.setBackground(shape(Color.WHITE,9,1,line));twoColumnButton=layoutButton(new LayoutIconDrawable(2,false),"Two-column item view");twoColumnButton.setOnClickListener(v->setColumns(2));densityGroup.addView(twoColumnButton,new LinearLayout.LayoutParams(dp(42),dp(42)));threeColumnButton=layoutButton(new LayoutIconDrawable(3,false),"Three-column item view");threeColumnButton.setOnClickListener(v->setColumns(3));densityGroup.addView(threeColumnButton,new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout.LayoutParams densityParams=new LinearLayout.LayoutParams(dp(88),dp(46));densityParams.leftMargin=dp(8);browseTools.addView(densityGroup,densityParams);groupButton=layoutButton(new LayoutIconDrawable(1,true),"Toggle category browsing");groupButton.setOnClickListener(v->toggleCategoryBrowse());LinearLayout.LayoutParams groupParams=new LinearLayout.LayoutParams(dp(46),dp(46));groupParams.leftMargin=dp(8);browseTools.addView(groupButton,groupParams);root.addView(browseTools);updateLayoutButtons();
    categoryScroller=new HorizontalScrollView(this);categoryScroller.setHorizontalScrollBarEnabled(false);categories=row();categories.setPadding(dp(14),dp(9),dp(8),dp(8));categoryScroller.addView(categories);root.addView(categoryScroller);refreshCategories();
    grid=new GridView(this);grid.setNumColumns(itemColumns);grid.setHorizontalSpacing(dp(itemColumns==3?6:8));grid.setVerticalSpacing(dp(8));grid.setPadding(dp(itemColumns==3?8:12),0,dp(itemColumns==3?8:12),dp(10));grid.setClipToPadding(false);grid.setAdapter(new ItemAdapter());root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout footer=column();footer.setPadding(dp(14),dp(9),dp(14),dp(12));footer.setBackground(shape(Color.WHITE,0,1,line));LinearLayout amountRow=row();amountRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout amount=column();amount.addView(text("Order total",12,muted,true));total=text("Rs. 0.00",25,ink,true);amount.addView(total);amountRow.addView(amount,new LinearLayout.LayoutParams(0,-2,1));boolean showCustomer="delivery".equals(current.orderType)||"takeaway".equals(current.orderType);if(showCustomer){customerButton=button("\uD83D\uDC64",Color.rgb(241,245,249),ink);customerButton.setContentDescription("Add customer details");customerButton.setOnClickListener(v->editDeliveryCustomer());LinearLayout.LayoutParams cbp=new LinearLayout.LayoutParams(dp(48),dp(42));cbp.rightMargin=dp(8);amountRow.addView(customerButton,cbp);refreshCustomerButton();}Button orderNoteButton=button("+ Note",Color.rgb(241,245,249),ink);orderNoteButton.setContentDescription("Add an order note");orderNoteButton.setOnClickListener(v->editOrderNote());amountRow.addView(orderNoteButton,new LinearLayout.LayoutParams(dp(96),dp(42)));footer.addView(amountRow);LinearLayout actions=row();Button hold=button("Hold",Color.rgb(255,247,220),ink);hold.setOnClickListener(v->hold());actions.addView(hold,weight());actions.addView(gap());Button saveKot=button("KOT",Color.rgb(233,246,236),Color.rgb(21,128,61));saveKot.setContentDescription("Save items to the table without printing");saveKot.setOnClickListener(v->saveKot());actions.addView(saveKot,weight());actions.addView(gap());Button kot=button("Print KOT",Color.rgb(229,247,249),teal);kot.setOnClickListener(v->sendKot());actions.addView(kot,weight());footer.addView(actions,top(9));LinearLayout checkoutRow=row();Button checkout=button("Checkout",red,Color.WHITE);checkout.setOnClickListener(v->openCheckout());checkoutRow.addView(checkout,new LinearLayout.LayoutParams(0,dp(48),1));footer.addView(checkoutRow,top(8));root.addView(footer);setContentView(root);loadMenu();refreshCart();
  }
  private void refreshCategories(){categories.removeAllViews();if(categoryFirst){if(categoryLanding){categoryScroller.setVisibility(View.GONE);return;}categoryScroller.setVisibility(View.VISIBLE);Button back=button("‹ Categories",Color.WHITE,ink);back.setOnClickListener(v->{categoryId=null;categoryLanding=true;search.setText("");refreshCategories();loadMenu();});categories.addView(back,chip());for(PosDatabase.CategoryInfo category:db.categories())if(category.id.equals(categoryId)){Button current=button(category.name,red,Color.WHITE);categories.addView(current,chip());break;}return;}categoryScroller.setVisibility(View.VISIBLE);categories.addView(categoryButton("All",null),chip());for(PosDatabase.CategoryInfo category:db.categories())categories.addView(categoryButton(category.name,category.id),chip());}
  private Button categoryButton(String label,String id){boolean active=(id==null&&categoryId==null)||(id!=null&&id.equals(categoryId));Button b=button(label,active?red:Color.WHITE,active?Color.WHITE:ink);b.setOnClickListener(v->{categoryId=id;refreshCategories();loadMenu();});return b;}
  private void setColumns(int columns){itemColumns=columns==3?3:2;db.saveOrderingPreferences(categoryFirst?"categories":"items",itemColumns);applyGridDensity();updateLayoutButtons();loadMenu();}
  private void toggleCategoryBrowse(){categoryFirst=!categoryFirst;categoryLanding=categoryFirst;categoryId=null;db.saveOrderingPreferences(categoryFirst?"categories":"items",itemColumns);search.setHint(categoryFirst?"Search all menu items":"Search menu");updateLayoutButtons();refreshCategories();if(search.getText().length()>0)search.setText("");else loadMenu();}
  private void applyGridDensity(){if(grid==null)return;grid.setNumColumns(itemColumns);grid.setHorizontalSpacing(dp(itemColumns==3?6:8));grid.setVerticalSpacing(dp(8));grid.setPadding(dp(itemColumns==3?8:12),0,dp(itemColumns==3?8:12),dp(10));}
  private void updateLayoutButtons(){if(twoColumnButton==null)return;styleLayoutButton(twoColumnButton,itemColumns==2);styleLayoutButton(threeColumnButton,itemColumns==3);styleLayoutButton(groupButton,categoryFirst);}
  private void styleLayoutButton(ImageButton button,boolean active){button.setColorFilter(active?Color.WHITE:ink);button.setBackground(shape(active?red:Color.TRANSPARENT,9,0,active?red:line));}
  private ImageButton layoutButton(Drawable icon,String description){ImageButton button=new ImageButton(this);button.setImageDrawable(icon);button.setContentDescription(description);button.setPadding(dp(11),dp(11),dp(11),dp(11));button.setOnLongClickListener(v->{Toast.makeText(this,description,Toast.LENGTH_SHORT).show();return true;});return button;}
  private void loadMenu(){final int version=++loadVersion;final String query=search==null?"":search.getText().toString().trim();final boolean showCategories=categoryFirst&&categoryLanding&&query.isEmpty();final String cat=categoryFirst&&categoryLanding?null:categoryId;new Thread(()->{List<PosDatabase.CategoryInfo> loadedCategories=showCategories?db.categories():new ArrayList<>();List<PosDatabase.ProductInfo> result=showCategories?new ArrayList<>():db.products(cat,query,true);runOnUiThread(()->{if(version!=loadVersion)return;menuCategories.clear();menuCategories.addAll(loadedCategories);products.clear();products.addAll(result);applyGridDensity();((BaseAdapter)grid.getAdapter()).notifyDataSetChanged();});}).start();}
  private void refreshCart(){lines.clear();lines.addAll(db.orderLines(orderId));selected.clear();double amount=0,quantity=0;for(PosDatabase.OrderLine lineItem:lines){selected.computeIfAbsent(lineItem.productId,k->new ArrayList<>()).add(lineItem);amount+=lineItem.lineTotal+("exclusive".equals(lineItem.taxMode)?lineItem.taxAmount:0);quantity+=lineItem.quantity;}total.setText("Rs. "+money(amount));selection.setText(lines.isEmpty()?"Choose items":formatQty(quantity)+" total qty  •  "+lines.size()+" line(s)");if(grid!=null)((BaseAdapter)grid.getAdapter()).notifyDataSetChanged();}
  private void choose(PosDatabase.ProductInfo summary){
    PosDatabase.ProductInfo product=db.product(summary.id);if(product==null)return;
    if("variants".equals(product.priceMode)){chooseVariant(product);return;}
    if("manual".equals(product.priceMode)||("fixed".equals(product.priceMode)&&product.price<=0)){askPrice(product);return;}
    // Fixed-price item: go straight to the quantity pop-up.
    askQuantity(product,null,product.price);
  }
  // Manage pop-up shown when tapping an item that is already "In cart".
  //  - Variant item: one row per size showing its current cart quantity with − / +.
  //    Sizes at 0 can be increased to add them; the current qty for each size is
  //    always shown. Only the item's real sizes appear.
  //  - Plain item: a single row with the current quantity and − / +.
  // Every change writes to the DB and refreshes both the pop-up rows and the grid
  // so the item quantity, sizes and cart totals stay in sync.
  private void manageItem(PosDatabase.ProductInfo summary){
    PosDatabase.ProductInfo product=db.product(summary.id);
    if(product==null)return;
    boolean hasSizes="variants".equals(product.priceMode)&&product.variants!=null&&!product.variants.isEmpty();
    LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);container.setPadding(dp(20),dp(6),dp(20),dp(4));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(product.name).setView(container).setPositiveButton("Done",null).create();
    Runnable rebuild=new Runnable(){ @Override public void run(){
      container.removeAllViews();
      if(hasSizes){
        for(PosDatabase.VariantInfo variant:product.variants){
          double qty=db.orderLineQuantity(orderId,product.id,variant.name);
          container.addView(manageRow(product,variant,variant.price,variant.name,qty,this),top(8));
        }
      }else{
        double price=("manual".equals(product.priceMode)||product.price<=0)?db.orderLineUnitPrice(orderId,product.id,""):product.price;
        double qty=db.orderLineQuantity(orderId,product.id,"");
        container.addView(manageRow(product,null,price,"",qty,this),top(8));
      }
    }};
    rebuild.run();
    dialog.show();
  }

  // A single size/plain row inside the manage pop-up: [label + qty] and − / +.
  private View manageRow(PosDatabase.ProductInfo product,PosDatabase.VariantInfo variant,double unitPrice,String variantName,double qty,Runnable rebuild){
    LinearLayout rowView=row();rowView.setGravity(Gravity.CENTER_VERTICAL);rowView.setPadding(dp(14),dp(10),dp(12),dp(10));
    rowView.setBackground(shape(qty>0?Color.rgb(229,247,249):Color.WHITE,10,1,qty>0?teal:line));
    LinearLayout copy=column();
    copy.addView(text(variantName.isEmpty()?"Quantity":variantName,15,ink,true));
    copy.addView(text("Rs. "+money(unitPrice)+(qty>0?"  •  "+formatQty(qty)+" in cart":""),12,qty>0?teal:muted,true),top(2));
    rowView.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
    LinearLayout controls=row();controls.setGravity(Gravity.CENTER_VERTICAL);
    controls.setBackground(shape(qty>0?teal:Color.rgb(240,249,250),8,1,qty>0?teal:Color.rgb(190,224,228)));
    controls.setPadding(dp(2),dp(2),dp(2),dp(2));
    int fg=qty>0?Color.WHITE:teal;
    Button minus=compactButton("−",qty>0?Color.WHITE:Color.rgb(240,249,250),fg);
    minus.setEnabled(qty>0);
    minus.setOnClickListener(v->{double now=db.orderLineQuantity(orderId,product.id,variantName);db.setOrderItemQuantity(orderId,product,variant,unitPrice,Math.max(0,now-1));CloudSyncManager.syncAsync(this);refreshCart();rebuild.run();});
    TextView count=text(formatQty(qty),15,fg,true);count.setGravity(Gravity.CENTER);
    Button plus=compactButton("+",qty>0?Color.WHITE:teal,qty>0?teal:Color.WHITE);
    plus.setOnClickListener(v->{double now=db.orderLineQuantity(orderId,product.id,variantName);db.setOrderItemQuantity(orderId,product,variant,unitPrice,now+1);CloudSyncManager.syncAsync(this);refreshCart();rebuild.run();});
    controls.addView(minus,new LinearLayout.LayoutParams(dp(40),dp(40)));
    controls.addView(count,new LinearLayout.LayoutParams(dp(44),dp(40)));
    controls.addView(plus,new LinearLayout.LayoutParams(dp(40),dp(40)));
    rowView.addView(controls);
    return rowView;
  }

  private void chooseVariant(PosDatabase.ProductInfo product){
    if(product.variants.isEmpty()){Toast.makeText(this,"No sizes configured for this item",Toast.LENGTH_SHORT).show();return;}
    // Size picker pop-up: item name as title, each size shown as a tappable row
    // with its price. Choosing a size opens the quantity pop-up for that size.
    LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);container.setPadding(dp(20),dp(8),dp(20),dp(4));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Choose size · "+product.name).setView(container).setNegativeButton("Cancel",null).create();
    for(PosDatabase.VariantInfo variant:product.variants){
      LinearLayout rowView=row();rowView.setGravity(Gravity.CENTER_VERTICAL);rowView.setPadding(dp(14),dp(13),dp(14),dp(13));
      rowView.setBackground(shape(Color.WHITE,10,1,line));
      rowView.addView(text(variant.name,16,ink,true),new LinearLayout.LayoutParams(0,-2,1));
      rowView.addView(text("Rs. "+money(variant.price),15,teal,true));
      rowView.setOnClickListener(v->{dialog.dismiss();askQuantity(product,variant,variant.price);});
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);
      container.addView(rowView,lp);
    }
    dialog.show();
  }
  private void askPrice(PosDatabase.ProductInfo product){EditText amount=FormControls.input(this,"0.00",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);LinearLayout form=FormControls.dialogForm(this);form.addView(FormControls.field(this,"Selling price (Rs.)",amount));new AlertDialog.Builder(this).setTitle(product.name).setMessage("Enter the price for this order").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Next",(d,w)->{double value=parse(amount);if(value<=0){Toast.makeText(this,"Enter a valid price",Toast.LENGTH_SHORT).show();return;}askQuantity(product,null,value);}).show();}

  // Quantity pop-up: a clean stepper (− qty +) plus a direct entry field. When the
  // item/size is already in the cart it opens showing the ACTUAL current quantity,
  // and confirming SETS the quantity (not adds to it).
  private void askQuantity(PosDatabase.ProductInfo product,PosDatabase.VariantInfo variant,double unitPrice){
    double current=db.orderLineQuantity(orderId,product.id,variant==null?"":variant.name);
    final double[] qty={current>0?current:1};
    boolean editing=current>0;
    LinearLayout form=FormControls.dialogForm(this);
    String heading=product.name+(variant==null?"":"  ·  "+variant.name);
    form.addView(text(heading,16,ink,true));
    form.addView(text("Rs. "+money(unitPrice)+" each"+(editing?"  •  "+formatQty(current)+" in cart":""),13,muted,false),top(3));

    LinearLayout stepper=row();stepper.setGravity(Gravity.CENTER_VERTICAL);
    Button minus=compactButton("−",Color.WHITE,ink);
    Button plus=compactButton("+",teal,Color.WHITE);
    EditText qtyField=FormControls.input(this,"1",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    qtyField.setText(formatQty(qty[0]));qtyField.setGravity(Gravity.CENTER);
    TextView lineTotal=text("Total  Rs. "+money(MoneyMath.round(qty[0]*unitPrice)),14,teal,true);lineTotal.setGravity(Gravity.CENTER);
    Runnable sync=()->{qty[0]=Math.max(1,qty[0]);qtyField.setText(formatQty(qty[0]));qtyField.setSelection(qtyField.getText().length());lineTotal.setText("Total  Rs. "+money(MoneyMath.round(qty[0]*unitPrice)));};
    minus.setOnClickListener(v->{qty[0]=Math.max(1,qty[0]-1);sync.run();});
    plus.setOnClickListener(v->{qty[0]=qty[0]+1;sync.run();});
    qtyField.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){double v=parse(qtyField);if(v>0){qty[0]=v;lineTotal.setText("Total  Rs. "+money(MoneyMath.round(qty[0]*unitPrice)));}}});
    stepper.addView(minus,new LinearLayout.LayoutParams(dp(52),dp(52)));
    LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(0,dp(52),1);qp.leftMargin=dp(10);qp.rightMargin=dp(10);
    stepper.addView(qtyField,qp);
    stepper.addView(plus,new LinearLayout.LayoutParams(dp(52),dp(52)));
    form.addView(stepper,top(14));
    form.addView(lineTotal,top(12));

    AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(editing?"Update quantity":"Quantity").setView(form).setNegativeButton("Cancel",null)
      .setPositiveButton(editing?"Update":"Add",(d,w)->{double v=parse(qtyField);if(v<=0)v=qty[0];if(v<=0){Toast.makeText(this,"Enter a valid quantity",Toast.LENGTH_SHORT).show();return;}db.setOrderItemQuantity(orderId,product,variant,unitPrice,v);CloudSyncManager.syncAsync(this);refreshCart();Toast.makeText(this,formatQty(v)+" × "+product.name+(variant==null?"":" ("+variant.name+")"),Toast.LENGTH_SHORT).show();});
    // When editing an existing line, offer a quick Remove.
    if(editing)builder.setNeutralButton("Remove",(d,w)->{db.setOrderItemQuantity(orderId,product,variant,unitPrice,0);CloudSyncManager.syncAsync(this);refreshCart();Toast.makeText(this,"Removed from cart",Toast.LENGTH_SHORT).show();});
    builder.show();
  }
  private double minVariantPrice(PosDatabase.ProductInfo item){double min=Double.MAX_VALUE;if(item.variants!=null)for(PosDatabase.VariantInfo v:item.variants)if(v.price<min)min=v.price;return min==Double.MAX_VALUE?0:min;}
  private void openCheckout(){if(lines.isEmpty()){Toast.makeText(this,"Select at least one item",Toast.LENGTH_SHORT).show();return;}Intent intent=new Intent(this,CheckoutActivity.class);intent.putExtra("orderId",orderId);startActivity(intent);}
  private void hold(){try{db.holdOrder(orderId);Toast.makeText(this,"Order held",Toast.LENGTH_SHORT).show();finish();}catch(Exception error){showError(error);}}
  private void sendKot(){try{int number=db.sendKot(orderId);PrintDispatcher.processAsync(this);CloudSyncManager.syncAsync(this);Toast.makeText(this,"KOT #"+number+" queued",Toast.LENGTH_SHORT).show();finish();}catch(Exception error){showError(error);}}
  private void saveKot(){try{int number=db.saveKot(orderId);CloudSyncManager.syncAsync(this);Toast.makeText(this,"Added to table (KOT #"+number+")",Toast.LENGTH_SHORT).show();finish();}catch(Exception error){showError(error);}}
  // Order-level note that prints on the KOT and the bill.
  private void editOrderNote(){
    EditText input=FormControls.multiline(this,"Example: No onions, less spicy");
    input.setText(db.orderNote(orderId));input.setSelection(input.getText().length());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Order note (prints on KOT and bill)",input));
    new AlertDialog.Builder(this).setTitle("Order note").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{db.setOrderNote(orderId,input.getText().toString());CloudSyncManager.syncAsync(this);refreshCart();Toast.makeText(this,"Order note saved",Toast.LENGTH_SHORT).show();}).show();
  }
  // Delivery customer-details pop-up (opened from the customer icon). Name / phone /
  // address; saved onto the order. The icon turns green once details exist and,
  // when green, reopens the form pre-filled with the saved details.
  private void editDeliveryCustomer(){
    PosDatabase.OrderInfo info=db.orderInfo(orderId);
    EditText name=FormControls.input(this,"Customer name",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    EditText phone=FormControls.input(this,"Phone number",InputType.TYPE_CLASS_PHONE);
    EditText address=FormControls.multiline(this,"Delivery address");
    if(info!=null){name.setText(info.deliveryName);phone.setText(info.deliveryPhone);address.setText(info.deliveryAddress);}
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Customer name",name));
    form.addView(FormControls.field(this,"Phone",phone),top(12));
    form.addView(FormControls.field(this,"Address",address),top(12));
    boolean hasDetails=hasDeliveryDetails(info);
    new AlertDialog.Builder(this)
      .setTitle(hasDetails?"Customer details":"Add customer details")
      .setView(form)
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Save",(d,w)->{
        String customer=name.getText().toString().trim();
        String number=phone.getText().toString().trim();
        if(customer.isEmpty()&&number.isEmpty()){Toast.makeText(this,"Enter a name or phone",Toast.LENGTH_SHORT).show();return;}
        db.setDeliveryDetails(orderId,customer,number,address.getText().toString());
        CloudSyncManager.syncAsync(this);
        refreshCustomerButton();
        Toast.makeText(this,"Customer details saved",Toast.LENGTH_SHORT).show();
      }).show();
  }
  private boolean hasDeliveryDetails(PosDatabase.OrderInfo info){
    return info!=null&&(!info.deliveryName.isEmpty()||!info.deliveryPhone.isEmpty()||!info.deliveryAddress.isEmpty());
  }
  // Recolor the customer icon: green with a check when details are saved, neutral otherwise.
  private void refreshCustomerButton(){
    if(customerButton==null)return;
    boolean has=hasDeliveryDetails(db.orderInfo(orderId));
    if(has){customerButton.setText("\uD83D\uDC64\u2713");customerButton.setTextColor(Color.WHITE);customerButton.setBackground(shape(Color.rgb(22,163,74),9,1,Color.rgb(22,163,74)));}
    else{customerButton.setText("\uD83D\uDC64");customerButton.setTextColor(ink);customerButton.setBackground(shape(Color.rgb(241,245,249),9,1,line));}
  }
  // Per-item note (long-press an item in the cart). Prints under the item on the KOT.
  private void editLineNote(PosDatabase.OrderLine lineItem){
    EditText input=FormControls.multiline(this,"Example: extra cheese");
    input.setText(lineItem.note);input.setSelection(input.getText().length());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Note for "+lineItem.itemName,input));
    new AlertDialog.Builder(this).setTitle("Item note").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{db.setOrderLineNote(orderId,lineItem.id,input.getText().toString());CloudSyncManager.syncAsync(this);refreshCart();}).show();
  }
  private void showError(Throwable error){Toast.makeText(this,error.getMessage()==null?"Could not update order":error.getMessage(),Toast.LENGTH_LONG).show();}

  private final class ItemAdapter extends BaseAdapter {
    private boolean categoriesVisible(){return categoryFirst&&categoryLanding&&(search==null||search.getText().toString().trim().isEmpty());}
    public int getCount(){return categoriesVisible()?menuCategories.size():products.size();}
    public Object getItem(int position){return categoriesVisible()?menuCategories.get(position):products.get(position);}
    public long getItemId(int position){return position;}

    public View getView(int position,View old,ViewGroup parent){
      if(categoriesVisible())return categoryCard(menuCategories.get(position));
      PosDatabase.ProductInfo item=products.get(position);
      List<PosDatabase.OrderLine> activeLines=selected.get(item.id);
      boolean active=activeLines!=null&&!activeLines.isEmpty();
      double activeQty=0;if(activeLines!=null)for(PosDatabase.OrderLine l:activeLines)activeQty+=l.quantity;

      // Uniform card: every tile is the SAME fixed height regardless of item type,
      // sizes, or whether it's in the cart. Selecting an item / size / quantity all
      // happens in a pop-up, so nothing stretches a card. Structure per card:
      //   [image] [name (2 lines)] [price] ......... [+ Add  or  In cart: N]
      int cardHeight=itemColumns==3?dp(168):dp(150);
      LinearLayout card=column();
      card.setMinimumHeight(cardHeight);
      card.setPadding(dp(itemColumns==3?8:10),dp(itemColumns==3?8:10),dp(itemColumns==3?8:10),dp(itemColumns==3?8:10));
      card.setBackground(shape(active?Color.rgb(229,247,249):Color.WHITE,10,active?2:1,active?teal:line));

      ImageView image=new ImageView(OrderActivity.this);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackground(shape(Color.rgb(241,245,249),8,0,line));
      image.setClipToOutline(true);
      ItemImageLoader.load(OrderActivity.this,image,item.imageUri,dp(180));

      boolean hasSizes="variants".equals(item.priceMode)&&item.variants!=null&&!item.variants.isEmpty();
      TextView itemName=text(item.name,itemColumns==3?13:15,ink,true);
      itemName.setMaxLines(2);itemName.setMinLines(2);
      itemName.setEllipsize(android.text.TextUtils.TruncateAt.END);
      String price=hasSizes?"From Rs. "+money(minVariantPrice(item)):"manual".equals(item.priceMode)||item.price<=0?"Enter price":"Rs. "+money(item.price);
      TextView priceView=text(price,itemColumns==3?11:13,active?teal:ink,true);
      priceView.setSingleLine(true);

      if(itemColumns==2){
        LinearLayout identity=row();identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(image,new LinearLayout.LayoutParams(dp(60),dp(60)));
        LinearLayout copy=column();copy.setPadding(dp(10),0,0,0);copy.addView(itemName);copy.addView(priceView,top(4));
        identity.addView(copy,new LinearLayout.LayoutParams(0,-2,1));card.addView(identity);
      }else{
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(-1,dp(64));imageParams.bottomMargin=dp(6);card.addView(image,imageParams);card.addView(itemName);card.addView(priceView,top(3));
      }

      // Spacer pushes the action footer to the bottom so footers align across cards.
      View spacer=new View(OrderActivity.this);card.addView(spacer,new LinearLayout.LayoutParams(-1,0,1));

      // Footer is a single pill on every card so all cards stay the same size:
      //  - not in cart -> "+ Add"
      //  - in cart      -> "In cart: N" (tapping opens the manage pop-up)
      TextView action=text(active?"In cart: "+formatQty(activeQty):"+  Add",12,active?Color.WHITE:teal,true);
      action.setGravity(Gravity.CENTER);action.setPadding(0,dp(9),0,dp(9));
      action.setBackground(shape(active?teal:Color.rgb(240,249,250),8,1,active?teal:Color.rgb(190,224,228)));
      card.addView(action,top(8));
      // Tap the whole card: not in cart -> add flow; in cart -> manage quantities.
      card.setOnClickListener(v->{if(active)manageItem(item);else choose(item);});
      return card;
    }

    private View categoryCard(PosDatabase.CategoryInfo category){LinearLayout card=column();card.setGravity(Gravity.CENTER);card.setPadding(dp(12),dp(14),dp(12),dp(14));card.setMinimumHeight(dp(itemColumns==3?96:108));card.setBackground(shape(Color.WHITE,10,1,line));TextView name=text(category.name,itemColumns==3?15:17,ink,true);name.setGravity(Gravity.CENTER);name.setMaxLines(2);card.addView(name,new LinearLayout.LayoutParams(-1,-2));TextView detail=text("View items  ›",12,teal,true);detail.setGravity(Gravity.CENTER);card.addView(detail,top(6));card.setContentDescription("Open "+category.name+" category");card.setOnClickListener(v->{categoryId=category.id;categoryLanding=false;refreshCategories();loadMenu();});return card;}
  }

  // Find the cart line for a given size (variant) of an item, if it's already added.
  private PosDatabase.OrderLine activeVariant(List<PosDatabase.OrderLine> lines,String variantName){
    if(lines==null)return null;
    for(PosDatabase.OrderLine line:lines)if(variantName.equals(line.variantName))return line;
    return null;
  }

  private View inlineQuantity(PosDatabase.OrderLine lineItem){
    LinearLayout controls=row();
    controls.setGravity(Gravity.CENTER_VERTICAL);
    Button minus=compactButton("−",Color.WHITE,ink);
    minus.setContentDescription("Decrease "+lineItem.itemName);
    minus.setOnClickListener(v->{db.changeOrderLineQuantity(orderId,lineItem.id,-1);refreshCart();});
    int controlSize=itemColumns==3?32:38;
    controls.addView(minus,new LinearLayout.LayoutParams(dp(controlSize),dp(controlSize)));
    TextView quantity=text(formatQty(lineItem.quantity),15,ink,true);
    quantity.setGravity(Gravity.CENTER);
    quantity.setBackground(shape(Color.WHITE,8,1,line));
    controls.addView(quantity,new LinearLayout.LayoutParams(0,dp(controlSize),1));
    Button plus=compactButton("+",teal,Color.WHITE);
    plus.setContentDescription("Increase "+lineItem.itemName);
    plus.setOnClickListener(v->{db.changeOrderLineQuantity(orderId,lineItem.id,1);refreshCart();});
    controls.addView(plus,new LinearLayout.LayoutParams(dp(controlSize),dp(controlSize)));
    return controls;
  }

  private Button compactButton(String label,int fill,int color){
    Button value=button(label,fill,color);
    value.setTextSize(18);
    value.setMinWidth(0);
    value.setMinimumWidth(0);
    value.setMinHeight(0);
    value.setMinimumHeight(0);
    value.setPadding(0,0,0,0);
    return value;
  }
  private final class LayoutIconDrawable extends Drawable {private final int count;private final boolean groups;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);LayoutIconDrawable(int count,boolean groups){this.count=count;this.groups=groups;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);}public void draw(Canvas canvas){paint.setColor(getColorFilter()==null?ink:Color.WHITE);android.graphics.Rect bounds=getBounds();float left=bounds.left+dp(2),top=bounds.top+dp(2),right=bounds.right-dp(2),bottom=bounds.bottom-dp(2);if(groups){float step=(bottom-top)/3f;for(int i=0;i<3;i++){float y=top+step*i+step/2;canvas.drawCircle(left+dp(2),y,dp(1),paint);canvas.drawLine(left+dp(7),y,right,y,paint);}}else{int rows=2;float gap=dp(2),cellWidth=(right-left-gap*(count-1))/count,cellHeight=(bottom-top-gap)/rows;for(int row=0;row<rows;row++)for(int column=0;column<count;column++){float x=left+column*(cellWidth+gap),y=top+row*(cellHeight+gap);canvas.drawRoundRect(x,y,x+cellWidth,y+cellHeight,dp(1),dp(1),paint);}}}public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}public int getIntrinsicWidth(){return dp(22);}public int getIntrinsicHeight(){return dp(22);}}
  private double parse(EditText value){try{return Double.parseDouble(value.getText().toString().trim());}catch(Exception ignored){return 0;}}private String money(double v){return String.format(Locale.US,"%.2f",v);}private String formatQty(double v){return Math.rint(v)==v?String.valueOf((long)v):String.format(Locale.US,"%.2f",v);}
  private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}private TextView text(String s,int z,int c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(b)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(shape(fill,9,1,line));return b;}private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}private LinearLayout.LayoutParams chip(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.rightMargin=dp(8);return p;}private LinearLayout.LayoutParams top(int m){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(m);return p;}private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(46),1);}private View gap(){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(dp(7),1));return v;}private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
}
