package ir.smartremote.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-level persistent disk + in-memory LruCache for channel thumbnails and captured live frames.
 * Stored persistently in filesDir/channel_thumbs/ keyed by Sepehr channel UID.
 */
public class ImageCacheManager {
    private final File diskDir;
    private final LruCache<String, Bitmap> memCache;
    private final ExecutorService loaderPool = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());

    public ImageCacheManager(Context context) {
        this.diskDir = new File(context.getApplicationContext().getFilesDir(), "channel_thumbs");
        if (!this.diskDir.exists()) {
            this.diskDir.mkdirs();
        }
        int maxMemKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = Math.min(24 * 1024, Math.max(4 * 1024, maxMemKb / 8));
        this.memCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public File getFrameFile(String uid) {
        return new File(diskDir, sanitize(uid) + "_frame.jpg");
    }

    public File getPreviewFile(String uid) {
        return new File(diskDir, sanitize(uid) + "_preview.jpg");
    }

    private String sanitize(String uid) {
        return uid.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Saves a captured first-frame bitmap to persistent disk cache + updates memory cache immediately.
     */
    public boolean saveCapturedFrame(String uid, Bitmap bitmap) {
        if (uid == null || bitmap == null) return false;
        File outFile = getFrameFile(uid);
        File tmp = new File(diskDir, sanitize(uid) + "_frame.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, fos);
        } catch (Exception e) {
            return false;
        }
        if (outFile.exists()) outFile.delete();
        if (!tmp.renameTo(outFile)) return false;

        // Store a copy in memory cache
        Bitmap decoded = BitmapFactory.decodeFile(outFile.getAbsolutePath());
        if (decoded != null) {
            memCache.put(uid, decoded);
        }
        return true;
    }

    /**
     * Loads channel image into ImageView with priority:
     * 1. Memory cache
     * 2. Persistent disk captured frame (<uid>_frame.jpg)
     * 3. Persistent disk preview (<uid>_preview.jpg)
     * 4. Network download of Sepehr preview (or icon fallback) -> cached to persistent disk
     */
    public void loadChannelThumbnail(final ChannelItem channel, final ImageView imageView) {
        final String uid = channel.uid;
        imageView.setTag(uid);

        Bitmap cached = memCache.get(uid);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);
        imageView.setBackgroundColor(0xFF163D5B);

        loaderPool.execute(() -> {
            // Try persistent disk cache first (captured frame preferred over preview)
            File frameFile = getFrameFile(uid);
            Bitmap bmp = decodeIfExists(frameFile);
            if (bmp == null) {
                File prevFile = getPreviewFile(uid);
                bmp = decodeIfExists(prevFile);
            }

            if (bmp != null) {
                memCache.put(uid, bmp);
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    if (uid.equals(imageView.getTag())) {
                        imageView.setImageBitmap(finalBmp);
                    }
                });
                return;
            }

            // Otherwise fetch preview/icon from network and cache permanently to disk
            if (!inFlight.add(uid)) return;
            try {
                Bitmap netBmp = downloadAndSavePreview(uid, channel.previewUrl);
                if (netBmp == null && channel.iconUrl != null && !channel.iconUrl.equals(channel.previewUrl)) {
                    netBmp = downloadAndSavePreview(uid, channel.iconUrl);
                }
                if (netBmp != null) {
                    memCache.put(uid, netBmp);
                    final Bitmap finalBmp = netBmp;
                    mainHandler.post(() -> {
                        if (uid.equals(imageView.getTag())) {
                            imageView.setImageBitmap(finalBmp);
                        }
                    });
                }
            } finally {
                inFlight.remove(uid);
            }
        });
    }

    /**
     * Refreshes preview thumbnails from network if no captured frame exists yet,
     * or forces re-download when requested.
     */
    public void invalidateMemory(String uid) {
        memCache.remove(uid);
    }

    private Bitmap decodeIfExists(File file) {
        if (file == null || !file.exists() || file.length() < 64) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap downloadAndSavePreview(String uid, String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 12; Mobile; rv:161.0) Gecko/161.0 Firefox/161.0");
            conn.setRequestProperty("Referer", "https://sepehrtv.ir/");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;

            byte[] bytes;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                bytes = baos.toByteArray();
            }
            if (bytes.length < 64) return null;

            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return null;

            File outFile = getPreviewFile(uid);
            File tmp = new File(diskDir, sanitize(uid) + "_preview.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            }
            if (outFile.exists()) outFile.delete();
            tmp.renameTo(outFile);
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void shutdown() {
        loaderPool.shutdownNow();
    }
}
