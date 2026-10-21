package com.github.tvbox.osc.ui.adapter;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ColorUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
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

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback) {
        super(diffCallback);
        this.dialogInterface = dialogInterface;
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


    @Override
    public SelectDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new SelectDialogAdapter.SelectViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull SelectDialogAdapter.SelectViewHolder holder, @SuppressLint("RecyclerView") int position) {
        T value = data.get(position);
        MaterialCheckBox view = holder.itemView.findViewById(R.id.tvName);
        view.setChecked(position == select);
        view.setText(dialogInterface.getDisplay(value));
        bindAvatar(holder, value);
        holder.itemView.setOnClickListener(v -> {
            if (position == select)
                return;
            notifyItemChanged(select);
            select = position;
            notifyItemChanged(select);
            dialogInterface.click(value, position);
        });
    }

    /**
     * 绑定封面：配置给了 pic 就加载网络图，加载失败（或压根没给）回退到首字头像。
     * <p>RecyclerView 复用时必须先复位状态，否则上一条目的图会串到下一条。
     */
    private void bindAvatar(SelectViewHolder holder, T value) {
        View flAvatar = holder.itemView.findViewById(R.id.fl_avatar);
        ImageView ivPic = holder.itemView.findViewById(R.id.iv_pic);
        TextView tvAvatar = holder.itemView.findViewById(R.id.tv_avatar);
        String pic = dialogInterface.getPic(value);
        String avatar = dialogInterface.getAvatarText(value);

        ivPic.setVisibility(View.GONE);
        ivPic.setImageDrawable(null);
        tvAvatar.setVisibility(View.GONE);
        tvAvatar.setText("");
        flAvatar.setVisibility(View.VISIBLE);

        if (!TextUtils.isEmpty(pic)) {
            Glide.with(holder.itemView)
                    .load(pic)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            // 图拉不到就退回首字头像，别留一个空白方块
                            showAvatar(tvAvatar, avatar);
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            ivPic.setVisibility(View.VISIBLE);
                            tvAvatar.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(ivPic);
        } else {
            showAvatar(tvAvatar, avatar);
        }
        if (TextUtils.isEmpty(pic) && TextUtils.isEmpty(avatar)) {
            flAvatar.setVisibility(View.GONE);
        }
    }

    private void showAvatar(TextView tvAvatar, String avatar) {
        if (TextUtils.isEmpty(avatar)) {
            tvAvatar.setVisibility(View.GONE);
            return;
        }
        tvAvatar.setText(avatar);
        tvAvatar.setVisibility(View.VISIBLE);
    }
}
