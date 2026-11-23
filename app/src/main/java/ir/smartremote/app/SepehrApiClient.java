package ir.smartremote.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sepehr TV API client with verified OAuth 1.0 HMAC-SHA1 request signing.
 */
public class SepehrApiClient {
    private static final String TAG = "SepehrApiClient";

    private static final String CONSUMER_KEY = "QKORpgyu9mpw3MZUUwu8Mm4qxYMsXq3L";
    private static final String CONSUMER_SECRET = "jtroj3hkyjlU06j7MtJimJ1I3PTTpx39";

    private static final String SESSION_URL = "https://sepehrtv.ir/api/auth/session";
    private static final String CHANNELS_BASE_URL = "https://sepehrapi.sepehrtv.ir/beta/v0/channels/2";
    private static final String CACHE_FILE = "sepehr_channels_cache.json";

    private static final String NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Context context;

    public SepehrApiClient(Context context) {
        this.context = context.getApplicationContext();
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }
    }

    public String fetchChannelsJson() throws Exception {
        warmupSession();

        Map<String, String> query = new TreeMap<>();
        query.put("page", "1");
        query.put("page_size", "100");

        String fullUrl = CHANNELS_BASE_URL + "?page=1&page_size=100";
        String authHeader = buildOAuthHeader("GET", CHANNELS_BASE_URL, query);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "okhttp/4.9.0");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "fa-IR");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Origin", "https://sepehrtv.ir");
            conn.setRequestProperty("Referer", "https://sepehrtv.ir/");
            conn.setRequestProperty("Authorization", authHeader);

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                throw new RuntimeException("HTTP " + code);
            }
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                stream = new GZIPInputStream(stream);
            }
            String body = readAllUtf8(stream);
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + ": " + body.substring(0, Math.min(180, body.length())));
            }
            saveCachedChannelsJson(body);
            return body;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void warmupSession() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SESSION_URL).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 12; Mobile; rv:161.0) Gecko/161.0 Firefox/161.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "fa-IR");
            conn.setRequestProperty("Referer", "https://sepehrtv.ir/live");
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public String getCachedOrSeedChannelsJson() {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        if (f.exists() && f.length() > 10) {
            try (InputStream is = new FileInputStream(f)) {
                return readAllUtf8(is);
            } catch (Exception ignored) {
            }
        }
        try (InputStream is = context.getAssets().open("default_channels.json")) {
            return readAllUtf8(is);
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private void saveCachedChannelsJson(String json) {
        try (FileOutputStream fos = new FileOutputStream(new File(context.getFilesDir(), CACHE_FILE))) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Failed to cache channels", e);
        }
    }

    public static List<ChannelItem> parseChannels(String rawJson, Map<String, String> savedCodes, boolean overwriteCodesFromApi) {
        List<ChannelItem> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(rawJson);
            JSONArray list = root.optJSONArray("list");
            if (list == null) return result;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String uid = item.optString("uid", "").trim();
                if (uid.isEmpty()) continue;

                String name = item.optString("name", uid);
                String defaultNum = item.has("number") && !item.isNull("number")
                        ? String.valueOf(item.optInt("number")) : "";

                String code;
                if (overwriteCodesFromApi) {
                    code = defaultNum;
                } else if (savedCodes != null && savedCodes.containsKey(uid)) {
                    code = savedCodes.get(uid);
                } else {
                    code = defaultNum;
                }

                String program = "پخش زنده";
                JSONObject curProg = item.optJSONObject("currentProgram");
                if (curProg != null) {
                    String title = curProg.optString("title", "").trim();
                    if (!title.isEmpty()) program = title;
                }

                String streamSrc = "";
                JSONArray streams = item.optJSONArray("streams");
                if (streams != null && streams.length() > 0) {
                    JSONObject s0 = streams.optJSONObject(0);
                    if (s0 != null) streamSrc = s0.optString("src", "");
                }

                String icon = item.optString("icon", item.optString("poster", ""));
                String preview = item.optString("preview", "");
                if (preview.isEmpty() || "null".equals(preview)) {
                    preview = item.optString("cover", "");
                }
                if (preview.isEmpty() || "null".equals(preview)) {
                    preview = icon;
                }

                result.add(new ChannelItem(uid, name, defaultNum, program, code, icon, preview, streamSrc));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse channels JSON", e);
        }
        return result;
    }

    public static String buildOAuthHeader(String method, String baseUrl, Map<String, String> queryParams) throws Exception {
        Map<String, String> oauth = new TreeMap<>();
        oauth.put("oauth_consumer_key", CONSUMER_KEY);
        oauth.put("oauth_nonce", randomNonce(32));
        oauth.put("oauth_signature_method", "HMAC-SHA1");
        oauth.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000L));
        oauth.put("oauth_token", "");
        oauth.put("oauth_version", "1.0");

        TreeMap<String, String> allParams = new TreeMap<>();
        if (queryParams != null) allParams.putAll(queryParams);
        allParams.putAll(oauth);

        StringBuilder paramBuilder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            if (!first) paramBuilder.append('&');
            first = false;
            paramBuilder.append(percentEncode(entry.getKey()))
                    .append('=')
                    .append(percentEncode(entry.getValue()));
        }

        String baseString = method.toUpperCase()
                + "&" + percentEncode(baseUrl)
                + "&" + percentEncode(paramBuilder.toString());

        String signingKey = percentEncode(CONSUMER_SECRET) + "&";
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = Base64.encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        oauth.put("oauth_signature", signature);

        StringBuilder header = new StringBuilder("OAuth ");
        boolean hFirst = true;
        for (Map.Entry<String, String> entry : oauth.entrySet()) {
            if (!hFirst) header.append(", ");
            hFirst = false;
            header.append(percentEncode(entry.getKey()))
                    .append("=\"")
                    .append(percentEncode(entry.getValue()))
                    .append("\"");
        }
        return header.toString();
    }

    public static String percentEncode(String s) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static String randomNonce(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(NONCE_CHARS.charAt(RANDOM.nextInt(NONCE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String readAllUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
