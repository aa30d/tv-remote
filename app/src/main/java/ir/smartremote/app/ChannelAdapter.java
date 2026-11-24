package ir.smartremote.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelVH> {

    public interface Listener {
        void onDialChannel(ChannelItem channel);
        void onPlayStream(ChannelItem channel, String source);
    }

    private final List<ChannelItem> channels = new ArrayList<>();
    private final ImageCacheManager imageCache;
    private final Listener listener;
    private final boolean tvFocusEnabled;

    // UIDs whose frame image is still being captured: shown dimmed until updated.
    private final Set<String> pendingUids = new HashSet<>();

    public ChannelAdapter(ImageCacheManager imageCache, Listener listener, boolean tvFocusEnabled) {
        this.imageCache = imageCache;
        this.listener = listener;
        this.tvFocusEnabled = tvFocusEnabled;
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

    /** Marks the given channels as still pending (dimmed) during frame capture. */
    public void setPendingUids(Collection<String> uids) {
        pendingUids.clear();
        if (uids != null) pendingUids.addAll(uids);
        notifyDataSetChanged();
    }

    /** Marks one channel as updated: it becomes bright again. */
    public void markUpdated(String uid) {
        pendingUids.remove(uid);
        notifyChannelUpdated(uid);
    }

    /** Clears all pending marks (capture finished): everything is bright again. */
    public void clearPending() {
        if (pendingUids.isEmpty()) return;
        pendingUids.clear();
        notifyDataSetChanged();
    }

    public void notifyChannelUpdated(String uid) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).uid.equals(uid)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public long getItemId(int position) {
        return channels.get(position).uid.hashCode();
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    @NonNull
    @Override
    public ChannelVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        View item = inf.inflate(R.layout.item_channel_card, parent, false);
        if (!tvFocusEnabled) MainActivity.clearFocusRings(item);
        return new ChannelVH(item);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelVH holder, int position) {
        final ChannelItem ch = channels.get(position);
        holder.txtName.setText(ch.name);
        holder.txtProgram.setText(ch.program);

        if (ch.code != null && !ch.code.trim().isEmpty()) {
            holder.txtCodeBadge.setText(ch.code);
            holder.txtCodeBadge.setVisibility(View.VISIBLE);
        } else {
            holder.txtCodeBadge.setVisibility(View.GONE);
        }

        // Not-yet-updated images are dimmed; updated ones are full brightness.
        holder.imgThumb.setAlpha(pendingUids.contains(ch.uid) ? 0.35f : 1f);

        // Thumbnail box is 16:9: height derived from the card width.
        holder.thumbContainer.post(() -> {
            int w = holder.thumbContainer.getWidth();
            if (w > 0) {
                ViewGroup.LayoutParams lp = holder.thumbContainer.getLayoutParams();
                int h = Math.round(w * 9f / 16f);
                if (lp.height != h) {
                    lp.height = h;
                    holder.thumbContainer.setLayoutParams(lp);
                }
            }
        });

        imageCache.loadChannelThumbnail(ch, holder.imgThumb);

        holder.dialArea.setOnClickListener(v -> listener.onDialChannel(ch));
        holder.btnTelewebion.setOnClickListener(v -> listener.onPlayStream(ch, "telewebion"));
        holder.btnSepehr.setOnClickListener(v -> listener.onPlayStream(ch, "sepehr"));
    }

    static class ChannelVH extends RecyclerView.ViewHolder {
        final View dialArea;
        final View thumbContainer;
        final ImageView imgThumb;
        final TextView txtCodeBadge;
        final TextView txtName;
        final TextView txtProgram;
        final View btnTelewebion;
        final View btnSepehr;

        ChannelVH(@NonNull View v) {
            super(v);
            dialArea = v.findViewById(R.id.dialArea);
            thumbContainer = v.findViewById(R.id.thumbContainer);
            imgThumb = v.findViewById(R.id.imgChannelThumb);
            txtCodeBadge = v.findViewById(R.id.txtCodeBadge);
            txtName = v.findViewById(R.id.txtChannelName);
            txtProgram = v.findViewById(R.id.txtProgramTitle);
            btnTelewebion = v.findViewById(R.id.btnPlayTelewebion);
            btnSepehr = v.findViewById(R.id.btnPlaySepehr);
        }
    }
}
