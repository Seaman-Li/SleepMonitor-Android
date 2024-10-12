package com.venavitals.ble_ptt

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "Polar_MainActivity"
        private const val SHARED_PREFS_KEY = "polar_device_id"
        private const val PERMISSION_REQUEST_CODE = 1

        private var instance: MainActivity? = null

        //新增getDeviceId方法，在其他类中获取DeviceId
        fun getDeviceId(): String? {
            return instance?.deviceId
        }
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val bluetoothOnActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Bluetooth off")
        }
    }
    private var deviceId: String? = null

    // 注册 ActivityResultLauncher 用于启动 DeviceListActivity 并接收结果
    private val selectDeviceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val deviceAddress = result.data?.getStringExtra(BluetoothDevice.EXTRA_DEVICE)
            if (deviceAddress != null) {
                showToast(getString(R.string.connecting) + " " + deviceAddress)

                val intent = Intent(this, ECGActivity::class.java)
                intent.putExtra("id", deviceAddress)
                Log.d(TAG, "Navigating to ECGActivity with deviceId: $deviceAddress")

                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Request storage permissions
        requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
            PERMISSION_REQUEST_CODE
        )


        setContentView(R.layout.activity_main)

        //配置AppBar
        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // 以下代码为可选，如果你未来需要处理导航点击事件
        toolbar.setNavigationOnClickListener {
            // Handle navigation icon click event
        }


        sharedPreferences = getPreferences(MODE_PRIVATE)
        deviceId = sharedPreferences.getString(SHARED_PREFS_KEY, "")

        val setIdButton: Button = findViewById(R.id.buttonSetID)
        val ppgEcgConnectButton: Button = findViewById(R.id.buttonConnectPpg)
//        val hrConnectButton: Button = findViewById(R.id.buttonConnectHr)
        checkBT()

        setIdButton.setOnClickListener { onClickChangeID(it) }
        ppgEcgConnectButton.setOnClickListener { onClickConnectPpgEcg(it) }
//        hrConnectButton.setOnClickListener { onClickConnectHr(it) }


        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.navigation_connect  // 设置选中的项为 connect

        val deviceId = sharedPreferences.getString(SHARED_PREFS_KEY, "")

        bottomNavigationView.setOnItemSelectedListener { item ->
            NavigationHelper.handleNavigation(this, item.itemId, deviceId)
        }


    }

    private fun onClickConnectPpgEcg(view: View) {
        checkBT()  // 确保蓝牙已启用

        // 启动 DeviceListActivity 以扫描并选择设备
        val intent = Intent(this, DeviceListActivity::class.java)
        selectDeviceLauncher.launch(intent)  // 使用 ActivityResultLauncher 启动活动
    }

    private fun onClickConnectHr(view: View) {
        checkBT()
        if (deviceId == null || deviceId == "") {
            deviceId = sharedPreferences.getString(SHARED_PREFS_KEY, "")
            showDialog(view)
        } else {
            showToast(getString(R.string.connecting) + " " + deviceId)
            val intent = Intent(this, HRActivity::class.java)
            intent.putExtra("id", deviceId)
            startActivity(intent)
        }
    }

    private fun onClickChangeID(view: View) {
        showDialog(view)
    }

//    dialog修改过使用了MaterialAlertDialogBuilder
    private fun showDialog(view: View) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Enter your Polar device's ID")
            .setView(R.layout.device_id_dialog_layout)
            .setPositiveButton("OK") { dialog, which ->
                // Handle OK button press
                val input = (dialog as AlertDialog).findViewById<EditText>(R.id.input)
                deviceId = input?.text.toString().uppercase(Locale.getDefault())
                sharedPreferences.edit().putString(SHARED_PREFS_KEY, deviceId).apply()
            }
            .setNegativeButton("Cancel", null) // Dismiss dialog
            .show()
    }


    private fun checkBT() {
        val btManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = btManager.adapter
        if (bluetoothAdapter == null) {
            showToast("Device doesn't support Bluetooth")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothOnActivityResultLauncher.launch(enableBtIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), PERMISSION_REQUEST_CODE)
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (index in 0..grantResults.lastIndex) {
                if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "Needed permissions are missing")
                    showToast("Needed permissions are missing")
                    return
                }
            }
            Log.d(TAG, "Needed permissions are granted")
        }
    }

    private fun showToast(message: String) {
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        toast.show()
    }

//    override fun onResume() {
//        super.onResume()
//
//        // 设置导航栏的选中状态为connect
//        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
//        bottomNavigationView.selectedItemId = R.id.navigation_connect
//    }

}