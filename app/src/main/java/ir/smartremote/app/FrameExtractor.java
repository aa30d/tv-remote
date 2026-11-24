package ir.smartremote.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Extracts live first-frame thumbnails from the LOWEST-QUALITY HLS variant
 * (lowest BANDWIDTH / smallest RESOLUTION) and stores them permanently in ImageCacheManager.
 */
public class FrameExtractor {
    private static final String TAG = "FrameExtractor";
    private static final String UA = "Mozilla/5.0 (Android 12; Mobile; rv:161.0) Gecko/161.0 Firefox/161.0";
    private static final Pattern BW_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern RES_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final int MAX_SEGMENT_BYTES = 1500 * 1024; // 1.5 MB cap

    public interface Listener {
        void onProgress(String uid, int completed, int total, boolean success);
        void onFinished(int succeeded, int total);
    }

    private final Context context;
    private final ImageCacheManager cacheManager;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public FrameExtractor(Context context, ImageCacheManager cacheManager) {
        this.context = context.getApplicationContext();
        this.cacheManager = cacheManager;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void extractAll(List<ChannelItem> channels, String preferredSource, Listener listener) {
        cancelled.set(false);
        final int total = channels != null ? channels.size() : 0;
        if (total == 0) {
            listener.onFinished(0, 0);
            return;
        }

        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicInteger succeeded = new AtomicInteger(0);

        for (final ChannelItem ch : channels) {
            pool.execute(() -> {
                if (cancelled.get()) return;
                boolean ok = false;
                try {
                    ok = captureChannelFrame(ch, preferredSource);
                    if (ok) succeeded.incrementAndGet();
                } catch (Exception e) {
                    Log.w(TAG, "Frame capture failed for " + ch.uid + ": " + e.getMessage());
                }
                int done = completed.incrementAndGet();
                if (!cancelled.get()) {
                    listener.onProgress(ch.uid, done, total, ok);
                    if (done >= total) {
                        listener.onFinished(succeeded.get(), total);
                    }
                }
            });
        }
    }

    private boolean captureChannelFrame(ChannelItem ch, String preferredSource) {
        // Use exactly the source selected by the user.
        String[] candidates = "sepehr".equals(preferredSource)
                ? new String[]{ch.sepehrStreamUrl}
                : new String[]{ch.getTelewebionStreamUrl()};
        for (String m3u8Url : candidates) {
            if (cancelled.get()) return false;
            if (m3u8Url == null || m3u8Url.trim().isEmpty()) continue;
            String referer = m3u8Url.contains("telewebion") ? "https://telewebion.com/" : "https://sepehrtv.ir/";
            if (captureFromLowestVariant(ch.uid, m3u8Url, referer)) {
                return true;
            }
        }
        return false;
    }

    private boolean captureFromLowestVariant(String uid, String m3u8Url, String referer) {
        File tmpSeg = null;
        MediaMetadataRetriever mmr = null;
        try {
            PlaylistFetch first = fetchPlaylist(m3u8Url, referer);
            if (first == null) return false;

            String mediaPlaylistText = first.text;
            String mediaPlaylistUrl = first.finalUrl;

            // Always pick the LOWEST quality variant (lowest resolution / lowest bandwidth)
            if (mediaPlaylistText.contains("#EXT-X-STREAM-INF")) {
                String lowestUrl = pickLowestQualityVariant(mediaPlaylistText, mediaPlaylistUrl);
                if (lowestUrl == null) return false;
                PlaylistFetch second = fetchPlaylist(lowestUrl, referer);
                if (second == null) return false;
                mediaPlaylistText = second.text;
                mediaPlaylistUrl = second.finalUrl;
            }

            String segmentUrl = pickFirstSegment(mediaPlaylistText, mediaPlaylistUrl);
            if (segmentUrl == null) return false;

            tmpSeg = File.createTempFile("seg_" + uid + "_", ".ts", context.getCacheDir());
            if (!downloadSegment(segmentUrl, referer, tmpSeg)) {
                return false;
            }

            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(tmpSeg.getAbsolutePath());
            Bitmap frame;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                frame = mmr.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270);
                if (frame == null) frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } else {
                frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) frame = mmr.getFrameAtTime();
            if (frame == null) return false;

            if (frame.getWidth() > 480) {
                int targetH = Math.max(1, (int) (frame.getHeight() * (480.0f / frame.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(frame, 480, targetH, true);
                if (scaled != frame) {
                    frame.recycle();
                    frame = scaled;
                }
            }

            boolean saved = cacheManager.saveCapturedFrame(uid, frame);
            frame.recycle();
            return saved;
        } catch (Exception e) {
            Log.d(TAG, "captureFromLowestVariant failed (" + uid + "): " + e.getMessage());
            return false;
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            if (tmpSeg != null && tmpSeg.exists()) {
                tmpSeg.delete();
            }
        }
    }

    /**
     * Picks the lowest-quality rendition from a master HLS playlist by lowest bandwidth
     * (and lowest pixel area as tiebreaker), ignoring audio-only variants if video exists.
     */
    private String pickLowestQualityVariant(String playlist, String baseUrl) throws Exception {
        String[] lines = playlist.split("\r?\n");
        URL base = new URL(baseUrl);
        long bestScore = Long.MAX_VALUE;
        String bestUrl = null;

        long pendingScore = -1;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#EXT-X-STREAM-INF")) {
                long bw = Long.MAX_VALUE / 4;
                Matcher bm = BW_PATTERN.matcher(t);
                if (bm.find()) bw = Long.parseLong(bm.group(1));

                long pixels = 0;
                Matcher rm = RES_PATTERN.matcher(t);
                if (rm.find()) {
                    pixels = Long.parseLong(rm.group(1)) * Long.parseLong(rm.group(2));
                }
                // Primary sort by pixel count if present, else by bandwidth
                pendingScore = (pixels > 0) ? (pixels * 1000L + Math.min(bw, 999L)) : (bw + 1_000_000_000_000L);
            } else if (!t.startsWith("#") && pendingScore >= 0) {
                String resolved = new URL(base, t).toString();
                if (bestUrl == null || pendingScore < bestScore) {
                    bestScore = pendingScore;
                    bestUrl = resolved;
                }
                pendingScore = -1;
            }
        }
        return bestUrl;
    }

    private String pickFirstSegment(String playlist, String baseUrl) throws Exception {
        String[] lines = playlist.split("\r?\n");
        URL base = new URL(baseUrl);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            return new URL(base, t).toString();
        }
        return null;
    }

    private PlaylistFetch fetchPlaylist(String url, String referer) {
        HttpURLConnection conn = null;
        try {
            RedirectResult r = openFollowingRedirects(url, referer);
            conn = r.conn;
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;
            InputStream is = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                is = new GZIPInputStream(is);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return new PlaylistFetch(new String(baos.toByteArray(), StandardCharsets.UTF_8), r.finalUrl);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean downloadSegment(String url, String referer, File dest) {
        HttpURLConnection conn = null;
        try {
            RedirectResult r = openFollowingRedirects(url, referer);
            conn = r.conn;
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return false;
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                int total = 0;
                while ((n = is.read(buf)) > 0 && total < MAX_SEGMENT_BYTES) {
                    if (cancelled.get()) return false;
                    fos.write(buf, 0, n);
                    total += n;
                }
                return total > 1024;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static RedirectResult openFollowingRedirects(String initialUrl, String referer) throws IOException {
        String current = initialUrl;
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            if (referer != null) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Empty redirect");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return new RedirectResult(conn, current);
        }
        throw new IOException("Too many redirects");
    }

    public void shutdown() {
        cancelled.set(true);
        pool.shutdownNow();
    }

    private static class RedirectResult {
        final HttpURLConnection conn;
        final String finalUrl;
        RedirectResult(HttpURLConnection conn, String finalUrl) {
            this.conn = conn;
            this.finalUrl = finalUrl;
        }
    }

    private static class PlaylistFetch {
        final String text;
        final String finalUrl;
        PlaylistFetch(String text, String finalUrl) {
            this.text = text;
            this.finalUrl = finalUrl;
        }
    }
}
