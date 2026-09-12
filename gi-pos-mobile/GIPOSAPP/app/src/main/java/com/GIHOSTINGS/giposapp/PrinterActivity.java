package com.GIHOSTINGS.giposapp;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PrinterActivity extends InsetActivity {
  private static final int BLUETOOTH_PERMISSION_REQUEST = 301;

  private final int ink = Color.rgb(18, 32, 51);
  private final int red = Color.rgb(199, 22, 55);
  private final int muted = Color.rgb(99, 115, 138);
  private final int teal = Color.rgb(8, 127, 140);
  private final int line = Color.rgb(217, 226, 236);
  private final int surface = Color.rgb(244, 247, 250);
  private final int paleTeal = Color.rgb(229, 247, 249);

  private PosDatabase db;
  private String editingId = "";
  private String editingBluetoothAddress = "";
  private int selectedConnection;
  private int selectedPaper = 80;
  private String selectedRole = "both";

  private EditText name;
  private EditText address;
  private EditText port;
  private Spinner device;
  private CheckBox primary;
  private LinearLayout networkFields;
  private LinearLayout bluetoothFields;
  private Button bluetoothChoice;
  private Button networkChoice;
  private Button paper80Choice;
  private Button paper58Choice;
  private Button bothRoleChoice;
  private Button billRoleChoice;
  private Button kotRoleChoice;
  private final List<String> deviceAddresses = new ArrayList<>();
  private final Set<String> selectedFailedIds = new java.util.HashSet<>();

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (!requireFeature("bluetoothPrinting", "networkPrinting")) return;
    db = PosDatabase.get(this);
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() {
        if (name != null) showList();
        else {
          setEnabled(false);
          getOnBackPressedDispatcher().onBackPressed();
        }
      }
    });
    showList();
  }

  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == BLUETOOTH_PERMISSION_REQUEST && name != null) loadPairedDevices();
  }

  private void showList() {
    clearEditorReferences();
    List<PosDatabase.PrinterProfile> profiles = db.printerProfiles();
    LinearLayout content = column();
    content.setPadding(dp(18), dp(16), dp(18), dp(28));
    content.setBackgroundColor(surface);
    content.addView(header("Printers", "Bills and KOT destinations", this::finish, null));
    content.addView(readinessPanel(profiles), top(18));
    List<PosDatabase.FailedPrintJob> failedJobs = db.failedPrintJobs();
    if (!failedJobs.isEmpty()) content.addView(failedPrintQueue(failedJobs), top(14));

    LinearLayout titleRow = row();
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout copy = column();
    copy.addView(text("Saved printers", 18, ink, true));
    copy.addView(text(profiles.size() + (profiles.size() == 1 ? " connection" : " connections"), 13, muted, false), top(2));
    titleRow.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    Button add = button("+  Add printer", red, Color.WHITE);
    add.setOnClickListener(v -> showEditor(null));
    titleRow.addView(add, new LinearLayout.LayoutParams(-2, dp(44)));
    content.addView(titleRow, top(22));

    if (profiles.isEmpty()) content.addView(emptyState(), top(13));
    else for (PosDatabase.PrinterProfile profile : profiles) content.addView(printerCard(profile), top(10));

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(content);
    setContentView(scroll);
  }

  private View readinessPanel(List<PosDatabase.PrinterProfile> profiles) {
    boolean billReady = false;
    boolean kotReady = false;
    for (PosDatabase.PrinterProfile profile : profiles) {
      if ("both".equals(profile.role) || "bill".equals(profile.role)) billReady = true;
      if ("both".equals(profile.role) || "kot".equals(profile.role)) kotReady = true;
    }
    LinearLayout panel = column();
    panel.setPadding(dp(15), dp(14), dp(15), dp(14));
    panel.setBackground(shape(Color.WHITE, 10, 1, line));
    LinearLayout status = row();
    status.setGravity(Gravity.CENTER_VERTICAL);
    status.addView(metric("Configured", String.valueOf(profiles.size())), new LinearLayout.LayoutParams(0, -2, 1));
    status.addView(divider(), new LinearLayout.LayoutParams(dp(1), dp(38)));
    status.addView(metric("Bill printing", billReady ? "Ready" : "Not set"), new LinearLayout.LayoutParams(0, -2, 1));
    status.addView(divider(), new LinearLayout.LayoutParams(dp(1), dp(38)));
    status.addView(metric("KOT printing", kotReady ? "Ready" : "Not set"), new LinearLayout.LayoutParams(0, -2, 1));
    panel.addView(status);
    int pending = db.pendingPrintCount();
    if (pending > 0) {
      TextView queued = text(pending + " print " + (pending == 1 ? "job is" : "jobs are") + " waiting to retry", 13, Color.rgb(146, 64, 14), true);
      queued.setPadding(dp(10), dp(9), dp(10), dp(9));
      queued.setBackground(shape(Color.rgb(255, 247, 220), 8, 1, Color.rgb(245, 202, 116)));
      panel.addView(queued, top(12));
    }
    return panel;
  }

  private View failedPrintQueue(List<PosDatabase.FailedPrintJob> jobs) {
    java.util.HashSet<String> available = new java.util.HashSet<>();
    for (PosDatabase.FailedPrintJob job : jobs) available.add(job.id);
    selectedFailedIds.retainAll(available);

    LinearLayout panel = column();
    panel.setPadding(dp(14), dp(13), dp(14), dp(14));
    panel.setBackground(shape(Color.WHITE, 10, 1, Color.rgb(252, 165, 165)));

    LinearLayout heading = row();
    heading.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout copy = column();
    copy.addView(text("Failed prints", 17, ink, true));
    copy.addView(text("Select jobs to retry or remove", 12, muted, false), top(2));
    heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    TextView count = text("", 12, red, true);
    heading.addView(count);
    panel.addView(heading);

    LinearLayout actions = row();
    actions.setGravity(Gravity.CENTER_VERTICAL);
    CheckBox selectAll = new CheckBox(this);
    selectAll.setText("Select all");
    selectAll.setTextColor(ink);
    selectAll.setTextSize(13);
    actions.addView(selectAll, new LinearLayout.LayoutParams(0, dp(44), 1));
    Button retry = button("Retry", paleTeal, teal);
    Button delete = button("Delete", Color.rgb(254, 242, 242), red);
    actions.addView(retry, new LinearLayout.LayoutParams(dp(76), dp(40)));
    LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(80), dp(40));
    deleteParams.leftMargin = dp(7);
    actions.addView(delete, deleteParams);
    panel.addView(actions, top(8));

    List<CheckBox> checks = new ArrayList<>();
    Runnable refreshActions = () -> {
      int selected = selectedFailedIds.size();
      count.setText(selected == 0 ? jobs.size() + " failed" : selected + " selected");
      retry.setEnabled(selected > 0);
      delete.setEnabled(selected > 0);
      retry.setAlpha(selected > 0 ? 1f : .45f);
      delete.setAlpha(selected > 0 ? 1f : .45f);
      selectAll.setOnCheckedChangeListener(null);
      selectAll.setChecked(selected == jobs.size());
      selectAll.setOnCheckedChangeListener((button, checked) -> {
        selectedFailedIds.clear();
        if (checked) for (PosDatabase.FailedPrintJob job : jobs) selectedFailedIds.add(job.id);
        for (CheckBox item : checks) item.setChecked(checked);
        count.setText(checked ? jobs.size() + " selected" : jobs.size() + " failed");
        retry.setEnabled(checked);
        delete.setEnabled(checked);
        retry.setAlpha(checked ? 1f : .45f);
        delete.setAlpha(checked ? 1f : .45f);
      });
    };

    for (PosDatabase.FailedPrintJob job : jobs) {
      LinearLayout row = row();
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(9), dp(9), dp(9), dp(9));
      row.setBackground(shape(Color.rgb(255, 248, 248), 8, 1, Color.rgb(254, 202, 202)));
      CheckBox check = new CheckBox(this);
      check.setChecked(selectedFailedIds.contains(job.id));
      checks.add(check);
      row.addView(check, new LinearLayout.LayoutParams(dp(42), dp(44)));
      LinearLayout detail = column();
      String document = "kot".equals(job.type) ? "KOT" : "report".equals(job.type) ? "Report" : "Bill";
      String location = job.orderId.isEmpty() ? "" : "  /  " + (job.tableName == null || job.tableName.isBlank() ? "Direct" : job.tableName);
      detail.addView(text(document + " print" + location, 14, ink, true));
      String references = "";
      if (job.billNumber != null) references = "Bill #" + job.billNumber;
      if (job.kotNumber != null) references += (references.isEmpty() ? "" : "  |  ") + "KOT #" + job.kotNumber;
      if (!references.isEmpty()) detail.addView(text(references, 12, teal, true), top(3));
      detail.addView(text(job.error == null || job.error.isBlank() ? "Printer did not accept the job" : job.error, 12, Color.rgb(153, 27, 27), false), top(2));
      String when = android.text.format.DateFormat.format("dd MMM, hh:mm a", job.updatedAt).toString();
      detail.addView(text("Attempt " + job.attempts + "  |  " + when, 11, muted, false), top(3));
      row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
      check.setOnCheckedChangeListener((button, checked) -> {
        if (checked) selectedFailedIds.add(job.id); else selectedFailedIds.remove(job.id);
        refreshActions.run();
      });
      row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
      panel.addView(row, top(7));
    }

    retry.setOnClickListener(v -> {
      db.retryFailedPrintJobs(new ArrayList<>(selectedFailedIds));
      selectedFailedIds.clear();
      PrintDispatcher.processAsync(this);
      Toast.makeText(this, "Selected prints queued again", Toast.LENGTH_SHORT).show();
      showList();
    });
    delete.setOnClickListener(v -> new AlertDialog.Builder(this)
        .setTitle("Delete selected prints?")
        .setMessage(selectedFailedIds.size() + " failed print job(s) will be removed from the queue.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete", (dialog, which) -> {
          db.deleteFailedPrintJobs(new ArrayList<>(selectedFailedIds));
          selectedFailedIds.clear();
          Toast.makeText(this, "Failed prints deleted", Toast.LENGTH_SHORT).show();
          showList();
        }).show());
    refreshActions.run();
    return panel;
  }

  private View metric(String label, String value) {
    LinearLayout box = column();
    box.setGravity(Gravity.CENTER);
    TextView labelView = text(label, 11, muted, true);
    labelView.setGravity(Gravity.CENTER);
    TextView valueView = text(value, 15, "Ready".equals(value) ? teal : ink, true);
    valueView.setGravity(Gravity.CENTER);
    box.addView(labelView);
    box.addView(valueView, top(3));
    return box;
  }

  private View divider() {
    View view = new View(this);
    view.setBackgroundColor(line);
    return view;
  }

  private View emptyState() {
    LinearLayout empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(dp(22), dp(34), dp(22), dp(34));
    empty.setBackground(shape(Color.WHITE, 10, 1, line));
    TextView mark = text("PRINT", 12, teal, true);
    mark.setGravity(Gravity.CENTER);
    mark.setPadding(dp(13), dp(9), dp(13), dp(9));
    mark.setBackground(shape(paleTeal, 8, 1, Color.rgb(148, 210, 218)));
    empty.addView(mark, new LinearLayout.LayoutParams(-2, -2));
    TextView title = text("Connect your first printer", 18, ink, true);
    title.setGravity(Gravity.CENTER);
    empty.addView(title, top(13));
    TextView hint = text("Use Bluetooth for portable counters or a network printer on the same Wi-Fi.", 14, muted, false);
    hint.setGravity(Gravity.CENTER);
    empty.addView(hint, top(7));
    Button add = button("Add printer", red, Color.WHITE);
    add.setOnClickListener(v -> showEditor(null));
    LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(dp(170), dp(46));
    action.topMargin = dp(18);
    empty.addView(add, action);
    return empty;
  }

  private View printerCard(PosDatabase.PrinterProfile profile) {
    LinearLayout card = column();
    card.setPadding(dp(15), dp(14), dp(15), dp(13));
    card.setBackground(shape(Color.WHITE, 10, 1, profile.isDefault ? Color.rgb(148, 210, 218) : line));
    // Note: no card-wide click listener. Navigation to the editor happens only via
    // the explicit "Configure" button below, so tapping the "Test print" button (or
    // anywhere else on the card) no longer accidentally opens the editor.
    LinearLayout top = row();
    top.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout label = column();
    LinearLayout nameRow = row();
    nameRow.setGravity(Gravity.CENTER_VERTICAL);
    nameRow.addView(text(profile.name, 17, ink, true));
    if (profile.isDefault) nameRow.addView(tag("Primary"), left(8));
    label.addView(nameRow);
    label.addView(text(targetLabel(profile), 13, muted, false), top(4));
    top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    TextView arrow = text("›", 27, muted, false);
    arrow.setGravity(Gravity.CENTER);
    top.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(40)));
    // Tapping the printer's name/summary row opens its setup; the Test print button
    // (added below) stays independent so it never navigates.
    top.setOnClickListener(v -> showEditor(profile));
    card.addView(top);
    LinearLayout tags = row();
    tags.setGravity(Gravity.CENTER_VERTICAL);
    tags.addView(tag("POS " + profile.paperWidth + "mm"));
    tags.addView(tag(roleLabel(profile.role)), left(7));
    card.addView(tags, top(12));
    card.addView(divider(), top(13));
    LinearLayout actions = row();
    Button configure = quietButton("Configure");
    configure.setOnClickListener(v -> showEditor(profile));
    actions.addView(configure, new LinearLayout.LayoutParams(0, dp(42), 1));
    Button test = button("Test print", paleTeal, teal);
    test.setOnClickListener(v -> test(profile, test));
    LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(0, dp(42), 1);
    testParams.leftMargin = dp(8);
    actions.addView(test, testParams);
    card.addView(actions, top(10));
    return card;
  }

  private String targetLabel(PosDatabase.PrinterProfile profile) {
    return "bluetooth".equals(profile.connectionType)
        ? "Bluetooth  ·  " + profile.address
        : "Network  ·  " + profile.address + ":" + profile.port;
  }

  private void showEditor(PosDatabase.PrinterProfile profile) {
    editingId = profile == null ? "" : profile.id;
    editingBluetoothAddress = profile != null && "bluetooth".equals(profile.connectionType) ? profile.address : "";
    selectedConnection = profile != null && "network".equals(profile.connectionType) ? 1 : 0;
    selectedPaper = profile == null ? 80 : profile.paperWidth;
    selectedRole = profile == null ? "both" : profile.role;

    LinearLayout content = column();
    content.setPadding(dp(18), dp(16), dp(18), dp(30));
    content.setBackgroundColor(surface);
    Button save = button("Save", red, Color.WHITE);
    save.setOnClickListener(v -> save());
    content.addView(header(profile == null ? "Add printer" : "Printer setup", profile == null ? "Create a reliable print destination" : profile.name, this::showList, save));

    LinearLayout connectionCard = formCard();
    connectionCard.addView(section("Connection", "Choose how this Android device reaches the printer."));
    name = input("Example: Counter bill printer");
    connectionCard.addView(field("Printer name", name));
    bluetoothChoice = choiceButton("Bluetooth");
    networkChoice = choiceButton("Network / Wi-Fi");
    connectionCard.addView(field("Connection type", choiceRow(bluetoothChoice, networkChoice)));
    bluetoothChoice.setOnClickListener(v -> selectConnection(0));
    networkChoice.setOnClickListener(v -> selectConnection(1));

    bluetoothFields = column();
    device = spinner(new String[]{"Looking for paired printers..."});
    bluetoothFields.addView(field("Paired printer", device));
    LinearLayout bluetoothActions = row();
    Button refresh = quietButton("Refresh list");
    refresh.setOnClickListener(v -> loadPairedDevices());
    bluetoothActions.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1));
    Button pair = quietButton("Pair a device");
    pair.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
    LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(0, dp(44), 1);
    pairParams.leftMargin = dp(8);
    bluetoothActions.addView(pair, pairParams);
    bluetoothFields.addView(bluetoothActions, top(8));
    connectionCard.addView(bluetoothFields);

    networkFields = column();
    address = input("Example: 192.168.1.50");
    port = FormControls.input(this, "Usually 9100", android.text.InputType.TYPE_CLASS_NUMBER);
    networkFields.addView(field("Printer IP address", address));
    networkFields.addView(field("Port", port));
    connectionCard.addView(networkFields);
    content.addView(connectionCard, top(18));

    LinearLayout outputCard = formCard();
    outputCard.addView(section("Output", "Match the paper roll and choose what this printer handles."));
    paper80Choice = choiceButton("POS 80mm");
    paper58Choice = choiceButton("POS 58mm");
    outputCard.addView(field("Paper width", choiceRow(paper80Choice, paper58Choice)));
    paper80Choice.setOnClickListener(v -> selectPaper(80));
    paper58Choice.setOnClickListener(v -> selectPaper(58));
    bothRoleChoice = choiceButton("Bills + KOT");
    billRoleChoice = choiceButton("Bills only");
    kotRoleChoice = choiceButton("KOT only");
    outputCard.addView(field("Prints", bothRoleChoice));
    outputCard.addView(choiceRow(billRoleChoice, kotRoleChoice), top(8));
    bothRoleChoice.setOnClickListener(v -> selectRole("both"));
    billRoleChoice.setOnClickListener(v -> selectRole("bill"));
    kotRoleChoice.setOnClickListener(v -> selectRole("kot"));
    primary = new CheckBox(this);
    primary.setText("Use as the primary printer for this role");
    primary.setTextColor(ink);
    primary.setTextSize(15);
    primary.setPadding(0, dp(6), 0, dp(3));
    outputCard.addView(primary, top(12));
    content.addView(outputCard, top(12));

    LinearLayout help = column();
    help.setPadding(dp(14), dp(13), dp(14), dp(13));
    help.setBackground(shape(Color.WHITE, 10, 1, line));
    help.addView(text("Before testing", 14, ink, true));
    help.addView(text("Bluetooth printers must be paired in Android settings. Network printers must be on the same Wi-Fi and normally use port 9100.", 13, muted, false), top(5));
    content.addView(help, top(12));

    if (profile != null) {
      Button test = button("Test this printer", paleTeal, teal);
      test.setOnClickListener(v -> test(profile, test));
      content.addView(test, top(12));
      Button delete = button("Delete printer", Color.rgb(255, 241, 242), red);
      delete.setOnClickListener(v -> confirmDelete(profile));
      content.addView(delete, top(8));
      name.setText(profile.name);
      if ("network".equals(profile.connectionType)) address.setText(profile.address);
      port.setText(String.valueOf(profile.port));
      primary.setChecked(profile.isDefault);
    } else {
      port.setText("9100");
      primary.setChecked(db.printerProfiles().isEmpty());
    }

    selectConnection(selectedConnection);
    selectPaper(selectedPaper);
    selectRole(selectedRole);
    loadPairedDevices();
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(content);
    setContentView(scroll);
  }

  private LinearLayout formCard() {
    LinearLayout card = column();
    card.setPadding(dp(15), dp(15), dp(15), dp(16));
    card.setBackground(shape(Color.WHITE, 10, 1, line));
    return card;
  }

  private void selectConnection(int value) {
    selectedConnection = value;
    updateChoice(bluetoothChoice, value == 0);
    updateChoice(networkChoice, value == 1);
    toggleConnection(value == 0);
  }

  private void selectPaper(int value) {
    selectedPaper = value;
    updateChoice(paper80Choice, value == 80);
    updateChoice(paper58Choice, value == 58);
  }

  private void selectRole(String value) {
    selectedRole = value;
    updateChoice(bothRoleChoice, "both".equals(value));
    updateChoice(billRoleChoice, "bill".equals(value));
    updateChoice(kotRoleChoice, "kot".equals(value));
  }

  private void updateChoice(Button choice, boolean selected) {
    if (choice == null) return;
    choice.setTextColor(selected ? Color.WHITE : ink);
    choice.setBackground(shape(selected ? teal : Color.rgb(248, 250, 252), 8, 1, selected ? teal : line));
  }

  private void toggleConnection(boolean bluetooth) {
    if (bluetoothFields != null) bluetoothFields.setVisibility(bluetooth ? View.VISIBLE : View.GONE);
    if (networkFields != null) networkFields.setVisibility(bluetooth ? View.GONE : View.VISIBLE);
  }

  private void loadPairedDevices() {
    if (device == null) return;
    if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, BLUETOOTH_PERMISSION_REQUEST);
      return;
    }
    BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    List<String> labels = new ArrayList<>();
    deviceAddresses.clear();
    if (adapter != null) {
      Set<BluetoothDevice> bonded = adapter.getBondedDevices();
      List<BluetoothDevice> sorted = new ArrayList<>(bonded);
      sorted.sort(Comparator.comparing(item -> {
        String value = item.getName();
        return value == null ? "" : value.toLowerCase(Locale.US);
      }));
      for (BluetoothDevice item : sorted) {
        String deviceName = item.getName();
        labels.add((deviceName == null || deviceName.isBlank() ? "Thermal printer" : deviceName) + "  ·  " + item.getAddress());
        deviceAddresses.add(item.getAddress());
      }
    }
    if (labels.isEmpty()) labels.add("No paired printer found");
    device.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    int index = deviceAddresses.indexOf(editingBluetoothAddress);
    if (index >= 0) device.setSelection(index);
  }

  private void save() {
    String printerName = name.getText().toString().trim();
    boolean bluetooth = selectedConnection == 0;
    String target = bluetooth
        ? (deviceAddresses.isEmpty() ? "" : deviceAddresses.get(Math.min(device.getSelectedItemPosition(), deviceAddresses.size() - 1)))
        : address.getText().toString().trim();
    int targetPort = 9100;
    try { targetPort = Integer.parseInt(port.getText().toString().trim()); }
    catch (Exception ignored) { }
    if (printerName.isEmpty()) {
      name.setError("Enter a printer name");
      name.requestFocus();
      return;
    }
    if (target.isEmpty()) {
      Toast.makeText(this, bluetooth ? "Pair and select a Bluetooth printer" : "Enter the printer IP address", Toast.LENGTH_LONG).show();
      return;
    }
    if (!bluetooth && (targetPort < 1 || targetPort > 65535)) {
      port.setError("Enter a valid port");
      port.requestFocus();
      return;
    }
    db.savePrinter(new PosDatabase.PrinterProfile(editingId, printerName, bluetooth ? "bluetooth" : "network", target, targetPort, selectedPaper, selectedRole, primary.isChecked()));
    Toast.makeText(this, "Printer saved", Toast.LENGTH_SHORT).show();
    PrintDispatcher.processAsync(this);
    showList();
  }

  private void test(PosDatabase.PrinterProfile profile, Button button) {
    button.setEnabled(false);
    button.setText("Sending...");
    PrintDispatcher.testAsync(this, profile, (success, message) -> runOnUiThread(() -> {
      button.setEnabled(true);
      button.setText("Test print");
      Toast.makeText(this, message, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }));
  }

  private void confirmDelete(PosDatabase.PrinterProfile profile) {
    new AlertDialog.Builder(this)
        .setTitle("Delete printer?")
        .setMessage(profile.name + " will no longer receive bills or KOTs.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete", (dialog, which) -> {
          db.removePrinter(profile.id);
          Toast.makeText(this, "Printer deleted", Toast.LENGTH_SHORT).show();
          showList();
        }).show();
  }

  private String roleLabel(String value) {
    return "bill".equals(value) ? "Bills" : "kot".equals(value) ? "KOT" : "Bills + KOT";
  }

  private LinearLayout header(String title, String detail, Runnable back, Button action) {
    LinearLayout head = row();
    head.setGravity(Gravity.CENTER_VERTICAL);
    View close = AppBackButton.create(this, v -> back.run());
    head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
    LinearLayout copy = column();
    copy.setPadding(dp(11), 0, dp(8), 0);
    copy.addView(text(title, 25, ink, true));
    copy.addView(text(detail, 13, muted, false));
    head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    if (action != null) head.addView(action, new LinearLayout.LayoutParams(dp(82), dp(44)));
    return head;
  }

  private LinearLayout section(String title, String detail) {
    LinearLayout box = column();
    box.addView(text(title, 18, ink, true));
    box.addView(text(detail, 13, muted, false), top(3));
    return box;
  }

  private LinearLayout field(String label, View control) {
    LinearLayout box = FormControls.field(this, label, control);
    LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
    outer.topMargin = dp(15);
    box.setLayoutParams(outer);
    return box;
  }

  private EditText input(String hint) {
    return FormControls.input(this, hint, android.text.InputType.TYPE_CLASS_TEXT);
  }

  private Spinner spinner(String[] values) {
    Spinner value = new Spinner(this);
    value.setPadding(dp(8), 0, dp(8), 0);
    value.setBackground(shape(Color.rgb(248, 250, 252), 8, 1, line));
    value.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
    return value;
  }

  private Button choiceButton(String value) {
    Button choice = button(value, Color.rgb(248, 250, 252), ink);
    choice.setTextSize(14);
    return choice;
  }

  private Button quietButton(String value) {
    Button result = button(value, Color.rgb(248, 250, 252), ink);
    result.setTextSize(14);
    return result;
  }

  private LinearLayout choiceRow(Button first, Button second) {
    LinearLayout choices = row();
    choices.addView(first, new LinearLayout.LayoutParams(0, dp(56), 1));
    LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(56), 1);
    secondParams.leftMargin = dp(8);
    choices.addView(second, secondParams);
    return choices;
  }

  private TextView tag(String value) {
    TextView view = text(value, 11, teal, true);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(9), dp(5), dp(9), dp(5));
    view.setBackground(shape(paleTeal, 16, 1, Color.rgb(148, 210, 218)));
    return view;
  }

  private void clearEditorReferences() {
    name = null;
    address = null;
    port = null;
    device = null;
    primary = null;
    networkFields = null;
    bluetoothFields = null;
    bluetoothChoice = null;
    networkChoice = null;
    paper80Choice = null;
    paper58Choice = null;
    bothRoleChoice = null;
    billRoleChoice = null;
    kotRoleChoice = null;
    editingId = "";
    editingBluetoothAddress = "";
  }

  private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
  private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); return value; }
  private TextView text(String value, int size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT_BOLD); return view; }
  private Button button(String value, int fill, int color) { Button view = new Button(this); view.setText(value); view.setAllCaps(false); view.setTextColor(color); view.setTypeface(Typeface.DEFAULT_BOLD); view.setBackground(shape(fill, 8, 1, line)); return view; }
  private GradientDrawable shape(int fill, int radius, int stroke, int strokeColor) { GradientDrawable value = new GradientDrawable(); value.setColor(fill); value.setCornerRadius(dp(radius)); if (stroke > 0) value.setStroke(dp(stroke), strokeColor); return value; }
  private LinearLayout.LayoutParams top(int margin) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2); value.topMargin = dp(margin); return value; }
  private LinearLayout.LayoutParams left(int margin) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-2, -2); value.leftMargin = dp(margin); return value; }
  private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
}
