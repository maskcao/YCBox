package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.lxj.xpopup.core.BottomPopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 底部弹出的单选取择面板（订阅 / 首页数据源共用）。
 *
 * <p>替代原来居中的 {@link SelectDialog}：选项在顶栏上，面板从顶部弹出会盖住触发它的
 * 位置，从底部弹出则不遮挡顶栏，且与「关于」等弹层是同一套观感。
 */
public class BottomPickDialog<T> extends BottomPopupView {

    public interface OnPick<T> {
        void onPick(T value, int position);
    }

    private final String mTitle;
    private final List<T> mData;
    private final int mSelect;
    private final int mSpanCount;
    private final SelectDialogAdapter.SelectDialogInterface<T> mDisplay;
    private final DiffUtil.ItemCallback<T> mDiffCallback;
    private final String mManageLabel;
    private final Runnable mOnManage;

    private OnPick<T> mOnPick;

    public BottomPickDialog(@NonNull @NotNull Context context,
                            String title,
                            SelectDialogAdapter.SelectDialogInterface<T> display,
                            DiffUtil.ItemCallback<T> diffCallback,
                            List<T> data,
                            int select,
                            int spanCount,
                            @Nullable String manageLabel,
                            @Nullable Runnable onManage) {
        super(context);
        mTitle = title;
        mDisplay = display;
        mDiffCallback = diffCallback;
        mData = data;
        mSelect = select;
        mSpanCount = spanCount;
        mManageLabel = manageLabel;
        mOnManage = onManage;
    }

    public BottomPickDialog<T> setOnPick(OnPick<T> onPick) {
        mOnPick = onPick;
        return this;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_pick_bottom;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        ((TextView) findViewById(R.id.title)).setText(mTitle);
        findViewById(R.id.iv_close).setOnClickListener(v -> dismiss());

        TextView manage = findViewById(R.id.btn_manage);
        if (mManageLabel == null) {
            manage.setVisibility(View.GONE);
        } else {
            manage.setText(mManageLabel);
            manage.setVisibility(View.VISIBLE);
            manage.setOnClickListener(v -> dismissWith(mOnManage));
        }

        TvRecyclerView rv = findViewById(R.id.list);
        if (mSpanCount > 1) {
            rv.setLayoutManager(new V7GridLayoutManager(getContext(), mSpanCount));
        } else {
            rv.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        }
        // 横向间距只在多列时有意义（列与列之间要留白）。单列还按同一个值走的话，
        // 条目左右各缩进半格，会比面板底部那条「管理订阅」按钮窄一圈，看着像没对齐。
        int vertical = getResources().getDimensionPixelSize(R.dimen.dp_8);
        int horizontal = mSpanCount > 1 ? getResources().getDimensionPixelSize(R.dimen.dp_12) : 0;
        rv.setSpacingWithMargins(vertical, horizontal);
        SelectDialogAdapter<T> adapter = new SelectDialogAdapter<>(
                new SelectDialogAdapter.SelectDialogInterface<T>() {
                    @Override
                    public void click(T value, int pos) {
                        // 先收起再回调：切换订阅/数据源会重建 MainActivity，
                        // 弹窗挂在旧 Activity 上，留着它只会挡在新界面上。
                        dismissWith(() -> {
                            if (mOnPick != null) mOnPick.onPick(value, pos);
                        });
                    }

                    @Override
                    public String getDisplay(T val) {
                        return mDisplay.getDisplay(val);
                    }
                }, mDiffCallback);
        adapter.setBorderMode(true);
        adapter.setData(mData, mSelect);
        rv.setAdapter(adapter);
    }
}
