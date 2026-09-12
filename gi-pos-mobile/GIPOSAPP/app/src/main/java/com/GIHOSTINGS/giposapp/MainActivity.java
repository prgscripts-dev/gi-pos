package com.GIHOSTINGS.giposapp;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.provider.Settings;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends InsetActivity {
  private final int ink = Color.rgb(18, 32, 51), red = Color.rgb(199, 22, 55), muted = Color.rgb(99,115,138);
  private EditText serverAddress, account, password;
  private TextView status;
  private Button signInButton, autoFindButton;

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    SecureStore.Session session = SecureStore.load(this);
    if (session != null && session.isActive() && !SecureStore.isSignedOut(this)) { if(SecureStore.hasPin(this))showPinUnlock();else openDashboard(); return; }
    showActivation();
  }

  // On the login screen we don't relaunch ourselves; just show the sign-in form
  // with a note explaining why the session ended.
  @Override protected void handleForcedSignOut(String reason) {
    if (isFinishing()) return;
    showActivation();
    if (status != null && reason != null && !reason.isBlank()) {
      status(reason, true);
    }
  }

  private void showPinUnlock() {
    LinearLayout root=column();root.setGravity(Gravity.CENTER_HORIZONTAL);root.setPadding(dp(24),dp(44),dp(24),dp(30));root.setBackgroundColor(Color.rgb(244,247,250));
    ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.app_logo);logo.setScaleType(ImageView.ScaleType.CENTER_CROP);logo.setContentDescription("GI POS logo");root.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(64)));
    TextView title=text("Unlock GI POS",27,ink,true);title.setGravity(Gravity.CENTER);root.addView(title,top(18));TextView detail=text("Enter your optional device PIN",14,muted,false);detail.setGravity(Gravity.CENTER);root.addView(detail,top(5));
    LinearLayout card=column();card.setPadding(dp(20),dp(20),dp(20),dp(18));card.setBackground(shape(Color.WHITE,12,1,Color.rgb(217,226,236)));EditText pin=input("Enter PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);pin.setTransformationMethod(PasswordTransformationMethod.getInstance());card.addView(FormControls.field(this,"Device PIN",pin));TextView error=text("",13,Color.rgb(185,28,28),false);error.setVisibility(View.GONE);card.addView(error,top(9));Button unlock=button("Unlock",red,Color.WHITE);Runnable unlockAction=()->{String value=pin.getText().toString();pin.setText("");if(SecureStore.verifyPin(this,value))openDashboard();else{error.setText("Incorrect PIN");error.setVisibility(View.VISIBLE);pin.requestFocus();}};unlock.setOnClickListener(v->unlockAction.run());FormControls.submitOnDone(pin,unlockAction);card.addView(unlock,topHeight(16,50));Button passwordLogin=button("Use account password",Color.rgb(241,245,249),ink);passwordLogin.setOnClickListener(v->{SecureStore.signOut(this);showActivation();});card.addView(passwordLogin,topHeight(9,46));root.addView(card,top(28));setContentView(root);pin.requestFocus();
  }

  private void showActivation() {
    ScrollView root = new ScrollView(this); root.setFillViewport(true); root.setBackgroundColor(Color.rgb(244,247,250));
    LinearLayout content = column(); content.setGravity(Gravity.CENTER_HORIZONTAL); content.setPadding(dp(22),dp(30),dp(22),dp(28));
    ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.app_logo); logo.setScaleType(ImageView.ScaleType.CENTER_CROP); logo.setContentDescription("GI POS logo"); logo.setBackground(shape(Color.rgb(16,21,24),15,0,0)); logo.setClipToOutline(true);
    content.addView(logo, new LinearLayout.LayoutParams(dp(58),dp(58)));
    TextView title = text("GI POS Mobile",28,ink,true); title.setGravity(Gravity.CENTER); content.addView(title, top(16));
    TextView sub = text("Sign in to connect your account",15,muted,false); sub.setGravity(Gravity.CENTER); content.addView(sub,top(6));

    LinearLayout card = column(); card.setPadding(dp(20),dp(22),dp(20),dp(20)); card.setElevation(dp(2)); card.setBackground(shape(Color.WHITE,12,1,Color.rgb(217,226,236)));
    card.addView(text("Sign in",21,ink,true));
    card.addView(text("Enter your account credentials to continue.",14,muted,false),top(6));
    serverAddress = input("https://goldensea.gihostings.in",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
    serverAddress.setText("https://goldensea.gihostings.in");
    card.addView(FormControls.field(this,"Server address",serverAddress),top(24));
    autoFindButton = new Button(this); autoFindButton.setText("Find Server"); autoFindButton.setAllCaps(false); autoFindButton.setTextColor(ink); autoFindButton.setTextSize(14); autoFindButton.setTypeface(Typeface.DEFAULT_BOLD); autoFindButton.setBackground(shape(Color.rgb(241,245,249),10,1,Color.rgb(203,213,225))); autoFindButton.setOnClickListener(v -> autoFindServer()); card.addView(autoFindButton,topHeight(10,46));
    account = input("Registered phone number or email",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); card.addView(FormControls.field(this,"Phone or email",account),top(16));
    password = input("Enter your password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setTransformationMethod(PasswordTransformationMethod.getInstance()); FormControls.submitOnDone(password,this::activate); card.addView(FormControls.field(this,"Password",password),top(16));
    signInButton = new Button(this); signInButton.setText("Sign in"); signInButton.setAllCaps(false); signInButton.setTextColor(Color.WHITE); signInButton.setTextSize(16); signInButton.setTypeface(Typeface.DEFAULT_BOLD); signInButton.setBackground(shape(red,10,0,0)); signInButton.setOnClickListener(v -> activate()); card.addView(signInButton,topHeight(24,52));

    // Owner and staff both sign in here: enter the account owner credentials, OR a
    // staff member's phone + password created in User management. The app detects
    // which one and signs in accordingly.
    TextView signInHint = text("Owners use account credentials. Staff use the phone number and password set in User management.",12,muted,false);
    card.addView(signInHint, top(12));

    status = text("",13,muted,false); status.setPadding(dp(12),dp(10),dp(12),dp(10)); status.setVisibility(View.GONE); card.addView(status,top(12));
    content.addView(card,top(30));
    root.addView(content); setContentView(root);
  }

  private void activate() {
    if (serverAddress.getText().toString().trim().isEmpty()) { status("Enter the server address.",true); return; }
    if (account.getText().toString().trim().isEmpty() || password.getText().toString().isEmpty()) { status("Enter your phone/email and password.",true); return; }
    signInButton.setEnabled(false); status("Connecting securely...",false);String passwordValue=password.getText().toString();password.setText("");
    String fingerprint = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    String serverValue = serverAddress.getText().toString();
    String loginValue = account.getText().toString();
    new Thread(() -> {
      // Try owner sign-in first. If the account credentials aren't recognised,
      // fall back to staff sign-in (phone + password created in User management).
      CloudClient.Result result = null;
      boolean isStaff = false;
      Exception ownerError = null;
      try {
        result = CloudClient.activate(serverValue, loginValue, passwordValue, fingerprint, SecureStore.load(this));
      } catch (Exception error) {
        ownerError = error;
      }
      if (result == null) {
        try {
          result = CloudClient.staffActivate(serverValue, loginValue, passwordValue, fingerprint);
          isStaff = true;
        } catch (Exception staffError) {
          // Surface the most helpful message: if the server was reachable but both
          // owner and staff credentials failed, show the staff (credential) error.
          Exception shown = staffError;
          if (ownerError != null && ownerError.getMessage() != null
              && (ownerError.getMessage().toLowerCase().contains("resolve host")
                  || ownerError.getMessage().toLowerCase().contains("unable to connect")
                  || ownerError.getMessage().toLowerCase().contains("failed to connect"))) {
            shown = ownerError;
          }
          final String message = shown.getMessage() == null ? "Invalid login or password." : shown.getMessage();
          runOnUiThread(() -> { signInButton.setEnabled(true); status(message, true); });
          return;
        }
      }
      try {
        SecureStore.save(this, result.session);
        SecureStore.clearPin(this);
        // Record the active staff identity (permissions + table access) when this
        // is a staff sign-in; clear it for an owner sign-in.
        if (isStaff) SecureStore.setActiveStaff(this, result.session.optJSONObject("staff"));
        else SecureStore.clearActiveStaff(this);
        // New session on this device: re-arm the forced-sign-out guard so a
        // future logout-elsewhere is handled again.
        CloudSyncManager.resetSignOutGuard();
        PosDatabase.get(this).getWritableDatabase();
        if (new SecureStore.Session(result.session).hasFeature("cloudSync")) {
          // Fresh activation on this device: pull the full data set from the cloud
          // so all existing data (menu, tables, customers, orders) appears here.
          CloudSyncManager.resetPullCursor(this);
          CloudSyncManager.initialSync(this);
          // Push the friendly connection name so this device shows a recognizable
          // label on the portal's Signed Devices page.
          try { CloudClient.renameDevice(new SecureStore.Session(result.session), SecureStore.cloudConnectionName(this)); } catch (Exception ignored) {}
        }
        runOnUiThread(this::openDashboard);
      } catch (Exception error) {
        runOnUiThread(() -> { signInButton.setEnabled(true); status(error.getMessage() == null ? "Unable to connect to the server." : error.getMessage(), true); });
      }
    }).start();
  }
  private void autoFindServer() {
    if (autoFindButton == null) return;
    autoFindButton.setEnabled(false);
    autoFindButton.setText("Searching\u2026");
    status("Looking for the GI POS Main PC on this Wi-Fi\u2026", false);
    // Stage 1: fast mDNS discovery. Stage 2 (fallback): active subnet scan probing /api/health.
    LocalServerDiscovery.find(this, SecureStore.localServerName(this), new LocalServerDiscovery.Callback() {
      @Override public void onFound(String serviceName, String endpoint) {
        if (isFinishing()) return;
        applyFoundServer(serviceName, endpoint);
      }
      @Override public void onError(String message) {
        if (isFinishing()) return;
        scanForServer();
      }
    });
  }

  private void scanForServer() {
    if (autoFindButton == null || isFinishing()) return;
    autoFindButton.setText("Scanning network\u2026");
    status("Scanning the Wi-Fi network for the Main PC\u2026", false);
    LocalServerScanner.scan(this, new LocalServerScanner.Callback() {
      @Override public void onFound(String endpoint) {
        if (isFinishing()) return;
        applyFoundServer(SecureStore.localServerName(MainActivity.this), endpoint);
      }
      @Override public void onError(String message) {
        if (isFinishing()) return;
        resetFindButton();
        status(message == null ? "Main PC was not found on this network." : message, true);
      }
    });
  }

  private void applyFoundServer(String serviceName, String endpoint) {
    serverAddress.setText(endpoint);
    serverAddress.setSelection(endpoint.length());
    SecureStore.saveLocalServer(MainActivity.this, serviceName, endpoint);
    resetFindButton();
    status("Main PC found: " + endpoint + ". Enter your login and sign in.", false);
  }

  private void resetFindButton() {
    if (autoFindButton == null) return;
    autoFindButton.setEnabled(true);
    autoFindButton.setText("Find Server");
  }
  // Staff sign-in dialog: authenticate a staff member (created in User management)
  // against the cloud using this linked device's credentials, then open the app
  // under that staff identity.
  private void openStaffLogin() {
    SecureStore.Session linked = SecureStore.load(this);
    if (linked == null || linked.deviceId.isBlank() || linked.apiKey.isBlank()) {
      status("Link this device with the owner account first, then staff can sign in.", true);
      return;
    }
    LinearLayout form = FormControls.dialogForm(this);
    EditText phone = input("Phone number", InputType.TYPE_CLASS_PHONE);
    EditText pass = input("Password", InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    pass.setTransformationMethod(PasswordTransformationMethod.getInstance());
    form.addView(FormControls.field(this, "Phone number", phone));
    View spacer = new View(this); spacer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
    form.addView(spacer);
    form.addView(FormControls.field(this, "Password", pass));

    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
      .setTitle("Staff sign-in")
      .setView(form)
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Sign in", null)
      .create();
    dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      String phoneValue = phone.getText().toString().trim();
      String passValue = pass.getText().toString();
      if (phoneValue.isEmpty()) { phone.setError("Enter your phone number"); phone.requestFocus(); return; }
      if (passValue.isEmpty()) { pass.setError("Enter your password"); pass.requestFocus(); return; }
      Button ok = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
      ok.setEnabled(false); ok.setText("Signing in\u2026");
      new Thread(() -> {
        try {
          org.json.JSONObject staff = CloudClient.staffLogin(linked, phoneValue, passValue);
          if (staff == null) throw new Exception("Invalid phone number or password");
          SecureStore.setActiveStaff(this, staff);
          runOnUiThread(() -> { dialog.dismiss(); openDashboard(); });
        } catch (Exception error) {
          runOnUiThread(() -> {
            ok.setEnabled(true); ok.setText("Sign in");
            status(error.getMessage() == null ? "Could not sign in." : error.getMessage(), true);
          });
        }
      }).start();
    }));
    dialog.show();
  }

  private void openDashboard(){startActivity(new Intent(this,DashboardActivity.class));finish();}
  private LinearLayout column(){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
  private TextView label(String s){return text(s,13,ink,true);}
  private Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(color);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(shape(fill,10,0,0));return b;}
  private EditText input(String hint,int type){return FormControls.input(this,hint,type);}
  private TextView text(String s,int size,int color,boolean bold){ TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); if(bold)v.setTypeface(Typeface.DEFAULT_BOLD); return v; }
  private void status(String s,boolean error){status.setVisibility(View.VISIBLE);status.setText(s);status.setTextColor(error?Color.rgb(185,28,28):Color.rgb(5,116,100));status.setBackground(shape(error?Color.rgb(254,242,242):Color.rgb(236,253,245),8,1,error?Color.rgb(252,165,165):Color.rgb(110,231,183)));}
  private LinearLayout.LayoutParams top(int px){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(px);return p;}
  private LinearLayout.LayoutParams topHeight(int margin,int height){LinearLayout.LayoutParams p=top(margin);p.height=dp(height);return p;}
  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
  private GradientDrawable shape(int fill,int radius,int stroke,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke>0)d.setStroke(dp(stroke),strokeColor);return d;}
}
