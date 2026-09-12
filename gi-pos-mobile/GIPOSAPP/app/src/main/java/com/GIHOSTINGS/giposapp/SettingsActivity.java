package com.GIHOSTINGS.giposapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class SettingsActivity extends InsetActivity {
  private static final int BACKUP=501,RESTORE=502;
  private final int ink=Color.rgb(18,32,51),red=Color.rgb(199,22,55),muted=Color.rgb(99,115,138),teal=Color.rgb(8,127,140),line=Color.rgb(217,226,236),surface=Color.rgb(244,247,250);
  private PosDatabase db;

  @Override public void onCreate(Bundle state){super.onCreate(state);db=PosDatabase.get(this);render();}

  private void render(){
    SecureStore.Session session=SecureStore.load(this);
    LinearLayout root=column();
    root.setPadding(dp(18),dp(18),dp(18),dp(28));
    root.setBackgroundColor(surface);
    LinearLayout header=row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    ImageView logo=new ImageView(this);
    logo.setImageResource(R.drawable.app_logo);
    logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
    header.addView(logo,new LinearLayout.LayoutParams(dp(48),dp(48)));
    LinearLayout heading=column();
    heading.setPadding(dp(12),0,0,0);
    heading.addView(text("Settings",25,ink,true));
    heading.addView(text("Business, operations and this device",13,muted,false),top(3));
    header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    root.addView(header);

    root.addView(text("Business operations",14,muted,true),top(22));
    root.addView(action("Business & billing","Receipt details and payment methods",BusinessSettingsActivity.class),top(9));
    root.addView(action("Payment gateways","Create custom payment methods for checkout and reports",PaymentGatewayActivity.class),top(9));
    root.addView(action("GST management","Enable GST, set rate, tax mode and bill display",GstSettingsActivity.class),top(9));
    root.addView(action("Menu management","Categories, products, variants, GST and availability",MenuActivity.class),top(9));
    root.addView(tableLayoutAction(),top(9));
    root.addView(action("Customer directory","Customer profiles, bills and dues",CustomerActivity.class),top(9));
    root.addView(action("User management","Create staff logins, permissions and table access",UserManagementActivity.class),top(9));
    root.addView(action("Printer setup","Bluetooth/network printers and POS58/POS80",PrinterActivity.class),top(9));

    root.addView(text("Cloud sync",14,muted,true),top(22));
    boolean cloudCapable=session!=null&&session.hasFeature("cloudSync");
    boolean connected=session!=null&&session.isActive()&&cloudCapable;
    LinearLayout cloud=panel("Cloud Connection");
    LinearLayout statusRow=row();
    statusRow.setGravity(Gravity.CENTER_VERTICAL);
    View dot=new View(this);
    int statusColor=connected?Color.rgb(22,163,74):red;
    dot.setBackground(shape(statusColor,7,0,0));
    statusRow.addView(dot,new LinearLayout.LayoutParams(dp(10),dp(10)));
    TextView statusLabel=text(connected?"Connected":(session==null?"Not connected":"Connection inactive"),14,statusColor,true);
    LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-2,-2);slp.leftMargin=dp(8);statusLabel.setLayoutParams(slp);
    statusRow.addView(statusLabel);
    cloud.addView(statusRow,top(8));
    cloud.addView(pair("Connection name",SecureStore.cloudConnectionName(this)),top(11));
    String host=SecureStore.cloudServerHost(this);
    cloud.addView(pair("Link",host.isBlank()?"Not set":host),top(9));
    cloud.addView(pair("Last sync",relativeSync(CloudSyncManager.lastSync(this))),top(9));
    cloud.addView(pair("Pending sync",String.valueOf(db.pendingSyncCount())),top(9));
    String syncError=CloudSyncManager.lastError(this);
    if(syncError!=null&&!syncError.isBlank()) cloud.addView(text("Last error: "+syncError,12,red,false),top(8));
    if(!cloudCapable&&session!=null) cloud.addView(text("This plan does not include cloud sync.",12,muted,false),top(8));

    LinearLayout cloudActions=row();
    Button syncNow=button("Sync now",Color.rgb(229,247,249),teal);
    syncNow.setEnabled(connected);
    syncNow.setOnClickListener(v->runManualSync(syncNow));
    cloudActions.addView(syncNow,new LinearLayout.LayoutParams(0,dp(46),1));
    cloudActions.addView(gap());
    Button renameCloud=button("Rename",Color.WHITE,ink);
    renameCloud.setOnClickListener(v->showCloudNameEditor());
    cloudActions.addView(renameCloud,new LinearLayout.LayoutParams(0,dp(46),1));
    cloud.addView(cloudActions,top(13));
    Button changeUrl=button("Change Cloud URL",Color.WHITE,ink);
    changeUrl.setEnabled(session!=null);
    changeUrl.setOnClickListener(v->showChangeCloudUrl());
    cloud.addView(changeUrl,top(9));
    Button terminate=button("Terminate connection",Color.rgb(255,241,243),red);
    terminate.setOnClickListener(v->confirmTerminateCloud());
    cloud.addView(terminate,top(9));
    root.addView(cloud,top(10));

    LinearLayout localServer=panel("Local Main PC");
    localServer.addView(text("Find the desktop POS by its saved connection name. Discovery follows the PC even when its Wi-Fi IP changes.",13,muted,false),top(6));
    localServer.addView(pair("Connection name",SecureStore.localServerName(this)),top(13));
    localServer.addView(pair("Last endpoint",SecureStore.localServerEndpoint(this).isBlank()?"Not discovered":SecureStore.localServerEndpoint(this)),top(9));
    LinearLayout localActions=row();
    Button findServer=button("Find Main PC",Color.rgb(229,247,249),teal);
    findServer.setOnClickListener(v->findLocalServer(findServer));
    localActions.addView(findServer,new LinearLayout.LayoutParams(0,dp(46),1));
    localActions.addView(gap());
    Button renameServer=button("Change name",Color.WHITE,ink);
    renameServer.setOnClickListener(v->showLocalServerNameEditor());
    localActions.addView(renameServer,new LinearLayout.LayoutParams(0,dp(46),1));
    localServer.addView(localActions,top(13));
    root.addView(localServer,top(18));

    LinearLayout license=panel("License & account");
    license.addView(pair("Business",session==null?"Not activated":session.businessName));
    license.addView(pair("Plan",session==null?"-":session.plan),top(9));
    license.addView(pair("Status",session==null?"Inactive":session.status),top(9));
    license.addView(pair("Activated",session==null?"-":date(session.activatedAt)),top(9));
    license.addView(pair("Expires",session==null?"-":date(session.expiresAt)),top(9));
    license.addView(pair("Pending sync",String.valueOf(db.pendingSyncCount())),top(9));
    root.addView(license,top(20));

    LinearLayout security=panel("Optional app PIN");
    boolean pinEnabled=SecureStore.hasPin(this);
    security.addView(text(pinEnabled?"PIN protection is enabled":"PIN protection is off",14,pinEnabled?teal:ink,true),top(6));
    security.addView(text("When enabled, the PIN unlocks this phone after a fresh app launch. Your account password is still used for activation.",13,muted,false),top(6));
    LinearLayout pinActions=row();
    Button configurePin=button(pinEnabled?"Change PIN":"Set PIN",Color.rgb(229,247,249),teal);
    configurePin.setOnClickListener(v->showPinEditor(pinEnabled));
    pinActions.addView(configurePin,new LinearLayout.LayoutParams(0,dp(46),1));
    if(pinEnabled){pinActions.addView(gap());Button removePin=button("Remove PIN",Color.rgb(255,241,243),red);removePin.setOnClickListener(v->showRemovePin());pinActions.addView(removePin,new LinearLayout.LayoutParams(0,dp(46),1));}
    security.addView(pinActions,top(13));
    root.addView(security,top(18));

    LinearLayout data=panel("Data safety");
    data.addView(text("Create a manual SQLite backup before major menu or device changes. Restoring replaces the current local data.",13,muted,false),top(6));
    LinearLayout actions=row();
    Button backup=button("Create backup",Color.rgb(229,247,249),teal);
    backup.setOnClickListener(v->backup());
    actions.addView(backup,new LinearLayout.LayoutParams(0,dp(46),1));
    actions.addView(gap());
    Button restore=button("Restore backup",Color.WHITE,ink);
    restore.setOnClickListener(v->confirmRestore());
    actions.addView(restore,new LinearLayout.LayoutParams(0,dp(46),1));
    data.addView(actions,top(14));
    root.addView(data,top(18));

    LinearLayout account=panel("Account access");
    account.addView(text("Log out of this phone without deleting bills, menu, customers, or other local data.",13,muted,false),top(6));
    Button logout=button("Log out",Color.rgb(255,241,243),red);
    logout.setOnClickListener(v->confirmLogout());
    account.addView(logout,top(13));
    root.addView(account,top(18));

    TextView version=text("GI POS Mobile - Version "+appVersion(),12,muted,false);
    version.setGravity(Gravity.CENTER);
    root.addView(version,top(22));
    ScrollView scroll=new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(root);
    setContentView(MobileBottomNavigation.wrap(this,scroll,MobileBottomNavigation.Destination.SETTINGS));
  }

  private void confirmLogout(){
    new AlertDialog.Builder(this)
      .setTitle("Log out?")
      .setMessage("You will return to sign in. All locally saved restaurant data will remain on this phone.")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Log out",(dialog,which)->logout())
      .show();
  }

  private void findLocalServer(Button button){
    button.setEnabled(false);
    button.setText("Searching...");
    LocalServerDiscovery.find(this,SecureStore.localServerName(this),new LocalServerDiscovery.Callback(){
      @Override public void onFound(String name,String endpoint){
        if(isFinishing())return;
        SecureStore.saveLocalServer(SettingsActivity.this,name,endpoint);
        Toast.makeText(SettingsActivity.this,"Main PC found",Toast.LENGTH_SHORT).show();
        render();
      }
      @Override public void onError(String message){
        if(isFinishing())return;
        button.setEnabled(true);
        button.setText("Find Main PC");
        Toast.makeText(SettingsActivity.this,message,Toast.LENGTH_LONG).show();
      }
    });
  }

  private void showLocalServerNameEditor(){
    EditText name=FormControls.input(this,"GI POS Main PC",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    name.setText(SecureStore.localServerName(this));
    name.setSelection(name.getText().length());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Connection name",name));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Main PC connection name").setMessage("Enter the same name configured on the desktop Local POS Server page.").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String value=name.getText().toString().trim();if(value.isBlank()){name.setError("Enter a connection name");name.requestFocus();return;}SecureStore.setLocalServerName(this,value);dialog.dismiss();render();}));
    dialog.show();
  }

  private void logout(){
    // Stop the real-time channel and securely mark this device signed out.
    RealtimeSync.stop();
    CloudSyncManager.resetSignOutGuard();
    SecureStore.signOut(this);
    Intent intent=new Intent(this,MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity(intent);
    finish();
  }

  private void runManualSync(Button button){
    button.setEnabled(false);
    button.setText("Syncing...");
    CloudSyncManager.syncNow(this,(success,message)->{
      if(isFinishing())return;
      Toast.makeText(this,message,success?Toast.LENGTH_SHORT:Toast.LENGTH_LONG).show();
      render();
    });
  }

  private void showCloudNameEditor(){
    EditText name=FormControls.input(this,"GI POS Cloud",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    name.setText(SecureStore.cloudConnectionName(this));
    name.setSelection(name.getText().length());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Connection name",name));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rename cloud connection").setMessage("A friendly name for this cloud connection on this device.").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
    dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String value=name.getText().toString().trim();if(value.isBlank()){name.setError("Enter a connection name");name.requestFocus();return;}SecureStore.setCloudConnectionName(this,value);pushDeviceName(value);dialog.dismiss();render();}));
    dialog.show();
  }

  // Push the friendly connection name to the cloud so it shows on the portal's
  // Signed Devices page. Best-effort and off the UI thread; local name is already
  // saved regardless of network.
  private void pushDeviceName(String value){
    SecureStore.Session session=SecureStore.load(this);
    if(session==null||!session.isActive()||session.deviceId.isBlank()||session.apiKey.isBlank())return;
    new Thread(()->{try{CloudClient.renameDevice(session,value);}catch(Exception ignored){}}).start();
  }

  // Let the user enter/change the cloud server URL and Connect to it. Verifies
  // the device works against the new URL before switching, then re-syncs.
  private void showChangeCloudUrl(){
    SecureStore.Session session=SecureStore.load(this);
    if(session==null){Toast.makeText(this,"Activate the app first.",Toast.LENGTH_SHORT).show();return;}
    EditText url=FormControls.input(this,"https://your-cloud-server.com",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    url.setText(session.serverUrl);
    url.setSelection(url.getText().length());
    LinearLayout form=FormControls.dialogForm(this);
    form.addView(FormControls.field(this,"Cloud server URL",url));
    TextView status=text("",13,muted,false);status.setPadding(0,dp(8),0,0);form.addView(status);
    AlertDialog dialog=new AlertDialog.Builder(this)
      .setTitle("Change Cloud URL")
      .setMessage("Enter the cloud server address, then Connect. Your login and device stay the same.")
      .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Connect",null).create();
    dialog.setOnShowListener(v->{
      Button connect=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      connect.setOnClickListener(button->{
        final String entered=url.getText().toString().trim();
        if(entered.isBlank()){url.setError("Enter a server URL");url.requestFocus();return;}
        String normalized;
        try{normalized=CloudClient.normalizeServerUrl(entered);}
        catch(Exception error){url.setError(error.getMessage()==null?"Invalid URL":error.getMessage());url.requestFocus();return;}
        connect.setEnabled(false);status.setTextColor(muted);status.setText("Connecting\u2026");
        final String target=normalized;
        new Thread(()->{
          try{
            CloudClient.verifyDeviceUrl(session,target);
            SecureStore.updateServerUrl(this,target);
            CloudSyncManager.resetPullCursor(this);
            runOnUiThread(()->{Toast.makeText(this,"Connected to new cloud URL",Toast.LENGTH_SHORT).show();dialog.dismiss();CloudSyncManager.syncAsync(this);render();});
          }catch(CloudClient.DeviceAuthException authError){
            runOnUiThread(()->{connect.setEnabled(true);status.setTextColor(red);status.setText("This device isn't registered on that server.");});
          }catch(Exception error){
            runOnUiThread(()->{connect.setEnabled(true);status.setTextColor(red);status.setText("Could not reach that server. Check the URL.");});
          }
        }).start();
      });
    });
    dialog.show();
  }

  private void confirmTerminateCloud(){
    new AlertDialog.Builder(this)
      .setTitle("Terminate cloud connection?")
      .setMessage("This device will disconnect from the cloud and return to sign in. Local data stays on this phone, and other signed-in devices are not affected.")
      .setNegativeButton("Cancel",null)
      .setPositiveButton("Terminate",(dialog,which)->logout())
      .show();
  }

  private String relativeSync(String value){
    if(value==null||value.isBlank())return "Not synced yet";
    try{
      long then=Instant.parse(value).toEpochMilli();
      long mins=Math.max(0,(System.currentTimeMillis()-then)/60000);
      if(mins<1)return "Just now";
      if(mins<60)return mins+" min ago";
      long hrs=mins/60;
      if(hrs<24)return hrs+" hr ago";
      return date(value);
    }catch(Exception ignored){return value;}
  }

  private void showPinEditor(boolean changing){
    LinearLayout form=FormControls.dialogForm(this);
    EditText current=pinInput("Enter current PIN"),next=pinInput("Enter new PIN"),confirm=pinInput("Repeat new PIN");
    if(changing)form.addView(FormControls.field(this,"Current PIN",current));
    form.addView(FormControls.field(this,"New PIN",next),changing?top(13):new LinearLayout.LayoutParams(-1,-2));
    form.addView(FormControls.field(this,"Confirm PIN",confirm),top(13));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(changing?"Change optional PIN":"Set optional PIN").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
    dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
      String newPin=next.getText().toString(),confirmation=confirm.getText().toString();
      if(changing&&!SecureStore.verifyPin(this,current.getText().toString())){current.setError("Current PIN is incorrect");current.requestFocus();return;}
      if(!newPin.matches("\\d{4,8}")){next.setError("Use 4 to 8 digits");next.requestFocus();return;}
      if(!newPin.equals(confirmation)){confirm.setError("PINs do not match");confirm.requestFocus();return;}
      try{SecureStore.setPin(this,newPin);Toast.makeText(this,"Optional PIN enabled",Toast.LENGTH_SHORT).show();dialog.dismiss();render();}catch(Exception error){Toast.makeText(this,"Could not save PIN",Toast.LENGTH_LONG).show();}
    });FormControls.submitOnDone(confirm,()->dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());});
    dialog.show();
  }

  private void showRemovePin(){
    EditText current=pinInput("Enter current PIN");LinearLayout form=FormControls.dialogForm(this);form.addView(FormControls.field(this,"Current PIN",current));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Remove optional PIN?").setMessage("The app will open directly while this account remains activated.").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Remove",null).create();
    dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{if(!SecureStore.verifyPin(this,current.getText().toString())){current.setError("Current PIN is incorrect");current.requestFocus();return;}SecureStore.clearPin(this);Toast.makeText(this,"Optional PIN removed",Toast.LENGTH_SHORT).show();dialog.dismiss();render();});FormControls.submitOnDone(current,()->dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());});
    dialog.show();
  }

  private EditText pinInput(String hint){EditText value=FormControls.input(this,hint,InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);value.setTransformationMethod(PasswordTransformationMethod.getInstance());return value;}

  private View action(String title,String detail,Class<?> target){
    LinearLayout card=row();
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(dp(16),dp(14),dp(12),dp(14));
    card.setBackground(shape(Color.WHITE,12,1,line));
    LinearLayout copy=column();
    copy.addView(text(title,16,ink,true));
    copy.addView(text(detail,12,muted,false),top(3));
    card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
    card.addView(text("›",24,muted,true));
    card.setOnClickListener(v->startActivity(new Intent(this,target)));
    return card;
  }

  private View tableLayoutAction(){
    View card=action("Table layout","Floors, tables and seating",TableActivity.class);
    card.setOnClickListener(v->{Intent intent=new Intent(this,TableActivity.class);intent.putExtra("layoutOnly",true);startActivity(intent);});
    return card;
  }

  private void backup(){Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/x-sqlite3");intent.putExtra(Intent.EXTRA_TITLE,"gi-pos-mobile-backup-"+java.time.LocalDate.now()+".sqlite");startActivityForResult(intent,BACKUP);}
  private void confirmRestore(){new AlertDialog.Builder(this).setTitle("Restore local backup?").setMessage("All current local tables, menu, customers, and bills will be replaced. Keep a backup of the current database first.").setNegativeButton("Cancel",null).setPositiveButton("Choose backup",(d,w)->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,RESTORE);}).show();}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==BACKUP){try(OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new IllegalStateException("Cannot open selected file");db.backupTo(output);Toast.makeText(this,"Backup created",Toast.LENGTH_SHORT).show();}catch(Exception error){Toast.makeText(this,"Backup failed: "+error.getMessage(),Toast.LENGTH_LONG).show();}}else if(request==RESTORE){try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IllegalStateException("Cannot open selected file");db.restoreFrom(input);Toast.makeText(this,"Backup restored",Toast.LENGTH_SHORT).show();Intent restart=new Intent(this,MainActivity.class);restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(restart);finish();}catch(Exception error){Toast.makeText(this,"Restore failed: "+error.getMessage(),Toast.LENGTH_LONG).show();}}}

  private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "1.0";}}
  private LinearLayout panel(String title){LinearLayout value=column();value.setPadding(dp(16),dp(15),dp(16),dp(16));value.setBackground(shape(Color.WHITE,13,1,line));value.addView(text(title,17,ink,true));return value;}
  private LinearLayout pair(String label,String value){LinearLayout row=row();row.setGravity(Gravity.CENTER_VERTICAL);row.addView(text(label,13,muted,false),new LinearLayout.LayoutParams(0,-2,1));row.addView(text(value==null||value.isBlank()?"-":value,14,ink,true));return row;}
  private String date(String value){try{return DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.parse(value));}catch(Exception ignored){return value==null||value.isBlank()?"-":value;}}
  private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}
  private LinearLayout row(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.HORIZONTAL);return value;}
  private TextView text(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);return view;}
  private Button button(String value,int fill,int color){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(color);view.setTypeface(Typeface.DEFAULT_BOLD);view.setBackground(shape(fill,9,1,line));return view;}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable value=new GradientDrawable();value.setColor(fill);value.setCornerRadius(dp(radius));if(stroke>0)value.setStroke(dp(stroke),strokeColor);return value;}
  private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);value.topMargin=dp(margin);return value;}
  private LinearLayout.LayoutParams topHeight(int margin,int height){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,dp(height));value.topMargin=dp(margin);return value;}
  private View gap(){View value=new View(this);value.setLayoutParams(new LinearLayout.LayoutParams(dp(8),1));return value;}
  private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
}
