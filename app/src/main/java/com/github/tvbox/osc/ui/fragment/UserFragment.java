package com.github.tvbox.osc.ui.fragment;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SectionAllActivity;
import com.github.tvbox.osc.ui.adapter.SectionVodAdapter;
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
 * 首页「主页」tab，参考 NewBox 的实现：
 *
 * <ul>
 *   <li>hero：订阅配置里的站点推荐做通栏大图轮播（自动 5s 切换）</li>
 *   <li>分区：豆瓣接口按 tags 拉取，单行 108×150 卡片 + 右侧「全部」</li>
 *   <li>下拉刷新：忽略当天缓存重新拉取，并覆盖缓存</li>
 * </ul>
 */
public class UserFragment extends BaseLazyFragment {

    RecyclerView tvHotList1;
    SwipeRefreshLayout swipeRefresh;

    /** 一个分区：标题 + 横滑行的适配器 + 是否有数据（决定是否渲染） */
    private static class Section {
        String title;
        SectionVodAdapter adapter;
        boolean visible = false;
    }

    /** 标题 / 排序 / 豆瓣 tag，顺序即页面展示顺序 */
    private static final String[][] DOUBAN_SECTIONS = {
            {"热门电视剧", "U", "电视剧"},
            {"豆瓣热门", "U", ""},
            {"高分电影", "R", "电影"},
            {"综艺热播", "U", "综艺"},
            {"动漫精选", "U", "动漫"}
    };

    /** hero 最多展示的推荐条数：再多轮播既费流量也没人看完 */
    private static final int HERO_LIMIT = 10;

    /** hero 高度占列表视口的比例；1.0 即 NewBox 的整屏大图 */
    private static final float HERO_VIEWPORT_RATIO = 0.62f;

    /**
     * hero 轮播的循环倍数。ViewPager2 自己没有「首尾相接」，把条目数放大成 N 轮、
     * 起始落在中间那一轮，两头就都还剩几十轮可滑，滑到本轮最后一张再往前自然
     * 就是下一轮的第一张。100 轮足够没人真滑到头，也不会让位置溢出 int。
     */
    private static final int HERO_LOOP_TIMES = 100;

    private final List<Section> mSections = new ArrayList<>();
    private final ArrayList<Movie.Video> mHeroVideos = new ArrayList<>();
    private final Handler mRetryHandler = new Handler(Looper.getMainLooper());
    private SectionOuterAdapter mOuterAdapter;
    private int doubanPending = 0;
    /** 每次重建分区自增：刷新后丢弃上一轮还在路上的回调，避免提前收尾/串数据 */
    private int loadGen = 0;

    /** 单次失败后的自动重试次数：域名解析失败/网络抖动很常见，不重试首页会一直空着 */
    private static final int RETRY_TIMES = 2;
    private static final long RETRY_BASE_DELAY = 3000L;

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        mHeroVideos.clear();
        if (recVod != null) {
            for (Movie.Video v : recVod) {
                if (v == null) continue;
                mHeroVideos.add(v);
                if (mHeroVideos.size() >= HERO_LIMIT) break;
            }
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
        swipeRefresh = findViewById(R.id.swipeRefresh);
        tvHotList1.setLayoutManager(new LinearLayoutManager(mContext));
        mOuterAdapter = new SectionOuterAdapter();
        tvHotList1.setAdapter(mOuterAdapter);
        setLoadSir2(tvHotList1);
        // 有 hero 时列表顶到状态栏（大图压在玻璃顶栏底下）；没有 hero 就得自己留出
        // 顶栏 + 分类栏的高度，否则第一个分区直接埋在浮层下面（和分类页的 122dp 对齐）。
        if (mHeroVideos.isEmpty()) {
            tvHotList1.setPadding(0, ConvertUtils.dp2px(122f), 0,
                    ConvertUtils.dp2px(96f));
        }

        swipeRefresh.setColorSchemeResources(R.color.dock_accent);
        swipeRefresh.setOnRefreshListener(() -> loadSections(true));

        loadSections(false);
    }

    // ---------------- 分区数据加载 ----------------

    /** 重建全部分区；force=true 时跳过当天缓存（下拉刷新） */
    private void loadSections(boolean force) {
        final int gen = ++loadGen;
        if (mSections.isEmpty()) {
            for (String[] def : DOUBAN_SECTIONS) newSection(def[0]);
        }
        // 不清空已有分区：刷新失败时保留当前内容，否则首页会从有内容直接变成整片空白
        mOuterAdapter.notifyDataSetChanged();
        doubanPending = DOUBAN_SECTIONS.length;
        for (int i = 0; i < DOUBAN_SECTIONS.length; i++) {
            String[] def = DOUBAN_SECTIONS[i];
            fetchDouban(mSections.get(i), def[1], def[2], force, gen);
        }
    }

    private static String cacheKey(String sort, String tags) {
        return "home_hot_" + Integer.toHexString((sort + "/" + tags).hashCode());
    }

    private static String today() {
        Calendar cal = Calendar.getInstance();
        return String.format("%d%d%d", cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE));
    }

    /** 豆瓣按 tag 拉取，结果按天缓存（不同 tags/sort 各自缓存） */
    private void fetchDouban(final Section section, String sort, String tags, boolean force, final int gen) {
        fetchDouban(section, sort, tags, force, gen, 0);
    }

    /**
     * 豆瓣按 tag 拉取，失败后自动重试。
     *
     * <p>缓存分两层用：当天缓存直接收尾不再请求；更早的缓存先渲染出来再走网络刷新，
     * 这样断网/域名解析失败时首页不会只剩 hero、下面整片空白。
     */
    private void fetchDouban(final Section section, final String sort, final String tags,
                             final boolean force, final int gen, final int attempt) {
        try {
            final String fToday = today();
            final String fCacheKey = cacheKey(sort, tags);
            String cachedJson = Hawk.get(fCacheKey, "");
            if (!force && !cachedJson.isEmpty()) {
                if (section.adapter.getData().isEmpty()) {
                    fillSection(section, loadHots(cachedJson));
                }
                if (fToday.equals(Hawk.get(fCacheKey + "_day", ""))) {
                    onDoubanDone(gen);
                    return;
                }
            }
            String url = "https://movie.douban.com/j/new_search_subjects?sort=" + sort
                    + "&range=0,10&tags=" + Uri.encode(tags) + "&playable=1&start=0";
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
                            if (gen != loadGen) return;
                            String netJson = response.body();
                            ArrayList<Movie.Video> videos = loadHots(netJson);
                            // 空结果不缓存：豆瓣偶尔返回验证页/空页，缓存了当天就一直空
                            if (videos.size() > 0) {
                                Hawk.put(fCacheKey + "_day", fToday);
                                Hawk.put(fCacheKey, netJson);
                            }
                            if (mActivity == null) {
                                onDoubanDone(gen);
                                return;
                            }
                            mActivity.runOnUiThread(() -> {
                                if (gen != loadGen) return;
                                fillSection(section, videos);
                                onDoubanDone(gen);
                            });
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            android.util.Log.e("HomeSections", "douban FAIL tags=" + tags + " attempt=" + attempt
                                    + " code=" + response.code()
                                    + " msg=" + (response.getException() == null ? "?" : response.getException().getMessage()));
                            retryOrFinish(section, sort, tags, force, gen, attempt);
                        }
                    });
        } catch (Throwable th) {
            android.util.Log.e("HomeSections", "douban SYNC ERROR tags=" + tags + " " + th);
            th.printStackTrace();
            retryOrFinish(section, sort, tags, force, gen, attempt);
        }
    }

    private void retryOrFinish(final Section section, final String sort, final String tags,
                               final boolean force, final int gen, final int attempt) {
        if (gen != loadGen) return;
        if (attempt >= RETRY_TIMES) {
            onDoubanDone(gen);
            return;
        }
        mRetryHandler.postDelayed(() -> {
            if (gen != loadGen) return;
            fetchDouban(section, sort, tags, force, gen, attempt + 1);
        }, RETRY_BASE_DELAY * (attempt + 1));
    }

    private void fillSection(Section section, ArrayList<Movie.Video> videos) {
        int n = videos == null ? 0 : videos.size();
        if (n <= 0) return;
        section.adapter.setNewData(videos);
        section.visible = true;
        // 分区增删会改变 getItemCount，整体刷新最稳妥
        mOuterAdapter.notifyDataSetChanged();
    }

    private synchronized void onDoubanDone(int gen) {
        if (gen != loadGen) return;
        if (doubanPending > 0) doubanPending--;
        if (doubanPending > 0) return;
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        boolean any = !mHeroVideos.isEmpty();
        if (!any) {
            for (Section s : mSections) {
                if (s.visible) {
                    any = true;
                    break;
                }
            }
        }
        if (any) showSuccess();
        else showEmpty();
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
        if (vod == null) return;
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
        if (vod == null) return;
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

    /** 静态回调里跳页用的中转（Fragment 实例注册，销毁时置空） */
    private static UserFragment sInstance;

    private static class AppStarter {
        static void startDetail(Bundle bundle) {
            if (sInstance != null) sInstance.jumpActivity(DetailActivity.class, bundle);
        }

        static void startSearch(Bundle bundle) {
            if (sInstance != null) sInstance.jumpActivity(FastSearchActivity.class, bundle);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sInstance == this) sInstance = null;
        mRetryHandler.removeCallbacksAndMessages(null);
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
    }

    /** 「全部」：把当前分区已加载的数据带进网格页（数据量不大，直接 Serializable 传） */
    private void openSectionAll(Section section) {
        if (section == null || section.adapter.getData().isEmpty()) return;
        Bundle bundle = new Bundle();
        bundle.putString("title", section.title);
        bundle.putSerializable("videos", new ArrayList<>(section.adapter.getData()));
        jumpActivity(SectionAllActivity.class, bundle);
    }

    // ---------------- hero 轮播 ----------------

    private Movie.Video currentHero() {
        if (mHeroVideos.isEmpty()) return null;
        ViewPager2 pager = null;
        View hero = tvHotList1.findViewHolderForAdapterPosition(0) != null
                ? tvHotList1.findViewHolderForAdapterPosition(0).itemView : null;
        if (hero != null) pager = hero.findViewById(R.id.heroPager);
        int index = pager == null ? 0 : heroRealPage(pager.getCurrentItem());
        return mHeroVideos.get(index);
    }

    /** 循环适配器里的位置 -> 真实第几张推荐 */
    private int heroRealPage(int loopPosition) {
        int count = mHeroVideos.size();
        return count == 0 ? 0 : Math.floorMod(loopPosition, count);
    }

    private class HeroPagerAdapter extends RecyclerView.Adapter<HeroPageVH> {

        @NonNull
        @Override
        public HeroPageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new HeroPageVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_hero_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull HeroPageVH holder, int position) {
            Movie.Video vod = mHeroVideos.get(heroRealPage(position));
            ThumbLoader.loadLarge(holder.poster, vod.pic);
            holder.itemView.setOnClickListener(v -> onVodClickStatic(vod));
            holder.itemView.setOnLongClickListener(v -> {
                onVodLongClickStatic(vod);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            int count = mHeroVideos.size();
            return count == 0 ? 0 : count * HERO_LOOP_TIMES;
        }
    }

    private static class HeroPageVH extends RecyclerView.ViewHolder {
        ImageView poster;

        HeroPageVH(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.ivHeroPoster);
        }
    }

    private class HeroVH extends RecyclerView.ViewHolder {
        final ViewPager2 pager;
        final TextView title;
        final TextView note;
        final LinearLayout dots;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable autoScroll = new Runnable() {
            @Override
            public void run() {
                if (mHeroVideos.size() > 1) {
                    // 只往前走：循环适配器里「最后一张的下一张」就是下一轮的第一张，
                    // 平滑滚动始终是正向。以前写成 (cur + 1) % count，滑到最后一张
                    // 时动画会倒着把中间几张全部退回去
                    pager.setCurrentItem(pager.getCurrentItem() + 1, true);
                    handler.postDelayed(this, 5000);
                }
            }
        };

        HeroVH(@NonNull View itemView) {
            super(itemView);
            pager = itemView.findViewById(R.id.heroPager);
            title = itemView.findViewById(R.id.heroTitle);
            note = itemView.findViewById(R.id.heroNote);
            dots = itemView.findViewById(R.id.heroDots);
            // NewBox 的 hero 占满整屏，首屏就只剩一个可点项；这里收到视口的 62%，
            // 让下一个分区的标题和第一行海报顶部从底边露进来——既补回信息量，
            // 露出的半截图本身就是「还能往下滑」的暗示
            int viewport = tvHotList1.getHeight();
            if (viewport > 0) {
                itemView.getLayoutParams().height = Math.round(viewport * HERO_VIEWPORT_RATIO);
            }
            pager.setOffscreenPageLimit(1);
            pager.setAdapter(new HeroPagerAdapter());
            alignToLoop();
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    updateCaption(position);
                    startAuto();
                }
            });
            itemView.setOnClickListener(v -> {
                Movie.Video vod = currentHero();
                if (vod != null) onVodClickStatic(vod);
            });
        }

        /** 落到中间那一轮，两边各留一半可滑，滑到本轮最后一张才能继续往前接上第一张 */
        void alignToLoop() {
            int count = mHeroVideos.size();
            if (count == 0) return;
            int real = heroRealPage(pager.getCurrentItem());
            int middle = real + count * (HERO_LOOP_TIMES / 2);
            if (pager.getCurrentItem() != middle) pager.setCurrentItem(middle, false);
        }

        void bind() {
            if (pager.getAdapter() != null) pager.getAdapter().notifyDataSetChanged();
            alignToLoop();
            rebuildDots();
            updateCaption(pager.getCurrentItem());
            startAuto();
        }

        void rebuildDots() {
            dots.removeAllViews();
            if (mHeroVideos.size() <= 1) return;
            int size = ConvertUtils.dp2px(6f);
            int gap = ConvertUtils.dp2px(5f);
            for (int i = 0; i < mHeroVideos.size(); i++) {
                ImageView dot = new ImageView(itemView.getContext());
                boolean selected = i == heroRealPage(pager.getCurrentItem());
                dot.setBackgroundResource(selected
                        ? R.drawable.dot_hero_selected : R.drawable.dot_hero_normal);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        selected ? ConvertUtils.dp2px(18f) : size, size);
                lp.setMarginEnd(gap);
                dots.addView(dot, lp);
            }
        }

        void updateCaption(int position) {
            if (mHeroVideos.isEmpty()) return;
            int real = heroRealPage(position);
            Movie.Video vod = mHeroVideos.get(real);
            title.setText(vod.name == null ? "" : vod.name);
            String subtitle = heroSubtitle(vod);
            if (TextUtils.isEmpty(subtitle)) {
                note.setVisibility(View.GONE);
            } else {
                note.setVisibility(View.VISIBLE);
                note.setText(subtitle);
            }
            for (int i = 0; i < dots.getChildCount(); i++) {
                View dot = dots.getChildAt(i);
                boolean selected = i == real;
                dot.setBackgroundResource(selected
                        ? R.drawable.dot_hero_selected : R.drawable.dot_hero_normal);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
                lp.width = ConvertUtils.dp2px(selected ? 18f : 6f);
                dot.setLayoutParams(lp);
            }
        }

        void startAuto() {
            handler.removeCallbacks(autoScroll);
            if (mHeroVideos.size() > 1) handler.postDelayed(autoScroll, 5000);
        }

        void stopAuto() {
            handler.removeCallbacks(autoScroll);
        }
    }

    private static String heroSubtitle(Movie.Video vod) {
        String note = vod.note == null ? "" : vod.note.trim();
        String num = note.replaceAll("[^0-9.]", "");
        if (!num.isEmpty() && !num.equals("0") && !num.equals("0.0")) {
            return note;
        }
        StringBuilder extra = new StringBuilder();
        if (vod.year > 0) extra.append(vod.year);
        if (!TextUtils.isEmpty(vod.area)) {
            if (extra.length() > 0) extra.append(" · ");
            extra.append(vod.area);
        }
        return extra.toString();
    }

    // ---------------- 外层列表适配器（hero + 分区标题 + 横滑行） ----------------

    private class SectionOuterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HERO = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_ROW = 2;

        private boolean hasHero() {
            return !mHeroVideos.isEmpty();
        }

        private int visibleSections() {
            int n = 0;
            for (Section s : mSections) if (s.visible) n++;
            return n;
        }

        @Override
        public int getItemCount() {
            return (hasHero() ? 1 : 0) + visibleSections() * 2;
        }

        private Section sectionAt(int cellIndex) {
            int index = 0;
            for (Section s : mSections) {
                if (!s.visible) continue;
                if (cellIndex == index) return s;
                index++;
            }
            return null;
        }

        @Override
        public int getItemViewType(int position) {
            if (hasHero() && position == 0) return TYPE_HERO;
            int p = position - (hasHero() ? 1 : 0);
            return p % 2 == 0 ? TYPE_HEADER : TYPE_ROW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HERO) {
                return new HeroVH(inflater.inflate(R.layout.item_home_hero, parent, false));
            }
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_home_section_header, parent, false));
            }
            RowVH vh = new RowVH(inflater.inflate(R.layout.item_home_section_row, parent, false));
            // 每个分类一行横滑海报
            V7GridLayoutManager lm = new V7GridLayoutManager(parent.getContext(), 1,
                    GridLayoutManager.HORIZONTAL, false);
            vh.rv.setLayoutManager(lm);
            // owen 的 TvRecyclerView 会用自身 spacing 覆盖 item margin，
            // 这里给 10dp 横向间距，与卡片宽度一起决定一屏能露出的卡片数
            int gap = ConvertUtils.dp2px(10f);
            vh.rv.setSpacingWithMargins(0, gap);
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeroVH) {
                ((HeroVH) holder).bind();
                return;
            }
            int cell = position - (hasHero() ? 1 : 0);
            Section section = sectionAt(cell / 2);
            if (section == null) return;
            if (holder instanceof HeaderVH) {
                HeaderVH h = (HeaderVH) holder;
                h.title.setText(section.title);
                h.all.setOnClickListener(v -> openSectionAll(section));
            } else if (holder instanceof RowVH) {
                RowVH rvh = (RowVH) holder;
                if (rvh.rv.getAdapter() != section.adapter) {
                    rvh.rv.setAdapter(section.adapter);
                }
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            super.onViewAttachedToWindow(holder);
            if (holder instanceof HeroVH) ((HeroVH) holder).startAuto();
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
            super.onViewDetachedFromWindow(holder);
            if (holder instanceof HeroVH) ((HeroVH) holder).stopAuto();
        }
    }

    private static class HeaderVH extends RecyclerView.ViewHolder {
        TextView title;
        TextView all;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tvSectionTitle);
            all = itemView.findViewById(R.id.tvSectionAll);
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
