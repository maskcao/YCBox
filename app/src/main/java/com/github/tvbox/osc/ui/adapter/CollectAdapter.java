package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.ThumbLoader;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class CollectAdapter extends BaseQuickAdapter<VodCollect, BaseViewHolder> {
    public CollectAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodCollect item) {
        helper.setVisible(R.id.tvNote, false);
        TextView tvYear = helper.getView(R.id.tvYear);
        if (ApiConfig.get().getSource(item.sourceKey)!=null) {
            tvYear.setText(ApiConfig.get().getSource(item.sourceKey).getName());
            tvYear.setVisibility(View.VISIBLE);
        } else {
            tvYear.setVisibility(View.GONE);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //统一走 ThumbLoader：data: base64 封面 Picasso 加载不了
        ThumbLoader.load(ivThumb, item.pic);

        // 与 GridAdapter 相同：ShadowLayout 会消费封面区的触摸，把封面点击转发给整张卡片
        View poster = helper.getView(R.id.shadowPoster);
        poster.setOnClickListener(v -> helper.itemView.performClick());
        poster.setClickable(true);
    }
}