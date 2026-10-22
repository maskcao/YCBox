package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.net.Uri;
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
 * <p>每个分区 = 标题 + 两行横滑海报：
 * <ul>
 *   <li>站点推荐 —— 来自订阅配置的推荐视频（HOME_REC=1 且有数据时显示）</li>
 *   <li>热门电视剧 / 豆瓣热门 / 高分电影 / 综艺热播 —— 豆瓣接口按 tags 异步加载，结果按天缓存</li>
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

        // 站点推荐在 setArguments 里已就位（HOME_REC=1 且有数据）

        // 豆瓣分区：按 tags 异步加载
        doubanPending = 4;
        fetchDouban(newSection("热门电视剧"), "U", "电视剧");
        fetchDouban(newSection("豆瓣热门"), "U", "");
        fetchDouban(newSection("高分电影"), "R", "电影");
        fetchDouban(newSection("综艺热播"), "U", "综艺");
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
        if (n > 0) {
            section.adapter.setNewData(videos);
            if (!section.visible) {
                section.visible = true;
                mOuterAdapter.notifyDataSetChanged();
            } else {
                section.adapter.notifyDataSetChanged();
            }
        }
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

    private static void onVodClickStatic(Movie.Video vod) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) {
            ToastUtils.showShort("暂无订阅");
            return;
        }
        Bundle bundle = new Bundle();
        if (!TextUtils.isEmpty(vod.id)) {
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            AppStarter.startDetail(bundle);
        } else {
            bundle.putString("title", vod.name);
            AppStarter.startSearch(bundle);
        }
    }

    private static void onVodLongClickStatic(Movie.Video vod) {
        if (ApiConfig.get().getSourceBeanList().isEmpty()) return;
        Bundle bundle = new Bundle();
        bundle.putString("title", vod.name);
        AppStarter.startSearch(bundle);
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

    // ---------------- 外层分区列表适配器 ----------------

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
