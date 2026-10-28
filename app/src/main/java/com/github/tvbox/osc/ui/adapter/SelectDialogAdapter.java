package com.github.tvbox.osc.ui.adapter;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SelectDialogAdapter<T> extends ListAdapter<T, SelectDialogAdapter.SelectViewHolder> {

    class SelectViewHolder extends RecyclerView.ViewHolder {

        public SelectViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
        }
    }

    public interface SelectDialogInterface<T> {
        void click(T value, int pos);

        String getDisplay(T val);

        /**
         * 选项的封面图地址；返回 null/空表示没有图，列表改用 {@link #getAvatarText} 的首字头像。
         * <p>默认不提供图标（播放器、安全 DNS 这类选项不需要头像）。
         */
        default String getPic(T val) {
            return null;
        }

        /**
         * 没有封面图时显示的首字头像文本（例如站点名首字）；返回 null 表示连头像也不显示。
         */
        default String getAvatarText(T val) {
            return null;
        }
    }


    public static DiffUtil.ItemCallback<String> stringDiff = new DiffUtil.ItemCallback<String>() {

        @Override
        public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }
    };


    private ArrayList<T> data = new ArrayList<>();

    private int select = 0;

    private SelectDialogInterface dialogInterface;

    private boolean mBorderMode;

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback) {
        super(diffCallback);
        this.dialogInterface = dialogInterface;
    }

    /** 选中态改用条目描边表示，不画对勾圆圈（底部面板的双列卡片里圆圈太占位）。 */
    public void setBorderMode(boolean borderMode) {
        mBorderMode = borderMode;
    }

    public void setData(List<T> newData, int defaultSelect) {
        data.clear();
        data.addAll(newData);
        select = defaultSelect;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }


    @NonNull
    @NotNull
    @Override
    public SelectDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new SelectDialogAdapter.SelectViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull SelectDialogAdapter.SelectViewHolder holder, @SuppressLint("RecyclerView") int position) {
        T value = data.get(position);
        MaterialCheckBox view = holder.itemView.findViewById(R.id.tvName);
        boolean picked = position == select;
        if (mBorderMode) {
            view.setButtonDrawable((Drawable) null);
            holder.itemView.setBackgroundResource(R.drawable.bg_pick_item);
            holder.itemView.setSelected(picked);
        } else {
            view.setChecked(picked);
        }
        view.setText(dialogInterface.getDisplay(value));
        holder.itemView.setOnClickListener(v -> {
            if (position == select)
                return;
            // select 为 -1 表示当前没有选中项（例如生效的配置不在订阅列表里），
            // 此时没有旧条目需要刷新，直接把它挪到新点的这一项即可
            if (select >= 0)
                notifyItemChanged(select);
            select = position;
            notifyItemChanged(select);
            dialogInterface.click(value, position);
        });
    }
}
