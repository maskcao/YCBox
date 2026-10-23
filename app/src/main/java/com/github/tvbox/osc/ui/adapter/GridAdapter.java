package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
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
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    public GridAdapter() {
        super(R.layout.item_grid, new ArrayList<>());

    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        TextView tvYear = helper.getView(R.id.tvYear);
        if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }

        if (TextUtils.isEmpty(item.note)) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //统一走 ThumbLoader：jar 爬虫给的封面可能是 data: base64，Picasso 不认
        ThumbLoader.load(ivThumb, item.pic);

        // ShadowLayout 会消费封面区的触摸（自身 clickable=true），事件不再冒泡到 itemView，
        // 导致挂在 itemView 上的 setOnItemClickListener 收不到点击（只有底部标题能点）。
        // 对策：让封面区的点击转发给 itemView。
        // 注意顺序：ShadowLayout 重写了 setOnClickListener——只有 isClickable=true 时
        // 才会把 listener 透传给父类，否则只存在自己字段里（永远不回调），故显式 setClickable(true)。
        View poster = helper.getView(R.id.shadowPoster);
        poster.setOnClickListener(v -> helper.itemView.performClick());
        poster.setClickable(true);
    }
}