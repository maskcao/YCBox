package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.ui.adapter.SectionVodAdapter;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * 首页分区「全部」页：接收分区已加载的影片列表，3 列网格展示。
 */
public class SectionAllActivity extends BaseActivity {

    private static final String MSE_PREFIX = "msearch:";

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_section_all;
    }

    @Override
    protected View customTitleBar() {
        // 这条返回栏是自己画的，不是 hjq TitleBar；不交出去的话状态栏高度没人垫，
        // 标题会顶到状态栏底下
        return findViewById(R.id.llHeader);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void init() {
        String title = getIntent().getStringExtra("title");
        ((TextView) findViewById(R.id.tvTitle)).setText(TextUtils.isEmpty(title) ? "全部" : title);
        findViewById(R.id.ivBack).setOnClickListener(v -> finish());

        ArrayList<Movie.Video> videos = new ArrayList<>();
        Serializable extra = getIntent().getSerializableExtra("videos");
        if (extra instanceof ArrayList) {
            videos = (ArrayList<Movie.Video>) extra;
        }

        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        SectionVodAdapter adapter = new SectionVodAdapter(R.layout.item_section_all_card);
        adapter.setOnItemClickListener((a, view, position) ->
                openVod((Movie.Video) a.getItem(position)));
        rv.setAdapter(adapter);
        adapter.setNewData(videos);

        if (videos.isEmpty()) {
            ToastUtils.showShort("暂无数据");
        }
    }

    private void openVod(Movie.Video vod) {
        if (vod == null) return;
        if (ApiConfig.get().getSourceBeanList().isEmpty()) {
            ToastUtils.showShort("暂无订阅");
            return;
        }
        Bundle bundle = new Bundle();
        String name = vod.name == null ? "" : vod.name.trim();
        if (!TextUtils.isEmpty(vod.id) && !vod.id.startsWith(MSE_PREFIX)) {
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            jumpActivity(DetailActivity.class, bundle);
        } else {
            String keyword = name;
            if (keyword.isEmpty() && vod.id != null && vod.id.startsWith(MSE_PREFIX)) {
                keyword = vod.id.substring(MSE_PREFIX.length()).trim();
            }
            bundle.putString("title", keyword);
            jumpActivity(FastSearchActivity.class, bundle);
        }
    }
}
