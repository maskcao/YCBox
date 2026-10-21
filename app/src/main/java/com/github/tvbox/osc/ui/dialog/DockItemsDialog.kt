package com.github.tvbox.osc.ui.dialog

import android.content.Context
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogDockItemsBinding
import com.github.tvbox.osc.util.HawkConfig
import com.lxj.xpopup.core.CenterPopupView
import com.orhanobut.hawk.Hawk

/**
 * 底部导航栏(dock)入口显隐设置。
 *
 * 直播与历史/收藏可勾选；首页与「我的」固定显示。
 */
class DockItemsDialog(
    context: Context,
    private val onChanged: (() -> Unit)? = null
) : CenterPopupView(context) {

    private lateinit var binding: DialogDockItemsBinding

    override fun getImplLayoutId(): Int = R.layout.dialog_dock_items

    override fun onCreate() {
        super.onCreate()
        binding = DialogDockItemsBinding.bind(popupImplView)

        binding.switchDockLive.isChecked = Hawk.get(HawkConfig.DOCK_SHOW_LIVE, true)
        binding.switchDockHistory.isChecked = Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)

        binding.llDockLive.setOnClickListener {
            val next = !Hawk.get(HawkConfig.DOCK_SHOW_LIVE, true)
            binding.switchDockLive.isChecked = next
            Hawk.put(HawkConfig.DOCK_SHOW_LIVE, next)
        }

        binding.llDockHistory.setOnClickListener {
            val next = !Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)
            binding.switchDockHistory.isChecked = next
            Hawk.put(HawkConfig.DOCK_SHOW_HISTORY, next)
        }

        binding.btnConfirm.setOnClickListener {
            onChanged?.invoke()
            dismiss()
        }
    }
}
