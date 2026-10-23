package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.adapter.SectionVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ThumbLoader;
import com.github.tvbox.osc.util.UA;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 首页「主页」tab：多分区结构（参考样式）。
 *
 * <p>布局自上而下：
 * <ul>
 *   <li>站点推荐 —— 来自订阅配置的推荐视频（源有推荐数据时常驻显示）</li>
 *   <li>热门电视剧 / 豆瓣热门 / 高分电影 / 综艺热播 / 动漫精选 —— 豆瓣接口按 tags 异步加载，结果按天缓存</li>
 * </ul>
 */
public class UserFragment extends BaseLazyFragment {

    RecyclerView tvHotList1;

    /** 一个分区：标题 + 横滑行的适配器 + 是否有数据（决定是否渲染） */
    private static class Section {
        String title;
        SectionVodAdapter adapter;
        boolean visible = false;
    }

    private final List<Section> mSections = new ArrayList<>();
    private SectionOuterAdapter mOuterAdapter;
    private int doubanPending = 0;

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        // 站点推荐数据在 init() 里用
        if (recVod != null && recVod.size() > 0) {
            Section s = newSection("站点推荐");
            s.adapter.setNewData(recVod);
            s.visible = true;
        }
        return this;
    }

    private Section newSection(String title) {
        Section s = new Section();
        s.title = title;
        s.adapter = new SectionVodAdapter();
        s.adapter.setOnItemClickListener((adapter, view, position) ->
                onVodClickStatic((Movie.Video) adapter.getItem(position)));
        s.adapter.setOnItemLongClickListener((adapter, view, position) -> {
            onVodLongClickStatic((Movie.Video) adapter.getItem(position));
            return true;
        });
        mSections.add(s);
        return s;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        sInstance = this;
        tvHotList1 = findViewById(R.id.tvHotList1);
        tvHotList1.setLayoutManager(new LinearLayoutManager(this.mContext));
        mOuterAdapter = new SectionOuterAdapter();
        tvHotList1.setAdapter(mOuterAdapter);
        setLoadSir2(tvHotList1);

        // 站点推荐在 setArguments 里已就位（源有推荐数据时常驻）

        // 豆瓣分区：按 tags 异步加载
        doubanPending = 5;
        fetchDouban(newSection("热门电视剧"), "U", "电视剧");
        fetchDouban(newSection("豆瓣热门"), "U", "");
        fetchDouban(newSection("高分电影"), "R", "电影");
        fetchDouban(newSection("综艺热播"), "U", "综艺");
        fetchDouban(newSection("动漫精选"), "U", "动漫");
    }

    // ---------------- 分区数据加载 ----------------

    /** 豆瓣按 tag 拉取，结果按天缓存（不同 tags/sort 各自缓存） */
    private void fetchDouban(final Section section, String sort, String tags) {
        try {
            Calendar cal = Calendar.getInstance();
            String today = String.format("%d%d%d", cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE));
            String cacheKey = "home_hot_" + Integer.toHexString((sort + "/" + tags).hashCode());
            if (today.equals(Hawk.get(cacheKey + "_day", ""))) {
                String json = Hawk.get(cacheKey, "");
                if (!json.isEmpty()) {
                    fillSection(section, loadHots(json));
                    onDoubanDone();
                    return;
                }
            }
            String url = "https://movie.douban.com/j/new_search_subjects?sort=" + sort
                    + "&range=0,10&tags=" + Uri.encode(tags) + "&playable=1&start=0";
            final String fToday = today;
            final String fCacheKey = cacheKey;
            OkGo.<String>get(url)
                    .headers("User-Agent", UA.randomOne())
                    .headers("Referer", "https://movie.douban.com/")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body().string();
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String netJson = response.body();
                            ArrayList<Movie.Video> videos = loadHots(netJson);
                            // 空结果不缓存：豆瓣偶尔返回验证页/空页，缓存了当天就一直空
                            if (videos.size() > 0) {
                                Hawk.put(fCacheKey + "_day", fToday);
                                Hawk.put(fCacheKey, netJson);
                            }
                            if (mActivity == null) return;
                            mActivity.runOnUiThread(() -> {
                                fillSection(section, videos);
                                onDoubanDone();
                            });
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            android.util.Log.e("HomeSections", "douban FAIL tags=" + tags + " code=" + response.code()
                                    + " msg=" + (response.getException() == null ? "?" : response.getException().getMessage()));
                            onDoubanDone();
                        }
                    });
        } catch (Throwable th) {
            android.util.Log.e("HomeSections", "douban SYNC ERROR tags=" + tags + " " + th);
            th.printStackTrace();
            onDoubanDone();
        }
    }

    private void fillSection(Section section, ArrayList<Movie.Video> videos) {
        int n = videos == null ? 0 : videos.size();
        if (n <= 0) return;
        section.adapter.setNewData(videos);
        section.visible = true;
        // 分区增删会改变 getItemCount，整体刷新最稳妥
        mOuterAdapter.notifyDataSetChanged();
    }

    private synchronized void onDoubanDone() {
        doubanPending--;
        if (doubanPending <= 0) {
            boolean any = false;
            for (Section s : mSections) if (s.visible) { any = true; break; }
            if (any) showSuccess();
            else showEmpty();
        }
    }

    /** 豆瓣 new_search_subjects 的 json -> Video 列表（title/rate/cover） */
    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                // 评分徽章只放数字（如 9.3），与参考样式一致
                vod.note = obj.get("rate").getAsString();
                vod.pic = obj.get("cover").getAsString() + "@Referer=https://movie.douban.com/@User-Agent=" + UA.random();
                result.add(vod);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    // ---------------- 点击行为（与旧版一致：详情 / 搜索） ----------------

    /**
     * 豆瓣数据（站点推荐/豆瓣热门/动漫精选等）的 id 形如 "msearch:片名"，
     * 它只是一个"多源搜索"占位标记，详情页拿它去拉详情必然返回空（暂无数据）。
     * 与分类页 GridFragment 保持一致：这类 id 一律走搜索页，用片名在全站可搜索源里搜，
     * 结果按站点分 tab，用户可以自行换源。
     */
    private static final String MSE_PREFIX = "msearch:";

    private static void onVodClickStatic(Movie.Video vod) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) {
            ToastUtils.showShort("暂无订阅");
            return;
        }
        Bundle bundle = new Bundle();
        if (!TextUtils.isEmpty(vod.id) && !vod.id.startsWith(MSE_PREFIX)) {
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            AppStarter.startDetail(bundle);
        } else {
            bundle.putString("title", searchKeywordOf(vod));
            AppStarter.startSearch(bundle);
        }
    }

    private static void onVodLongClickStatic(Movie.Video vod) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) return;
        Bundle bundle = new Bundle();
        bundle.putString("title", searchKeywordOf(vod));
        AppStarter.startSearch(bundle);
    }

    /**
     * 搜索关键词：优先用片名。
     * 注意 msearch: 后面跟的是豆瓣条目号（如 msearch:36675942），不是片名，
     * 拿它当关键词必然搜不到东西，所以只在片名为空时才退到 id 后缀。
     */
    private static String searchKeywordOf(Movie.Video vod) {
        String name = vod.name == null ? "" : vod.name.trim();
        if (!TextUtils.isEmpty(name)) return name;
        if (!TextUtils.isEmpty(vod.id) && vod.id.startsWith(MSE_PREFIX)) {
            return vod.id.substring(MSE_PREFIX.length()).trim();
        }
        return vod.id == null ? "" : vod.id.trim();
    }

    /** 静态回调里跳页用的中转（Fragment 实例通过 WeakReference 注册） */
    private static UserFragment sInstance;

    private static class AppStarter {
        static void startDetail(Bundle bundle) {
            if (sInstance != null) sInstance.jumpActivity(DetailActivity.class, bundle);
        }

        static void startSearch(Bundle bundle) {
            if (sInstance != null) sInstance.jumpActivity(FastSearchActivity.class, bundle);
        }
    }

    // ---------------- 外层列表适配器（分区标题 + 横滑行） ----------------

    private class SectionOuterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ROW = 1;

        /** 可见分区数 × 2（标题 + 横滑行） */
        @Override
        public int getItemCount() {
            int n = 0;
            for (Section s : mSections) if (s.visible) n++;
            return n * 2;
        }

        private Section sectionAt(int cellIndex) {
            int visible = 0;
            for (Section s : mSections) {
                if (!s.visible) continue;
                if (cellIndex / 2 == visible) return s;
                visible++;
            }
            return mSections.get(0);
        }

        @Override
        public int getItemViewType(int position) {
            return position % 2 == 0 ? TYPE_HEADER : TYPE_ROW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_home_section_header, parent, false));
            }
            RowVH vh = new RowVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_section_row, parent, false));
            // 每个分类一行横滑海报
            V7GridLayoutManager lm = new V7GridLayoutManager(parent.getContext(), 1,
                    GridLayoutManager.HORIZONTAL, false);
            vh.rv.setLayoutManager(lm);
            // 注意：owen 的 TvRecyclerView 会用它自己的 spacing 覆盖 item 的 margin
            // （见 TvRecyclerView.checkLayoutParams，默认 spacing=0 会把海报贴死、margin 失效）。
            // 这里补上与分类网格页一致的 10dp 间距（每边 5dp），让首页横滑与分类页观感统一。
            int gap = ConvertUtils.dp2px(10f);
            vh.rv.setSpacingWithMargins(gap, gap);
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Section section = sectionAt(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).title.setText(section.title);
            } else if (holder instanceof RowVH) {
                RowVH rvh = (RowVH) holder;
                if (rvh.rv.getAdapter() != section.adapter) {
                    rvh.rv.setAdapter(section.adapter);
                }
            }
        }
    }

    private static class HeaderVH extends RecyclerView.ViewHolder {
        TextView title;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tvSectionTitle);
        }
    }

    private static class RowVH extends RecyclerView.ViewHolder {
        TvRecyclerView rv;

        RowVH(@NonNull View itemView) {
            super(itemView);
            rv = (TvRecyclerView) itemView;
        }
    }
}
