package ir.smartremote.app;

import java.util.HashMap;
import java.util.Map;

public class ChannelItem {
    // Key = Sepehr UID, Value = Telewebion channel ID (stored internally by Sepehr UID)
    private static final Map<String, String> TELEWEBION_MAP = new HashMap<>();
    static {
        TELEWEBION_MAP.put("pouya", "pooya");
        TELEWEBION_MAP.put("uhd", "faratar");
        TELEWEBION_MAP.put("tamasha", "hdtest");
        TELEWEBION_MAP.put("tv5", "tehran");
        TELEWEBION_MAP.put("ifilmfa", "ifilm");
    }

    public final String uid;          // Sepehr UID (canonical storage key)
    public final String name;
    public final String defaultNumber;
    public String program;
    public String code;               // User-editable IR channel code
    public String iconUrl;
    public String previewUrl;
    public String sepehrStreamUrl;

    public ChannelItem(String uid, String name, String defaultNumber, String program,
                       String code, String iconUrl, String previewUrl, String sepehrStreamUrl) {
        this.uid = uid;
        this.name = name;
        this.defaultNumber = defaultNumber;
        this.program = program;
        this.code = code;
        this.iconUrl = iconUrl;
        this.previewUrl = previewUrl;
        this.sepehrStreamUrl = sepehrStreamUrl;
    }

    public String getTelewebionId() {
        String mapped = TELEWEBION_MAP.get(uid);
        return mapped != null ? mapped : uid;
    }

    public String getTelewebionStreamUrl() {
        return "https://ncdn.telewebion.ir/" + getTelewebionId() + "/live/playlist.m3u8";
    }
}
