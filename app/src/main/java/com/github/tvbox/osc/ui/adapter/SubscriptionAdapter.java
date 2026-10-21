package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.LogUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VideoFolder;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.util.BuiltinConfig;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SubscriptionAdapter extends BaseQuickAdapter<Subscription, BaseViewHolder> {
    public SubscriptionAdapter() {
        super(R.layout.item_subscription);
    }

    /** 只有「直播订阅」页才显示"来自：xxx"，配置订阅页没有这个概念 */
    private boolean showFrom = false;

    public void setShowFrom(boolean showFrom) {
        this.showFrom = showFrom;
    }

    @Override
    protected void convert(BaseViewHolder helper, Subscription item) {
        // 内置源的地址是 builtin:// 协议，配置打包在 APK 里，断网也能加载
        boolean isBuiltin = BuiltinConfig.isBuiltin(item.getUrl());
        boolean checked = item.isChecked();

        helper.setText(R.id.tv_name,item.getName())
        .setText(R.id.tv_url,item.getUrl())
        .setChecked(R.id.cb,checked)
        .setVisible(R.id.iv_pushpin,item.isTop())
        .setVisible(R.id.tv_builtin,isBuiltin);

        String from = item.getFrom();
        boolean hasFrom = showFrom && from != null && !from.trim().isEmpty();
        helper.setVisible(R.id.tv_from, hasFrom);
        if (hasFrom) {
            helper.setText(R.id.tv_from, "来自：" + from);
        }

        // 内置源不给删除入口：地址走内置协议，删掉后手填也填不回来，只能重装
        // 用 INVISIBLE 而不是 GONE —— 它还占着右侧的位置，iv_pushpin 锚在它左边
        helper.getView(R.id.iv_del).setVisibility(isBuiltin ? View.INVISIBLE : View.VISIBLE);
        helper.addOnClickListener(R.id.iv_del);

        // 选中态（参考样式）：卡片加 accent 描边、名称变为 accent 色，未选中为正文色
        helper.itemView.setBackgroundResource(checked ? R.drawable.bg_card_selected : R.drawable.bg_card_rounded);
        helper.setTextColor(R.id.tv_name, ColorUtils.getColor(checked ? R.color.dock_accent : R.color.text_foreground));
    }

    /**
     * 刷新列表时候,添加去重和排序
     * @param data
     */
    @Override
    public void setNewData(@Nullable List<Subscription> data) {
        if (data!=null){
            //去除url重复的订阅
            for (int i = 0; i < data.size(); i++) {
                for (int j = i+1; j < data.size(); j++) {
                    if (data.get(i).getUrl().equals(data.get(j).getUrl())){
                        data.remove(j);
                        j--;
                    }
                }
            }
            data.sort(mComparator);
        }
        super.setNewData(data);
    }

    Comparator<Subscription> mComparator = (s1, s2) -> {
        if (s1.isTop() && !s2.isTop()) {
            return -1;
        } else if (!s1.isTop() && s2.isTop()) {
            return 1;
        } else if (s1.isChecked() && !s2.isChecked()) {
            return -1;
        } else if (!s1.isChecked() && s2.isChecked()) {
            return 1;
        } else {
            return 0;
        }
    };
}