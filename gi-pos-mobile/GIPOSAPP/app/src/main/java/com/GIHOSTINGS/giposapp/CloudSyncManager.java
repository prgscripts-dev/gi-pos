package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudSyncManager {
  private static final ExecutorService EXECUTOR=Executors.newSingleThreadExecutor();
  private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
  private static final String PREFS="gi_pos_mobile_sync",LAST_SYNC="last_sync",LAST_ERROR="last_error";
  private CloudSyncManager(){}
  public static void initialSync(Context context)throws Exception{Context app=context.getApplicationContext();SecureStore.Session session=SecureStore.load(app);sync(app,session);}
  // Serialize every sync on a single lock so background (1s ticker) and manual
  // syncs never overlap and race the SQLite DB / cursor.
  private static final Object SYNC_LOCK=new Object();

  public static void syncAsync(Context context){
    Context app=context.getApplicationContext();
    // Skip only if a sync is already queued/running so the 1s ticker never piles up.
    if(!RUNNING.compareAndSet(false,true))return;
    EXECUTOR.execute(()->{
      SecureStore.Session session=SecureStore.load(app);
      try{ synchronized(SYNC_LOCK){ sync(app,session); } }
      catch(CloudClient.DeviceAuthException authError){ forceSignOut(app,message(authError)); }
      catch(Exception error){ prefs(app).edit().putString(key(LAST_ERROR,session),message(error)).apply(); if(usesCloud(app))reportFailure(app,error); }
      finally{ RUNNING.set(false); }
    });
  }

  public interface SyncCallback { void onDone(boolean success, String message); }

  // Foreground manual sync used by the Settings "Sync now" button. Always runs a
  // full sync (waiting for any in-flight background sync via the lock) and reports
  // the outcome on the main thread.
  public static void syncNow(Context context, SyncCallback callback){
    Context app=context.getApplicationContext();
    android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    EXECUTOR.execute(()->{
      SecureStore.Session session=SecureStore.load(app);
      boolean ok; String message;
      if(session==null||!session.isActive()){ ok=false; message="Not connected. Activate the app first."; }
      else if(session.deviceId==null||session.deviceId.isEmpty()||session.apiKey==null||session.apiKey.isEmpty()){ ok=false; message="This device is not activated for cloud sync."; }
      else if(!online(app)){ ok=false; message="No internet connection."; setConnectionState(ConnectionState.DISCONNECTED,"No internet connection"); }
      else {
        try { synchronized(SYNC_LOCK){ sync(app,session); } ok=true; message="Sync complete. All data is up to date."; }
        catch(CloudClient.DeviceAuthException authError){ ok=false; message=message(authError); forceSignOut(app,message(authError)); }
        catch(Exception error){ ok=false; message=message(error); prefs(app).edit().putString(key(LAST_ERROR,session),message(error)).apply(); reportFailure(app,error); }
      }
      final boolean result=ok; final String text=message;
      main.post(()->{ if(callback!=null) callback.onDone(result,text); });
    });
  }

  // Reset the pull cursor so the next sync pulls the full data set again. Used
  // when a device needs to re-fetch everything (e.g. after connecting).
  public static void resetPullCursor(Context context){
    SecureStore.Session session=SecureStore.load(context);
    prefs(context).edit().remove(key(LAST_SYNC,session)).apply();
  }

  public static String lastError(Context context){SecureStore.Session session=SecureStore.load(context);return prefs(context).getString(key(LAST_ERROR,session),"");}
  public static String lastSync(Context context){SecureStore.Session session=SecureStore.load(context);return prefs(context).getString(key(LAST_SYNC,session),"");}
  // Lightweight, dependency-free listener registry so any visible screen can
  // re-render immediately when a sync applies remote changes (real-time updates).
  public interface UpdateListener { void onSyncUpdated(); }
  private static final java.util.List<UpdateListener> LISTENERS=new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());
  public static void addUpdateListener(UpdateListener listener){if(listener!=null&&!LISTENERS.contains(listener))LISTENERS.add(listener);}
  public static void removeUpdateListener(UpdateListener listener){LISTENERS.remove(listener);}
  private static void notifyUpdated(){if(LISTENERS.isEmpty())return;MAIN.post(()->{for(UpdateListener listener:LISTENERS){try{listener.onSyncUpdated();}catch(Throwable ignored){}}});}

  // Signed-out listener registry so the visible screen can react instantly and
  // route to the login screen when this device's session is invalidated
  // (e.g. the same user signed in on another device).
  public interface SignedOutListener { void onForcedSignOut(String reason); }
  private static final java.util.List<SignedOutListener> SIGNED_OUT_LISTENERS=new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final java.util.concurrent.atomic.AtomicBoolean SIGNED_OUT_ONCE=new java.util.concurrent.atomic.AtomicBoolean(false);
  public static void addSignedOutListener(SignedOutListener listener){if(listener!=null&&!SIGNED_OUT_LISTENERS.contains(listener))SIGNED_OUT_LISTENERS.add(listener);}
  public static void removeSignedOutListener(SignedOutListener listener){SIGNED_OUT_LISTENERS.remove(listener);}

  // Securely invalidate the local session and notify the UI. Idempotent: only
  // the first invalidation clears state and fires listeners, so overlapping
  // sync/stream failures don't loop. Called from any thread.
  public static void forceSignOut(Context context, String reason){
    Context app=context.getApplicationContext();
    if(!SIGNED_OUT_ONCE.compareAndSet(false,true))return;
    // Mark signed out first (survives a crash), then wipe the encrypted session
    // so no device credentials remain on this device.
    SecureStore.signOut(app);
    SecureStore.clear(app);
    final String text=(reason==null||reason.isBlank())?"You were signed out because this account was used on another device.":reason;
    MAIN.post(()->{for(SignedOutListener listener:SIGNED_OUT_LISTENERS){try{listener.onForcedSignOut(text);}catch(Throwable ignored){}}});
  }

  // Allow a fresh activation/login to re-arm the forced-sign-out guard.
  public static void resetSignOutGuard(){SIGNED_OUT_ONCE.set(false);}

  // ---- Cloud connection state ---------------------------------------------
  // A single, observable connection state so any screen can show a clear
  // "Cloud not connected" / "connection error" warning and know when syncing
  // has resumed. CONNECTING is shown while (re)establishing the link.
  // CONNECTED = healthy (silent). CONNECTING = background (re)connect (silent).
  // DISCONNECTED/ERROR = genuinely down (shows "Connection lost"). RECONNECTED =
  // transient signal fired once when the link recovers (brief "Cloud connected").
  public enum ConnectionState { CONNECTED, CONNECTING, DISCONNECTED, ERROR, RECONNECTED }
  public interface ConnectionListener { void onConnectionChanged(ConnectionState state, String message); }
  private static final java.util.List<ConnectionListener> CONNECTION_LISTENERS=new java.util.concurrent.CopyOnWriteArrayList<>();
  // Start assuming healthy so opening/refreshing a page never flashes a banner.
  private static volatile ConnectionState CONNECTION_STATE=ConnectionState.CONNECTED;
  private static volatile String CONNECTION_MESSAGE="";
  // Tracks whether we were in a genuinely-down state, so recovery can fire a
  // one-time RECONNECTED ("Cloud connected") signal.
  private static volatile boolean WAS_DOWN=false;
  public static void addConnectionListener(ConnectionListener listener){if(listener==null)return;if(!CONNECTION_LISTENERS.contains(listener))CONNECTION_LISTENERS.add(listener);
    // Replay only a genuinely-down state to a new screen, so a screen opening
    // during an outage shows "Connection lost". Healthy/connecting/reconnected
    // states are NOT replayed (they must never flash on page changes).
    final ConnectionState state=CONNECTION_STATE;final String msg=CONNECTION_MESSAGE;
    if(state==ConnectionState.DISCONNECTED||state==ConnectionState.ERROR){MAIN.post(()->{try{listener.onConnectionChanged(state,msg);}catch(Throwable ignored){}});}
  }
  public static void removeConnectionListener(ConnectionListener listener){CONNECTION_LISTENERS.remove(listener);}
  public static ConnectionState connectionState(){return CONNECTION_STATE;}
  public static String connectionMessage(){return CONNECTION_MESSAGE;}
  // Update the state and notify listeners only when it actually changes.
  // Recovering from a down state emits a one-shot RECONNECTED before settling
  // to CONNECTED, so the UI can flash a brief "Cloud connected".
  public static void setConnectionState(ConnectionState state,String message){
    String msg=message==null?"":message;
    boolean down=(state==ConnectionState.DISCONNECTED||state==ConnectionState.ERROR);
    if(state==ConnectionState.CONNECTED&&WAS_DOWN){
      WAS_DOWN=false;
      CONNECTION_STATE=ConnectionState.CONNECTED;CONNECTION_MESSAGE="";
      MAIN.post(()->{for(ConnectionListener listener:CONNECTION_LISTENERS){try{listener.onConnectionChanged(ConnectionState.RECONNECTED,"");}catch(Throwable ignored){}}});
      return;
    }
    if(down)WAS_DOWN=true;
    if(state==CONNECTION_STATE&&msg.equals(CONNECTION_MESSAGE))return;
    CONNECTION_STATE=state;CONNECTION_MESSAGE=msg;
    MAIN.post(()->{for(ConnectionListener listener:CONNECTION_LISTENERS){try{listener.onConnectionChanged(state,msg);}catch(Throwable ignored){}}});
  }
  // Does this device's session actually use cloud sync? The banner/state is
  // only meaningful for cloud-connected accounts.
  public static boolean usesCloud(Context context){SecureStore.Session session=SecureStore.load(context);return session!=null&&session.isActive()&&session.hasFeature("cloudSync")&&!session.deviceId.isEmpty()&&!session.apiKey.isEmpty();}
  // Classify a sync/stream failure into a user-friendly state + message.
  private static void reportFailure(Context context,Throwable error){
    if(!online(context)){setConnectionState(ConnectionState.DISCONNECTED,"No internet connection");return;}
    setConnectionState(ConnectionState.ERROR,"Cloud connection error");
  }

  private static void sync(Context context,SecureStore.Session session)throws Exception{if(session==null||!session.isActive()||session.deviceId.isEmpty()||session.apiKey.isEmpty())return;if(!online(context)){if(usesCloud(context))setConnectionState(ConnectionState.DISCONNECTED,"No internet connection");return;}PosDatabase db=PosDatabase.get(context);boolean applied=pull(context,session,db);for(int batch=0;batch<20;batch++){List<PosDatabase.SyncChange> pending=db.pendingCloudChanges(50);if(pending.isEmpty())break;JSONArray changes=new JSONArray();for(PosDatabase.SyncChange item:pending)changes.put(item.json());JSONObject result=CloudClient.push(session,changes);JSONArray accepted=result.optJSONArray("acceptedIds");List<String> ids=new ArrayList<>();if(accepted!=null)for(int i=0;i<accepted.length();i++)ids.add(accepted.optString(i));if(ids.isEmpty())break;db.markCloudSynced(ids);}prefs(context).edit().putString(key(LAST_SYNC,session),java.time.Instant.now().toString()).remove(key(LAST_ERROR,session)).apply();setConnectionState(ConnectionState.CONNECTED,"");if(applied)notifyUpdated();}
  private static boolean pull(Context context,SecureStore.Session session,PosDatabase db)throws Exception{String syncKey=key(LAST_SYNC,session);String cursor=prefs(context).getString(syncKey,"1970-01-01T00:00:00.000Z"),serverTime=cursor;boolean appliedAny=false;for(int page=0;page<20;page++){JSONObject response=CloudClient.pull(session,cursor);JSONArray changes=response.optJSONArray("changes");serverTime=response.optString("serverTime",serverTime);if(changes==null||changes.length()==0)break;List<RemoteValue> values=new ArrayList<>();String latest=cursor;boolean staffApplied=false;for(int i=0;i<changes.length();i++){JSONObject item=changes.optJSONObject(i);if(item==null)continue;String key=item.optString("key"),updated=item.optString("updatedAt");JSONObject value=item.optJSONObject("value");if(key.startsWith("mobile-v1:")&&value!=null)values.add(new RemoteValue(key,value));else if("pos-staff-users".equals(key)){org.json.JSONArray staffUsers=item.optJSONArray("value");if(staffUsers!=null){db.applyStaffDirectory(staffUsers);staffApplied=true;}}if(updated.compareTo(latest)>0)latest=updated;}values.sort(Comparator.comparingInt(item->priority(item.value.optString("type"))));for(RemoteValue item:values)db.applyCloudValue(item.key,item.value);if(!values.isEmpty()||staffApplied)appliedAny=true;if(changes.length()<500)break;if(latest.equals(cursor))break;cursor=latest;}prefs(context).edit().putString(syncKey,serverTime).apply();return appliedAny;}
  private static int priority(String type){if("category".equals(type)||"floor".equals(type)||"customer".equals(type)||"gateway".equals(type))return 0;if("table".equals(type)||"product".equals(type))return 1;return 2;}
  private static boolean online(Context context){ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);if(manager==null)return false;Network network=manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}
  private static String key(String prefix,SecureStore.Session session){return prefix+":"+(session==null||session.restaurantId.isEmpty()?"none":session.restaurantId);}
  private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}private static String message(Throwable error){String text=error.getMessage();return text==null||text.isBlank()?error.getClass().getSimpleName():text;}
  private static final class RemoteValue {final String key;final JSONObject value;RemoteValue(String key,JSONObject value){this.key=key;this.value=value;}}
}
