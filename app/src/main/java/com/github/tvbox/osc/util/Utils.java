package com.github.tvbox.osc.util;

import android.content.res.Configuration;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.bean.VodInfo;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;


public class Utils {

    public static boolean supportsPiPMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static int getSeriesSpanCount(List<VodInfo.VodSeries> list) {
        int spanCount = 4;
        int total = 0;
        for (VodInfo.VodSeries item : list) total += item.name.length();
        int offset = (int) Math.ceil((double) total / list.size());
        if (offset >= 12) spanCount = 1;
        else if (offset >= 8) spanCount = 2;
        else if (offset >= 4) spanCount = 3;
        else if (offset >= 2) spanCount = 4;
        return spanCount;
    }

    public static String stringForTime(long timeMs) {
//        if (timeMs <= 0 || timeMs >= 24 * 60 * 60 * 1000) {
//            return "00:00";
//        }
        long totalSeconds = timeMs / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        StringBuilder stringBuilder = new StringBuilder();
        Formatter mFormatter = new Formatter(stringBuilder, Locale.getDefault());
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    public static List<VideoInfo> getVideoList() {
        List<VideoInfo> videoList = new ArrayList<>();
        Cursor cursor = App.getInstance().getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] { // 查询内容
                        MediaStore.Video.Media._ID, // 视频id
                        MediaStore.Video.Media.DATA, // 视频路径
                        MediaStore.Video.Media.SIZE, // 视频字节大小
                        MediaStore.Video.Media.DISPLAY_NAME, // 视频名称 xxx.mp4
                        MediaStore.Video.Media.TITLE, // 视频标题
                        MediaStore.Video.Media.DURATION, // 视频时长
                        MediaStore.Video.Media.RESOLUTION, // 视频分辨率 X x Y格式
                        MediaStore.Video.Media.IS_PRIVATE,
                        MediaStore.Video.Media.BUCKET_ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.BOOKMARK // 上次视频播放的位置
                },
                null,
                null,
                null
        );
        if (cursor != null && cursor.moveToFirst()) {
            do {
                VideoInfo videoInfo = new VideoInfo();
                videoInfo.setId(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                videoInfo.setPath(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
                videoInfo.setSize(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)));
                videoInfo.setDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)));
                videoInfo.setTitle(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)));
                videoInfo.setDuration(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)));
                videoInfo.setResolution(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION)));
                videoInfo.setIsPrivate(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PRIVATE)));
                videoInfo.setBucketId(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)));
                String bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME));
                if (bucketName == null || bucketName.trim().isEmpty()) {
                    String path = videoInfo.getPath();
                    File parent = path == null ? null : new File(path).getParentFile();
                    if (parent != null && parent.equals(android.os.Environment.getExternalStorageDirectory())) {
                        bucketName = "本地存储";
                    } else {
                        bucketName = parent == null ? "本地存储" : parent.getName();
                    }
                }
                videoInfo.setBucketDisplayName(bucketName);
                videoInfo.setBookmark(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BOOKMARK)));
                videoList.add(videoInfo);
            } while (cursor.moveToNext());
            cursor.close();
        }
        return videoList;
    }

    /**
     * 当前是否深色主题。
     *
     * <p>**以用户保存的 THEME_TAG 为准**，而不是读 Application 的 uiMode 或
     * {@code AppCompatDelegate.getDefaultNightMode()}：
     * <ul>
     *   <li>Application 的配置在主题切换后可能滞后于 Activity（本项目的切换方式是
     *   「新建 MainActivity + finish 旧的」，Application 并不重建）；</li>
     *   <li>原来的判断是 {@code uiMode==NIGHT_YES || defaultNightMode==NIGHT_YES}，
     *   这个「或」会让结果长期偏向深色：从深色切回浅色时 defaultNightMode 已经是
     *   NIGHT_NO，但 App 的 uiMode 还停在 NIGHT_YES，于是依旧判定为深色，
     *   状态栏/导航栏图标就按深色渲染，压在浅色背景上几乎看不见。</li>
     * </ul>
     * 只有 tag=0（跟随系统）时才回落到系统 uiMode。
     */
    public static boolean isDarkTheme(){
        try {
            int tag = Hawk.get(HawkConfig.THEME_TAG, 0);
            if (tag == 1) return false;
            if (tag == 2) return true;
        } catch (Throwable ignored) {
            // Hawk 还没初始化：继续往下判断
        }
        // tag=0（跟随系统）：优先看栈顶 Activity 的配置 —— Activity 的配置会随系统
        // 深色模式即时更新，而 Application 的 uiMode 在某些机型/时序下会慢一拍。
        try {
            android.app.Activity top = com.blankj.utilcode.util.ActivityUtils.getTopActivity();
            if (top != null) {
                int mode = top.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                if (mode == Configuration.UI_MODE_NIGHT_YES) return true;
                if (mode == Configuration.UI_MODE_NIGHT_NO) return false;
            }
        } catch (Throwable ignored) {
            // ActivityUtils 不可用（多进程/初始化早期）
        }
        int currentNightMode = App.getInstance().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void initTheme(){
        switch (Hawk.get(HawkConfig.THEME_TAG,0)) {
            case 0:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }
}