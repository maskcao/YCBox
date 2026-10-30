package com.github.tvbox.osc.ui.adapter;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.ThumbLoader;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class HistoryAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {
    public HistoryAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo item) {
        TextView tvYear = helper.getView(R.id.tvYear);
        /*if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }*/
        tvYear.setText(ApiConfig.get().getSource(item.sourceKey).getName());

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            Drawable drawable = ContextCompat.getDrawable(mContext, R.drawable.ic_history_18);
            tvNote.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        }
        helper.setText(R.id.tvName, item.name);
        // helper.setText(R.id.tvActor, item.actor);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //统一走 ThumbLoader：data: base64 封面 Picasso 加载不了
        ThumbLoader.load(ivThumb, item.pic);

        // 与 GridAdapter 相同：ShadowLayout 自己 clickable=true，会吃掉封面区的触摸，
        // 只能点到底部标题；这里把封面点击转发给整张卡片，否则点封面无法进入详情。
        View poster = helper.getView(R.id.shadowPoster);
        poster.setOnClickListener(v -> helper.itemView.performClick());
        poster.setClickable(true);
    }
}