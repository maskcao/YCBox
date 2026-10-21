package com.github.tvbox.osc.ui.adapter;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelItemNewAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    private int selectedChannelIndex = -1;
    private int focusedChannelIndex = -1;

    public LiveChannelItemNewAdapter() {
        super(R.layout.item_live_channel_new, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        View root = holder.getView(R.id.root);
        // 取色必须用 itemView 自己的 context。
        // 用 adapter 的 mContext 时，一旦它和布局 inflate 时的 context 落在不同的
        // Resources（例如主题切换后的 createConfigurationContext 场景），就会出现
        // 背景按浅色解析（白）、文字按深色解析（白）—— 白字白底，真机上整列看不见。
        Context ctx = holder.itemView.getContext();

        TextView tvChannelNum = holder.getView(R.id.tvChannelNum);
        TextView tvChannel = holder.getView(R.id.tvChannelName);
        tvChannelNum.setText(String.format("%s", item.getChannelNum()));
        tvChannel.setText(item.getChannelName());
        int channelIndex = item.getChannelIndex();
        if (channelIndex == selectedChannelIndex && channelIndex != focusedChannelIndex) {
            int onAccent = ContextCompat.getColor(ctx, R.color.on_accent_text);
            tvChannelNum.setTextColor(onAccent);
            tvChannel.setTextColor(onAccent);
            root.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_live_selected));
        } else{
            int normal = ContextCompat.getColor(ctx, R.color.text_foreground);
            tvChannelNum.setTextColor(normal);
            tvChannel.setTextColor(normal);
            root.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_transparent));
        }
    }

    public void setSelectedChannelIndex(int selectedChannelIndex) {
        if (selectedChannelIndex == this.selectedChannelIndex) return;
        int preSelectedChannelIndex = this.selectedChannelIndex;
        this.selectedChannelIndex = selectedChannelIndex;
        if (preSelectedChannelIndex != -1)
            notifyItemChanged(preSelectedChannelIndex);
        if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }

    public void setFocusedChannelIndex(int focusedChannelIndex) {
        int preFocusedChannelIndex = this.focusedChannelIndex;
        this.focusedChannelIndex = focusedChannelIndex;
        if (preFocusedChannelIndex != -1)
            notifyItemChanged(preFocusedChannelIndex);
        if (this.focusedChannelIndex != -1)
            notifyItemChanged(this.focusedChannelIndex);
        else if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }
}