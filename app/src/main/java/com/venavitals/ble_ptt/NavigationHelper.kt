package com.venavitals.ble_ptt

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

// 处理导航栏点击事件
object NavigationHelper {

    var saveDataCallback: (() -> Unit)? = null  // 用于注册从ECGActivity保存数据的回调

    fun handleNavigation(activity: AppCompatActivity, itemId: Int): Boolean {
        // 当在ECGActivity中，并且尝试导航到非navigation_chart项时
        if (activity is ECGActivity && itemId != R.id.navigation_chart) {
            saveDataCallback?.invoke()  // 调用保存数据的方法
            return false  // 暂停导航，等待用户响应
        }

        return when (itemId) {
            R.id.navigation_connect -> {
                if (activity !is MainActivity) {
                    val intent = Intent(activity, MainActivity::class.java)
                    activity.startActivity(intent)
                    activity.finish()
                }
                true
            }
            R.id.navigation_chart -> {
                showConnectDeviceDialog(activity)//默认不可以点击底部导航栏进行连接
                false
            }
            else -> false
        }
    }


    private fun showConnectDeviceDialog(activity: AppCompatActivity) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("No Device Connected")
        builder.setMessage("Please connect to a device before accessing the chart.")
        builder.setPositiveButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        val dialog = builder.create()
        dialog.show()
    }
}
