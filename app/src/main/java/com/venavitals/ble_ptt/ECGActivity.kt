package com.venavitals.ble_ptt

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYPlot
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.PolarSensorSetting
import com.venavitals.ble_ptt.filters.ButterworthBandpassFilter
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.UUID


class ECGActivity : AppCompatActivity(), PlotterListener {
    companion object {
        private const val TAG = "ECGActivity"
    }

    private lateinit var api: PolarBleApi
    private lateinit var textViewHR: TextView
    private lateinit var textViewRR: TextView
    private lateinit var textViewDeviceId: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewFwVersion: TextView
    private lateinit var ppgPlot: XYPlot
    private lateinit var ecgPlot: XYPlot
    private lateinit var ppgPlotter: EcgPlotter
    private lateinit var ecgPlotter: EcgPlotter
    private var ppgDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null

    private lateinit var ppgDeviceId: String
    private var uart: UartOld =
        UartOld()

    private var ecgSamples: ArrayList<Double> = ArrayList()
    private var ppgSamples: ArrayList<Double> = ArrayList()
    private var startTimestamp: Long = 0
    @Volatile private var isSynchronized:Boolean =false

    private var ecgSR: Int = 250
    private var ppgSR: Int = 55  //28Hz, 44Hz, 55Hz, 135Hz, 176Hz

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg)
        // 尝试从 Intent 获取 deviceId
        ppgDeviceId = intent.getStringExtra("id").toString()
        Log.d(TAG, "ECGActivity received deviceId: $ppgDeviceId")

        if (ppgDeviceId.isNullOrEmpty()) {
            Toast.makeText(this, "No device ID provided. Please connect to a device first.", Toast.LENGTH_LONG).show()
            // 使用 Intent 显式地返回到 MainActivity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            // 结束当前活动并返回
            finish()
            return
        }
        textViewHR = findViewById(R.id.hr)
        textViewRR = findViewById(R.id.rr)
        textViewDeviceId = findViewById(R.id.deviceId)
        textViewBattery = findViewById(R.id.battery_level)
        textViewFwVersion = findViewById(R.id.fw_version)
        ppgPlot = findViewById(R.id.plot)
        ecgPlot = findViewById(R.id.ecg_plot)




        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE
            )
        )
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected " + polarDeviceInfo.deviceId)
                Toast.makeText(applicationContext, R.string.connected, Toast.LENGTH_SHORT).show()
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connecting ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device disconnected ${polarDeviceInfo.deviceId}")
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                Log.d(TAG, "feature ready $feature")

                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {

                        streamPPG()
                        streamHR()
                    }
                    else -> {}
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    val msg = "fm: " + value.trim { it <= ' ' }
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                    textViewFwVersion.append(msg.trimIndent())
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "Battery level $identifier $level%")
                val batteryLevelText = "$level%"
                textViewBattery.append(batteryLevelText)
            }

        })
        try {
            api.connectToDevice(ppgDeviceId)
        } catch (a: PolarInvalidArgument) {
            a.printStackTrace()
        }

        uart.setCallback{plotECG(it)}

        bindService(
            Intent(this, UartService::class.java),
            uart.mServiceConnection,
            BIND_AUTO_CREATE
        ) // ServiceConnection.onServiceConnected() invoked after this

        val deviceIdText = "ID: $ppgDeviceId"
        textViewDeviceId.text = deviceIdText

        ppgPlotter = EcgPlotter("PPG", ppgSR)
        ppgPlotter.setListener(this)
        ppgPlot.addSeries(ppgPlotter.getSeries(), ppgPlotter.formatter)
        ppgPlot.setRangeBoundaries(160000, 200000, BoundaryMode.AUTO)
        ppgPlot.setRangeStep(StepMode.INCREMENT_BY_FIT, 2000.0)
        ppgPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 300.0)
        ppgPlot.setDomainBoundaries(0, 200, BoundaryMode.AUTO)
        ppgPlot.linesPerRangeLabel = 2


        ecgPlotter = EcgPlotter("ECG", ecgSR)
        ecgPlotter.setListener(this)
        ecgPlot.addSeries(ecgPlotter.getSeries(), ecgPlotter.formatter)
        ecgPlot.setRangeBoundaries(160000, 200000, BoundaryMode.AUTO)
        ecgPlot.setRangeStep(StepMode.INCREMENT_BY_FIT, 2000.0)
        ecgPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 300.0)
        ecgPlot.setDomainBoundaries(0, 200, BoundaryMode.AUTO)
        ecgPlot.linesPerRangeLabel = 2

//        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
//        bottomNavigationView.selectedItemId = R.id.navigation_chart
//        bottomNavigationView.setOnItemSelectedListener { item ->
//            when (item.itemId) {
//                R.id.navigation_connect -> {
//                    // Navigate to MainActivity
//                    startActivity(Intent(this, MainActivity::class.java))
//                    true
//                }
//                R.id.navigation_chart -> {
//                    // Stay in ECGActivity
//                    true
//                }
//                R.id.navigation_user -> {
//                    // Placeholder for future user activity
//                    true
//                }
//                R.id.navigation_settings -> {
//                    // Placeholder for future settings activity
//                    true
//                }
//                else -> false
//            }
//        }

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.navigation_chart  // 设置选中的项为 chart

        val deviceId = intent.getStringExtra("id")

        bottomNavigationView.setOnItemSelectedListener { item ->
            NavigationHelper.handleNavigation(this, item.itemId, deviceId)
        }

    }

    public override fun onDestroy() {
        super.onDestroy()

        uart.shutdown()
        unbindService(uart.mServiceConnection)

        ppgDisposable?.let {
            if (!it.isDisposed) it.dispose()
        }
        api.shutDown()


        val path = getExternalFilesDir(null).toString();
//        path = Environment.getExternalStorageDirectory().toString();
        Log.d(TAG, "file save path: $path");
        Utils.saveSamples(ecgSamples,path,"ecg_samples"+System.currentTimeMillis()+".txt");
        Utils.saveSamples(ppgSamples,path,"ppg_samples"+System.currentTimeMillis()+".txt");
    }


    fun streamPPG() {
        val isDisposed = ppgDisposable?.isDisposed ?: true
        if (isDisposed) {
            val settingMap=HashMap<PolarSensorSetting.SettingType, Int>()
            settingMap[PolarSensorSetting.SettingType.SAMPLE_RATE] = ppgSR
            settingMap[PolarSensorSetting.SettingType.RESOLUTION] = 22
            settingMap[PolarSensorSetting.SettingType.CHANNELS] = 4
            val setting = PolarSensorSetting(settingMap)

            ppgDisposable = api.startPpgStreaming(ppgDeviceId, setting).subscribe(
                { polarPpgData: PolarPpgData ->
                    if (polarPpgData.type == PolarPpgData.PpgDataType.PPG3_AMBIENT1) {
                        plotPPG(polarPpgData.samples)
                    }
                },
                { error: Throwable ->
                    Log.e(TAG, "PPG stream failed. Reason $error")
                },
                { Log.d(TAG, "PPG stream complete") }
            )
        } else {
            // NOTE stops streaming if it is "running"
            ppgDisposable?.dispose()
            ppgDisposable = null
        }
    }

    private var ppgBufferSelector: Boolean = false
    private var ppgBufferIdx: Int = 0
    private var ppgBufferA = DoubleArray(ppgSR * 5) //5 sec
    private var ppgBufferB = DoubleArray(ppgSR * 5)
    
    private var ecgBufferSelector: Boolean = false
    private var ecgBufferIdx: Int = 0
    private var ecgBufferA = DoubleArray(ecgSR * 5) //5 sec
    private var ecgBufferB = DoubleArray(ecgSR * 5)


    private fun plotPPG(samples: List<PolarPpgData.PolarPpgSample>){
        Log.d(TAG, "PPG data available ${samples.size} Thread:${Thread.currentThread()}")
        if(!isSynchronized){
            isSynchronized = true
            startTimestamp=System.currentTimeMillis();
            return
        }
        for (data in samples) {
            //Log.d(TAG, "PPG data available    ppg0: ${data.channelSamples[0]} ppg1: ${data.channelSamples[1]} ppg2: ${data.channelSamples[2]} ambient: ${data.channelSamples[3]} timeStamp: ${data.timeStamp}")
            val value = data.channelSamples[0].toDouble()
            ppgSamples.add(value)

            val buffer: DoubleArray = if(ppgBufferSelector)ppgBufferA else ppgBufferB

            buffer[ppgBufferIdx++]=value;
            if(ppgBufferIdx>=buffer.size && ppgBufferIdx%ppgSR==0){
                ppgBufferSelector=!ppgBufferSelector;
                ppgBufferIdx=0;

//                val res = ButterworthBandpassFilter.ppg55hzBandpassFilter(buffer);
                ppgPlotter.sendSamples(buffer);
            }
        }

        Log.d(TAG,"Current Sample Rate: ppg: "+ppgSamples.size.toDouble()/((System.currentTimeMillis()-startTimestamp)/1000)+" ecg: "+ecgSamples.size.toDouble()/((System.currentTimeMillis()-startTimestamp)/1000))
    }


    private fun plotECG(num: Double) {
        if(isSynchronized){
            val buffer: DoubleArray = if(ecgBufferSelector)ecgBufferA else ecgBufferB
//
//            buffer[ecgBufferIdx++]=num;
//            if(ecgBufferIdx>=buffer.size){
//                ecgBufferSelector=!ecgBufferSelector;
//                ecgBufferIdx=0;
//
////                val res = ButterworthBandpassFilter.ecg250hzBandpassFilter(buffer);
//                ecgPlotter.sendSamples(buffer);
//            }

            ecgPlotter.sendSingleSample(num);
            
            ecgSamples.add(num)
        }
    }

    fun streamHR() {
        val isDisposed = hrDisposable?.isDisposed ?: true
        if (isDisposed) {
            hrDisposable = api.startHrStreaming(ppgDeviceId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { hrData: PolarHrData ->
                        for (sample in hrData.samples) {
                            Log.d(TAG, "HR " + sample.hr)
                            if (sample.rrsMs.isNotEmpty()) {
                                val rrText = "(${sample.rrsMs.joinToString(separator = "ms, ")}ms)"
                                textViewRR.text = rrText
                            }

                            textViewHR.text = sample.hr.toString()

                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "HR stream failed. Reason $error")
                        hrDisposable = null
                    },
                    { Log.d(TAG, "HR stream complete") }
                )
        } else {
            // NOTE stops streaming if it is "running"
            hrDisposable?.dispose()
            hrDisposable = null
        }
    }

    override fun update() {
        runOnUiThread {
            ppgPlot.redraw()
        }
        runOnUiThread {
            ecgPlot.redraw()
        }

    }

//    override fun onResume() {
//        super.onResume()
//
//        // 设置导航栏的选中状态为chart
//        val bottomNavigationView: BottomNavigationView = findViewById(R.id.bottom_navigation)
//        bottomNavigationView.selectedItemId = R.id.navigation_chart
//    }


}