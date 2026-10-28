package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ThumbLoader;

import java.util.ArrayList;

/**
 * 首页分区横滑行适配器：NewBox 同款 108×150 卡片 + 右上角评分徽章 + 卡下片名。
 */
public class SectionVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    public SectionVodAdapter() {
        this(R.layout.item_home_video_card);
    }

    /** 「全部」网格页复用同一套字段，只换卡片布局 */
    public SectionVodAdapter(int layoutRes) {
        super(layoutRes, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        helper.setText(R.id.tvName, item.name == null ? "" : item.name);

        // 部分源给 "评分: 0" 这类占位（该视频没有评分），挂个徽章很难看，按无评分隐藏
        String note = item.note;
        if (!TextUtils.isEmpty(note)) {
            String num = note.replaceAll("[^0-9.]", "");
            if (num.isEmpty() || num.equals("0") || num.equals("0.0")) {
                note = null;
            }
        }
        if (TextUtils.isEmpty(note)) {
            helper.getView(R.id.tvNote).setVisibility(View.GONE);
        } else {
            helper.getView(R.id.tvNote).setVisibility(View.VISIBLE);
            helper.setText(R.id.tvNote, note);
        }

        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 统一走 ThumbLoader：jar 爬虫给的封面可能是 data: base64，Picasso 不认
        ThumbLoader.load(ivThumb, item.pic);
    }
}
