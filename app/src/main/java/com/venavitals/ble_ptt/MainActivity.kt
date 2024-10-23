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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.venavitals.ble_ptt.adapters.DeviceListAdapter
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

    private val bluetoothOnActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "Bluetooth off")
            }
        }
    private var deviceId: String? = null

    //当用户点击DeviceListActivity列表的item后， 注册 ActivityResultLauncher 用于启动 DeviceListActivity 并接收结果
    private val selectDeviceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val deviceAddress = result.data?.getStringExtra(BluetoothDevice.EXTRA_DEVICE)
            if (deviceAddress != null) {
                showToast(getString(R.string.connecting) + " " + deviceAddress)
                saveDeviceId(deviceAddress); // 保存设备ID
                Log.d(TAG, "deviceID saved: $deviceAddress")
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
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            PERMISSION_REQUEST_CODE
        )


        setContentView(R.layout.activity_main)
        sharedPreferences = getPreferences(MODE_PRIVATE)
        displayDeviceIds()

        //配置AppBar
        val toolbar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // 以下代码为可选，如果你未来需要处理导航点击事件
        toolbar.setNavigationOnClickListener {
            // Handle navigation icon click event
        }


//        deviceId = sharedPreferences.getString(SHARED_PREFS_KEY, "")

        val setIdButton: Button = findViewById(R.id.buttonSetID)
        val ppgEcgConnectButton: Button = findViewById(R.id.buttonConnectPpg)
//        val hrConnectButton: Button = findViewById(R.id.buttonConnectHr)
        checkBT()

        setIdButton.setOnClickListener { onClickChangeID(it) }
        ppgEcgConnectButton.setOnClickListener { onClickConnectPpgEcg(it) }
//        hrConnectButton.setOnClickListener { onClickConnectHr(it) }

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.navigation_connect  // 设置选中的项为 connect


        bottomNavigationView.setOnItemSelectedListener { item ->
            NavigationHelper.handleNavigation(this, item.itemId)
        }


    }

    private fun onClickConnectPpgEcg(view: View) {
        // 确保蓝牙已启用
        if (checkBT()) {
            // 检查是否已有必要的位置权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 请求位置权限
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            } else {
                // 如果已有权限，直接启动 DeviceListActivity
                launchDeviceListActivity()
            }
        }
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


    private fun checkBT(): Boolean {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = btManager.adapter
        if (bluetoothAdapter == null) {
            showToast("Device doesn't support Bluetooth")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothOnActivityResultLauncher.launch(enableBtIntent)
            return false
        }
        return true
    }

    private fun launchDeviceListActivity() {
        val intent = Intent(this, DeviceListActivity::class.java)
        selectDeviceLauncher.launch(intent)
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

    private fun saveDeviceId(deviceId: String) {
        val existingIds = sharedPreferences.getStringSet(SHARED_PREFS_KEY, setOf()) ?: setOf()
        val updatedIds = existingIds.toMutableSet()
        updatedIds.add(deviceId)
        sharedPreferences.edit().putStringSet(SHARED_PREFS_KEY, updatedIds).apply()
    }

    private fun removeDeviceId(deviceId: String) {
        val deviceIds = getStoredDeviceIds().toMutableSet()
        deviceIds.remove(deviceId)
        sharedPreferences.edit().putStringSet(SHARED_PREFS_KEY, deviceIds).apply()
    }


    private fun getStoredDeviceIds(): Set<String> {
        return sharedPreferences.getStringSet(SHARED_PREFS_KEY, emptySet()) ?: emptySet()
    }

    private fun displayDeviceIds() {
        val deviceIds = getStoredDeviceIds().toList()

        val adapter = DeviceListAdapter(this, deviceIds, { deviceId ->
            // 处理取消配对操作
            removeDeviceId(deviceId)
            displayDeviceIds() // 重新加载设备列表
        }, { deviceId ->
            // 处理连接操作
            Log.d(TAG, "Connecting to device: $deviceId")
            checkBT()
            deviceId?.let {
                val intent = Intent(this, ECGActivity::class.java)
                intent.putExtra("id", deviceId)
                startActivity(intent)
            }
        })

        val listView = findViewById<ListView>(R.id.device_id_list)
        listView.adapter = adapter
    }

}