package com.github.tvbox.osc.ui.dialog

import android.content.Context
import android.view.View
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.databinding.DialogWallpaperBinding
import com.github.tvbox.osc.util.Utils
import com.github.tvbox.osc.util.WallpaperHelper
import com.lxj.xpopup.core.BottomPopupView

/** 无参无返回的回调；用普通 interface 而不是 Kotlin 的 `() -> Unit`，
 *  这样 MyFragment（Java）可以直接用 lambda 传进来。 */
interface SimpleCallback {
    fun run()
}

/**
 * 「我的」页 → 换张壁纸。
 *
 * <p>壁纸有两条来源：
 * 1. 当前订阅配置里的 `wallpaper` 字段（一般是随机壁纸接口，每次换一张）
 * 2. 用户相册里的图
 *
 * 两条来源最终都落到 app 私有目录，只在 Hawk 里记路径。
 */
class WallpaperDialog(
    context: Context,
    private val onPickFromGallery: SimpleCallback,
    private val onChanged: SimpleCallback
) : BottomPopupView(context) {

    private lateinit var binding: DialogWallpaperBinding

    override fun getImplLayoutId(): Int = com.github.tvbox.osc.R.layout.dialog_wallpaper

    override fun onCreate() {
        super.onCreate()
        binding = DialogWallpaperBinding.bind(popupImplView)

        // 这个开关控制「壁纸虚化」：开=背景壁纸虚化，关=显示原图
        binding.switchWallpaper.isChecked = WallpaperHelper.isBlurEnabled()
        binding.switchWallpaper.setOnCheckedChangeListener { _, checked ->
            WallpaperHelper.setBlurEnabled(checked)
            onChanged.run()
        }

        // 订阅没给壁纸地址时，提示一下并禁用随机按钮
        if (ApiConfig.get().wallpaper.isNullOrEmpty()) {
            binding.tvTip.visibility = View.VISIBLE
            binding.btnRandom.alpha = 0.5f
        }

        binding.btnRandom.setOnClickListener {
            binding.btnRandom.isEnabled = false
            binding.btnRandom.text = "获取中…"
            WallpaperHelper.downloadRandom(context) { ok, msg ->
                post {
                    binding.btnRandom.isEnabled = true
                    binding.btnRandom.text = "随机壁纸"
                    if (ok) {
                        binding.switchWallpaper.isChecked = WallpaperHelper.isBlurEnabled()
                        ToastUtils.showShort("壁纸已更新")
                        onChanged.run()
                    } else {
                        ToastUtils.showShort(msg.ifEmpty { "壁纸获取失败" })
                    }
                }
            }
        }

        binding.btnFromGallery.setOnClickListener {
            onPickFromGallery.run()
        }

        binding.btnSaveGallery.setOnClickListener {
            WallpaperHelper.saveToGallery(context) { ok, msg ->
                post {
                    ToastUtils.showShort(if (ok) "已保存到相册" else msg.ifEmpty { "保存失败" })
                }
            }
        }

        binding.btnReset.setOnClickListener {
            WallpaperHelper.reset(context)
            binding.switchWallpaper.isChecked = WallpaperHelper.isBlurEnabled()
            onChanged.run()
            ToastUtils.showShort("壁纸已重置")
        }
    }

    /** 选图回调走 Activity，这里刷新一下开关状态 */
    fun onPickedResult(ok: Boolean, message: String) {
        post {
            if (ok) {
                binding.switchWallpaper.isChecked = WallpaperHelper.isBlurEnabled()
                onChanged.run()
                ToastUtils.showShort("壁纸已更新")
            } else if (message.isNotEmpty()) {
                ToastUtils.showShort(message)
            }
        }
    }
}
