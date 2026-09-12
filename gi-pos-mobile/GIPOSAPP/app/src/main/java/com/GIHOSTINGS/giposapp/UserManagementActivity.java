package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User Management (Settings ▸ Business operations).
 *
 * Owner creates staff members (name / phone / password), grants permission
 * categories and specific table access, and staff then sign in from their own
 * phones with those credentials. Staff are stored in the cloud (portal_staff)
 * via device-authed endpoints, so the directory syncs to every device in
 * real time.
 */
public class UserManagementActivity extends InsetActivity {
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),
    teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250),green=Color.rgb(22,163,74);

  // Permission categories offered when creating a user. Key = stored value, label = shown text.
  private static final String[][] PERMISSIONS = {
    {"take_orders", "Take orders"},
    {"kot_print", "Print KOT"},
    {"checkout", "Checkout & billing"},
    {"apply_discount", "Apply discounts"},
    {"cancel_orders", "Cancel / delete orders"},
    {"manage_tables", "Manage tables"},
    {"view_reports", "View reports"},
    {"manage_menu", "Manage menu"},
    {"manage_users", "Manage users"},
    {"settings", "Change settings"},
  };

  private PosDatabase db;
  private SecureStore.Session session;
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private LinearLayout listContainer;
  private TextView emptyLabel;
  private ProgressBar spinner;

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    db=PosDatabase.get(this);
    session=SecureStore.load(this);
    render();
    if(guardCloud()) refresh();
  }

  private boolean guardCloud(){
    if(session==null||!session.isActive()||session.deviceId.isBlank()||session.apiKey.isBlank()){
      if(emptyLabel!=null){emptyLabel.setText("Connect this device to the cloud to manage staff users.");emptyLabel.setVisibility(View.VISIBLE);}
      if(spinner!=null)spinner.setVisibility(View.GONE);
      return false;
    }
    return true;
  }

  private void render(){
    ScrollView scroll=new ScrollView(this);
    scroll.setBackgroundColor(surface);
    scroll.setFillViewport(true);
    LinearLayout root=column();
    root.setPadding(dp(18),dp(18),dp(18),dp(28));

    LinearLayout header=column();
    header.addView(text("User management",25,ink,true));
    header.addView(text("Create staff logins, set permissions and table access",13,muted,false),top(3));
    root.addView(header);

    Button create=button("+  Create new user",teal,Color.WHITE);
    create.setOnClickListener(v->{if(guardCloud())openCreateDialog();});
    root.addView(create,topHeight(18,54));

    root.addView(text("Staff members",14,muted,true),top(22));

    spinner=new ProgressBar(this);
    spinner.setVisibility(View.GONE);
    root.addView(spinner,top(14));

    emptyLabel=text("No staff yet. Tap Create new user to add one.",13,muted,false);
    emptyLabel.setVisibility(View.GONE);
    root.addView(emptyLabel,top(12));

    listContainer=column();
    root.addView(listContainer,top(10));

    scroll.addView(root);
    setContentView(scroll);
  }

  private void refresh(){
    spinner.setVisibility(View.VISIBLE);
    emptyLabel.setVisibility(View.GONE);
    // Show the locally-synced staff immediately so the list is populated even
    // before (or without) a live cloud round-trip.
    JSONArray local=db.localStaff();
    if(local.length()>0) renderList(local,-1,0);
    io.execute(()->{
      try{
        JSONObject response=CloudClient.listStaff(session);
        JSONArray staff=response.optJSONArray("staff");
        int maxUsers=response.optInt("maxUsers",-1);
        int activeCount=response.optInt("activeCount",0);
        // Keep the local mirror in step with the authoritative cloud list.
        db.applyStaffDirectory(staff);
        runOnUiThread(()->renderList(staff,maxUsers,activeCount));
      }catch(Exception error){
        runOnUiThread(()->{
          JSONArray cached=db.localStaff();
          if(cached.length()>0){
            renderList(cached,-1,0);
            Toast.makeText(this,"Showing saved staff (offline): "+message(error),Toast.LENGTH_SHORT).show();
          }else{
            spinner.setVisibility(View.GONE);
            emptyLabel.setText("Could not load staff: "+message(error));
            emptyLabel.setVisibility(View.VISIBLE);
          }
        });
      }
    });
  }

  private void renderList(JSONArray staff,int maxUsers,int activeCount){
    spinner.setVisibility(View.GONE);
    listContainer.removeAllViews();
    if(staff==null||staff.length()==0){
      emptyLabel.setText("No staff yet. Tap Create new user to add one.");
      emptyLabel.setVisibility(View.VISIBLE);
      return;
    }
    emptyLabel.setVisibility(View.GONE);
    if(maxUsers>0){
      listContainer.addView(text(activeCount+" of "+maxUsers+" user slots used",12,muted,false),top(0));
    }
    for(int i=0;i<staff.length();i++){
      JSONObject item=staff.optJSONObject(i);
      if(item==null)continue;
      listContainer.addView(staffCard(item),top(i==0?10:9));
    }
  }

  private View staffCard(JSONObject staff){
    String id=staff.optString("id");
    String name=staff.optString("name","Staff");
    String mobile=staff.optString("mobile");
    String staffCode=staff.optString("staffCode");
    boolean active=staff.optBoolean("active",true);
    JSONArray permissions=staff.optJSONArray("permissions");
    JSONArray tableAccess=staff.optJSONArray("tableAccess");

    LinearLayout card=column();
    card.setPadding(dp(16),dp(14),dp(16),dp(14));
    card.setBackground(shape(Color.WHITE,12,1,line));

    LinearLayout topRow=row();
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout titleCol=column();
    titleCol.addView(text(name,16,ink,true));
    String sub=mobile.isBlank()?(staffCode.isBlank()?"":staffCode):mobile;
    titleCol.addView(text(sub,12,muted,false),top(2));
    topRow.addView(titleCol,new LinearLayout.LayoutParams(0,-2,1));

    TextView badge=text(active?"Active":"Disabled",11,active?green:red,true);
    badge.setPadding(dp(10),dp(4),dp(10),dp(4));
    badge.setBackground(shape(active?Color.rgb(233,247,238):Color.rgb(253,235,239),20,0,0));
    topRow.addView(badge);
    card.addView(topRow);

    String permText=joinPermissionLabels(permissions);
    if(!permText.isBlank()) card.addView(text("Permissions: "+permText,12,muted,false),top(8));
    String tableText=joinArray(tableAccess);
    card.addView(text("Tables: "+(tableText.isBlank()?"All tables":tableText),12,muted,false),top(4));

    LinearLayout actions=row();
    Button toggle=button(active?"Disable":"Enable",active?Color.rgb(255,247,220):Color.rgb(229,247,249),active?ink:teal);
    toggle.setOnClickListener(v->updateStaff(id,field("active",!active)));
    actions.addView(toggle,new LinearLayout.LayoutParams(0,dp(46),1));
    actions.addView(gap());
    Button reset=button("Reset password",Color.rgb(240,244,248),ink);
    reset.setOnClickListener(v->openPasswordDialog(id,name));
    actions.addView(reset,new LinearLayout.LayoutParams(0,dp(46),1));
    actions.addView(gap());
    Button delete=button("Delete",Color.rgb(253,235,239),red);
    delete.setOnClickListener(v->confirmDelete(id,name));
    actions.addView(delete,new LinearLayout.LayoutParams(0,dp(46),1));
    try{card.addView(actions,top(12));}catch(Exception ignored){}
    return card;
  }

  // ---- Create dialog -----------------------------------------------------------

  private void openCreateDialog(){
    ScrollView scroll=new ScrollView(this);
    LinearLayout form=FormControls.dialogForm(this);

    EditText name=FormControls.input(this,"Full name",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    EditText phone=FormControls.input(this,"Phone number",InputType.TYPE_CLASS_PHONE);
    EditText password=FormControls.input(this,"Password (min 4 chars)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setTransformationMethod(PasswordTransformationMethod.getInstance());

    form.addView(FormControls.field(this,"Name",name));
    form.addView(spacer(12));
    form.addView(FormControls.field(this,"Phone number",phone));
    form.addView(spacer(12));
    form.addView(FormControls.field(this,"Password",password));

    form.addView(spacer(16));
    form.addView(sectionLabel("Permissions"));
    Map<String,CheckBox> permBoxes=new LinkedHashMap<>();
    for(String[] perm:PERMISSIONS){
      CheckBox box=checkbox(perm[1]);
      // Sensible defaults for a floor staff member.
      if(perm[0].equals("take_orders")||perm[0].equals("kot_print"))box.setChecked(true);
      permBoxes.put(perm[0],box);
      form.addView(box,top(2));
    }

    form.addView(spacer(16));
    form.addView(sectionLabel("Table access"));
    TextView tableHint=text("Leave all unchecked to allow every table.",12,muted,false);
    form.addView(tableHint,top(2));
    List<String> tableNames=allTableNames();
    Map<String,CheckBox> tableBoxes=new LinkedHashMap<>();
    if(tableNames.isEmpty()){
      form.addView(text("No tables found on this device yet.",12,muted,false),top(6));
    }else{
      for(String tableName:tableNames){
        CheckBox box=checkbox(tableName);
        tableBoxes.put(tableName,box);
        form.addView(box,top(2));
      }
    }

    scroll.addView(form);
    AlertDialog dialog=new AlertDialog.Builder(this)
      .setTitle("Create new user")
      .setView(scroll)
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Submit",null)
      .create();
    dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      String nameValue=name.getText().toString().trim();
      String phoneValue=phone.getText().toString().trim();
      String passwordValue=password.getText().toString();
      if(nameValue.isEmpty()){name.setError("Enter a name");name.requestFocus();return;}
      if(phoneValue.isEmpty()){phone.setError("Enter a phone number");phone.requestFocus();return;}
      if(passwordValue.length()<4){password.setError("At least 4 characters");password.requestFocus();return;}

      JSONArray permissions=new JSONArray();
      for(Map.Entry<String,CheckBox> entry:permBoxes.entrySet())if(entry.getValue().isChecked())permissions.put(entry.getKey());
      JSONArray tableAccess=new JSONArray();
      for(Map.Entry<String,CheckBox> entry:tableBoxes.entrySet())if(entry.getValue().isChecked())tableAccess.put(entry.getKey());

      submitCreate(dialog,nameValue,phoneValue,passwordValue,permissions,tableAccess);
    }));
    dialog.show();
  }

  private void submitCreate(AlertDialog dialog,String name,String phone,String password,JSONArray permissions,JSONArray tableAccess){
    Button submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    submit.setEnabled(false);submit.setText("Saving…");
    io.execute(()->{
      try{
        CloudClient.createStaff(session,name,phone,password,permissions,tableAccess);
        runOnUiThread(()->{
          Toast.makeText(this,"User created",Toast.LENGTH_SHORT).show();
          dialog.dismiss();
          refresh();
        });
      }catch(Exception error){
        runOnUiThread(()->{
          submit.setEnabled(true);submit.setText("Submit");
          Toast.makeText(this,message(error),Toast.LENGTH_LONG).show();
        });
      }
    });
  }

  private void openPasswordDialog(String staffId,String name){
    EditText password=FormControls.input(this,"New password (min 4 chars)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setTransformationMethod(PasswordTransformationMethod.getInstance());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"New password for "+name,password));
    new AlertDialog.Builder(this)
      .setTitle("Reset password")
      .setView(form)
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Save",(d,w)->{
        String value=password.getText().toString();
        if(value.length()<4){Toast.makeText(this,"Password must be at least 4 characters",Toast.LENGTH_LONG).show();return;}
        updateStaff(staffId,field("password",value));
      })
      .show();
  }

  private void confirmDelete(String staffId,String name){
    new AlertDialog.Builder(this)
      .setTitle("Delete "+name+"?")
      .setMessage("This staff member will no longer be able to sign in.")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Delete",(d,w)->io.execute(()->{
        try{
          CloudClient.deleteStaff(session,staffId);
          runOnUiThread(()->{Toast.makeText(this,"User deleted",Toast.LENGTH_SHORT).show();refresh();});
        }catch(Exception error){
          runOnUiThread(()->Toast.makeText(this,message(error),Toast.LENGTH_LONG).show());
        }
      }))
      .show();
  }

  private void updateStaff(String staffId,JSONObject fields){
    io.execute(()->{
      try{
        CloudClient.updateStaff(session,staffId,fields);
        runOnUiThread(this::refresh);
      }catch(Exception error){
        runOnUiThread(()->Toast.makeText(this,message(error),Toast.LENGTH_LONG).show());
      }
    });
  }

  // ---- Helpers -----------------------------------------------------------------

  private List<String> allTableNames(){
    List<String> names=new ArrayList<>();
    try{
      for(PosDatabase.TableInfo table:db.tables(null)){
        if(table.name!=null&&!table.name.isBlank()&&!names.contains(table.name))names.add(table.name);
      }
    }catch(Exception ignored){}
    return names;
  }

  private String joinPermissionLabels(JSONArray permissions){
    if(permissions==null||permissions.length()==0)return "";
    Map<String,String> labels=new LinkedHashMap<>();
    for(String[] perm:PERMISSIONS)labels.put(perm[0],perm[1]);
    StringBuilder builder=new StringBuilder();
    for(int i=0;i<permissions.length();i++){
      String key=permissions.optString(i);
      String label=labels.getOrDefault(key,key);
      if(builder.length()>0)builder.append(", ");
      builder.append(label);
    }
    return builder.toString();
  }

  private String joinArray(JSONArray value){
    if(value==null||value.length()==0)return "";
    StringBuilder builder=new StringBuilder();
    for(int i=0;i<value.length();i++){
      if(builder.length()>0)builder.append(", ");
      builder.append(value.optString(i));
    }
    return builder.toString();
  }

  private String message(Exception error){
    String text=error.getMessage();
    return text==null||text.isBlank()?"Something went wrong":text;
  }

  // Small helper to build a one-key JSON body without the checked JSONException
  // surfacing at every call site.
  private JSONObject field(String key,Object value){
    JSONObject object=new JSONObject();
    try{object.put(key,value);}catch(Exception ignored){}
    return object;
  }

  private CheckBox checkbox(String label){
    CheckBox box=new CheckBox(this);
    box.setText(label);
    box.setTextSize(14);
    box.setTextColor(ink);
    return box;
  }

  private TextView sectionLabel(String value){
    TextView view=text(value,13,ink,true);
    return view;
  }

  private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}
  private LinearLayout row(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);return value;}
  private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);return view;}
  private Button button(String value,int fill,int color){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(color);view.setTypeface(Typeface.DEFAULT_BOLD);view.setBackground(shape(fill,9,1,line));return view;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}
  private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}
  private LinearLayout.LayoutParams topHeight(int margin,int height){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,dp(height));value.topMargin=dp(margin);return value;}
  private View gap(){View value=new View(this);value.setLayoutParams(new LinearLayout.LayoutParams(dp(8),1));return value;}
  private View spacer(int height){View value=new View(this);value.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(height)));return value;}
  private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}

  @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
