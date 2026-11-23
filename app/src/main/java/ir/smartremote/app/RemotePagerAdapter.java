package ir.smartremote.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ir.smartremote.app.ir.IrManager;

/**
 * Two-page ViewPager2 adapter for the floating remote control panel:
 * Page 0: Numeric keypad (1-9, 0, CH+, CH-, List, Back, Home, Settings)
 * Page 1: Directional Pad (Up, Down, Left, Right, OK)
 * Every button uses IrManager's native TransmitOnTouchListener from divested-mobile/irremote.
 */
public class RemotePagerAdapter extends RecyclerView.Adapter<RemotePagerAdapter.PageVH> {

    private final IrManager irManager;

    public RemotePagerAdapter(IrManager irManager) {
        this.irManager = irManager;
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    @NonNull
    @Override
    public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        View view = inf.inflate(
                viewType == 0 ? R.layout.page_remote_numpad : R.layout.page_remote_dpad,
                parent,
                false
        );
        return new PageVH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageVH holder, int position) {
        View root = holder.itemView;
        if (position == 0) {
            bindKey(root, R.id.key0, IrManager.ID_DIGIT_0, false);
            bindKey(root, R.id.key1, IrManager.ID_DIGIT_1, false);
            bindKey(root, R.id.key2, IrManager.ID_DIGIT_2, false);
            bindKey(root, R.id.key3, IrManager.ID_DIGIT_3, false);
            bindKey(root, R.id.key4, IrManager.ID_DIGIT_4, false);
            bindKey(root, R.id.key5, IrManager.ID_DIGIT_5, false);
            bindKey(root, R.id.key6, IrManager.ID_DIGIT_6, false);
            bindKey(root, R.id.key7, IrManager.ID_DIGIT_7, false);
            bindKey(root, R.id.key8, IrManager.ID_DIGIT_8, false);
            bindKey(root, R.id.key9, IrManager.ID_DIGIT_9, false);
            bindKey(root, R.id.keyChUp, IrManager.ID_CH_UP, true);
            bindKey(root, R.id.keyChDown, IrManager.ID_CH_DOWN, true);
            bindKey(root, R.id.keyList, IrManager.ID_LIST, false);
            bindKey(root, R.id.keyBack, IrManager.ID_BACK, false);
            bindKey(root, R.id.keyHome, IrManager.ID_HOME, false);
            bindKey(root, R.id.keySettings, IrManager.ID_SETTING, false);
        } else {
            bindKey(root, R.id.dpadUp, IrManager.ID_NAV_UP, true);
            bindKey(root, R.id.dpadDown, IrManager.ID_NAV_DOWN, true);
            bindKey(root, R.id.dpadLeft, IrManager.ID_NAV_LEFT, true);
            bindKey(root, R.id.dpadRight, IrManager.ID_NAV_RIGHT, true);
            bindKey(root, R.id.dpadOk, IrManager.ID_NAV_OK, false);
        }
    }

    private void bindKey(View root, int viewId, int buttonId, boolean allowRepeat) {
        View v = root.findViewById(viewId);
        if (v != null) {
            v.setOnTouchListener(irManager.createButtonTouchListener(buttonId, allowRepeat));
        }
    }

    static class PageVH extends RecyclerView.ViewHolder {
        PageVH(@NonNull View itemView) {
            super(itemView);
        }
    }
}
