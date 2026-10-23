package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ThumbLoader;

import java.util.ArrayList;

/**
 * 首页分区（站点推荐/热门电视剧/豆瓣热门/高分电影/综艺热播/动漫精选）横滑行的海报适配器。
 * 与 GridAdapter 同款条目，只是布局宽度固定（横向网格用）。
 */
public class SectionVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    public SectionVodAdapter() {
        super(R.layout.item_grid_section, new ArrayList<>());
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

        // 部分源给 "评分: 0" 这类占位（该视频没有评分），挂个徽章很难看，按无评分隐藏
        String note = item.note;
        if (!TextUtils.isEmpty(note)) {
            String num = note.replaceAll("[^0-9.]", "");
            if (num.isEmpty() || num.equals("0") || num.equals("0.0")) {
                note = null;
            }
        }
        if (TextUtils.isEmpty(note)) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, note);
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
