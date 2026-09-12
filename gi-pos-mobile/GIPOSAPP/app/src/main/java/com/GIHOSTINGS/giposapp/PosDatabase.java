package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import android.database.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PosDatabase extends SQLiteOpenHelper {
  private static final String NAME = "gi_pos_mobile.db";
  private static final int VERSION = 9;
  private static PosDatabase instance;
  private final Context appContext;

  public static synchronized PosDatabase get(Context context) {
    if (instance == null) instance = new PosDatabase(context.getApplicationContext());
    return instance;
  }
  private PosDatabase(Context context) { super(context, NAME, null, VERSION); appContext=context.getApplicationContext();setWriteAheadLoggingEnabled(true); }

  @Override public void onConfigure(SQLiteDatabase db) {
    super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.rawQuery("PRAGMA busy_timeout=5000", null).close();
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE floor_groups (id TEXT PRIMARY KEY, name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE dining_tables (id TEXT PRIMARY KEY, floor_id TEXT, name TEXT NOT NULL, seats INTEGER NOT NULL DEFAULT 4, row_index INTEGER NOT NULL DEFAULT 0, column_index INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, is_duplicate INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, FOREIGN KEY(floor_id) REFERENCES floor_groups(id))");
    db.execSQL("CREATE INDEX dining_tables_floor_sort_idx ON dining_tables(floor_id, sort_order)");
    db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE products (id TEXT PRIMARY KEY, category_id TEXT, name TEXT NOT NULL, alias TEXT NOT NULL DEFAULT '', price REAL NOT NULL DEFAULT 0, tax_rate REAL NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, description TEXT NOT NULL DEFAULT '', kot_description TEXT NOT NULL DEFAULT '', price_mode TEXT NOT NULL DEFAULT 'fixed', gst_mode TEXT NOT NULL DEFAULT 'exclusive', gst_type TEXT NOT NULL DEFAULT 'cgst_sgst', favorite INTEGER NOT NULL DEFAULT 0, special INTEGER NOT NULL DEFAULT 0, hot_item INTEGER NOT NULL DEFAULT 0, kot_route TEXT NOT NULL DEFAULT '', image_uri TEXT NOT NULL DEFAULT '', sync_state TEXT NOT NULL DEFAULT 'pending', FOREIGN KEY(category_id) REFERENCES categories(id))");
    db.execSQL("CREATE INDEX products_category_sort_idx ON products(category_id, sort_order)");
    db.execSQL("CREATE INDEX products_name_idx ON products(name COLLATE NOCASE)");
    createProductVariantTable(db);
    db.execSQL("CREATE TABLE orders (id TEXT PRIMARY KEY, table_id TEXT, kot_number INTEGER, bill_number INTEGER, kot_period TEXT NOT NULL DEFAULT '', bill_period TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, subtotal REAL NOT NULL DEFAULT 0, tax_total REAL NOT NULL DEFAULT 0, discount_total REAL NOT NULL DEFAULT 0, grand_total REAL NOT NULL DEFAULT 0, discount_mode TEXT NOT NULL DEFAULT 'none', discount_value REAL NOT NULL DEFAULT 0, customer_id TEXT, amount_received REAL NOT NULL DEFAULT 0, balance_return REAL NOT NULL DEFAULT 0, due_amount REAL NOT NULL DEFAULT 0, payment_status TEXT NOT NULL DEFAULT 'unpaid', receipt_snapshot TEXT NOT NULL DEFAULT '', version INTEGER NOT NULL DEFAULT 0, order_type TEXT NOT NULL DEFAULT 'dine_in', delivery_name TEXT NOT NULL DEFAULT '', delivery_phone TEXT NOT NULL DEFAULT '', delivery_address TEXT NOT NULL DEFAULT '', order_note TEXT NOT NULL DEFAULT '', opened_at INTEGER NOT NULL, closed_at INTEGER, finalized_at INTEGER, cancelled_at INTEGER, cancel_reason TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending', FOREIGN KEY(table_id) REFERENCES dining_tables(id))");
    db.execSQL("CREATE INDEX orders_status_updated_idx ON orders(status, updated_at DESC)");
    db.execSQL("CREATE TABLE order_items (id TEXT PRIMARY KEY, order_id TEXT NOT NULL, product_id TEXT, item_name TEXT NOT NULL, variant_name TEXT NOT NULL DEFAULT '', quantity REAL NOT NULL, unit_price REAL NOT NULL, tax_rate REAL NOT NULL DEFAULT 0, tax_mode TEXT NOT NULL DEFAULT 'exclusive', tax_type TEXT NOT NULL DEFAULT 'cgst_sgst', tax_amount REAL NOT NULL DEFAULT 0, line_total REAL NOT NULL, note TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE)");
    db.execSQL("CREATE INDEX order_items_order_idx ON order_items(order_id)");
    db.execSQL("CREATE TABLE customers (id TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', address TEXT NOT NULL DEFAULT '', due_balance REAL NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX customers_phone_idx ON customers(phone)");
    db.execSQL("CREATE TABLE payments (id TEXT PRIMARY KEY, order_id TEXT NOT NULL, method TEXT NOT NULL, gateway_id TEXT NOT NULL DEFAULT '', amount REAL NOT NULL, reference TEXT NOT NULL DEFAULT '', idempotency_key TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE)");
    db.execSQL("CREATE UNIQUE INDEX payments_idempotency_idx ON payments(idempotency_key) WHERE idempotency_key<>''");
    createPaymentGatewayTable(db);
    db.execSQL("CREATE TABLE print_jobs (id TEXT PRIMARY KEY, order_id TEXT, job_type TEXT NOT NULL, printer_id TEXT, payload TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '', idempotency_key TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX print_jobs_status_idx ON print_jobs(status, created_at)");
    db.execSQL("CREATE UNIQUE INDEX print_jobs_idempotency_idx ON print_jobs(idempotency_key) WHERE idempotency_key<>''");
    createBillingTables(db);
    createPrinterProfileTable(db);
    db.execSQL("CREATE TABLE sync_queue (id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, operation TEXT NOT NULL, payload TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX sync_queue_due_idx ON sync_queue(next_attempt_at, created_at)");
    createStaffTable(db);
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if(oldVersion<2){
      addColumn(db,"products","description TEXT NOT NULL DEFAULT ''");
      addColumn(db,"products","kot_description TEXT NOT NULL DEFAULT ''");
      addColumn(db,"products","price_mode TEXT NOT NULL DEFAULT 'fixed'");
      addColumn(db,"products","gst_mode TEXT NOT NULL DEFAULT 'exclusive'");
      addColumn(db,"products","gst_type TEXT NOT NULL DEFAULT 'cgst_sgst'");
      addColumn(db,"products","favorite INTEGER NOT NULL DEFAULT 0");
      addColumn(db,"products","special INTEGER NOT NULL DEFAULT 0");
      addColumn(db,"products","hot_item INTEGER NOT NULL DEFAULT 0");
      addColumn(db,"products","kot_route TEXT NOT NULL DEFAULT ''");
      addColumn(db,"products","image_uri TEXT NOT NULL DEFAULT ''");
      addColumn(db,"products","sync_state TEXT NOT NULL DEFAULT 'pending'");
      addColumn(db,"order_items","tax_mode TEXT NOT NULL DEFAULT 'exclusive'");
      addColumn(db,"order_items","tax_type TEXT NOT NULL DEFAULT 'cgst_sgst'");
      addColumn(db,"order_items","tax_amount REAL NOT NULL DEFAULT 0");
      createProductVariantTable(db);
    }
    if(oldVersion<3){
      addColumn(db,"orders","kot_period TEXT NOT NULL DEFAULT ''");
      addColumn(db,"orders","bill_period TEXT NOT NULL DEFAULT ''");
      addColumn(db,"orders","discount_mode TEXT NOT NULL DEFAULT 'none'");
      addColumn(db,"orders","discount_value REAL NOT NULL DEFAULT 0");
      addColumn(db,"orders","amount_received REAL NOT NULL DEFAULT 0");
      addColumn(db,"orders","balance_return REAL NOT NULL DEFAULT 0");
      addColumn(db,"orders","due_amount REAL NOT NULL DEFAULT 0");
      addColumn(db,"orders","payment_status TEXT NOT NULL DEFAULT 'unpaid'");
      addColumn(db,"orders","receipt_snapshot TEXT NOT NULL DEFAULT ''");
      addColumn(db,"orders","version INTEGER NOT NULL DEFAULT 0");
      addColumn(db,"orders","finalized_at INTEGER");
      addColumn(db,"payments","reference TEXT NOT NULL DEFAULT ''");
      addColumn(db,"payments","idempotency_key TEXT NOT NULL DEFAULT ''");
      addColumn(db,"print_jobs","idempotency_key TEXT NOT NULL DEFAULT ''");
      createBillingTables(db);
      backfillOrderPeriodsAndCounters(db);
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS payments_idempotency_idx ON payments(idempotency_key) WHERE idempotency_key<>''");
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS print_jobs_idempotency_idx ON print_jobs(idempotency_key) WHERE idempotency_key<>''");
    }
    if(oldVersion<4)createPrinterProfileTable(db);
    if(oldVersion<5){
      addColumn(db,"payments","gateway_id TEXT NOT NULL DEFAULT ''");
      createPaymentGatewayTable(db);
    }
    if(oldVersion<6){
      addColumn(db,"orders","cancelled_at INTEGER");
      addColumn(db,"orders","cancel_reason TEXT NOT NULL DEFAULT ''");
    }
    if(oldVersion<7){
      addColumn(db,"orders","order_type TEXT NOT NULL DEFAULT 'dine_in'");
      addColumn(db,"orders","delivery_name TEXT NOT NULL DEFAULT ''");
      addColumn(db,"orders","delivery_phone TEXT NOT NULL DEFAULT ''");
      addColumn(db,"orders","delivery_address TEXT NOT NULL DEFAULT ''");
      addColumn(db,"orders","order_note TEXT NOT NULL DEFAULT ''");
    }
    if(oldVersion<8)createStaffTable(db);
    if(oldVersion<9)addColumn(db,"dining_tables","is_duplicate INTEGER NOT NULL DEFAULT 0");
  }

  // Local mirror of the cloud staff directory (pos-staff-users). Lets every device
  // that logs into the same account see the same staff, their permissions and table
  // access — and keeps the User management list available even when briefly offline.
  private static void createStaffTable(SQLiteDatabase db){
    db.execSQL("CREATE TABLE IF NOT EXISTS staff (id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', mobile TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'Staff', staff_code TEXT NOT NULL DEFAULT '', permissions TEXT NOT NULL DEFAULT '[]', table_access TEXT NOT NULL DEFAULT '[]', active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE INDEX IF NOT EXISTS staff_mobile_idx ON staff(mobile)");
  }
  private static void createPaymentGatewayTable(SQLiteDatabase db){
    db.execSQL("CREATE TABLE IF NOT EXISTS payment_gateways (id TEXT PRIMARY KEY, name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX IF NOT EXISTS payment_gateways_active_idx ON payment_gateways(active, sort_order, name COLLATE NOCASE)");
  }
  private static void addColumn(SQLiteDatabase db,String table,String definition){try{db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+definition);}catch(Exception ignored){}}
  private static void createProductVariantTable(SQLiteDatabase db){
    db.execSQL("CREATE TABLE IF NOT EXISTS product_variants (id TEXT PRIMARY KEY, product_id TEXT NOT NULL, name TEXT NOT NULL, price REAL NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL, FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE)");
    db.execSQL("CREATE INDEX IF NOT EXISTS product_variants_product_idx ON product_variants(product_id, sort_order)");
  }
  private static void createBillingTables(SQLiteDatabase db){
    db.execSQL("CREATE TABLE IF NOT EXISTS number_counters (counter_type TEXT NOT NULL, period_key TEXT NOT NULL, last_value INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(counter_type,period_key))");
    db.execSQL("CREATE TABLE IF NOT EXISTS customer_ledger (id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, order_id TEXT, entry_type TEXT NOT NULL, amount REAL NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, FOREIGN KEY(customer_id) REFERENCES customers(id), FOREIGN KEY(order_id) REFERENCES orders(id))");
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS customer_ledger_order_due_idx ON customer_ledger(order_id,entry_type) WHERE order_id IS NOT NULL");
    db.execSQL("CREATE TABLE IF NOT EXISTS order_events (id TEXT PRIMARY KEY, order_id TEXT NOT NULL, event_type TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE)");
    db.execSQL("CREATE INDEX IF NOT EXISTS order_events_order_idx ON order_events(order_id,created_at)");
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS orders_bill_period_number_unique ON orders(bill_period,bill_number) WHERE bill_number IS NOT NULL AND bill_period<>''");
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS orders_kot_period_number_unique ON orders(kot_period,kot_number) WHERE kot_number IS NOT NULL AND kot_period<>''");
  }
  private static void createPrinterProfileTable(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS printer_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, connection_type TEXT NOT NULL, address TEXT NOT NULL, port INTEGER NOT NULL DEFAULT 9100, paper_width INTEGER NOT NULL DEFAULT 80, role TEXT NOT NULL DEFAULT 'both', is_default INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS printer_profiles_role_idx ON printer_profiles(role,is_default DESC,updated_at DESC)");}
  private static void backfillOrderPeriodsAndCounters(SQLiteDatabase db){long now=System.currentTimeMillis();try(Cursor c=db.rawQuery("SELECT id,bill_number,kot_number,COALESCE(finalized_at,closed_at,updated_at,opened_at) FROM orders WHERE bill_number IS NOT NULL OR kot_number IS NOT NULL",null)){while(c.moveToNext()){ContentValues row=new ContentValues();long timestamp=c.getLong(3);if(!c.isNull(1))row.put("bill_period",financialYearKey(timestamp));if(!c.isNull(2))row.put("kot_period",dailyKey(timestamp));db.update("orders",row,"id=?",new String[]{c.getString(0)});}}try(Cursor c=db.rawQuery("SELECT bill_period,MAX(bill_number) FROM orders WHERE bill_number IS NOT NULL AND bill_period<>'' GROUP BY bill_period",null)){while(c.moveToNext())upsertCounter(db,"bill",c.getString(0),c.getInt(1),now);}try(Cursor c=db.rawQuery("SELECT kot_period,MAX(kot_number) FROM orders WHERE kot_number IS NOT NULL AND kot_period<>'' GROUP BY kot_period",null)){while(c.moveToNext())upsertCounter(db,"kot",c.getString(0),c.getInt(1),now);}}
  private static void upsertCounter(SQLiteDatabase db,String type,String period,int value,long now){ContentValues row=new ContentValues();row.put("counter_type",type);row.put("period_key",period);row.put("last_value",value);row.put("updated_at",now);db.insertWithOnConflict("number_counters",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
  public int pendingSyncCount() { try (android.database.Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sync_queue", null)) { return cursor.moveToFirst() ? cursor.getInt(0) : 0; } }
  public synchronized void backupTo(OutputStream destination)throws Exception{SQLiteDatabase db=getWritableDatabase();try(Cursor ignored=db.rawQuery("PRAGMA wal_checkpoint(FULL)",null)){if(ignored.moveToFirst()){} }File source=appContext.getDatabasePath(NAME);try(FileInputStream input=new FileInputStream(source)){copy(input,destination);}destination.flush();}
  public synchronized void restoreFrom(InputStream source)throws Exception{File database=appContext.getDatabasePath(NAME),temporary=new File(database.getParentFile(),NAME+".restore"),previous=new File(database.getParentFile(),NAME+".previous");try(FileOutputStream output=new FileOutputStream(temporary)){copy(source,output);output.flush();}try(FileInputStream check=new FileInputStream(temporary)){byte[] header=new byte[16];if(check.read(header)!=16||!new String(header,java.nio.charset.StandardCharsets.US_ASCII).startsWith("SQLite format 3")){temporary.delete();throw new IllegalArgumentException("The selected file is not a valid SQLite backup");}}close();instance=null;if(previous.exists())previous.delete();if(database.exists()&&!database.renameTo(previous)){temporary.delete();throw new IllegalStateException("Could not secure the current database");}if(!temporary.renameTo(database)){if(previous.exists())previous.renameTo(database);throw new IllegalStateException("Could not restore the selected backup");}File wal=new File(database.getPath()+"-wal"),shm=new File(database.getPath()+"-shm");wal.delete();shm.delete();try{PosDatabase.get(appContext).getReadableDatabase().rawQuery("PRAGMA integrity_check",null).close();previous.delete();}catch(Exception error){PosDatabase.get(appContext).close();instance=null;database.delete();previous.renameTo(database);throw new IllegalStateException("Backup validation failed; the original database was restored",error);}}
  private static void copy(InputStream input,OutputStream output)throws Exception{byte[] buffer=new byte[64*1024];int read;while((read=input.read(buffer))!=-1)output.write(buffer,0,read);}

  public static final class FloorInfo { public final String id,name; public FloorInfo(String id,String name){this.id=id;this.name=name;} }
  public static final class TableInfo {
    public final String id,floorId,name,status,orderId; public final int seats; public final boolean isDuplicate;
    TableInfo(String id,String floorId,String name,int seats,String status,String orderId){this(id,floorId,name,seats,status,orderId,false);}
    TableInfo(String id,String floorId,String name,int seats,String status,String orderId,boolean isDuplicate){this.id=id;this.floorId=floorId;this.name=name;this.seats=seats;this.status=status;this.orderId=orderId;this.isDuplicate=isDuplicate;}
  }
  public static final class CategoryInfo { public final String id,name; public CategoryInfo(String id,String name){this.id=id;this.name=name;} @Override public String toString(){return name;} }
  public static final class VariantInfo { public final String id,name; public final double price; public VariantInfo(String id,String name,double price){this.id=id;this.name=name;this.price=price;} }
  public static final class ProductInfo {
    public final String id,categoryId,categoryName,name,alias,description,kotDescription,priceMode,gstMode,gstType,kotRoute,imageUri;
    public final double price,taxRate; public final boolean available,favorite,special,hotItem; public final List<VariantInfo> variants;
    ProductInfo(String id,String categoryId,String categoryName,String name,String alias,double price,double taxRate,boolean available,String description,String kotDescription,String priceMode,String gstMode,String gstType,boolean favorite,boolean special,boolean hotItem,String kotRoute,String imageUri,List<VariantInfo> variants){this.id=id;this.categoryId=categoryId;this.categoryName=categoryName;this.name=name;this.alias=alias;this.price=price;this.taxRate=taxRate;this.available=available;this.description=description;this.kotDescription=kotDescription;this.priceMode=priceMode;this.gstMode=gstMode;this.gstType=gstType;this.favorite=favorite;this.special=special;this.hotItem=hotItem;this.kotRoute=kotRoute;this.imageUri=imageUri;this.variants=variants;}
  }
  public static final class OrderLine {
    public final String id,productId,itemName,variantName,taxMode,note; public final double quantity,unitPrice,taxRate,taxAmount,lineTotal;
    OrderLine(String id,String productId,String itemName,String variantName,double quantity,double unitPrice,double taxRate,String taxMode,double taxAmount,double lineTotal){this(id,productId,itemName,variantName,quantity,unitPrice,taxRate,taxMode,taxAmount,lineTotal,"");}
    OrderLine(String id,String productId,String itemName,String variantName,double quantity,double unitPrice,double taxRate,String taxMode,double taxAmount,double lineTotal,String note){this.id=id;this.productId=productId;this.itemName=itemName;this.variantName=variantName;this.quantity=quantity;this.unitPrice=unitPrice;this.taxRate=taxRate;this.taxMode=taxMode;this.taxAmount=taxAmount;this.lineTotal=lineTotal;this.note=note==null?"":note;}
  }
  public static final class CustomerInfo {public final String id,name,phone,address;public final double dueBalance;CustomerInfo(String id,String name,String phone,String address,double dueBalance){this.id=id;this.name=name;this.phone=phone;this.address=address;this.dueBalance=dueBalance;}@Override public String toString(){return name+(phone.isEmpty()?"":"  •  "+phone);}}
  public static final class CheckoutTotals {public final double subtotal,tax,discount,payable;CheckoutTotals(double subtotal,double tax,double discount,double payable){this.subtotal=subtotal;this.tax=tax;this.discount=discount;this.payable=payable;}}
  public static final class CheckoutRequest {
    public final String discountMode,customerId;public final double discountValue,cash,upi,card,due,cashReceived;public final boolean printRequested;
    public final java.util.Map<String,Double> gatewayAllocations;
    public CheckoutRequest(String discountMode,double discountValue,String customerId,double cash,double upi,double card,double due,double cashReceived,boolean printRequested){this(discountMode,discountValue,customerId,cash,upi,card,due,cashReceived,printRequested,new java.util.HashMap<>());}
    public CheckoutRequest(String discountMode,double discountValue,String customerId,double cash,double upi,double card,double due,double cashReceived,boolean printRequested,java.util.Map<String,Double> gatewayAllocations){this.discountMode=discountMode;this.discountValue=discountValue;this.customerId=customerId==null?"":customerId;this.cash=cash;this.upi=upi;this.card=card;this.due=due;this.cashReceived=cashReceived;this.printRequested=printRequested;this.gatewayAllocations=gatewayAllocations==null?new java.util.HashMap<>():gatewayAllocations;}
  }
  public static final class OrderInfo {
    public final String id,tableName,status,billPeriod,kotPeriod,paymentStatus,customerName,receiptSnapshot;public final Integer billNumber,kotNumber;public final double subtotal,tax,discount,total,received,balance,due;
    public final String orderType,deliveryName,deliveryPhone,deliveryAddress,orderNote;
    OrderInfo(String id,String tableName,String status,String billPeriod,Integer billNumber,String kotPeriod,Integer kotNumber,String paymentStatus,String customerName,double subtotal,double tax,double discount,double total,double received,double balance,double due,String receiptSnapshot,String orderType,String deliveryName,String deliveryPhone,String deliveryAddress,String orderNote){this.id=id;this.tableName=tableName;this.status=status;this.billPeriod=billPeriod;this.billNumber=billNumber;this.kotPeriod=kotPeriod;this.kotNumber=kotNumber;this.paymentStatus=paymentStatus;this.customerName=customerName;this.subtotal=subtotal;this.tax=tax;this.discount=discount;this.total=total;this.received=received;this.balance=balance;this.due=due;this.receiptSnapshot=receiptSnapshot;this.orderType=orderType==null||orderType.isEmpty()?"dine_in":orderType;this.deliveryName=deliveryName==null?"":deliveryName;this.deliveryPhone=deliveryPhone==null?"":deliveryPhone;this.deliveryAddress=deliveryAddress==null?"":deliveryAddress;this.orderNote=orderNote==null?"":orderNote;}
    // Human-friendly label: table name, or the order-type name for tableless orders.
    public String serviceLabel(){if("delivery".equals(orderType))return "Delivery";if("takeaway".equals(orderType))return "Takeaway";return tableName==null||tableName.isEmpty()?"Direct":tableName;}
  }
  public static String orderTypeLabel(String orderType){if("delivery".equals(orderType))return "Delivery";if("takeaway".equals(orderType))return "Takeaway";return "Dine-in";}
  public static final class CheckoutResult {public final OrderInfo order;public final boolean alreadyFinalized,printQueued;CheckoutResult(OrderInfo order,boolean alreadyFinalized,boolean printQueued){this.order=order;this.alreadyFinalized=alreadyFinalized;this.printQueued=printQueued;}}
  private static final class TotalsMinor {long subtotal,tax,exclusiveTax,discount,payable;}
  public static final class PrinterProfile {public final String id,name,connectionType,address,role;public final int port,paperWidth;public final boolean isDefault;public PrinterProfile(String id,String name,String connectionType,String address,int port,int paperWidth,String role,boolean isDefault){this.id=id;this.name=name;this.connectionType=connectionType;this.address=address;this.port=port;this.paperWidth=paperWidth;this.role=role;this.isDefault=isDefault;}}
  public static final class PrintJobInfo {public final String id,orderId,type,payload;public final int attempts;PrintJobInfo(String id,String orderId,String type,String payload,int attempts){this.id=id;this.orderId=orderId;this.type=type;this.payload=payload;this.attempts=attempts;}}
  public static final class FailedPrintJob {public final String id,orderId,type,error,tableName;public final Integer billNumber,kotNumber;public final int attempts;public final long updatedAt;FailedPrintJob(String id,String orderId,String type,int attempts,String error,long updatedAt,String tableName,Integer billNumber,Integer kotNumber){this.id=id;this.orderId=orderId;this.type=type;this.attempts=attempts;this.error=error;this.updatedAt=updatedAt;this.tableName=tableName;this.billNumber=billNumber;this.kotNumber=kotNumber;}}
  public static final class NumberingSettings {public final String billPeriod,kotPeriod;public final int nextBillNumber,nextKotNumber;NumberingSettings(String billPeriod,String kotPeriod,int nextBillNumber,int nextKotNumber){this.billPeriod=billPeriod;this.kotPeriod=kotPeriod;this.nextBillNumber=nextBillNumber;this.nextKotNumber=nextKotNumber;}}
  public static final class ReportItem {public final String name;public final double quantity,amount;ReportItem(String name,double quantity,double amount){this.name=name;this.quantity=quantity;this.amount=amount;}}
  public static final class ReportSummary {public final long from,to;public final int bills;public final double sales,tax,discount,due,cash,upi,card,totalQuantity;public final List<ReportItem> items;public final List<PaymentTotal> methodTotals;public final List<PaymentTotal> gatewayTotals;public final String filterLabel;ReportSummary(long from,long to,int bills,double sales,double tax,double discount,double due,double cash,double upi,double card,double totalQuantity,List<ReportItem> items){this(from,to,bills,sales,tax,discount,due,cash,upi,card,totalQuantity,items,new ArrayList<>(),new ArrayList<>(),"All payments");}ReportSummary(long from,long to,int bills,double sales,double tax,double discount,double due,double cash,double upi,double card,double totalQuantity,List<ReportItem> items,List<PaymentTotal> methodTotals,List<PaymentTotal> gatewayTotals,String filterLabel){this.from=from;this.to=to;this.bills=bills;this.sales=sales;this.tax=tax;this.discount=discount;this.due=due;this.cash=cash;this.upi=upi;this.card=card;this.totalQuantity=totalQuantity;this.items=items;this.methodTotals=methodTotals;this.gatewayTotals=gatewayTotals;this.filterLabel=filterLabel;}}
  public static final class OrderListItem {
    public final String id,tableName,status,orderType; public final Integer billNumber,kotNumber; public final int itemCount; public final double total; public final long updatedAt;
    OrderListItem(String id,String tableName,String status,Integer billNumber,Integer kotNumber,int itemCount,double total,long updatedAt){this(id,tableName,status,billNumber,kotNumber,itemCount,total,updatedAt,"dine_in");}
    OrderListItem(String id,String tableName,String status,Integer billNumber,Integer kotNumber,int itemCount,double total,long updatedAt,String orderType){this.id=id;this.tableName=tableName;this.status=status;this.billNumber=billNumber;this.kotNumber=kotNumber;this.itemCount=itemCount;this.total=total;this.updatedAt=updatedAt;this.orderType=orderType==null||orderType.isEmpty()?"dine_in":orderType;}
    public boolean isDineIn(){return "dine_in".equals(orderType);}
  }
  public static final class BusinessSettings {
    public final String name,address,phone,taxId,footer,logoUri,template; public final boolean upi,card,due,split;
    BusinessSettings(String name,String address,String phone,String taxId,String footer,boolean upi,boolean card,boolean due,boolean split){this(name,address,phone,taxId,footer,upi,card,due,split,"","classic");}
    BusinessSettings(String name,String address,String phone,String taxId,String footer,boolean upi,boolean card,boolean due,boolean split,String logoUri){this(name,address,phone,taxId,footer,upi,card,due,split,logoUri,"classic");}
    BusinessSettings(String name,String address,String phone,String taxId,String footer,boolean upi,boolean card,boolean due,boolean split,String logoUri,String template){this.name=name;this.address=address;this.phone=phone;this.taxId=taxId;this.footer=footer;this.upi=upi;this.card=card;this.due=due;this.split=split;this.logoUri=logoUri==null?"":logoUri;this.template=template==null||template.isEmpty()?"classic":template;}
  }
  // Global GST configuration. When enabled, it becomes the default tax applied
  // to every order line (ordering/KOT/checkout/billing) for consistency.
  public static final class GstSettings {
    public final boolean enabled; public final double rate; public final String mode,type; // mode: inclusive|exclusive ; type: cgst_sgst|igst|vat|none
    public GstSettings(boolean enabled,double rate,String mode,String type){this.enabled=enabled;this.rate=rate;this.mode=("inclusive".equals(mode)?"inclusive":"exclusive");this.type=(type==null||type.isEmpty()?"cgst_sgst":type);}
  }
  public static final class OrderingPreferences {
    public final String browseMode; public final int columns;
    OrderingPreferences(String browseMode,int columns){this.browseMode=browseMode;this.columns=columns;}
  }
  public static final class PaymentGateway {
    public final String id,name; public final boolean active;
    PaymentGateway(String id,String name,boolean active){this.id=id;this.name=name;this.active=active;}
    @Override public String toString(){return name;}
  }
  // A single method/gateway total row used by the redesigned reports.
  public static final class PaymentTotal {
    public final String key,label; public final double amount; public final int count;
    PaymentTotal(String key,String label,double amount,int count){this.key=key;this.label=label;this.amount=amount;this.count=count;}
  }
  public static final class SyncChange {public final String id,key;public final JSONObject value;SyncChange(String id,String key,JSONObject value){this.id=id;this.key=key;this.value=value;}public JSONObject json()throws Exception{return new JSONObject().put("id",id).put("entityType","app_kv").put("entityId",key).put("operation","upsert").put("payload",new JSONObject().put("key",key).put("value",value));}}

  public void seedDefaultTables() {
    SQLiteDatabase db=getWritableDatabase();
    try(Cursor cursor=db.rawQuery("SELECT COUNT(*) FROM dining_tables",null)){if(cursor.moveToFirst()&&cursor.getInt(0)>0)return;}
    long now=System.currentTimeMillis(); db.beginTransaction();
    try {
      String floor="floor-main"; ContentValues group=new ContentValues(); group.put("id",floor);group.put("name","Main Hall");group.put("sort_order",0);group.put("updated_at",now);db.insertOrThrow("floor_groups",null,group);
      for(int i=1;i<=12;i++){ContentValues table=new ContentValues();table.put("id","table-"+i);table.put("floor_id",floor);table.put("name","T"+i);table.put("seats",4);table.put("row_index",(i-1)/3);table.put("column_index",(i-1)%3);table.put("sort_order",i);table.put("updated_at",now);db.insertOrThrow("dining_tables",null,table);}
      db.setTransactionSuccessful();
    } finally {db.endTransaction();}
    queueSync("floor","floor-main","upsert");
    for(int i=1;i<=12;i++)queueSync("table","table-"+i,"upsert");
  }

  public List<FloorInfo> floors(){List<FloorInfo> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name FROM floor_groups WHERE active=1 ORDER BY sort_order,name",null)){while(c.moveToNext())result.add(new FloorInfo(c.getString(0),c.getString(1)));}return result;}

  public List<CategoryInfo> categories(){List<CategoryInfo> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name FROM categories WHERE active=1 ORDER BY sort_order,name COLLATE NOCASE",null)){while(c.moveToNext())result.add(new CategoryInfo(c.getString(0),c.getString(1)));}return result;}
  public String addCategory(String name){String clean=name.trim();if(clean.isEmpty())throw new IllegalArgumentException("Category name is required");try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM categories WHERE lower(name)=lower(?) LIMIT 1",new String[]{clean})){if(c.moveToFirst())return c.getString(0);}String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("name",clean);row.put("sort_order",(int)(System.currentTimeMillis()%100000));row.put("updated_at",System.currentTimeMillis());getWritableDatabase().insertOrThrow("categories",null,row);queueSync("category",id,"upsert");return id;}

  public List<ProductInfo> products(String categoryId,String query,Boolean availableOnly){
    List<ProductInfo> result=new ArrayList<>();List<String> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1 ");
    if(categoryId!=null&&!categoryId.isEmpty()){where.append("AND p.category_id=? ");args.add(categoryId);}if(query!=null&&!query.trim().isEmpty()){where.append("AND (p.name LIKE ? OR p.alias LIKE ?) ");String like="%"+query.trim()+"%";args.add(like);args.add(like);}if(availableOnly!=null){where.append("AND p.available=? ");args.add(availableOnly?"1":"0");}
    String sql="SELECT p.id,COALESCE(p.category_id,''),COALESCE(c.name,''),p.name,p.alias,p.price,p.tax_rate,p.available,p.description,p.kot_description,p.price_mode,p.gst_mode,p.gst_type,p.favorite,p.special,p.hot_item,p.kot_route,p.image_uri FROM products p LEFT JOIN categories c ON c.id=p.category_id"+where+"ORDER BY c.sort_order,p.sort_order,p.name COLLATE NOCASE";
    // Load variants for variant-priced items so the ordering grid can list each
    // size directly under the item (base/manual items skip the extra query).
    try(Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]))){while(c.moveToNext()){boolean variantItem="variants".equals(c.getString(10));result.add(productFromCursor(c,variantItem));}}return result;
  }
  public ProductInfo product(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT p.id,COALESCE(p.category_id,''),COALESCE(c.name,''),p.name,p.alias,p.price,p.tax_rate,p.available,p.description,p.kot_description,p.price_mode,p.gst_mode,p.gst_type,p.favorite,p.special,p.hot_item,p.kot_route,p.image_uri FROM products p LEFT JOIN categories c ON c.id=p.category_id WHERE p.id=?",new String[]{id})){return c.moveToFirst()?productFromCursor(c,true):null;}}
  private ProductInfo productFromCursor(Cursor c,boolean includeVariants){String id=c.getString(0);return new ProductInfo(id,c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getDouble(5),c.getDouble(6),c.getInt(7)==1,c.getString(8),c.getString(9),c.getString(10),c.getString(11),c.getString(12),c.getInt(13)==1,c.getInt(14)==1,c.getInt(15)==1,c.getString(16),c.getString(17),includeVariants?variants(id):new ArrayList<>());}
  public List<VariantInfo> variants(String productId){List<VariantInfo> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,price FROM product_variants WHERE product_id=? AND active=1 ORDER BY sort_order,name COLLATE NOCASE",new String[]{productId})){while(c.moveToNext())result.add(new VariantInfo(c.getString(0),c.getString(1),c.getDouble(2)));}return result;}

  public String saveProduct(ProductInfo value,List<VariantInfo> variants){
    SQLiteDatabase db=getWritableDatabase();String id=value.id==null||value.id.isEmpty()?UUID.randomUUID().toString():value.id;long now=System.currentTimeMillis();db.beginTransaction();try{ContentValues row=new ContentValues();row.put("id",id);row.put("category_id",value.categoryId);row.put("name",value.name.trim());row.put("alias",value.alias.trim());row.put("price",value.price);row.put("tax_rate",value.taxRate);row.put("available",value.available?1:0);row.put("sort_order",(int)(now%100000));row.put("updated_at",now);row.put("description",value.description.trim());row.put("kot_description",value.kotDescription.trim());row.put("price_mode",value.priceMode);row.put("gst_mode",value.gstMode);row.put("gst_type",value.gstType);row.put("favorite",value.favorite?1:0);row.put("special",value.special?1:0);row.put("hot_item",value.hotItem?1:0);row.put("kot_route",value.kotRoute.trim());row.put("image_uri",value.imageUri.trim());row.put("sync_state","pending");db.insertWithOnConflict("products",null,row,SQLiteDatabase.CONFLICT_REPLACE);db.delete("product_variants","product_id=?",new String[]{id});int sort=0;for(VariantInfo variant:variants){ContentValues vr=new ContentValues();vr.put("id",variant.id==null||variant.id.isEmpty()?UUID.randomUUID().toString():variant.id);vr.put("product_id",id);vr.put("name",variant.name.trim());vr.put("price",variant.price);vr.put("sort_order",sort++);vr.put("updated_at",now);db.insertOrThrow("product_variants",null,vr);}db.setTransactionSuccessful();}finally{db.endTransaction();}queueSync("product",id,"upsert");return id;
  }
  public void setProductAvailable(String id,boolean available){ContentValues row=new ContentValues();row.put("available",available?1:0);row.put("updated_at",System.currentTimeMillis());row.put("sync_state","pending");getWritableDatabase().update("products",row,"id=?",new String[]{id});queueSync("product",id,"upsert");}

  public List<TableInfo> tables(String floorId){
    List<TableInfo> result=new ArrayList<>(); String where=floorId==null?"":"AND t.floor_id=?";String[] args=floorId==null?new String[0]:new String[]{floorId};
    String sql="SELECT t.id,t.floor_id,t.name,t.seats,COALESCE(o.status,'available'),COALESCE(o.id,''),COALESCE(t.is_duplicate,0) FROM dining_tables t LEFT JOIN orders o ON o.id=(SELECT x.id FROM orders x WHERE x.table_id=t.id AND x.status IN ('draft','kot','held','unclosed') AND EXISTS(SELECT 1 FROM order_items present WHERE present.order_id=x.id) ORDER BY x.updated_at DESC LIMIT 1) WHERE t.active=1 "+where+" ORDER BY t.sort_order,t.name";
    try(Cursor c=getReadableDatabase().rawQuery(sql,args)){while(c.moveToNext())result.add(new TableInfo(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getString(4),c.getString(5),c.getInt(6)==1));}return result;
  }

  public String openOrCreateOrder(String tableId){
    SQLiteDatabase db=getWritableDatabase();
    try(Cursor c=db.rawQuery("SELECT id FROM orders WHERE table_id=? AND status IN ('draft','kot','held','unclosed') ORDER BY updated_at DESC LIMIT 1",new String[]{tableId})){if(c.moveToFirst())return c.getString(0);}
    String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();ContentValues row=new ContentValues();row.put("id",id);row.put("table_id",tableId);row.put("status","draft");row.put("order_type","dine_in");row.put("opened_at",now);row.put("updated_at",now);db.insertOrThrow("orders",null,row);return id;
  }

  // Create a new tableless order for Delivery or Takeaway. Each tap starts a
  // fresh order (unlike dine-in which reuses the table's open order).
  public String createTypedOrder(String orderType,String deliveryName,String deliveryPhone,String deliveryAddress,String note){
    String type="delivery".equals(orderType)?"delivery":"takeaway".equals(orderType)?"takeaway":"dine_in";
    SQLiteDatabase db=getWritableDatabase();
    String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
    ContentValues row=new ContentValues();
    row.put("id",id);row.putNull("table_id");row.put("status","draft");row.put("order_type",type);
    row.put("delivery_name",deliveryName==null?"":deliveryName.trim());
    row.put("delivery_phone",deliveryPhone==null?"":deliveryPhone.trim());
    row.put("delivery_address",deliveryAddress==null?"":deliveryAddress.trim());
    row.put("order_note",note==null?"":note.trim());
    row.put("opened_at",now);row.put("updated_at",now);row.put("sync_state","pending");
    db.insertOrThrow("orders",null,row);
    // A draft with no items isn't synced until items are added (queueSync on item add),
    // matching dine-in behavior.
    return id;
  }

  // Cancel an active (not-yet-billed) order: draft/kot/held/unclosed. Voids it so
  // it stops appearing as active and frees the table. Reuses the same cancelled
  // status as finalized-bill cancellation.
  public synchronized void cancelActiveOrder(String orderId,String reason){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{OrderInfo info=orderInfo(db,orderId);if(info==null)throw new IllegalStateException("Order not found");if("cancelled".equals(info.status))throw new IllegalStateException("This order is already cancelled");if("paid".equals(info.status)||"due".equals(info.status))throw new IllegalStateException("This bill is already completed. Cancel it from Orders history instead.");long now=System.currentTimeMillis();String cleanReason=reason==null?"":reason.trim();ContentValues row=new ContentValues();row.put("status","cancelled");row.put("payment_status","cancelled");row.put("cancelled_at",now);row.put("cancel_reason",cleanReason);row.put("updated_at",now);row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});insertEvent(db,orderId,"cancelled",cleanReason,now);queueSync("order",orderId,"upsert");db.setTransactionSuccessful();}finally{db.endTransaction();}}

  public List<OrderLine> orderLines(String orderId){List<OrderLine> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,COALESCE(product_id,''),item_name,variant_name,quantity,unit_price,tax_rate,tax_mode,tax_amount,line_total,note FROM order_items WHERE order_id=? ORDER BY updated_at,id",new String[]{orderId})){while(c.moveToNext())result.add(new OrderLine(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getDouble(4),c.getDouble(5),c.getDouble(6),c.getString(7),c.getDouble(8),c.getDouble(9),c.getString(10)));}return result;}
  // Set/replace the free-text note on a single order line (per-item note).
  public void setOrderLineNote(String orderId,String lineId,String note){SQLiteDatabase db=getWritableDatabase();ContentValues row=new ContentValues();row.put("note",note==null?"":note.trim());row.put("updated_at",System.currentTimeMillis());db.update("order_items",row,"id=? AND order_id=?",new String[]{lineId,orderId});db.execSQL("UPDATE orders SET updated_at=?,sync_state='pending',version=version+1 WHERE id=?",new Object[]{System.currentTimeMillis(),orderId});queueSync("order",orderId,"upsert");}
  // Set/replace the order-level note (applies to the whole order/KOT/bill).
  public void setOrderNote(String orderId,String note){SQLiteDatabase db=getWritableDatabase();ContentValues row=new ContentValues();row.put("order_note",note==null?"":note.trim());row.put("updated_at",System.currentTimeMillis());row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});queueSync("order",orderId,"upsert");}
  public String orderNote(String orderId){try(Cursor c=getReadableDatabase().rawQuery("SELECT order_note FROM orders WHERE id=?",new String[]{orderId})){return c.moveToFirst()?c.getString(0):"";}}
  // Save/replace the delivery customer details (name, phone, address) on an order.
  public void setDeliveryDetails(String orderId,String name,String phone,String address){SQLiteDatabase db=getWritableDatabase();ContentValues row=new ContentValues();row.put("delivery_name",name==null?"":name.trim());row.put("delivery_phone",phone==null?"":phone.trim());row.put("delivery_address",address==null?"":address.trim());row.put("updated_at",System.currentTimeMillis());row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});queueSync("order",orderId,"upsert");}
  public CheckoutTotals checkoutTotals(String orderId,String discountMode,double discountValue){TotalsMinor totals=calculateTotals(getReadableDatabase(),orderId,discountMode,discountValue);return new CheckoutTotals(MoneyMath.fromMinor(totals.subtotal),MoneyMath.fromMinor(totals.tax),MoneyMath.fromMinor(totals.discount),MoneyMath.fromMinor(totals.payable));}
  private TotalsMinor calculateTotals(SQLiteDatabase db,String orderId,String discountMode,double discountValue){TotalsMinor value=new TotalsMinor();try(Cursor c=db.rawQuery("SELECT line_total,tax_amount,tax_mode FROM order_items WHERE order_id=?",new String[]{orderId})){while(c.moveToNext()){value.subtotal+=MoneyMath.toMinor(c.getDouble(0));long itemTax=MoneyMath.toMinor(c.getDouble(1));value.tax+=itemTax;if("exclusive".equals(c.getString(2)))value.exclusiveTax+=itemTax;}}long beforeDiscount=value.subtotal+value.exclusiveTax;if("percent".equals(discountMode))value.discount=MoneyMath.percentOf(beforeDiscount,Math.min(100,Math.max(0,discountValue)));else if("amount".equals(discountMode))value.discount=Math.min(beforeDiscount,Math.max(0,MoneyMath.toMinor(discountValue)));value.payable=Math.max(0,beforeDiscount-value.discount);return value;}

  public List<CustomerInfo> customers(String query){List<CustomerInfo> result=new ArrayList<>();String clean=query==null?"":query.trim();String like="%"+clean+"%";try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,phone,address,due_balance FROM customers WHERE ?='' OR name LIKE ? OR phone LIKE ? ORDER BY name COLLATE NOCASE LIMIT 200",new String[]{clean,like,like})){while(c.moveToNext())result.add(new CustomerInfo(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getDouble(4)));}return result;}
  public String saveCustomer(String name,String phone,String address){String cleanName=name==null?"":name.trim(),cleanPhone=phone==null?"":phone.trim();if(cleanName.isEmpty())throw new IllegalArgumentException("Customer name is required");SQLiteDatabase db=getWritableDatabase();String id=null;if(!cleanPhone.isEmpty())try(Cursor c=db.rawQuery("SELECT id FROM customers WHERE phone=? LIMIT 1",new String[]{cleanPhone})){if(c.moveToFirst())id=c.getString(0);}ContentValues row=new ContentValues();row.put("name",cleanName);row.put("phone",cleanPhone);row.put("address",address==null?"":address.trim());row.put("updated_at",System.currentTimeMillis());if(id==null){id=UUID.randomUUID().toString();row.put("id",id);db.insertOrThrow("customers",null,row);}else db.update("customers",row,"id=?",new String[]{id});queueSync("customer",id,"upsert");return id;}

  public synchronized CheckoutResult finalizeOrder(String orderId,CheckoutRequest request){
    SQLiteDatabase db=getWritableDatabase();boolean already=false,printQueued=false;db.beginTransaction();try{
      OrderInfo current=orderInfo(db,orderId);if(current==null)throw new IllegalStateException("Order not found");if(current.billNumber!=null&&("paid".equals(current.status)||"due".equals(current.status))){already=true;if(request.printRequested)printQueued=enqueuePrint(db,orderId,"receipt",current.receiptSnapshot,"receipt:"+orderId+":"+current.billNumber);db.setTransactionSuccessful();}else{
        TotalsMinor totals=calculateTotals(db,orderId,request.discountMode,request.discountValue);if(totals.subtotal<=0)throw new IllegalStateException("Add at least one item before billing");long cash=nonNegative(request.cash),upi=nonNegative(request.upi),card=nonNegative(request.card),due=nonNegative(request.due);java.util.LinkedHashMap<String,Long> gatewayMinor=new java.util.LinkedHashMap<>();long gatewayAllocated=0;for(java.util.Map.Entry<String,Double> entry:request.gatewayAllocations.entrySet()){String gatewayId=entry.getKey();if(gatewayId==null||gatewayId.isEmpty())continue;long amount=nonNegative(entry.getValue()==null?0:entry.getValue());if(amount<=0)continue;if(paymentGateway(gatewayId)==null)throw new IllegalStateException("Unknown payment gateway selected");gatewayMinor.put(gatewayId,amount);gatewayAllocated+=amount;}long allocated=cash+upi+card+due+gatewayAllocated;if(allocated!=totals.payable)throw new IllegalStateException("Payment total must match the bill total");if(due>0&&!customerExists(db,request.customerId))throw new IllegalStateException("Select a customer for due payment");long tendered=nonNegative(request.cashReceived);if(cash>0&&tendered<cash)throw new IllegalStateException("Cash received is less than the cash payment");long balance=Math.max(0,tendered-cash),now=System.currentTimeMillis();String billPeriod=financialYearKey(now),kotPeriod=dailyKey(now);int billNumber=nextNumber(db,"bill",billPeriod),kotNumber=current.kotNumber==null?nextNumber(db,"kot",kotPeriod):current.kotNumber;
        db.delete("payments","order_id=?",new String[]{orderId});insertPayment(db,orderId,"cash",cash,now);insertPayment(db,orderId,"upi",upi,now);insertPayment(db,orderId,"card",card,now);insertPayment(db,orderId,"due",due,now);for(java.util.Map.Entry<String,Long> entry:gatewayMinor.entrySet())insertPayment(db,orderId,"gateway",entry.getKey(),entry.getValue(),now);
        ContentValues order=new ContentValues();order.put("bill_number",billNumber);order.put("bill_period",billPeriod);if(current.kotNumber==null){order.put("kot_number",kotNumber);order.put("kot_period",kotPeriod);}order.put("status",due>0?"due":"paid");order.put("subtotal",MoneyMath.fromMinor(totals.subtotal));order.put("tax_total",MoneyMath.fromMinor(totals.tax));order.put("discount_total",MoneyMath.fromMinor(totals.discount));order.put("grand_total",MoneyMath.fromMinor(totals.payable));order.put("discount_mode",request.discountMode);order.put("discount_value",MoneyMath.round(request.discountValue));if(!request.customerId.isEmpty())order.put("customer_id",request.customerId);else order.putNull("customer_id");order.put("amount_received",MoneyMath.fromMinor(cash+upi+card+gatewayAllocated));order.put("balance_return",MoneyMath.fromMinor(balance));order.put("due_amount",MoneyMath.fromMinor(due));order.put("payment_status",due>0?"due":"paid");order.put("closed_at",now);order.put("finalized_at",now);order.put("updated_at",now);order.put("sync_state","pending");db.update("orders",order,"id=?",new String[]{orderId});
        if(due>0){db.execSQL("UPDATE customers SET due_balance=due_balance+?,updated_at=? WHERE id=?",new Object[]{MoneyMath.fromMinor(due),now,request.customerId});ContentValues ledger=new ContentValues();ledger.put("id",UUID.randomUUID().toString());ledger.put("customer_id",request.customerId);ledger.put("order_id",orderId);ledger.put("entry_type","due");ledger.put("amount",MoneyMath.fromMinor(due));ledger.put("note","Bill #"+billNumber);ledger.put("created_at",now);db.insertOrThrow("customer_ledger",null,ledger);queueSync("customer",request.customerId,"upsert");}
        String snapshot=buildReceiptSnapshot(db,orderId);ContentValues snap=new ContentValues();snap.put("receipt_snapshot",snapshot);db.update("orders",snap,"id=?",new String[]{orderId});if(request.printRequested)printQueued=enqueuePrint(db,orderId,"receipt",snapshot,"receipt:"+orderId+":"+billNumber);insertEvent(db,orderId,"finalized",due>0?"due":"paid",now);queueSync("order",orderId,"upsert");
        // If this bill was on a duplicated table, auto-close/reset it: deactivate
        // the duplicate table so it disappears from the list once its bill is done.
        // The finalized order/bill is preserved for history and reports.
        deactivateDuplicateTableForOrder(db,orderId,now);
        db.setTransactionSuccessful();
      }
    }finally{db.endTransaction();}return new CheckoutResult(orderInfo(getReadableDatabase(),orderId),already,printQueued);
  }

  // After a bill is finalized, if the order's table was a duplicate, deactivate it
  // so it no longer shows in the table list. Runs inside the finalize transaction.
  private void deactivateDuplicateTableForOrder(SQLiteDatabase db,String orderId,long now){
    String tableId=null;
    try(Cursor c=db.rawQuery("SELECT table_id FROM orders WHERE id=?",new String[]{orderId})){if(c.moveToFirst()&&!c.isNull(0))tableId=c.getString(0);}
    if(tableId==null||tableId.isEmpty())return;
    boolean duplicate=false;
    try(Cursor c=db.rawQuery("SELECT COALESCE(is_duplicate,0) FROM dining_tables WHERE id=?",new String[]{tableId})){if(c.moveToFirst())duplicate=c.getInt(0)==1;}
    if(!duplicate)return;
    ContentValues row=new ContentValues();row.put("active",0);row.put("updated_at",now);
    db.update("dining_tables",row,"id=?",new String[]{tableId});
    queueSync("table",tableId,"upsert");
  }

  public synchronized int sendKot(String orderId){SQLiteDatabase db=getWritableDatabase();int number;db.beginTransaction();try{if(orderLinesCount(db,orderId)==0)throw new IllegalStateException("Add at least one item before printing KOT");long now=System.currentTimeMillis();String period=dailyKey(now);Integer existing=null;int version=0;try(Cursor c=db.rawQuery("SELECT kot_number,version FROM orders WHERE id=?",new String[]{orderId})){if(!c.moveToFirst())throw new IllegalStateException("Order not found");if(!c.isNull(0))existing=c.getInt(0);version=c.getInt(1);}number=existing==null?nextNumber(db,"kot",period):existing;ContentValues row=new ContentValues();if(existing==null){row.put("kot_number",number);row.put("kot_period",period);}row.put("status","unclosed");row.put("updated_at",now);row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});String payload=buildKotSnapshot(db,orderId);enqueuePrint(db,orderId,"kot",payload,"kot:"+orderId+":"+version);insertEvent(db,orderId,"kot_queued","KOT #"+number,now);queueSync("order",orderId,"upsert");db.setTransactionSuccessful();}finally{db.endTransaction();}return number;}
  // Save the current items to the table as an open KOT WITHOUT printing. Assigns
  // a KOT number on first save (so the order shows on the table as a running
  // KOT) and keeps the order open for more items or checkout.
  public synchronized int saveKot(String orderId){SQLiteDatabase db=getWritableDatabase();int number;db.beginTransaction();try{if(orderLinesCount(db,orderId)==0)throw new IllegalStateException("Add at least one item first");long now=System.currentTimeMillis();String period=dailyKey(now);Integer existing=null;try(Cursor c=db.rawQuery("SELECT kot_number FROM orders WHERE id=?",new String[]{orderId})){if(!c.moveToFirst())throw new IllegalStateException("Order not found");if(!c.isNull(0))existing=c.getInt(0);}number=existing==null?nextNumber(db,"kot",period):existing;ContentValues row=new ContentValues();if(existing==null){row.put("kot_number",number);row.put("kot_period",period);}row.put("status","unclosed");row.put("updated_at",now);row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});insertEvent(db,orderId,"kot_saved","KOT #"+number,now);queueSync("order",orderId,"upsert");db.setTransactionSuccessful();}finally{db.endTransaction();}return number;}
  public void holdOrder(String orderId){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{if(orderLinesCount(db,orderId)==0)throw new IllegalStateException("Add at least one item before holding");long now=System.currentTimeMillis();ContentValues row=new ContentValues();row.put("status","held");row.put("updated_at",now);row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});insertEvent(db,orderId,"held","",now);queueSync("order",orderId,"upsert");db.setTransactionSuccessful();}finally{db.endTransaction();}}
  public boolean queueReceiptPrint(String orderId){SQLiteDatabase db=getWritableDatabase();OrderInfo info=orderInfo(db,orderId);if(info==null||info.billNumber==null||info.receiptSnapshot.isEmpty())return false;return enqueuePrint(db,orderId,"receipt",info.receiptSnapshot,"receipt:"+orderId+":"+info.billNumber);}
  // Reprint a bill: always enqueue a fresh receipt print for a finalized bill,
  // bypassing idempotency so the same bill can be reprinted any number of times.
  // Works for the current bill and any previous bill (by order id).
  public boolean reprintReceipt(String orderId){SQLiteDatabase db=getWritableDatabase();OrderInfo info=orderInfo(db,orderId);if(info==null||info.billNumber==null||info.receiptSnapshot.isEmpty())return false;return enqueuePrint(db,orderId,"receipt",info.receiptSnapshot,"reprint:"+orderId+":"+System.currentTimeMillis());}
  public List<PrinterProfile> printerProfiles(){List<PrinterProfile> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,connection_type,address,port,paper_width,role,is_default FROM printer_profiles WHERE active=1 ORDER BY is_default DESC,name COLLATE NOCASE",null)){while(c.moveToNext())result.add(new PrinterProfile(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getInt(5),c.getString(6),c.getInt(7)==1));}return result;}
  public String savePrinter(PrinterProfile profile){SQLiteDatabase db=getWritableDatabase();String id=profile.id==null||profile.id.isEmpty()?UUID.randomUUID().toString():profile.id;db.beginTransaction();try{if(profile.isDefault){String roleWhere="both".equals(profile.role)?"1=1":"role IN (?, 'both')";String[] roleArgs="both".equals(profile.role)?new String[0]:new String[]{profile.role};ContentValues clear=new ContentValues();clear.put("is_default",0);db.update("printer_profiles",clear,roleWhere,roleArgs);}ContentValues row=new ContentValues();row.put("id",id);row.put("name",profile.name.trim());row.put("connection_type",profile.connectionType);row.put("address",profile.address.trim());row.put("port",profile.port);row.put("paper_width",profile.paperWidth);row.put("role",profile.role);row.put("is_default",profile.isDefault?1:0);row.put("active",1);row.put("updated_at",System.currentTimeMillis());db.insertWithOnConflict("printer_profiles",null,row,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();}finally{db.endTransaction();}return id;}
  public void removePrinter(String id){ContentValues row=new ContentValues();row.put("active",0);row.put("is_default",0);row.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("printer_profiles",row,"id=?",new String[]{id});}
  public PrinterProfile printerFor(String jobType){String role="kot".equals(jobType)?"kot":"bill";try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,connection_type,address,port,paper_width,role,is_default FROM printer_profiles WHERE active=1 AND role IN (?, 'both') ORDER BY is_default DESC,updated_at DESC LIMIT 1",new String[]{role})){if(!c.moveToFirst())return null;return new PrinterProfile(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getInt(5),c.getString(6),c.getInt(7)==1);}}
  public List<PrintJobInfo> pendingPrintJobs(){List<PrintJobInfo> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,COALESCE(order_id,''),job_type,payload,attempts FROM print_jobs WHERE status IN ('pending','retry') ORDER BY created_at LIMIT 20",null)){while(c.moveToNext())result.add(new PrintJobInfo(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4)));}return result;}
  public void updatePrintJob(String id,String status,String error){ContentValues row=new ContentValues();row.put("status",status);row.put("last_error",error==null?"":error);row.put("updated_at",System.currentTimeMillis());if("printing".equals(status))row.put("attempts",printAttempts(id)+1);getWritableDatabase().update("print_jobs",row,"id=?",new String[]{id});}
  private int printAttempts(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT attempts FROM print_jobs WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getInt(0):0;}}
  public int pendingPrintCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM print_jobs WHERE status IN ('pending','retry','printing')",null)){return c.moveToFirst()?c.getInt(0):0;}}
  public int failedPrintCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM print_jobs WHERE status='failed'",null)){return c.moveToFirst()?c.getInt(0):0;}}
  public String latestPrintError(){try(Cursor c=getReadableDatabase().rawQuery("SELECT last_error FROM print_jobs WHERE status='failed' ORDER BY updated_at DESC LIMIT 1",null)){return c.moveToFirst()?c.getString(0):"";}}
  public void retryFailedPrintJobs(){ContentValues row=new ContentValues();row.put("status","retry");row.put("attempts",0);row.put("last_error","");row.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("print_jobs",row,"status='failed'",null);}
  public List<FailedPrintJob> failedPrintJobs(){List<FailedPrintJob> result=new ArrayList<>();String sql="SELECT p.id,COALESCE(p.order_id,''),p.job_type,p.attempts,p.last_error,p.updated_at,COALESCE(t.name,'Direct'),o.bill_number,o.kot_number FROM print_jobs p LEFT JOIN orders o ON o.id=p.order_id LEFT JOIN dining_tables t ON t.id=o.table_id WHERE p.status='failed' ORDER BY p.updated_at DESC";try(Cursor c=getReadableDatabase().rawQuery(sql,null)){while(c.moveToNext()){Integer bill=c.isNull(7)?null:c.getInt(7),kot=c.isNull(8)?null:c.getInt(8);result.add(new FailedPrintJob(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getString(4),c.getLong(5),c.getString(6),bill,kot));}}return result;}
  public void retryFailedPrintJobs(List<String> ids){if(ids==null||ids.isEmpty())return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String id:ids){ContentValues row=new ContentValues();row.put("status","retry");row.put("attempts",0);row.put("last_error","");row.put("updated_at",System.currentTimeMillis());db.update("print_jobs",row,"id=? AND status='failed'",new String[]{id});}db.setTransactionSuccessful();}finally{db.endTransaction();}}
  public void deleteFailedPrintJobs(List<String> ids){if(ids==null||ids.isEmpty())return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String id:ids)db.delete("print_jobs","id=? AND status='failed'",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
  public List<SyncChange> pendingCloudChanges(int limit){List<SyncChange> changes=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,entity_type,entity_id FROM sync_queue ORDER BY created_at LIMIT ?",new String[]{String.valueOf(Math.max(1,Math.min(limit,100)))})){while(c.moveToNext()){String type=c.getString(1),entityId=c.getString(2);JSONObject value="settings".equals(type)?settingsSyncValue(entityId):syncValue(type,entityId);if(value!=null)changes.add(new SyncChange(c.getString(0),"mobile-v1:"+type+":"+entityId,value));}}return changes;}
  private JSONObject settingsSyncValue(String id){try{JSONObject value=new JSONObject().put("type","settings").put("id",id);long updated=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT key,value,updated_at FROM app_meta",null)){while(c.moveToNext()){value.put(c.getString(0),c.getString(1));updated=Math.max(updated,c.getLong(2));}}return value.put("updatedAt",updated);}catch(Exception error){throw new IllegalStateException("Could not prepare settings for cloud",error);}}
  public void markCloudSynced(List<String> ids){if(ids==null||ids.isEmpty())return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String id:ids)db.delete("sync_queue","id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
  public void applyCloudValue(String key,JSONObject value){if(key==null||!key.startsWith("mobile-v1:")||value==null)return;String type=value.optString("type"),id=value.optString("id");if(type.isEmpty()||id.isEmpty())return;SQLiteDatabase db=getWritableDatabase();long remote=value.optLong("updatedAt",0),local=localUpdatedAt(db,type,id);if(local>0&&local>=remote)return;db.beginTransaction();try{if("category".equals(type))upsertCategory(db,id,value);else if("floor".equals(type))upsertFloor(db,id,value);else if("table".equals(type))upsertTable(db,id,value);else if("customer".equals(type))upsertCustomer(db,id,value);else if("product".equals(type))upsertProduct(db,id,value);else if("gateway".equals(type))upsertGateway(db,id,value);else if("order".equals(type))upsertOrder(db,id,value);else if("settings".equals(type))upsertSettings(db,value);db.setTransactionSuccessful();}catch(Exception error){throw new IllegalStateException("Could not apply cloud "+type,error);}finally{db.endTransaction();}}
  // Replace the local staff mirror with the cloud staff directory (pos-staff-users).
  // The cloud shape carries id, name, staffCode, permissions[], tableAccess[], active.
  // We normalize the id (strip a "portal-" prefix) so it matches the device staff API.
  public void applyStaffDirectory(JSONArray users){
    if(users==null)return;
    SQLiteDatabase db=getWritableDatabase();
    long now=System.currentTimeMillis();
    db.beginTransaction();
    try{
      db.delete("staff",null,null);
      for(int i=0;i<users.length();i++){
        JSONObject user=users.optJSONObject(i);
        if(user==null)continue;
        String id=user.optString("id");
        if(id.startsWith("portal-"))id=id.substring("portal-".length());
        if(id.isEmpty())continue;
        ContentValues row=new ContentValues();
        row.put("id",id);
        row.put("name",user.optString("name","Staff"));
        row.put("mobile",user.optString("mobile"));
        row.put("role",user.optString("role","Staff"));
        row.put("staff_code",user.optString("staffCode"));
        JSONArray permissions=user.optJSONArray("permissions");
        JSONArray tableAccess=user.optJSONArray("tableAccess");
        row.put("permissions",permissions==null?"[]":permissions.toString());
        row.put("table_access",tableAccess==null?"[]":tableAccess.toString());
        row.put("active",user.optBoolean("active",true)?1:0);
        row.put("updated_at",now);
        db.insertWithOnConflict("staff",null,row,SQLiteDatabase.CONFLICT_REPLACE);
      }
      db.setTransactionSuccessful();
    }finally{db.endTransaction();}
  }

  // Local staff list in the same JSON shape the User management screen renders
  // (id, name, mobile, staffCode, permissions[], tableAccess[], active).
  public JSONArray localStaff(){
    JSONArray result=new JSONArray();
    try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,mobile,role,staff_code,permissions,table_access,active FROM staff ORDER BY name COLLATE NOCASE",null)){
      while(c.moveToNext()){
        try{
          JSONObject item=new JSONObject();
          item.put("id",c.getString(0));
          item.put("name",c.getString(1));
          item.put("mobile",c.getString(2));
          item.put("role",c.getString(3));
          item.put("staffCode",c.getString(4));
          item.put("permissions",new JSONArray(c.getString(5)==null||c.getString(5).isEmpty()?"[]":c.getString(5)));
          item.put("tableAccess",new JSONArray(c.getString(6)==null||c.getString(6).isEmpty()?"[]":c.getString(6)));
          item.put("active",c.getInt(7)==1);
          result.put(item);
        }catch(Exception ignored){}
      }
    }catch(Exception ignored){}
    return result;
  }

  private long localUpdatedAt(SQLiteDatabase db,String type,String id){if("settings".equals(type)){try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(updated_at),0) FROM app_meta",null)){return c.moveToFirst()?c.getLong(0):0;}}String table="category".equals(type)?"categories":"floor".equals(type)?"floor_groups":"table".equals(type)?"dining_tables":"customer".equals(type)?"customers":"product".equals(type)?"products":"gateway".equals(type)?"payment_gateways":"order".equals(type)?"orders":"";if(table.isEmpty())return 0;try(Cursor c=db.rawQuery("SELECT updated_at FROM "+table+" WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getLong(0):0;}}
  private void upsertCategory(SQLiteDatabase db,String id,JSONObject value){ContentValues row=new ContentValues();row.put("id",id);row.put("name",value.optString("name"));row.put("sort_order",value.optInt("sortOrder"));row.put("active",value.optBoolean("active",true)?1:0);row.put("updated_at",value.optLong("updatedAt"));db.insertWithOnConflict("categories",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
  private void upsertFloor(SQLiteDatabase db,String id,JSONObject value){ContentValues row=new ContentValues();row.put("id",id);row.put("name",value.optString("name"));row.put("sort_order",value.optInt("sortOrder"));row.put("active",value.optBoolean("active",true)?1:0);row.put("updated_at",value.optLong("updatedAt"));db.insertWithOnConflict("floor_groups",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
  private void upsertTable(SQLiteDatabase db,String id,JSONObject value){ContentValues row=new ContentValues();row.put("id",id);row.put("floor_id",value.optString("floorId"));row.put("name",value.optString("name"));row.put("seats",value.optInt("seats",4));row.put("row_index",value.optInt("row"));row.put("column_index",value.optInt("column"));row.put("sort_order",value.optInt("sortOrder"));row.put("active",value.optBoolean("active",true)?1:0);row.put("is_duplicate",value.optBoolean("isDuplicate",false)?1:0);row.put("updated_at",value.optLong("updatedAt"));db.insertWithOnConflict("dining_tables",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
  private void upsertCustomer(SQLiteDatabase db,String id,JSONObject value){ContentValues row=new ContentValues();row.put("id",id);row.put("name",value.optString("name"));row.put("phone",value.optString("phone"));row.put("address",value.optString("address"));row.put("due_balance",value.optDouble("dueBalance"));row.put("updated_at",value.optLong("updatedAt"));db.insertWithOnConflict("customers",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
  private void upsertGateway(SQLiteDatabase db,String id,JSONObject value){ContentValues row=new ContentValues();row.put("id",id);row.put("name",value.optString("name"));row.put("active",value.optBoolean("active",true)?1:0);row.put("sort_order",value.optInt("sortOrder"));row.put("updated_at",value.optLong("updatedAt"));db.insertWithOnConflict("payment_gateways",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
  private void upsertSettings(SQLiteDatabase db,JSONObject value){long updated=value.optLong("updatedAt",System.currentTimeMillis());String[] keys={"business_name","business_address","business_phone","business_tax_id","receipt_footer","business_logo_uri","receipt_template","gst_enabled","gst_rate","gst_mode","gst_type","payment_upi","payment_card","payment_due","payment_split","ordering_browse_mode","ordering_grid_columns"};for(String key:keys)if(value.has(key))putSetting(db,key,value.optString(key),updated);}
  private void upsertProduct(SQLiteDatabase db,String id,JSONObject value)throws Exception{ContentValues row=new ContentValues();row.put("id",id);row.put("category_id",value.optString("categoryId"));row.put("name",value.optString("name"));row.put("alias",value.optString("alias"));row.put("price",value.optDouble("price"));row.put("tax_rate",value.optDouble("taxRate"));row.put("available",value.optBoolean("available",true)?1:0);row.put("sort_order",value.optInt("sortOrder"));row.put("updated_at",value.optLong("updatedAt"));row.put("description",value.optString("description"));row.put("kot_description",value.optString("kotDescription"));row.put("price_mode",value.optString("priceMode","fixed"));row.put("gst_mode",value.optString("gstMode","exclusive"));row.put("gst_type",value.optString("gstType","cgst_sgst"));row.put("favorite",value.optBoolean("favorite")?1:0);row.put("special",value.optBoolean("special")?1:0);row.put("hot_item",value.optBoolean("hotItem")?1:0);row.put("kot_route",value.optString("kotRoute"));row.put("sync_state","synced");db.insertWithOnConflict("products",null,row,SQLiteDatabase.CONFLICT_REPLACE);db.delete("product_variants","product_id=?",new String[]{id});JSONArray variants=value.optJSONArray("variants");if(variants!=null)for(int i=0;i<variants.length();i++){JSONObject item=variants.getJSONObject(i);ContentValues variant=new ContentValues();variant.put("id",item.optString("id",UUID.randomUUID().toString()));variant.put("product_id",id);variant.put("name",item.optString("name"));variant.put("price",item.optDouble("price"));variant.put("sort_order",item.optInt("sortOrder",i));variant.put("active",item.optBoolean("active",true)?1:0);variant.put("updated_at",item.optLong("updatedAt",value.optLong("updatedAt")));db.insertWithOnConflict("product_variants",null,variant,SQLiteDatabase.CONFLICT_REPLACE);}}
  private void upsertOrder(SQLiteDatabase db,String id,JSONObject value)throws Exception{ContentValues row=new ContentValues();row.put("id",id);row.put("table_id",emptyNull(value.optString("tableId")));putNullableInt(row,"kot_number",value,"kotNumber");putNullableInt(row,"bill_number",value,"billNumber");row.put("kot_period",value.optString("kotPeriod"));row.put("bill_period",value.optString("billPeriod"));row.put("status",value.optString("status","unclosed"));row.put("subtotal",value.optDouble("subtotal"));row.put("tax_total",value.optDouble("tax"));row.put("discount_total",value.optDouble("discount"));row.put("grand_total",value.optDouble("total"));row.put("customer_id",emptyNull(value.optString("customerId")));row.put("amount_received",value.optDouble("received"));row.put("balance_return",value.optDouble("balance"));row.put("due_amount",value.optDouble("due"));row.put("payment_status",value.optString("paymentStatus","unpaid"));row.put("opened_at",value.optLong("openedAt"));if(!value.isNull("closedAt"))row.put("closed_at",value.optLong("closedAt"));String syncedStatus=value.optString("status","unclosed");long syncedFinalizedAt=value.isNull("finalizedAt")?0:value.optLong("finalizedAt");if(syncedFinalizedAt<=0&&("paid".equals(syncedStatus)||"due".equals(syncedStatus))){syncedFinalizedAt=value.optLong("updatedAt",System.currentTimeMillis());}if(syncedFinalizedAt>0)row.put("finalized_at",syncedFinalizedAt);if(value.has("cancelledAt")&&!value.isNull("cancelledAt"))row.put("cancelled_at",value.optLong("cancelledAt"));row.put("cancel_reason",value.optString("cancelReason",""));row.put("order_type",value.optString("orderType","dine_in"));row.put("delivery_name",value.optString("deliveryName",""));row.put("delivery_phone",value.optString("deliveryPhone",""));row.put("delivery_address",value.optString("deliveryAddress",""));row.put("order_note",value.optString("orderNote",""));row.put("updated_at",value.optLong("updatedAt"));row.put("sync_state","synced");db.insertWithOnConflict("orders",null,row,SQLiteDatabase.CONFLICT_REPLACE);db.delete("order_items","order_id=?",new String[]{id});JSONArray items=value.optJSONArray("items");if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);ContentValues line=new ContentValues();line.put("id",item.optString("id",UUID.randomUUID().toString()));line.put("order_id",id);line.put("product_id",emptyNull(item.optString("productId")));line.put("item_name",item.optString("name"));line.put("variant_name",item.optString("variant"));line.put("quantity",item.optDouble("quantity"));line.put("unit_price",item.optDouble("unitPrice"));line.put("tax_rate",item.optDouble("taxRate"));line.put("tax_mode",item.optString("taxMode","exclusive"));line.put("tax_type",item.optString("taxType","cgst_sgst"));line.put("tax_amount",item.optDouble("taxAmount"));line.put("line_total",item.optDouble("lineTotal"));line.put("note",item.optString("note"));line.put("updated_at",item.optLong("updatedAt"));db.insertWithOnConflict("order_items",null,line,SQLiteDatabase.CONFLICT_REPLACE);}db.delete("payments","order_id=?",new String[]{id});JSONArray payments=value.optJSONArray("payments");if(payments!=null)for(int i=0;i<payments.length();i++){JSONObject item=payments.getJSONObject(i);ContentValues payment=new ContentValues();payment.put("id",item.optString("id",UUID.randomUUID().toString()));payment.put("order_id",id);String method=item.optString("method");String gatewayId=item.optString("gatewayId");payment.put("method",method);payment.put("gateway_id",gatewayId);payment.put("amount",item.optDouble("amount"));payment.put("reference",item.optString("reference"));payment.put("idempotency_key",id+":"+method+(gatewayId.isEmpty()?"":":"+gatewayId));payment.put("created_at",item.optLong("createdAt"));db.insertWithOnConflict("payments",null,payment,SQLiteDatabase.CONFLICT_REPLACE);}}
  private static String emptyNull(String value){return value==null||value.isEmpty()?null:value;}private static void putNullableInt(ContentValues row,String column,JSONObject value,String key){if(value.isNull(key))row.putNull(column);else row.put(column,value.optInt(key));}
  private JSONObject syncValue(String type,String id){try{SQLiteDatabase db=getReadableDatabase();JSONObject value=new JSONObject().put("type",type).put("id",id);if("category".equals(type)){try(Cursor c=db.rawQuery("SELECT name,sort_order,active,updated_at FROM categories WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;return value.put("name",c.getString(0)).put("sortOrder",c.getInt(1)).put("active",c.getInt(2)==1).put("updatedAt",c.getLong(3));}}if("floor".equals(type)){try(Cursor c=db.rawQuery("SELECT name,sort_order,active,updated_at FROM floor_groups WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;return value.put("name",c.getString(0)).put("sortOrder",c.getInt(1)).put("active",c.getInt(2)==1).put("updatedAt",c.getLong(3));}}if("table".equals(type)){try(Cursor c=db.rawQuery("SELECT floor_id,name,seats,row_index,column_index,sort_order,active,updated_at,COALESCE(is_duplicate,0) FROM dining_tables WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;return value.put("floorId",c.getString(0)).put("name",c.getString(1)).put("seats",c.getInt(2)).put("row",c.getInt(3)).put("column",c.getInt(4)).put("sortOrder",c.getInt(5)).put("active",c.getInt(6)==1).put("updatedAt",c.getLong(7)).put("isDuplicate",c.getInt(8)==1);}}if("customer".equals(type)){try(Cursor c=db.rawQuery("SELECT name,phone,address,due_balance,updated_at FROM customers WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;return value.put("name",c.getString(0)).put("phone",c.getString(1)).put("address",c.getString(2)).put("dueBalance",c.getDouble(3)).put("updatedAt",c.getLong(4));}}if("product".equals(type)){try(Cursor c=db.rawQuery("SELECT category_id,name,alias,price,tax_rate,available,sort_order,updated_at,description,kot_description,price_mode,gst_mode,gst_type,favorite,special,hot_item,kot_route FROM products WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;value.put("categoryId",c.getString(0)).put("name",c.getString(1)).put("alias",c.getString(2)).put("price",c.getDouble(3)).put("taxRate",c.getDouble(4)).put("available",c.getInt(5)==1).put("sortOrder",c.getInt(6)).put("updatedAt",c.getLong(7)).put("description",c.getString(8)).put("kotDescription",c.getString(9)).put("priceMode",c.getString(10)).put("gstMode",c.getString(11)).put("gstType",c.getString(12)).put("favorite",c.getInt(13)==1).put("special",c.getInt(14)==1).put("hotItem",c.getInt(15)==1).put("kotRoute",c.getString(16));}JSONArray variants=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,name,price,sort_order,active,updated_at FROM product_variants WHERE product_id=? ORDER BY sort_order",new String[]{id})){while(c.moveToNext())variants.put(new JSONObject().put("id",c.getString(0)).put("name",c.getString(1)).put("price",c.getDouble(2)).put("sortOrder",c.getInt(3)).put("active",c.getInt(4)==1).put("updatedAt",c.getLong(5)));}return value.put("variants",variants);}if("gateway".equals(type)){try(Cursor c=db.rawQuery("SELECT name,active,sort_order,updated_at FROM payment_gateways WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;return value.put("name",c.getString(0)).put("active",c.getInt(1)==1).put("sortOrder",c.getInt(2)).put("updatedAt",c.getLong(3));}}if("order".equals(type))return orderSyncValue(db,id,value);return null;}catch(Exception error){throw new IllegalStateException("Could not prepare cloud data",error);}}
  private JSONObject orderSyncValue(SQLiteDatabase db,String id,JSONObject value)throws Exception{try(Cursor c=db.rawQuery("SELECT table_id,kot_number,bill_number,kot_period,bill_period,status,subtotal,tax_total,discount_total,grand_total,customer_id,amount_received,balance_return,due_amount,payment_status,opened_at,closed_at,finalized_at,updated_at,cancelled_at,cancel_reason,order_type,delivery_name,delivery_phone,delivery_address,order_note FROM orders WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;value.put("tableId",c.getString(0)).put("kotNumber",c.isNull(1)?JSONObject.NULL:c.getInt(1)).put("billNumber",c.isNull(2)?JSONObject.NULL:c.getInt(2)).put("kotPeriod",c.getString(3)).put("billPeriod",c.getString(4)).put("status",c.getString(5)).put("subtotal",c.getDouble(6)).put("tax",c.getDouble(7)).put("discount",c.getDouble(8)).put("total",c.getDouble(9)).put("customerId",c.getString(10)).put("received",c.getDouble(11)).put("balance",c.getDouble(12)).put("due",c.getDouble(13)).put("paymentStatus",c.getString(14)).put("openedAt",c.getLong(15)).put("closedAt",c.isNull(16)?JSONObject.NULL:c.getLong(16)).put("finalizedAt",c.isNull(17)?JSONObject.NULL:c.getLong(17)).put("updatedAt",c.getLong(18)).put("cancelledAt",c.isNull(19)?JSONObject.NULL:c.getLong(19)).put("cancelReason",c.getString(20)).put("orderType",c.getString(21)).put("deliveryName",c.getString(22)).put("deliveryPhone",c.getString(23)).put("deliveryAddress",c.getString(24)).put("orderNote",c.getString(25));}JSONArray items=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,product_id,item_name,variant_name,quantity,unit_price,tax_rate,tax_mode,tax_type,tax_amount,line_total,note,updated_at FROM order_items WHERE order_id=?",new String[]{id})){while(c.moveToNext())items.put(new JSONObject().put("id",c.getString(0)).put("productId",c.getString(1)).put("name",c.getString(2)).put("variant",c.getString(3)).put("quantity",c.getDouble(4)).put("unitPrice",c.getDouble(5)).put("taxRate",c.getDouble(6)).put("taxMode",c.getString(7)).put("taxType",c.getString(8)).put("taxAmount",c.getDouble(9)).put("lineTotal",c.getDouble(10)).put("note",c.getString(11)).put("updatedAt",c.getLong(12)));}JSONArray payments=new JSONArray();try(Cursor c=db.rawQuery("SELECT id,method,gateway_id,amount,reference,created_at FROM payments WHERE order_id=?",new String[]{id})){while(c.moveToNext())payments.put(new JSONObject().put("id",c.getString(0)).put("method",c.getString(1)).put("gatewayId",c.getString(2)).put("amount",c.getDouble(3)).put("reference",c.getString(4)).put("createdAt",c.getLong(5)));}return value.put("items",items).put("payments",payments);}
  public ReportSummary report(long from,long to){return report(from,to,"all","");}
  /**
   * Advanced sales report.
   * filterMethod: "all" | "cash" | "upi" | "card" | "due" | "gateway"
   * filterGatewayId: when non-empty, restrict to a single custom gateway (implies gateway method).
   * When a payment filter is active, bills/items are restricted to orders that
   * used that payment method/gateway, and the headline sales is the sum for
   * that method rather than the full bill totals.
   */
  public ReportSummary report(long from,long to,String filterMethod,String filterGatewayId){
    SQLiteDatabase db=getReadableDatabase();
    String method=filterMethod==null?"all":filterMethod;
    String gatewayId=filterGatewayId==null?"":filterGatewayId;
    boolean filtered=!"all".equals(method);
    String[] range={String.valueOf(from),String.valueOf(to)};

    // Always compute the full method/gateway breakdown for the period so the
    // Payments report and filter chips have complete data.
    double cash=0,upi=0,card=0;
    List<PaymentTotal> methodTotals=new ArrayList<>();
    java.util.Map<String,double[]> methodAgg=new java.util.LinkedHashMap<>();
    try(Cursor c=db.rawQuery("SELECT p.method,COALESCE(SUM(p.amount),0),COUNT(*) FROM payments p JOIN orders o ON o.id=p.order_id WHERE o.finalized_at>=? AND o.finalized_at<? AND o.status IN ('paid','due') GROUP BY p.method",range)){while(c.moveToNext()){String m=c.getString(0);double amount=c.getDouble(1);int count=c.getInt(2);methodAgg.put(m,new double[]{amount,count});if("cash".equals(m))cash=amount;else if("upi".equals(m))upi=amount;else if("card".equals(m))card=amount;}}
    for(String m:new String[]{"cash","upi","card","due"}){double[] agg=methodAgg.get(m);if(agg==null)continue;methodTotals.add(new PaymentTotal(m,methodLabel(m),MoneyMath.round(agg[0]),(int)agg[1]));}

    List<PaymentTotal> gatewayTotals=new ArrayList<>();
    try(Cursor c=db.rawQuery("SELECT p.gateway_id,COALESCE(g.name,'Removed gateway'),COALESCE(SUM(p.amount),0),COUNT(*) FROM payments p JOIN orders o ON o.id=p.order_id LEFT JOIN payment_gateways g ON g.id=p.gateway_id WHERE o.finalized_at>=? AND o.finalized_at<? AND o.status IN ('paid','due') AND p.method='gateway' AND p.gateway_id<>'' GROUP BY p.gateway_id,g.name ORDER BY SUM(p.amount) DESC",range)){while(c.moveToNext())gatewayTotals.add(new PaymentTotal(c.getString(0),c.getString(1),MoneyMath.round(c.getDouble(2)),c.getInt(3)));}
    // Show each custom gateway in the Payment mix by its own name (instead of one
    // combined "Gateways" row), each with its actual amount and payment count.
    for(PaymentTotal gateway:gatewayTotals)methodTotals.add(new PaymentTotal("gateway:"+gateway.key,gateway.label,gateway.amount,gateway.count));

    // Build the order-scope WHERE clause. When filtering by payment we restrict
    // to orders that have at least one matching payment row.
    StringBuilder orderScope=new StringBuilder("o.finalized_at>=? AND o.finalized_at<? AND o.status IN ('paid','due')");
    List<String> args=new ArrayList<>();args.add(String.valueOf(from));args.add(String.valueOf(to));
    if(filtered){
      orderScope.append(" AND EXISTS(SELECT 1 FROM payments fp WHERE fp.order_id=o.id AND fp.method=? AND fp.amount>0");
      args.add(method);
      if("gateway".equals(method)&&!gatewayId.isEmpty()){orderScope.append(" AND fp.gateway_id=?");args.add(gatewayId);}
      orderScope.append(")");
    }
    String[] scopeArgs=args.toArray(new String[0]);

    int bills=0;double sales=0,tax=0,discount=0,due=0,quantity=0;
    try(Cursor c=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(grand_total),0),COALESCE(SUM(tax_total),0),COALESCE(SUM(discount_total),0),COALESCE(SUM(due_amount),0) FROM orders o WHERE "+orderScope,scopeArgs)){if(c.moveToFirst()){bills=c.getInt(0);sales=c.getDouble(1);tax=c.getDouble(2);discount=c.getDouble(3);due=c.getDouble(4);}}

    // When filtering by a payment method/gateway, the headline sales figure is
    // the amount collected via that method (more meaningful than the full bill).
    String filterLabel="All payments";
    if(filtered){
      List<String> payArgs=new ArrayList<>();payArgs.add(String.valueOf(from));payArgs.add(String.valueOf(to));payArgs.add(method);
      StringBuilder payWhere=new StringBuilder("o.finalized_at>=? AND o.finalized_at<? AND o.status IN ('paid','due') AND p.method=?");
      if("gateway".equals(method)&&!gatewayId.isEmpty()){payWhere.append(" AND p.gateway_id=?");payArgs.add(gatewayId);}
      try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(p.amount),0) FROM payments p JOIN orders o ON o.id=p.order_id WHERE "+payWhere,payArgs.toArray(new String[0]))){if(c.moveToFirst())sales=c.getDouble(0);}
      if("gateway".equals(method)&&!gatewayId.isEmpty()){PaymentGateway gw=paymentGateway(gatewayId);filterLabel=(gw==null?"Gateway":gw.name)+" payments";}
      else filterLabel=methodLabel(method)+" payments";
    }

    List<ReportItem> items=new ArrayList<>();
    try(Cursor c=db.rawQuery("SELECT CASE WHEN oi.variant_name='' THEN oi.item_name ELSE oi.item_name||' ('||oi.variant_name||')' END,COALESCE(SUM(oi.quantity),0),COALESCE(SUM(oi.line_total),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE "+orderScope+" GROUP BY oi.item_name,oi.variant_name HAVING SUM(oi.quantity)>0 ORDER BY SUM(oi.quantity) DESC,oi.item_name COLLATE NOCASE",scopeArgs)){while(c.moveToNext()){ReportItem item=new ReportItem(c.getString(0),c.getDouble(1),c.getDouble(2));items.add(item);quantity+=item.quantity;}}

    return new ReportSummary(from,to,bills,MoneyMath.round(sales),MoneyMath.round(tax),MoneyMath.round(discount),MoneyMath.round(due),MoneyMath.round(cash),MoneyMath.round(upi),MoneyMath.round(card),MoneyMath.round(quantity),items,methodTotals,gatewayTotals,filterLabel);
  }
  private static String methodLabel(String method){if("cash".equals(method))return "Cash";if("upi".equals(method))return "UPI";if("card".equals(method))return "Card";if("due".equals(method))return "Due";if("gateway".equals(method))return "Gateways";return method;}
  public boolean queueReportPrint(ReportSummary report){try{JSONObject root=new JSONObject();SecureStore.Session session=SecureStore.load(appContext);root.put("schema",1);root.put("businessName",session==null?"GI POS":session.businessName);root.put("from",report.from);root.put("to",report.to);root.put("bills",report.bills);root.put("sales",report.sales);root.put("tax",report.tax);root.put("discount",report.discount);root.put("due",report.due);root.put("cash",report.cash);root.put("upi",report.upi);root.put("card",report.card);root.put("totalQuantity",report.totalQuantity);JSONArray items=new JSONArray();for(ReportItem entry:report.items)items.put(new JSONObject().put("name",entry.name).put("quantity",entry.quantity).put("amount",entry.amount));root.put("items",items);
    // Include each custom payment gateway (name + amount + count) so the printed
    // report shows the gateway breakdown, not just cash/upi/card.
    JSONArray gatewayArr=new JSONArray();for(PaymentTotal g:report.gatewayTotals)gatewayArr.put(new JSONObject().put("name",g.label).put("amount",g.amount).put("count",g.count));root.put("gateways",gatewayArr);
    return enqueuePrint(getWritableDatabase(),null,"report",root.toString(),"report:"+report.from+":"+System.currentTimeMillis());}catch(Exception error){throw new IllegalStateException("Could not prepare report print",error);}}

  public int activeOrderCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM orders WHERE status IN ('draft','kot','held','unclosed') AND EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=orders.id)",null)){return c.moveToFirst()?c.getInt(0):0;}}
  public List<OrderListItem> activeOrders(){return orderList("o.status IN ('draft','kot','held','unclosed') AND EXISTS(SELECT 1 FROM order_items present WHERE present.order_id=o.id)",new String[0]);}
  // Only dine-in active orders that are seated at a table (for the Table Orders page).
  public List<OrderListItem> activeTableOrders(){return orderList("o.status IN ('draft','kot','held','unclosed') AND o.order_type='dine_in' AND o.table_id IS NOT NULL AND EXISTS(SELECT 1 FROM order_items present WHERE present.order_id=o.id)",new String[0]);}
  public List<OrderListItem> completedOrders(long from,long to){return orderList("o.finalized_at>=? AND o.finalized_at<? AND o.status IN ('paid','due')",new String[]{String.valueOf(from),String.valueOf(to)});}
  public List<OrderListItem> customerOrders(String customerId){return orderList("o.customer_id=? AND o.status IN ('paid','due')",new String[]{customerId});}
  // Bill history for the Settings > Orders page: finalized bills (paid/due) plus
  // cancelled bills in the range, most recent first. Cancelled bills stay
  // visible here even though they are excluded from sales reports.
  public List<OrderListItem> orderHistory(long from,long to){List<OrderListItem> result=new ArrayList<>();String sql="SELECT o.id,COALESCE(t.name,''),o.status,o.bill_number,o.kot_number,COUNT(oi.id),o.grand_total,COALESCE(o.finalized_at,o.cancelled_at,o.updated_at),o.order_type FROM orders o LEFT JOIN dining_tables t ON t.id=o.table_id LEFT JOIN order_items oi ON oi.order_id=o.id WHERE COALESCE(o.finalized_at,o.cancelled_at,o.updated_at)>=? AND COALESCE(o.finalized_at,o.cancelled_at,o.updated_at)<? AND o.status IN ('paid','due','cancelled') GROUP BY o.id,t.name ORDER BY COALESCE(o.finalized_at,o.cancelled_at,o.updated_at) DESC LIMIT 500";try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{String.valueOf(from),String.valueOf(to)})){while(c.moveToNext()){Integer bill=c.isNull(3)?null:c.getInt(3),kot=c.isNull(4)?null:c.getInt(4);String type=c.getString(8);result.add(new OrderListItem(c.getString(0),displayName(c.getString(1),type),c.getString(2),bill,kot,c.getInt(5),c.getDouble(6),c.getLong(7),type));}}return result;}
  // Cancel a finalized bill. Reverses any customer due, marks the order
  // cancelled (so it drops out of sales reports), records an event, and syncs.
  public synchronized void cancelOrder(String orderId,String reason){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{OrderInfo info=orderInfo(db,orderId);if(info==null)throw new IllegalStateException("Order not found");if("cancelled".equals(info.status))throw new IllegalStateException("This bill is already cancelled");if(!("paid".equals(info.status)||"due".equals(info.status)))throw new IllegalStateException("Only completed bills can be cancelled");long now=System.currentTimeMillis();String cleanReason=reason==null?"":reason.trim();
    // Reverse any outstanding due against the customer.
    if(info.due>0){try(Cursor c=db.rawQuery("SELECT customer_id FROM orders WHERE id=?",new String[]{orderId})){if(c.moveToFirst()&&!c.isNull(0)){String customerId=c.getString(0);if(customerId!=null&&!customerId.isEmpty()){db.execSQL("UPDATE customers SET due_balance=MAX(0,due_balance-?),updated_at=? WHERE id=?",new Object[]{info.due,now,customerId});ContentValues ledger=new ContentValues();ledger.put("id",UUID.randomUUID().toString());ledger.put("customer_id",customerId);ledger.put("order_id",orderId);ledger.put("entry_type","cancel");ledger.put("amount",-info.due);ledger.put("note","Cancelled bill"+(info.billNumber==null?"":" #"+info.billNumber));ledger.put("created_at",now);db.insertOrThrow("customer_ledger",null,ledger);queueSync("customer",customerId,"upsert");}}}}
    ContentValues row=new ContentValues();row.put("status","cancelled");row.put("payment_status","cancelled");row.put("cancelled_at",now);row.put("cancel_reason",cleanReason);row.put("updated_at",now);row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});insertEvent(db,orderId,"cancelled",cleanReason,now);queueSync("order",orderId,"upsert");db.setTransactionSuccessful();}finally{db.endTransaction();}}
  private List<OrderListItem> orderList(String where,String[] args){List<OrderListItem> result=new ArrayList<>();String sql="SELECT o.id,COALESCE(t.name,''),o.status,o.bill_number,o.kot_number,COUNT(oi.id),o.grand_total,o.updated_at,o.order_type FROM orders o LEFT JOIN dining_tables t ON t.id=o.table_id LEFT JOIN order_items oi ON oi.order_id=o.id WHERE "+where+" GROUP BY o.id,t.name ORDER BY o.updated_at DESC LIMIT 250";try(Cursor c=getReadableDatabase().rawQuery(sql,args)){while(c.moveToNext()){Integer bill=c.isNull(3)?null:c.getInt(3),kot=c.isNull(4)?null:c.getInt(4);String type=c.getString(8);result.add(new OrderListItem(c.getString(0),displayName(c.getString(1),type),c.getString(2),bill,kot,c.getInt(5),c.getDouble(6),c.getLong(7),type));}}return result;}
  // Display name: the table name for dine-in, else the order-type label.
  private static String displayName(String tableName,String orderType){if("delivery".equals(orderType))return "Delivery";if("takeaway".equals(orderType))return "Takeaway";return tableName==null||tableName.isEmpty()?"Direct":tableName;}

  public BusinessSettings businessSettings(){SecureStore.Session session=SecureStore.load(appContext);String fallback=session==null?"GI POS":session.businessName;return new BusinessSettings(setting("business_name",fallback),setting("business_address",""),setting("business_phone",""),setting("business_tax_id",""),setting("receipt_footer","Thank you. Visit again."),enabled("payment_upi",true),enabled("payment_card",true),enabled("payment_due",true),enabled("payment_split",true),setting("business_logo_uri",""),setting("receipt_template","classic"));}
  public void saveBusinessSettings(BusinessSettings value){SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();db.beginTransaction();try{putSetting(db,"business_name",value.name,now);putSetting(db,"business_address",value.address,now);putSetting(db,"business_phone",value.phone,now);putSetting(db,"business_tax_id",value.taxId,now);putSetting(db,"receipt_footer",value.footer,now);putSetting(db,"payment_upi",value.upi?"1":"0",now);putSetting(db,"payment_card",value.card?"1":"0",now);putSetting(db,"payment_due",value.due?"1":"0",now);putSetting(db,"payment_split",value.split?"1":"0",now);putSetting(db,"business_logo_uri",value.logoUri,now);putSetting(db,"receipt_template",value.template,now);db.setTransactionSuccessful();}finally{db.endTransaction();}queueSync("settings","business","upsert");}
  public NumberingSettings numberingSettings(){long now=System.currentTimeMillis();String billPeriod=financialYearKey(now),kotPeriod=dailyKey(now);return new NumberingSettings(billPeriod,kotPeriod,nextConfiguredNumber("bill",billPeriod),nextConfiguredNumber("kot",kotPeriod));}
  private int nextConfiguredNumber(String type,String period){int counter=0,used=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT last_value FROM number_counters WHERE counter_type=? AND period_key=?",new String[]{type,period})){if(c.moveToFirst())counter=c.getInt(0);}String column="bill".equals(type)?"bill_number":"kot_number",periodColumn="bill".equals(type)?"bill_period":"kot_period";try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX("+column+"),0) FROM orders WHERE "+periodColumn+"=?",new String[]{period})){if(c.moveToFirst())used=c.getInt(0);}return Math.max(counter,used)+1;}
  public void saveNumberingSettings(int nextBillNumber,int nextKotNumber){if(nextBillNumber<1||nextKotNumber<1)throw new IllegalArgumentException("Numbers must be 1 or higher");long now=System.currentTimeMillis();String billPeriod=financialYearKey(now),kotPeriod=dailyKey(now);SQLiteDatabase db=getWritableDatabase();int minimumBill=highestUsedNumber(db,"bill_number","bill_period",billPeriod)+1,minimumKot=highestUsedNumber(db,"kot_number","kot_period",kotPeriod)+1;if(nextBillNumber<minimumBill)throw new IllegalArgumentException("Next bill number must be "+minimumBill+" or higher");if(nextKotNumber<minimumKot)throw new IllegalArgumentException("Next KOT number must be "+minimumKot+" or higher");db.beginTransaction();try{upsertCounter(db,"bill",billPeriod,nextBillNumber-1,now);upsertCounter(db,"kot",kotPeriod,nextKotNumber-1,now);db.setTransactionSuccessful();}finally{db.endTransaction();}queueSync("settings","numbering","upsert");}
  private int highestUsedNumber(SQLiteDatabase db,String numberColumn,String periodColumn,String period){try(Cursor c=db.rawQuery("SELECT COALESCE(MAX("+numberColumn+"),0) FROM orders WHERE "+periodColumn+"=?",new String[]{period})){return c.moveToFirst()?c.getInt(0):0;}}
  public OrderingPreferences orderingPreferences(){String mode=setting("ordering_browse_mode","items");if(!"categories".equals(mode))mode="items";int columns;try{columns=Integer.parseInt(setting("ordering_grid_columns","2"));}catch(Exception ignored){columns=2;}return new OrderingPreferences(mode,columns==3?3:2);}
  // ---- Global GST settings -----------------------------------------------
  public GstSettings gstSettings(){boolean enabled="1".equals(setting("gst_enabled","0"));double rate;try{rate=Double.parseDouble(setting("gst_rate","0"));}catch(Exception ignored){rate=0;}String mode=setting("gst_mode","exclusive");String type=setting("gst_type","cgst_sgst");return new GstSettings(enabled,rate,mode,type);}
  public void saveGstSettings(GstSettings value){SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();db.beginTransaction();try{putSetting(db,"gst_enabled",value.enabled?"1":"0",now);putSetting(db,"gst_rate",trimNumber(value.rate),now);putSetting(db,"gst_mode",value.mode,now);putSetting(db,"gst_type",value.type,now);db.setTransactionSuccessful();}finally{db.endTransaction();}queueSync("settings","gst","upsert");}
  private static String trimNumber(double value){if(value==Math.rint(value))return String.valueOf((long)value);return String.valueOf(value);}
  public void saveOrderingPreferences(String browseMode,int columns){SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();db.beginTransaction();try{putSetting(db,"ordering_browse_mode","categories".equals(browseMode)?"categories":"items",now);putSetting(db,"ordering_grid_columns",columns==3?"3":"2",now);db.setTransactionSuccessful();}finally{db.endTransaction();}queueSync("settings","ordering","upsert");}

  // ---- Custom payment gateways -------------------------------------------
  public List<PaymentGateway> paymentGateways(boolean activeOnly){List<PaymentGateway> result=new ArrayList<>();String where=activeOnly?"WHERE active=1 ":"";try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,active FROM payment_gateways "+where+"ORDER BY sort_order,name COLLATE NOCASE",null)){while(c.moveToNext())result.add(new PaymentGateway(c.getString(0),c.getString(1),c.getInt(2)==1));}return result;}
  public PaymentGateway paymentGateway(String id){if(id==null||id.isEmpty())return null;try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name,active FROM payment_gateways WHERE id=?",new String[]{id})){return c.moveToFirst()?new PaymentGateway(c.getString(0),c.getString(1),c.getInt(2)==1):null;}}
  public String saveGateway(String name){String clean=name==null?"":name.trim();if(clean.isEmpty())throw new IllegalArgumentException("Gateway name is required");SQLiteDatabase db=getWritableDatabase();try(Cursor c=db.rawQuery("SELECT id FROM payment_gateways WHERE lower(name)=lower(?) LIMIT 1",new String[]{clean})){if(c.moveToFirst()){String existing=c.getString(0);ContentValues reactivate=new ContentValues();reactivate.put("active",1);reactivate.put("updated_at",System.currentTimeMillis());db.update("payment_gateways",reactivate,"id=?",new String[]{existing});queueSync("gateway",existing,"upsert");return existing;}}String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("name",clean);row.put("active",1);row.put("sort_order",(int)(System.currentTimeMillis()%100000));row.put("updated_at",System.currentTimeMillis());db.insertOrThrow("payment_gateways",null,row);queueSync("gateway",id,"upsert");return id;}
  public void renameGateway(String id,String name){String clean=name==null?"":name.trim();if(clean.isEmpty())throw new IllegalArgumentException("Gateway name is required");ContentValues row=new ContentValues();row.put("name",clean);row.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("payment_gateways",row,"id=?",new String[]{id});queueSync("gateway",id,"upsert");}
  public void setGatewayActive(String id,boolean active){ContentValues row=new ContentValues();row.put("active",active?1:0);row.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("payment_gateways",row,"id=?",new String[]{id});queueSync("gateway",id,"upsert");}
  public void removeGateway(String id){setGatewayActive(id,false);}
  private String setting(String key,String fallback){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM app_meta WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}}
  private boolean enabled(String key,boolean fallback){return "1".equals(setting(key,fallback?"1":"0"));}
  private static void putSetting(SQLiteDatabase db,String key,String value,long now){ContentValues row=new ContentValues();row.put("key",key);row.put("value",value==null?"":value.trim());row.put("updated_at",now);db.insertWithOnConflict("app_meta",null,row,SQLiteDatabase.CONFLICT_REPLACE);}

  public OrderInfo orderInfo(String orderId){return orderInfo(getReadableDatabase(),orderId);}
  private OrderInfo orderInfo(SQLiteDatabase db,String orderId){try(Cursor c=db.rawQuery("SELECT o.id,COALESCE(t.name,''),o.status,o.bill_period,o.bill_number,o.kot_period,o.kot_number,o.payment_status,COALESCE(cu.name,''),o.subtotal,o.tax_total,o.discount_total,o.grand_total,o.amount_received,o.balance_return,o.due_amount,o.receipt_snapshot,o.order_type,o.delivery_name,o.delivery_phone,o.delivery_address,o.order_note FROM orders o LEFT JOIN dining_tables t ON t.id=o.table_id LEFT JOIN customers cu ON cu.id=o.customer_id WHERE o.id=?",new String[]{orderId})){if(!c.moveToFirst())return null;Integer bill=c.isNull(4)?null:c.getInt(4),kot=c.isNull(6)?null:c.getInt(6);return new OrderInfo(c.getString(0),c.getString(1),c.getString(2),c.getString(3),bill,c.getString(5),kot,c.getString(7),c.getString(8),c.getDouble(9),c.getDouble(10),c.getDouble(11),c.getDouble(12),c.getDouble(13),c.getDouble(14),c.getDouble(15),c.getString(16),c.getString(17),c.getString(18),c.getString(19),c.getString(20),c.getString(21));}}
  private boolean customerExists(SQLiteDatabase db,String id){if(id==null||id.isEmpty())return false;try(Cursor c=db.rawQuery("SELECT 1 FROM customers WHERE id=?",new String[]{id})){return c.moveToFirst();}}
  private int orderLinesCount(SQLiteDatabase db,String orderId){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM order_items WHERE order_id=?",new String[]{orderId})){return c.moveToFirst()?c.getInt(0):0;}}
  private long nonNegative(double value){long minor=MoneyMath.toMinor(value);if(minor<0)throw new IllegalStateException("Payment amount cannot be negative");return minor;}
  private int nextNumber(SQLiteDatabase db,String type,String period){long now=System.currentTimeMillis();ContentValues seed=new ContentValues();seed.put("counter_type",type);seed.put("period_key",period);seed.put("last_value",0);seed.put("updated_at",now);db.insertWithOnConflict("number_counters",null,seed,SQLiteDatabase.CONFLICT_IGNORE);int counter=0;try(Cursor c=db.rawQuery("SELECT last_value FROM number_counters WHERE counter_type=? AND period_key=?",new String[]{type,period})){if(!c.moveToFirst())throw new IllegalStateException("Could not allocate "+type+" number");counter=c.getInt(0);}
    // Guard against collisions with the orders table: cloud-synced or restored
    // orders can carry a kot/bill number higher than the local counter (the
    // counter is not advanced by sync ingest). Take the max of the counter and
    // the highest number actually used for this period before allocating.
    String numberColumn="bill".equals(type)?"bill_number":"kot_number",periodColumn="bill".equals(type)?"bill_period":"kot_period";
    int used=highestUsedNumber(db,numberColumn,periodColumn,period);
    int next=Math.max(counter,used)+1;
    ContentValues update=new ContentValues();update.put("last_value",next);update.put("updated_at",now);db.update("number_counters",update,"counter_type=? AND period_key=?",new String[]{type,period});return next;}
  private void insertPayment(SQLiteDatabase db,String orderId,String method,long amount,long now){insertPayment(db,orderId,method,"",amount,now);}
  private void insertPayment(SQLiteDatabase db,String orderId,String method,String gatewayId,long amount,long now){if(amount<=0)return;String gw=gatewayId==null?"":gatewayId;ContentValues row=new ContentValues();row.put("id",UUID.randomUUID().toString());row.put("order_id",orderId);row.put("method",method);row.put("gateway_id",gw);row.put("amount",MoneyMath.fromMinor(amount));row.put("idempotency_key",orderId+":"+method+(gw.isEmpty()?"":":"+gw));row.put("created_at",now);db.insertOrThrow("payments",null,row);}
  private boolean enqueuePrint(SQLiteDatabase db,String orderId,String type,String payload,String key){ContentValues row=new ContentValues();long now=System.currentTimeMillis();row.put("id",UUID.randomUUID().toString());row.put("order_id",orderId);row.put("job_type",type);row.put("payload",decoratePrintPayload(payload));row.put("status","pending");row.put("idempotency_key",key);row.put("created_at",now);row.put("updated_at",now);return db.insertWithOnConflict("print_jobs",null,row,SQLiteDatabase.CONFLICT_IGNORE)!=-1;}
  private String decoratePrintPayload(String payload){try{JSONObject root=new JSONObject(payload);BusinessSettings settings=businessSettings();root.put("businessName",settings.name).put("businessAddress",settings.address).put("businessPhone",settings.phone).put("businessTaxId",settings.taxId).put("receiptFooter",settings.footer).put("businessLogoUri",settings.logoUri).put("template",settings.template);return root.toString();}catch(Exception ignored){return payload;}}
  private void insertEvent(SQLiteDatabase db,String orderId,String type,String details,long now){ContentValues row=new ContentValues();row.put("id",UUID.randomUUID().toString());row.put("order_id",orderId);row.put("event_type",type);row.put("details",details);row.put("created_at",now);db.insertOrThrow("order_events",null,row);}
  private String buildReceiptSnapshot(SQLiteDatabase db,String orderId){try{OrderInfo order=orderInfo(db,orderId);if(order==null)throw new IllegalStateException("Order not found");JSONObject root=new JSONObject();SecureStore.Session session=SecureStore.load(appContext);root.put("schema",1);root.put("orderId",orderId);root.put("businessName",session==null?"GI POS":session.businessName);root.put("operator",session==null?"":session.ownerName);root.put("billNumber",order.billNumber);root.put("billPeriod",order.billPeriod);root.put("kotNumber",order.kotNumber);root.put("kotPeriod",order.kotPeriod);root.put("table",order.tableName);root.put("customer",order.customerName);root.put("orderType",order.orderType);root.put("orderTypeLabel",orderTypeLabel(order.orderType));root.put("deliveryName",order.deliveryName);root.put("deliveryPhone",order.deliveryPhone);root.put("deliveryAddress",order.deliveryAddress);root.put("orderNote",order.orderNote);root.put("createdAt",System.currentTimeMillis());JSONArray items=new JSONArray();try(Cursor c=db.rawQuery("SELECT item_name,variant_name,quantity,unit_price,line_total,tax_rate,tax_mode,tax_type,tax_amount,note FROM order_items WHERE order_id=? ORDER BY updated_at,id",new String[]{orderId})){while(c.moveToNext()){JSONObject item=new JSONObject();item.put("name",c.getString(0));item.put("variant",c.getString(1));item.put("quantity",c.getDouble(2));item.put("unitPrice",c.getDouble(3));item.put("lineTotal",c.getDouble(4));item.put("taxRate",c.getDouble(5));item.put("taxMode",c.getString(6));item.put("taxType",c.getString(7));item.put("taxAmount",c.getDouble(8));item.put("note",c.getString(9));items.put(item);}}root.put("items",items);JSONObject totals=new JSONObject();totals.put("subtotal",order.subtotal);totals.put("tax",order.tax);totals.put("discount",order.discount);totals.put("total",order.total);totals.put("received",order.received);totals.put("balance",order.balance);totals.put("due",order.due);root.put("totals",totals);JSONArray payments=new JSONArray();try(Cursor c=db.rawQuery("SELECT p.method,p.amount,p.reference,COALESCE(g.name,'') FROM payments p LEFT JOIN payment_gateways g ON g.id=p.gateway_id WHERE p.order_id=? ORDER BY p.created_at,p.id",new String[]{orderId})){while(c.moveToNext()){JSONObject payment=new JSONObject();String method=c.getString(0);String gatewayName=c.getString(3);payment.put("method","gateway".equals(method)&&!gatewayName.isEmpty()?gatewayName:method);payment.put("amount",c.getDouble(1));payment.put("reference",c.getString(2));payments.put(payment);}}root.put("payments",payments);return root.toString();}catch(Exception error){throw new IllegalStateException("Could not create receipt snapshot",error);}}
  private String buildKotSnapshot(SQLiteDatabase db,String orderId){try{OrderInfo order=orderInfo(db,orderId);if(order==null)throw new IllegalStateException("Order not found");SecureStore.Session session=SecureStore.load(appContext);JSONObject root=new JSONObject();root.put("schema",1);root.put("orderId",orderId);root.put("businessName",session==null?"GI POS":session.businessName);root.put("operator",session==null?"":session.ownerName);root.put("kotNumber",order.kotNumber);root.put("kotPeriod",order.kotPeriod);root.put("table",order.tableName);root.put("orderType",order.orderType);root.put("orderTypeLabel",orderTypeLabel(order.orderType));root.put("deliveryName",order.deliveryName);root.put("deliveryPhone",order.deliveryPhone);root.put("deliveryAddress",order.deliveryAddress);root.put("orderNote",order.orderNote);root.put("createdAt",System.currentTimeMillis());JSONArray items=new JSONArray();try(Cursor c=db.rawQuery("SELECT item_name,variant_name,quantity,note FROM order_items WHERE order_id=? ORDER BY updated_at,id",new String[]{orderId})){while(c.moveToNext()){JSONObject item=new JSONObject();item.put("name",c.getString(0));item.put("variant",c.getString(1));item.put("quantity",c.getDouble(2));item.put("note",c.getString(3));items.put(item);}}root.put("items",items);return root.toString();}catch(Exception error){throw new IllegalStateException("Could not create KOT snapshot",error);}}
  // ---- Business day (03:00 -> 03:00) --------------------------------------
  // The trading day runs from 03:00 to 03:00 the next morning, so late-night
  // sales (e.g. 01:30) still count under the day that just ended. All date-range
  // reporting/history and the "today" reset use these helpers so everything
  // stays consistent.
  public static final int BUSINESS_DAY_START_HOUR = 3;
  // The business-day LocalDate that a given instant belongs to. Before 03:00 the
  // instant still belongs to the previous calendar day's business day.
  public static java.time.LocalDate businessDayOf(long timestamp){
    java.time.ZonedDateTime zoned=Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());
    if(zoned.getHour()<BUSINESS_DAY_START_HOUR)return zoned.toLocalDate().minusDays(1);
    return zoned.toLocalDate();
  }
  public static java.time.LocalDate currentBusinessDay(){return businessDayOf(System.currentTimeMillis());}
  // Epoch millis at 03:00 of the given business-day date.
  public static long businessDayStart(java.time.LocalDate day){return day.atTime(BUSINESS_DAY_START_HOUR,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
  // Epoch millis at 03:00 the next morning (exclusive end of the business day).
  public static long businessDayEnd(java.time.LocalDate day){return day.plusDays(1).atTime(BUSINESS_DAY_START_HOUR,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();}
  // KOT numbers reset per business day (03:00 -> 03:00), matching the reports.
  static String dailyKey(long timestamp){return businessDayOf(timestamp).format(DateTimeFormatter.ISO_LOCAL_DATE);}
  static String financialYearKey(long timestamp){LocalDate date=Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();int start=date.getMonthValue()<4?date.getYear()-1:date.getYear();return start+"-"+String.format(java.util.Locale.US,"%02d",(start+1)%100);}
  public void toggleOrderItem(String orderId,ProductInfo product,VariantInfo variant,double manualPrice){
    SQLiteDatabase db=getWritableDatabase();String variantName=variant==null?"":variant.name;String variantId=variant==null?"":variant.id;String existing=null;
    try(Cursor c=db.rawQuery("SELECT id FROM order_items WHERE order_id=? AND product_id=? AND variant_name=? LIMIT 1",new String[]{orderId,product.id,variantName})){if(c.moveToFirst())existing=c.getString(0);}
    if(existing!=null)db.delete("order_items","id=?",new String[]{existing});else{double price=MoneyMath.round(variant!=null?variant.price:("manual".equals(product.priceMode)?manualPrice:product.price));
      // When global GST is enabled it becomes the default tax for every line so
      // ordering/KOT/checkout/billing stay consistent; otherwise use the item's
      // own tax settings.
      GstSettings gst=gstSettings();double taxRate=product.taxRate;String taxMode=product.gstMode,taxType=product.gstType;
      if(gst.enabled){taxRate=gst.rate;taxMode=gst.mode;taxType=gst.type;}
      long lineMinor=MoneyMath.toMinor(price);double taxAmount=MoneyMath.fromMinor("inclusive".equals(taxMode)?MoneyMath.inclusiveTax(lineMinor,taxRate):MoneyMath.exclusiveTax(lineMinor,taxRate));ContentValues row=new ContentValues();row.put("id",UUID.randomUUID().toString());row.put("order_id",orderId);row.put("product_id",product.id);row.put("item_name",product.name);row.put("variant_name",variantName);row.put("quantity",1);row.put("unit_price",price);row.put("tax_rate",taxRate);row.put("tax_mode",taxMode);row.put("tax_type",taxType);row.put("tax_amount",taxAmount);row.put("line_total",price);row.put("note",product.kotDescription);row.put("updated_at",System.currentTimeMillis());db.insertOrThrow("order_items",null,row);}recalculateOrder(orderId);queueSync("order",orderId,"upsert");
  }
  // Add a specific quantity of a product (optionally a size/variant) to the order.
  // If the same product+variant line already exists, its quantity increases by qty;
  // otherwise a new line is created. Used by the ordering screen's quantity pop-up so
  // adding an item does not toggle-remove an existing one.
  public void addOrderItemQuantity(String orderId,ProductInfo product,VariantInfo variant,double manualPrice,double qty){
    if(qty<=0)return;
    SQLiteDatabase db=getWritableDatabase();String variantName=variant==null?"":variant.name;String existing=null;
    try(Cursor c=db.rawQuery("SELECT id FROM order_items WHERE order_id=? AND product_id=? AND variant_name=? LIMIT 1",new String[]{orderId,product.id,variantName})){if(c.moveToFirst())existing=c.getString(0);}
    if(existing!=null){changeOrderLineQuantity(orderId,existing,qty);return;}
    double price=MoneyMath.round(variant!=null?variant.price:("manual".equals(product.priceMode)?manualPrice:product.price));
    GstSettings gst=gstSettings();double taxRate=product.taxRate;String taxMode=product.gstMode,taxType=product.gstType;
    if(gst.enabled){taxRate=gst.rate;taxMode=gst.mode;taxType=gst.type;}
    double lineTotal=MoneyMath.round(price*qty);long lineMinor=MoneyMath.toMinor(lineTotal);
    double taxAmount=MoneyMath.fromMinor("inclusive".equals(taxMode)?MoneyMath.inclusiveTax(lineMinor,taxRate):MoneyMath.exclusiveTax(lineMinor,taxRate));
    ContentValues row=new ContentValues();row.put("id",UUID.randomUUID().toString());row.put("order_id",orderId);row.put("product_id",product.id);row.put("item_name",product.name);row.put("variant_name",variantName);row.put("quantity",qty);row.put("unit_price",price);row.put("tax_rate",taxRate);row.put("tax_mode",taxMode);row.put("tax_type",taxType);row.put("tax_amount",taxAmount);row.put("line_total",lineTotal);row.put("note",product.kotDescription);row.put("updated_at",System.currentTimeMillis());
    db.insertOrThrow("order_items",null,row);recalculateOrder(orderId);queueSync("order",orderId,"upsert");
  }
  // Set the EXACT quantity for a product (optionally a size/variant). Creates the
  // line if it doesn't exist, updates it to exactly qty if it does, or removes it
  // when qty<=0. Used by the ordering quantity pop-up so re-opening an item and
  // confirming sets (not adds to) its quantity.
  public void setOrderItemQuantity(String orderId,ProductInfo product,VariantInfo variant,double manualPrice,double qty){
    SQLiteDatabase db=getWritableDatabase();String variantName=variant==null?"":variant.name;String existing=null;
    try(Cursor c=db.rawQuery("SELECT id FROM order_items WHERE order_id=? AND product_id=? AND variant_name=? LIMIT 1",new String[]{orderId,product.id,variantName})){if(c.moveToFirst())existing=c.getString(0);}
    if(qty<=0){
      if(existing!=null){db.delete("order_items","id=? AND order_id=?",new String[]{existing,orderId});recalculateOrder(orderId);queueSync("order",orderId,"upsert");}
      return;
    }
    double price=MoneyMath.round(variant!=null?variant.price:("manual".equals(product.priceMode)?manualPrice:product.price));
    GstSettings gst=gstSettings();double taxRate=product.taxRate;String taxMode=product.gstMode,taxType=product.gstType;
    if(gst.enabled){taxRate=gst.rate;taxMode=gst.mode;taxType=gst.type;}
    double lineTotal=MoneyMath.round(price*qty);long lineMinor=MoneyMath.toMinor(lineTotal);
    double taxAmount=MoneyMath.fromMinor("inclusive".equals(taxMode)?MoneyMath.inclusiveTax(lineMinor,taxRate):MoneyMath.exclusiveTax(lineMinor,taxRate));
    ContentValues row=new ContentValues();row.put("quantity",qty);row.put("unit_price",price);row.put("tax_rate",taxRate);row.put("tax_mode",taxMode);row.put("tax_type",taxType);row.put("tax_amount",taxAmount);row.put("line_total",lineTotal);row.put("updated_at",System.currentTimeMillis());
    if(existing!=null){db.update("order_items",row,"id=? AND order_id=?",new String[]{existing,orderId});}
    else{row.put("id",UUID.randomUUID().toString());row.put("order_id",orderId);row.put("product_id",product.id);row.put("item_name",product.name);row.put("variant_name",variantName);row.put("note",product.kotDescription);db.insertOrThrow("order_items",null,row);}
    recalculateOrder(orderId);queueSync("order",orderId,"upsert");
  }
  // Current quantity of a product+variant in the order (0 if not present).
  public double orderLineQuantity(String orderId,String productId,String variantName){
    try(Cursor c=getReadableDatabase().rawQuery("SELECT quantity FROM order_items WHERE order_id=? AND product_id=? AND variant_name=? LIMIT 1",new String[]{orderId,productId,variantName==null?"":variantName})){return c.moveToFirst()?c.getDouble(0):0;}
  }
  // Saved unit price of a product+variant line (0 if not present). Used to keep the
  // manage pop-up showing the actual price for manual-priced items already in cart.
  public double orderLineUnitPrice(String orderId,String productId,String variantName){
    try(Cursor c=getReadableDatabase().rawQuery("SELECT unit_price FROM order_items WHERE order_id=? AND product_id=? AND variant_name=? LIMIT 1",new String[]{orderId,productId,variantName==null?"":variantName})){return c.moveToFirst()?c.getDouble(0):0;}
  }
  public void changeOrderLineQuantity(String orderId,String lineId,double change){SQLiteDatabase db=getWritableDatabase();double quantity=0,price=0,rate=0;String mode="exclusive";try(Cursor c=db.rawQuery("SELECT quantity,unit_price,tax_rate,tax_mode FROM order_items WHERE id=? AND order_id=?",new String[]{lineId,orderId})){if(c.moveToFirst()){quantity=c.getDouble(0)+change;price=c.getDouble(1);rate=c.getDouble(2);mode=c.getString(3);}}if(quantity<=0)db.delete("order_items","id=? AND order_id=?",new String[]{lineId,orderId});else{double lineTotal=MoneyMath.round(quantity*price);long lineMinor=MoneyMath.toMinor(lineTotal);double taxAmount=MoneyMath.fromMinor("inclusive".equals(mode)?MoneyMath.inclusiveTax(lineMinor,rate):MoneyMath.exclusiveTax(lineMinor,rate));ContentValues row=new ContentValues();row.put("quantity",quantity);row.put("line_total",lineTotal);row.put("tax_amount",taxAmount);row.put("updated_at",System.currentTimeMillis());db.update("order_items",row,"id=? AND order_id=?",new String[]{lineId,orderId});}recalculateOrder(orderId);queueSync("order",orderId,"upsert");}
  public void removeOrderLine(String orderId,String lineId){SQLiteDatabase db=getWritableDatabase();db.delete("order_items","id=? AND order_id=?",new String[]{lineId,orderId});recalculateOrder(orderId);queueSync("order",orderId,"upsert");}
  private void recalculateOrder(String orderId){SQLiteDatabase db=getWritableDatabase();long subtotal=0,tax=0,exclusiveTax=0;try(Cursor c=db.rawQuery("SELECT line_total,tax_amount,tax_mode FROM order_items WHERE order_id=?",new String[]{orderId})){while(c.moveToNext()){subtotal+=MoneyMath.toMinor(c.getDouble(0));long lineTax=MoneyMath.toMinor(c.getDouble(1));tax+=lineTax;if("exclusive".equals(c.getString(2)))exclusiveTax+=lineTax;}}ContentValues row=new ContentValues();row.put("subtotal",MoneyMath.fromMinor(subtotal));row.put("tax_total",MoneyMath.fromMinor(tax));row.put("grand_total",MoneyMath.fromMinor(subtotal+exclusiveTax));row.put("updated_at",System.currentTimeMillis());row.put("sync_state","pending");db.update("orders",row,"id=?",new String[]{orderId});db.execSQL("UPDATE orders SET version=version+1 WHERE id=?",new Object[]{orderId});}
  private void queueSync(String type,String entityId,String operation){SQLiteDatabase db=getWritableDatabase();db.delete("sync_queue","entity_type=? AND entity_id=?",new String[]{type,entityId});ContentValues row=new ContentValues();row.put("id",UUID.randomUUID().toString());row.put("entity_type",type);row.put("entity_id",entityId);row.put("operation",operation);row.put("payload","{}");row.put("created_at",System.currentTimeMillis());db.insert("sync_queue",null,row);}

  public String addFloor(String name){String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("name",name.trim());row.put("sort_order",(int)(System.currentTimeMillis()%100000));row.put("updated_at",System.currentTimeMillis());getWritableDatabase().insertOrThrow("floor_groups",null,row);queueSync("floor",id,"upsert");return id;}
  public void addTable(String floorId,String name,int seats){String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("floor_id",floorId);row.put("name",name.trim());row.put("seats",seats);row.put("sort_order",(int)(System.currentTimeMillis()%100000));row.put("updated_at",System.currentTimeMillis());getWritableDatabase().insertOrThrow("dining_tables",null,row);queueSync("table",id,"upsert");}

  // Duplicate a table so the same physical table can serve another customer in
  // parallel. Creates a fresh table on the same floor with no bill, named after
  // the original with the next free " (2)", " (3)", ... suffix, flagged as a
  // duplicate so it is auto-removed once its bill is finalized. Returns the new id.
  public String duplicateTable(String sourceTableId){
    SQLiteDatabase db=getWritableDatabase();
    String floorId=null,baseName=null;int seats=4,sort=0;
    try(Cursor c=db.rawQuery("SELECT floor_id,name,seats,sort_order FROM dining_tables WHERE id=? LIMIT 1",new String[]{sourceTableId})){
      if(!c.moveToFirst())throw new IllegalStateException("Table not found");
      floorId=c.isNull(0)?null:c.getString(0);baseName=c.getString(1);seats=c.getInt(2);sort=c.getInt(3);
    }
    // Strip any existing " (n)" suffix so duplicates of duplicates share one base.
    String root=baseName.replaceAll("\\s*\\(\\d+\\)\\s*$","").trim();
    // Find the next free "root (n)" name among active tables on this floor.
    int suffix=2;String candidate;
    while(true){
      candidate=root+" ("+suffix+")";
      boolean taken=false;
      try(Cursor c=db.rawQuery("SELECT 1 FROM dining_tables WHERE active=1 AND name=? LIMIT 1",new String[]{candidate})){taken=c.moveToFirst();}
      if(!taken)break;
      suffix++;
      if(suffix>200){candidate=root+" ("+System.currentTimeMillis()%100000+")";break;}
    }
    String id=UUID.randomUUID().toString();
    ContentValues row=new ContentValues();
    row.put("id",id);
    if(floorId==null)row.putNull("floor_id");else row.put("floor_id",floorId);
    row.put("name",candidate);row.put("seats",seats);
    row.put("sort_order",sort+1);row.put("active",1);row.put("is_duplicate",1);
    row.put("updated_at",System.currentTimeMillis());
    db.insertOrThrow("dining_tables",null,row);
    queueSync("table",id,"upsert");
    return id;
  }

  // Manually remove a duplicate table (only if it has no active order). Regular
  // tables are never removed by this. Returns true if it was removed.
  public boolean removeDuplicateTable(String tableId){
    SQLiteDatabase db=getWritableDatabase();
    boolean duplicate=false;
    try(Cursor c=db.rawQuery("SELECT COALESCE(is_duplicate,0) FROM dining_tables WHERE id=?",new String[]{tableId})){if(c.moveToFirst())duplicate=c.getInt(0)==1;}
    if(!duplicate)return false;
    boolean hasActiveOrder=false;
    try(Cursor c=db.rawQuery("SELECT 1 FROM orders WHERE table_id=? AND status IN ('draft','kot','held','unclosed') AND EXISTS(SELECT 1 FROM order_items i WHERE i.order_id=orders.id) LIMIT 1",new String[]{tableId})){hasActiveOrder=c.moveToFirst();}
    if(hasActiveOrder)throw new IllegalStateException("This duplicate has an open order. Checkout or cancel it first.");
    ContentValues row=new ContentValues();row.put("active",0);row.put("updated_at",System.currentTimeMillis());
    db.update("dining_tables",row,"id=?",new String[]{tableId});
    queueSync("table",tableId,"upsert");
    return true;
  }
  public void updateTable(String id,String name,int seats){ContentValues row=new ContentValues();row.put("name",name.trim());row.put("seats",seats);row.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("dining_tables",row,"id=?",new String[]{id});queueSync("table",id,"upsert");}
}
