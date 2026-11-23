package ir.smartremote.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_CHANNEL = 1;

    public interface Listener {
        void onRefreshClicked();
        void onCaptureFramesClicked();
        void onEditCodesClicked();
        void onSelectTvModelClicked();
        void onDefaultQualityClicked();
        void onDialChannel(ChannelItem channel);
        void onPlayStream(ChannelItem channel, String source);
    }

    private final List<ChannelItem> channels = new ArrayList<>();
    private final ImageCacheManager imageCache;
    private final Listener listener;

    private String tvModelLabel = "Samsung";
    private String defaultQualityLabel = "تطبیقی (بر اساس سرعت اینترنت)";
    private boolean isRefreshing = false;
    private String frameProgressText = null;

    public ChannelAdapter(ImageCacheManager imageCache, Listener listener) {
        this.imageCache = imageCache;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setChannels(List<ChannelItem> list) {
        channels.clear();
        if (list != null) channels.addAll(list);
        notifyDataSetChanged();
    }

    public List<ChannelItem> getChannels() {
        return channels;
    }

    public void setHeaderState(String tvModel, String defaultQuality, boolean refreshing, String frameProgress) {
        this.tvModelLabel = tvModel;
        this.defaultQualityLabel = defaultQuality;
        this.isRefreshing = refreshing;
        this.frameProgressText = frameProgress;
        notifyItemChanged(0);
    }

    public void notifyChannelUpdated(String uid) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).uid.equals(uid)) {
                notifyItemChanged(i + 1);
                return;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_CHANNEL;
    }

    @Override
    public long getItemId(int position) {
        if (position == 0) return -1L;
        return channels.get(position - 1).uid.hashCode();
    }

    @Override
    public int getItemCount() {
        return 1 + channels.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_top_actions, parent, false));
        }
        return new ChannelVH(inf.inflate(R.layout.item_channel_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.btnRefresh.setText(isRefreshing ? "در حال بروزرسانی..." : "به روز رسانی");
            h.btnRefresh.setEnabled(!isRefreshing);
            h.btnRefresh.setAlpha(isRefreshing ? 0.7f : 1f);

            if (frameProgressText != null) {
                h.btnCaptureFrames.setText(frameProgressText);
            } else {
                h.btnCaptureFrames.setText("دریافت عکس اولین فریم");
            }

            h.btnSelectTvModel.setText("مدل تلویزیون: " + tvModelLabel);
            h.btnDefaultQuality.setText("کیفیت پیش‌فرض پخش: " + defaultQualityLabel);

            h.btnRefresh.setOnClickListener(v -> listener.onRefreshClicked());
            h.btnCaptureFrames.setOnClickListener(v -> listener.onCaptureFramesClicked());
            h.btnEditCodes.setOnClickListener(v -> listener.onEditCodesClicked());
            h.btnSelectTvModel.setOnClickListener(v -> listener.onSelectTvModelClicked());
            h.btnDefaultQuality.setOnClickListener(v -> listener.onDefaultQualityClicked());
            return;
        }

        ChannelVH c = (ChannelVH) holder;
        final ChannelItem ch = channels.get(position - 1);
        c.txtName.setText(ch.name);
        c.txtProgram.setText(ch.program);

        if (ch.code != null && !ch.code.trim().isEmpty()) {
            c.txtCodeBadge.setText(ch.code);
            c.txtCodeBadge.setVisibility(View.VISIBLE);
        } else {
            c.txtCodeBadge.setVisibility(View.GONE);
        }

        imageCache.loadChannelThumbnail(ch, c.imgThumb);

        c.dialArea.setOnClickListener(v -> listener.onDialChannel(ch));
        c.btnTelewebion.setOnClickListener(v -> listener.onPlayStream(ch, "telewebion"));
        c.btnSepehr.setOnClickListener(v -> listener.onPlayStream(ch, "sepehr"));
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView btnRefresh;
        final TextView btnCaptureFrames;
        final TextView btnEditCodes;
        final TextView btnSelectTvModel;
        final TextView btnDefaultQuality;

        HeaderVH(@NonNull View v) {
            super(v);
            btnRefresh = v.findViewById(R.id.btnRefresh);
            btnCaptureFrames = v.findViewById(R.id.btnCaptureFrames);
            btnEditCodes = v.findViewById(R.id.btnEditCodes);
            btnSelectTvModel = v.findViewById(R.id.btnSelectTvModel);
            btnDefaultQuality = v.findViewById(R.id.btnDefaultQuality);
        }
    }

    static class ChannelVH extends RecyclerView.ViewHolder {
        final View dialArea;
        final ImageView imgThumb;
        final TextView txtCodeBadge;
        final TextView txtName;
        final TextView txtProgram;
        final View btnTelewebion;
        final View btnSepehr;

        ChannelVH(@NonNull View v) {
            super(v);
            dialArea = v.findViewById(R.id.dialArea);
            imgThumb = v.findViewById(R.id.imgChannelThumb);
            txtCodeBadge = v.findViewById(R.id.txtCodeBadge);
            txtName = v.findViewById(R.id.txtChannelName);
            txtProgram = v.findViewById(R.id.txtProgramTitle);
            btnTelewebion = v.findViewById(R.id.btnPlayTelewebion);
            btnSepehr = v.findViewById(R.id.btnPlaySepehr);
        }
    }
}
