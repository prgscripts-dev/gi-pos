package com.GIHOSTINGS.giposapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class CloudClient {
  public static final class Result {
    public final JSONObject session;
    Result(JSONObject session) { this.session = session; }
  }

  /**
   * Thrown when the cloud server rejects this device's credentials (HTTP 401/403).
   * This means the device was logged out — typically because the same user signed
   * in on another device — so the app must sign out and return to login.
   */
  public static final class DeviceAuthException extends Exception {
    public DeviceAuthException(String message) { super(message); }
  }

  private CloudClient() {}

  public static Result activate(String serverInput, String login, String password, String fingerprint, SecureStore.Session existingSession) throws Exception {
    String server = normalizeServer(serverInput);
    JSONObject loginResponse = request(server + "/api/v1/client/login", "POST", null,
      new JSONObject().put("login", login.trim()).put("password", password).put("appPlatform", "android"));
    String token = loginResponse.optString("token");
    if (token.isBlank()) throw new Exception("The server did not return a valid session.");

    JSONObject me = request(server + "/api/v1/client/me", "GET", token, null);
    JSONObject restaurant = selectRestaurant(me.optJSONArray("restaurants"), existingSession == null ? "" : existingSession.restaurantId);
    if (restaurant == null) throw new Exception("No approved restaurant with an active license was found.");

    String restaurantId = restaurant.optString("id");
    boolean reuseActivation = existingSession != null
      && server.equals(existingSession.serverUrl)
      && restaurantId.equals(existingSession.restaurantId)
      && !existingSession.deviceId.isBlank()
      && !existingSession.apiKey.isBlank();
    if (reuseActivation) {
      try {
        String now = URLEncoder.encode(Instant.now().toString(), StandardCharsets.UTF_8.name());
        deviceRequest(server + "/api/v1/sync/pull?since=" + now, "GET", existingSession, null);
      } catch (Exception ignored) {
        reuseActivation = false;
      }
    }
    JSONObject activation = reuseActivation ? null : request(server + "/api/v1/client/restaurants/" + restaurantId + "/devices/activate", "POST", token,
      new JSONObject().put("deviceName", "GI POS Mobile").put("platform", "android").put("deviceFingerprint", fingerprint));

    JSONObject account = loginResponse.optJSONObject("account");
    JSONObject activeRestaurant = reuseActivation ? restaurant : activation.optJSONObject("restaurant");
    JSONObject subscription = reuseActivation ? subscriptionFromRestaurant(restaurant) : activation.optJSONObject("subscription");
    JSONObject device = reuseActivation ? null : activation.optJSONObject("device");
    JSONObject offlineLicense = reuseActivation ? null : activation.optJSONObject("offlineLicense");
    String expiry = first(subscription, "expires_at", "expiresAt");
    if (expiry.isBlank()) expiry = restaurant.optString("expires_at");
    String activatedAt = offlineLicense == null ? Instant.now().toString() : offlineLicense.optString("activatedAt", Instant.now().toString());

    JSONObject session = new JSONObject()
      .put("serverUrl", server).put("token", token).put("restaurantId", restaurantId)
      .put("businessName", activeRestaurant == null ? restaurant.optString("name") : activeRestaurant.optString("name"))
      .put("ownerName", account == null ? "Owner" : account.optString("ownerName", "Owner"))
      .put("planId", first(subscription, "plan_id", "planId"))
      .put("plan", first(subscription, "plan_name", "planName"))
      .put("capabilities", subscription == null ? new JSONObject() : subscription.optJSONObject("plan_capabilities"))
      .put("status", subscription == null ? restaurant.optString("subscription_status") : subscription.optString("status"))
      .put("activatedAt", activatedAt).put("expiresAt", expiry)
      .put("deviceId", reuseActivation ? existingSession.deviceId : device == null ? "" : device.optString("id"))
      .put("apiKey", reuseActivation ? existingSession.apiKey : activation.optString("apiKey"));
    SecureStore.Session checked = new SecureStore.Session(session);
    if (!checked.isActive()) throw new Exception("The restaurant license is inactive or expired.");
    return new Result(session);
  }

  /**
   * Staff sign-in directly on this device with the phone + password the owner
   * created (no owner account needed). Activates a device for the staff's
   * restaurant and returns a Result whose session also carries the staff profile.
   * Multiple staff devices can be active at once.
   */
  public static Result staffActivate(String serverInput, String login, String password, String fingerprint) throws Exception {
    String server = normalizeServer(serverInput);
    JSONObject activation = request(server + "/api/v1/staff/activate", "POST", null,
      new JSONObject()
        .put("login", login == null ? "" : login.trim())
        .put("password", password == null ? "" : password)
        .put("platform", "android")
        .put("deviceName", "GI POS Mobile (Staff)")
        .put("deviceFingerprint", fingerprint == null ? "" : fingerprint));

    JSONObject restaurant = activation.optJSONObject("restaurant");
    JSONObject subscription = activation.optJSONObject("subscription");
    JSONObject device = activation.optJSONObject("device");
    JSONObject staff = activation.optJSONObject("staff");
    if (restaurant == null || device == null) throw new Exception("The server did not return a valid staff session.");

    String restaurantId = restaurant.optString("id");
    String expiry = first(subscription, "expires_at", "expiresAt");
    if (expiry.isBlank()) expiry = restaurant.optString("expires_at");

    JSONObject session = new JSONObject()
      .put("serverUrl", server)
      .put("token", "")
      .put("restaurantId", restaurantId)
      .put("businessName", restaurant.optString("name"))
      .put("ownerName", staff == null ? restaurant.optString("owner_name", "Staff") : staff.optString("name", "Staff"))
      .put("planId", first(subscription, "plan_id", "planId"))
      .put("plan", first(subscription, "plan_name", "planName"))
      .put("capabilities", subscription == null ? new JSONObject() : subscription.optJSONObject("plan_capabilities"))
      .put("status", subscription == null ? restaurant.optString("subscription_status") : subscription.optString("status"))
      .put("activatedAt", Instant.now().toString())
      .put("expiresAt", expiry)
      .put("deviceId", device.optString("id"))
      .put("apiKey", activation.optString("apiKey"))
      .put("staff", staff == null ? JSONObject.NULL : staff);
    SecureStore.Session checked = new SecureStore.Session(session);
    if (!checked.isActive()) throw new Exception("The restaurant license is inactive or expired.");
    return new Result(session);
  }

  private static JSONObject subscriptionFromRestaurant(JSONObject restaurant) throws Exception {
    return new JSONObject()
      .put("plan_id", restaurant.optString("plan_id"))
      .put("plan_name", restaurant.optString("plan_name"))
      .put("plan_capabilities", restaurant.optJSONObject("plan_capabilities"))
      .put("status", restaurant.optString("subscription_status"))
      .put("starts_at", restaurant.optString("starts_at"))
      .put("expires_at", restaurant.optString("expires_at"));
  }

  public static JSONObject push(SecureStore.Session session,JSONArray changes)throws Exception{return deviceRequest(session.serverUrl+"/api/v1/sync/push","POST",session,new JSONObject().put("changes",changes));}
  public static JSONObject pull(SecureStore.Session session,String since)throws Exception{return deviceRequest(session.serverUrl+"/api/v1/sync/pull?since="+URLEncoder.encode(since==null?"":since,StandardCharsets.UTF_8.name()),"GET",session,null);}

  // ---- Device-authed staff management (User Management page) --------------------

  /** List all staff for this restaurant (device-authed). Returns the full response object. */
  public static JSONObject listStaff(SecureStore.Session session) throws Exception {
    return deviceRequest(session.serverUrl + "/api/v1/device/staff", "GET", session, null);
  }

  /** Create a new staff member. permissions/tableAccess are JSON arrays of strings. */
  public static JSONObject createStaff(SecureStore.Session session, String name, String mobile,
                                       String password, JSONArray permissions, JSONArray tableAccess) throws Exception {
    JSONObject body = new JSONObject()
      .put("name", name == null ? "" : name.trim())
      .put("mobile", mobile == null ? "" : mobile.trim())
      .put("password", password == null ? "" : password)
      .put("role", "Staff")
      .put("permissions", permissions == null ? new JSONArray() : permissions)
      .put("tableAccess", tableAccess == null ? new JSONArray() : tableAccess);
    return deviceRequest(session.serverUrl + "/api/v1/device/staff", "POST", session, body);
  }

  /** Update an existing staff member. Pass null for fields you don't want to change. */
  public static JSONObject updateStaff(SecureStore.Session session, String staffId, JSONObject fields) throws Exception {
    return deviceRequest(session.serverUrl + "/api/v1/device/staff/" + URLEncoder.encode(staffId, StandardCharsets.UTF_8.name()),
      "POST", session, fields == null ? new JSONObject() : fields);
  }

  /** Delete a staff member. */
  public static JSONObject deleteStaff(SecureStore.Session session, String staffId) throws Exception {
    return deviceRequest(session.serverUrl + "/api/v1/device/staff/" + URLEncoder.encode(staffId, StandardCharsets.UTF_8.name()),
      "DELETE", session, null);
  }

  /**
   * Authenticate a staff member from their phone using phone number (or staff code) + password.
   * Returns the staff profile object (permissions, tableAccess, name, role) on success.
   * Uses a raw device request that does NOT treat 403 as a device sign-out — a wrong
   * staff password is a normal validation error, not a device logout.
   */
  public static JSONObject staffLogin(SecureStore.Session session, String login, String password) throws Exception {
    JSONObject body = new JSONObject()
      .put("login", login == null ? "" : login.trim())
      .put("password", password == null ? "" : password);
    JSONObject response = deviceRequestRaw(session.serverUrl + "/api/v1/device/staff/login", "POST", session, body);
    return response.optJSONObject("staff");
  }

  /** Validate + normalize a user-entered cloud URL (throws with a clear message if invalid). */
  public static String normalizeServerUrl(String input) throws Exception { return normalizeServer(input); }

  /**
   * Push this device's friendly name (the cloud-connection name set in Settings)
   * to the server so it shows on the client portal's Signed Devices page.
   * Device-authed; best-effort.
   */
  public static void renameDevice(SecureStore.Session session, String name) throws Exception {
    deviceRequest(session.serverUrl + "/api/v1/device/rename", "POST", session,
      new JSONObject().put("name", name == null ? "" : name.trim()));
  }

  /**
   * Verify that this device's credentials work against a NEW server URL by
   * doing a quick sync/pull probe. Throws DeviceAuthException if the device
   * isn't recognized there, or a generic Exception if the server is unreachable.
   */
  public static void verifyDeviceUrl(SecureStore.Session session, String newUrl) throws Exception {
    String server = normalizeServer(newUrl);
    String now = URLEncoder.encode(java.time.Instant.now().toString(), StandardCharsets.UTF_8.name());
    deviceRequest(server + "/api/v1/sync/pull?since=" + now, "GET", session, null);
  }

  private static JSONObject selectRestaurant(JSONArray restaurants, String preferredRestaurantId) {
    if (restaurants == null) return null;
    JSONObject firstActive = null;
    for (int index = 0; index < restaurants.length(); index++) {
      JSONObject item = restaurants.optJSONObject(index);
      if (item == null || !"approved".equalsIgnoreCase(item.optString("status"))) continue;
      String status = item.optString("subscription_status");
      String expiry = item.optString("expires_at");
      boolean active = "active".equalsIgnoreCase(status) || "trial".equalsIgnoreCase(status);
      boolean current;
      try { current = !expiry.isBlank() && Instant.parse(expiry).isAfter(Instant.now()); } catch (Exception ignored) { current = false; }
      if (active && current && item.optString("id").equals(preferredRestaurantId)) return item;
      if (active && current && firstActive == null) firstActive = item;
    }
    return firstActive;
  }

  private static JSONObject request(String endpoint, String method, String token, JSONObject body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
    connection.setRequestMethod(method); connection.setConnectTimeout(12_000); connection.setReadTimeout(20_000);
    connection.setRequestProperty("Accept", "application/json");
    if (token != null && !token.isBlank()) connection.setRequestProperty("x-client-token", token);
    if (body != null) {
      connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
    }
    int code = connection.getResponseCode();
    InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
    String raw = stream == null ? "" : readUtf8(stream);
    JSONObject response = raw.isBlank() ? new JSONObject() : new JSONObject(raw);
    connection.disconnect();
    if (code < 200 || code >= 300 || !response.optBoolean("ok", false)) throw new Exception(response.optString("error", "Server connection failed (" + code + ")."));
    return response;
  }
  private static JSONObject deviceRequest(String endpoint, String method, SecureStore.Session session, JSONObject body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(12_000);
    connection.setReadTimeout(25_000);
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("x-restaurant-id", session.restaurantId);
    connection.setRequestProperty("x-device-id", session.deviceId);
    connection.setRequestProperty("x-api-key", session.apiKey);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
    }
    int code = connection.getResponseCode();
    InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
    String raw = stream == null ? "" : readUtf8(stream);
    JSONObject response = raw.isBlank() ? new JSONObject() : new JSONObject(raw);
    connection.disconnect();
    // 401/403 mean this device is no longer a valid session (logged out because
    // the user signed in elsewhere, or the device was deactivated). Surface this
    // distinctly so the app can sign out securely instead of retrying forever.
    if (code == 401 || code == 403) {
      throw new DeviceAuthException(response.optString("error", "This device was signed out."));
    }
    if (code < 200 || code >= 300 || !response.optBoolean("ok", false)) {
      throw new Exception(response.optString("error", "Cloud sync failed (" + code + ")"));
    }
    return response;
  }

  /**
   * Like deviceRequest, but treats 401/403 as ordinary validation errors instead of a
   * device sign-out. Used for staff login where a 403 means "wrong password", not
   * "this device was logged out".
   */
  private static JSONObject deviceRequestRaw(String endpoint, String method, SecureStore.Session session, JSONObject body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(12_000);
    connection.setReadTimeout(25_000);
    connection.setRequestProperty("Accept", "application/json");
    connection.setRequestProperty("x-restaurant-id", session.restaurantId);
    connection.setRequestProperty("x-device-id", session.deviceId);
    connection.setRequestProperty("x-api-key", session.apiKey);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
    }
    int code = connection.getResponseCode();
    InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
    String raw = stream == null ? "" : readUtf8(stream);
    JSONObject response = raw.isBlank() ? new JSONObject() : new JSONObject(raw);
    connection.disconnect();
    if (code < 200 || code >= 300 || !response.optBoolean("ok", false)) {
      throw new Exception(response.optString("error", "Request failed (" + code + ")"));
    }
    return response;
  }

  private static String normalizeServer(String input) throws Exception {
    String value = input == null ? "" : input.trim().replaceAll("/+$", "");
    URI uri = URI.create(value);
    String scheme = uri.getScheme();
    if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || uri.getHost() == null) throw new Exception("Enter a valid server address.");
    // Allow plain HTTP only for a Main PC on the local network (private LAN IP or localhost).
    // Any public/internet address still requires HTTPS for a secure sign-in.
    if (!"https".equalsIgnoreCase(scheme) && !isPrivateHost(uri.getHost())) {
      throw new Exception("Secure sign-in requires an HTTPS server address. Use Auto-find for a Main PC on Wi-Fi.");
    }
    return value;
  }

  /** True for local/private network hosts where plain HTTP is acceptable (Main PC on LAN). */
  private static boolean isPrivateHost(String host) {
    if (host == null) return false;
    String h = host.trim().toLowerCase();
    if (h.isEmpty()) return false;
    if (h.equals("localhost") || h.endsWith(".local") || h.equals("127.0.0.1") || h.equals("::1")) return true;
    if (h.startsWith("192.168.")) return true;
    if (h.startsWith("10.")) return true;
    if (h.startsWith("169.254.")) return true; // link-local
    // 172.16.0.0 - 172.31.255.255
    if (h.startsWith("172.")) {
      String[] parts = h.split("\\.");
      if (parts.length >= 2) {
        try {
          int second = Integer.parseInt(parts[1]);
          if (second >= 16 && second <= 31) return true;
        } catch (NumberFormatException ignored) {}
      }
    }
    return false;
  }

  private static String first(JSONObject value, String snake, String camel) {
    if (value == null) return "";
    String result = value.optString(snake); return result.isBlank() ? value.optString(camel) : result;
  }

  private static String readUtf8(InputStream stream) throws Exception {
    try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toString(StandardCharsets.UTF_8.name());
    }
  }
}
