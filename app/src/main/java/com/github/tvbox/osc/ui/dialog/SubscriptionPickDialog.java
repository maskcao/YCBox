package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.ui.adapter.SubscriptionAdapter;
import com.lxj.xpopup.core.BottomPopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 「切换订阅」底部面板：顶部「配置订阅 / 直播订阅」分段切换，列表复用订阅管理页的
 * {@link SubscriptionAdapter}，所以条目观感（圆形单选 + 名称 + 内置/来自标签 + 地址）
 * 与订阅管理页完全一致。
 *
 * <p>面板里只做「选」：删除按钮隐藏，增删改、重命名、置顶仍回订阅管理页做。
 *
 * <p>两类订阅各有各的生效项（配置订阅看 API_URL，直播订阅看 LIVE_URL），混在一个列表里
 * 容易点错 —— 切直播源顺手把片源也换掉，所以分成两段各选各的。
 */
public class SubscriptionPickDialog extends BottomPopupView {

    public interface OnPick {
        void onPick(Subscription item, boolean live);
    }

    private final List<Subscription> mSubs;
    private final List<Subscription> mLives;

    private OnPick mOnPick;
    private boolean mLive;
    private SubscriptionAdapter mAdapter;

    public SubscriptionPickDialog(@NonNull @NotNull Context context,
                                  List<Subscription> subs,
                                  List<Subscription> lives) {
        super(context);
        mSubs = subs;
        mLives = lives;
    }

    public SubscriptionPickDialog setOnPick(OnPick onPick) {
        mOnPick = onPick;
        return this;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_pick_subscription;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        findViewById(R.id.iv_close).setOnClickListener(v -> dismiss());
        findViewById(R.id.tv_tab_config).setOnClickListener(v -> showTab(false));
        findViewById(R.id.tv_tab_live).setOnClickListener(v -> showTab(true));
        // 一条直播订阅都没有时不出这一段，免得点进去是个空列表
        if (mLives.isEmpty()) {
            findViewById(R.id.tv_tab_live).setVisibility(View.GONE);
        }

        TvRecyclerView rv = findViewById(R.id.list);
        rv.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        mAdapter = new SubscriptionAdapter();
        mAdapter.setPickerMode(true);
        mAdapter.setOnItemClickListener((adapter, view, position) -> {
            Subscription item = mAdapter.getItem(position);
            if (item == null) {
                return;
            }
            // 先收起再回调：换配置订阅会重建 MainActivity，弹窗挂在旧 Activity 上，
            // 留着它只会挡在新界面上
            dismissWith(() -> {
                if (mOnPick != null) mOnPick.onPick(item, mLive);
            });
        });
        rv.setAdapter(mAdapter);
        showTab(false);
    }

    private void showTab(boolean live) {
        mLive = live;
        mAdapter.setShowFrom(live);
        // setNewData 会顺带去重 + 排序（置顶、选中在前），和订阅管理页同一套
        mAdapter.setNewData(live ? mLives : mSubs);
        updateTabStyle();
    }

    private void updateTabStyle() {
        applyTab(findViewById(R.id.tv_tab_config), !mLive);
        applyTab(findViewById(R.id.tv_tab_live), mLive);
    }

    private void applyTab(TextView tab, boolean active) {
        tab.setBackground(active
                ? ContextCompat.getDrawable(getContext(), R.drawable.bg_tab_selected) : null);
        tab.setTextColor(ContextCompat.getColor(getContext(),
                active ? R.color.on_accent_text : R.color.text_foreground));
    }
}
