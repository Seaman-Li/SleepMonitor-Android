package com.venavitals.ble_ptt;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BLEScanActivity extends Activity {
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private TextView mEmptyList;
    private static final String TAG = "DeviceListActivity";

    private List<BluetoothDevice> deviceList;
    private DeviceAdapter deviceAdapter;
    private Map<String, Integer> devRssiValues;
    private static final long SCAN_PERIOD = 10000; // Scanning for 10 seconds
    private Handler mHandler;
    private boolean mScanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_device_list);
        mEmptyList = findViewById(R.id.empty);
        if (mEmptyList == null) {
            Log.e(TAG, "TextView with id 'empty' not found!");
        } else {
            Log.d(TAG, "Successfully found the TextView with id 'empty'");
        }

        mHandler = new Handler();

        // 初始化蓝牙适配器
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // 初始化设备列表
        deviceList = new ArrayList<>();
        devRssiValues = new HashMap<>();

        ListView newDevicesListView = findViewById(R.id.new_devices);
        deviceAdapter = new DeviceAdapter(this, deviceList);
        newDevicesListView.setAdapter(deviceAdapter);
        newDevicesListView.setOnItemClickListener(mDeviceClickListener);

        // 开始扫描蓝牙设备
        startLeScan(true);

        Button cancelButton = findViewById(R.id.btn_cancel);
        cancelButton.setOnClickListener(v -> {
            if (mScanning) {
                startLeScan(false);
            } else {
                finish(); // 关闭活动
            }
        });
    }

    // 开始或停止扫描
    private void startLeScan(final boolean enable) {
        final Button cancelButton = findViewById(R.id.btn_cancel);
        if (enable) {
            // 在 SCAN_PERIOD 后停止扫描
            mHandler.postDelayed(() -> {
                mScanning = false;
                bluetoothLeScanner.stopScan(mLeScanCallback);
                cancelButton.setText(R.string.scan);
                mEmptyList.setText(R.string.scan_complete);
            }, SCAN_PERIOD);

            mScanning = true;
            bluetoothLeScanner.startScan(mLeScanCallback);
            cancelButton.setText(R.string.cancel_scan);
            mEmptyList.setText(R.string.scanning);
        } else {
            mScanning = false;
            bluetoothLeScanner.stopScan(mLeScanCallback);
            cancelButton.setText(R.string.scan);
            mEmptyList.setText(R.string.scan_complete);
        }
    }

    // 蓝牙扫描回调
    private final ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            addDevice(device, rssi);
        }
    };

    // 添加设备到列表
    private void addDevice(BluetoothDevice device, int rssi) {
        boolean deviceFound = false;

        for (BluetoothDevice listDev : deviceList) {
            if (listDev.getAddress().equals(device.getAddress())) {
                deviceFound = true;
                break;
            }
        }

        devRssiValues.put(device.getAddress(), rssi);
        if (!deviceFound) {
            deviceList.add(device);
            mEmptyList.setVisibility(View.GONE);
            deviceAdapter.notifyDataSetChanged();
        }
    }

    // 列表项点击事件
    private final AdapterView.OnItemClickListener mDeviceClickListener = (parent, view, position, id) -> {
        BluetoothDevice device = deviceList.get(position);
        bluetoothLeScanner.stopScan(mLeScanCallback);

        Intent result = new Intent();
        result.putExtra(BluetoothDevice.EXTRA_DEVICE, device.getAddress());
        setResult(Activity.RESULT_OK, result);
        finish();  // 结束活动并返回选中的设备地址
    };

    @Override
    protected void onPause() {
        super.onPause();
        startLeScan(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        startLeScan(false);
    }

    // 设备列表适配器
    private class DeviceAdapter extends BaseAdapter {
        Context context;
        List<BluetoothDevice> devices;
        LayoutInflater inflater;

        public DeviceAdapter(Context context, List<BluetoothDevice> devices) {
            this.context = context;
            inflater = LayoutInflater.from(context);
            this.devices = devices;
        }

        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public Object getItem(int position) {
            return devices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewGroup vg;

            if (convertView != null) {
                vg = (ViewGroup) convertView;
            } else {
                vg = (ViewGroup) inflater.inflate(R.layout.device_element, null);
            }

            BluetoothDevice device = devices.get(position);
            final TextView tvAdd = vg.findViewById(R.id.address);
            final TextView tvName = vg.findViewById(R.id.name);
            final TextView tvRssi = vg.findViewById(R.id.rssi);

            int rssi = devRssiValues.get(device.getAddress());
            tvRssi.setText("RSSI: " + rssi);

            tvName.setText(device.getName() != null ? device.getName() : "Unknown Device");
            tvAdd.setText(device.getAddress());

            return vg;
        }
    }
}
