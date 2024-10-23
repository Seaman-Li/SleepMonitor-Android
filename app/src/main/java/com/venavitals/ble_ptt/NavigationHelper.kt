package com.venavitals.ble_ptt

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView


//处理导航栏点击事件
object NavigationHelper {

    fun handleNavigation(
        activity: AppCompatActivity,
        itemId: Int,
//        deviceId: String?
    ): Boolean {
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
                    showConnectDeviceDialog(activity)
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

