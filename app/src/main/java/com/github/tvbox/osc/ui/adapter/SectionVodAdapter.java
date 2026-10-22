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
 * 首页分区（站点推荐/热门电视剧/豆瓣热门/高分电影/综艺热播）横滑行的海报适配器。
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
    }
}
