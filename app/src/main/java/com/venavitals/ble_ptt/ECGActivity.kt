package com.venavitals.ble_ptt

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYPlot
import com.google.android.material.bottomnavigation.BottomNavigationView
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
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID


class ECGActivity : AppCompatActivity(), PlotterListener {
    companion object {
        private const val TAG = "ECGActivity"
    }
    private var isFullScreen = false
    private lateinit var api: PolarBleApi
    private lateinit var textViewHR: TextView
    private lateinit var textViewRR: TextView
    private lateinit var textViewDeviceId: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewFwVersion: TextView
    private lateinit var textViewInfo: TextView
    private lateinit var ppgPlot: XYPlot
    private lateinit var ecgPlot: XYPlot
    private lateinit var ppgPlotter: EcgPlotter
    private lateinit var ecgPlotter: EcgPlotter
    private var ppgDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null

    private lateinit var ppgDeviceId: String
    private var uart: UartOld =
        UartOld()

    private var ecgSamples: MutableList<Double> = Collections.synchronizedList(ArrayList()) //TODO: ConcurrentLinkedQueue might be better
    private var ppgSamples: MutableList<Double> = Collections.synchronizedList(ArrayList())
    @Volatile private var startTimestamp: Long = 0
    @Volatile private var isSynchronized:Boolean =false
    @Volatile private var isFilterApplied:Boolean =false

    private var ecgSR: Int = 250
    private var ppgSR: Int = 55  //28Hz, 44Hz, 55Hz, 135Hz, 176Hz

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg)

        // 注册保存数据的回调
        NavigationHelper.saveDataCallback = {
            showSaveDialog()
        }
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.navigation_chart  // 设置当前选中项为 chart
        // 设置底部导航的监听器
        bottomNavigationView.setOnItemSelectedListener { item ->
            NavigationHelper.handleNavigation(this, item.itemId)
        }

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
        textViewInfo = findViewById(R.id.info)
        ppgPlot = findViewById(R.id.plot)
        ecgPlot = findViewById(R.id.ecg_plot)

        var fullScreenButton = findViewById<Button>(R.id.fullscreen_button)




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
//                Toast.makeText(applicationContext, R.string.connected, Toast.LENGTH_SHORT).show()
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
                textViewBattery.text=batteryLevelText
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
        ppgPlot.setRangeStep(StepMode.INCREMENT_BY_FIT, 20000.0)
        ppgPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 30000.0)
        ppgPlot.setDomainBoundaries(0, 20000, BoundaryMode.AUTO)
        ppgPlot.linesPerRangeLabel = 2
        ppgPlot.graph.setMargins(-1000f,0f,0f,0f)


        ecgPlotter = EcgPlotter("ECG", ecgSR)
        ecgPlotter.setListener(this)
        ecgPlot.addSeries(ecgPlotter.getSeries(), ecgPlotter.formatter)
        ecgPlot.setRangeBoundaries(160000, 200000, BoundaryMode.AUTO)
        ecgPlot.setRangeStep(StepMode.INCREMENT_BY_FIT, 20000.0)
        ecgPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 30000.0)
        ecgPlot.setDomainBoundaries(0, 20000, BoundaryMode.AUTO)
        ecgPlot.linesPerRangeLabel = 2
        ecgPlot.graph.setMargins(-1000f,0f,0f,0f)
        val deviceId = intent.getStringExtra("id")





        // 点击按钮切换全屏显示
        fullScreenButton.setOnClickListener {
            if (!isFullScreen) {
                // 切换到横屏模式
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                enterFullScreenMode()
                fullScreenButton.text = "Return"  // 修改按钮文本为"Return"
            } else {
                // 切换回竖屏模式
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                exitFullScreenMode()
                fullScreenButton.text = "Fullscreen"  // 修改按钮文本回"Fullscreen"
            }
            isFullScreen = !isFullScreen
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
        NavigationHelper.saveDataCallback = null  // 清除回调，避免内存泄漏

    }

    fun showSaveDialog() {
        AlertDialog.Builder(this)
            .setTitle("Save Data")
            .setMessage("Do you want to save the ECG/PPG data before exiting?")
            .setPositiveButton("Save") { _, _ ->
                saveData()
                finish();//结束ECGActivity
            }
            .setNegativeButton("Don't Save") { _, _ ->
                finish();
            }
            .setNeutralButton("Cancel", null)  // 不进行任何操作，只关闭对话框
            .show()
    }

    private fun saveData() {
        val path = getExternalFilesDir(null).toString();
//        path = Environment.getExternalStorageDirectory().toString();
        Log.d(TAG, "file save path: $path");
        val sdf = SimpleDateFormat("yyyy_MMdd_HH:mm:ss")
        val resultdate = Date(System.currentTimeMillis())
        Utils.saveSamples(ecgSamples,path,sdf.format(resultdate)+"_ecg_samples_"+ecgSR+".txt");
        Utils.saveSamples(ppgSamples,path,sdf.format(resultdate)+"_ppg_samples_"+ppgSR+".txt");
    }

    override fun onBackPressed() {
        showSaveDialog()  // 弹出保存对话框
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

    private var ppgPlotterSize = ppgSR*5
    private var ecgPlotterSize = ecgSR*5


    private fun plotPPG(samples: List<PolarPpgData.PolarPpgSample>){
        try {
            Log.d(TAG, "PPG data available ${samples.size} Thread:${Thread.currentThread()}")
            if (!isSynchronized) {
                isSynchronized = true
                startTimestamp = System.currentTimeMillis()
                runOnUiThread {
                    Toast.makeText(applicationContext, "Synchronized", Toast.LENGTH_SHORT).show()
                }

                for (data in samples) {
                    val value = data.channelSamples[0].toDouble()
                    ppgPlotter.sendSingleSampleWithoutUpdate(value)
                }
                ppgPlotter.update()
                return
            }
            for (data in samples) {
                val value = data.channelSamples[0].toDouble()
                ppgSamples.add(value)
            }

            //synchronized plotting
            //freeze state
            val ecgSize = ecgSamples.size
            val ppgSize = ppgSamples.size
            val timestamp = System.currentTimeMillis()

            if (ecgSize < ecgPlotterSize || ppgSize < ppgPlotterSize) {
                for (data in samples) {
                    val value = data.channelSamples[0].toDouble()
                    ppgPlotter.sendSingleSampleWithoutUpdate(value)
                }
                ppgPlotter.update()
                ecgPlotter.sendSamples(ecgSamples.toDoubleArray())
                return
            }
            val ecgLen = ecgSize / ecgSR.toDouble()
            val ppgLen = ppgSize / ppgSR.toDouble()
            val stopLen = Math.min(ppgLen, ecgLen)
            val ppgStopIdx = (ppgSR * stopLen).toInt() - 1
            val ecgStopIdx = (ecgSR * stopLen).toInt() - 1

            val ecgPast = DoubleArray(ecgPlotterSize)
            var idx = 0
            for (i in ecgStopIdx - ecgPlotterSize until ecgStopIdx) {
                ecgPast[idx++] = ecgSamples[i]
            }
            var ecgRes = ButterworthBandpassFilter.concatenate(ecgPast)
            ecgRes = ButterworthBandpassFilter.ppg55hzBandpassFilter(ecgRes)
            ecgRes = ButterworthBandpassFilter.trimSamples(ecgRes, ecgPlotterSize / 2)


            val ppgPast = DoubleArray(this.ppgPlotterSize)
            idx = 0
            for (i in ppgStopIdx - this.ppgPlotterSize until ppgStopIdx) {
                ppgPast[idx++] = ppgSamples[i]
            }
            var ppgRes = ButterworthBandpassFilter.concatenate(ppgPast)
            ppgRes = ButterworthBandpassFilter.ppg55hzBandpassFilter(ppgRes)
            ppgRes = ButterworthBandpassFilter.trimSamples(ppgRes, this.ppgPlotterSize / 2)

            ppgPlotter.sendSamples(ppgRes)
            ecgPlotter.sendSamples(ecgRes)

            runOnUiThread {
                textViewInfo.text = String.format(
                    "Filtering...\nDuration: %d sec" +
                            "\nActual Average PPG Sample Rate: %.2f" +
                            "\nActual Average ECG Sample Rate: %.2f" +
                            "\nECG-PPG Samples Length Diff: %.2f sec",
                    ((System.currentTimeMillis() - startTimestamp) / 1000),
                    ppgSize.toDouble() / ((timestamp - startTimestamp) / 1000),
                    ecgSize.toDouble() / ((timestamp - startTimestamp) / 1000),
                    (ecgLen - ppgLen)
                )
            }
        }catch (e:ConcurrentModificationException){
            e.printStackTrace()
        }
    }


    private fun plotECG(num: Double) {
        if(isSynchronized){
            ecgSamples.add(num)
        }else{
            ecgPlotter.sendSingleSample(num)
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
//                            Log.d(TAG, "HR " + sample.hr)
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



    // 进入全屏模式，隐藏除图表外的所有控件
    private fun enterFullScreenMode() {
        // Hide status bar
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        actionBar?.hide()

        // 隐藏其他控件
        findViewById<View>(R.id.ecgViewHeading).visibility = View.GONE
        findViewById<View>(R.id.hr).visibility = View.GONE
        findViewById<View>(R.id.rr).visibility = View.GONE
        findViewById<View>(R.id.info).visibility = View.GONE
        findViewById<View>(R.id.bottom_navigation).visibility = View.GONE

        val ppgplot = findViewById<XYPlot>(R.id.plot)
        val ecgPlot = findViewById<XYPlot>(R.id.ecg_plot)

        // 使用ConstraintLayout的实际高度来分配空间给XYPlot
        val constraintLayout = findViewById<ConstraintLayout>(R.id.ECGActivity_layout)
        val containerHeight = constraintLayout.width  // 获取ConstraintLayout的高度，而不是宽度

        val plotParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            dpToPx(150)
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = ecgPlot.id
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dpToPx(5)
//            verticalWeight = 1.0f  // 使用权重分配高度
        }

        val ecgPlotParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            dpToPx(120)
        ).apply {
            topToBottom = ppgplot.id
            bottomToTop = R.id.fullscreen_button
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dpToPx(10)  // 增加间距
//            verticalWeight = 1.0f
        }

        ppgplot.layoutParams = plotParams
        ecgPlot.layoutParams = ecgPlotParams
    }



    // 功能：将dp单位转换为px单位
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    // 更新退出全屏模式的代码
    private fun exitFullScreenMode() {
        // Show status bar
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        actionBar?.show()
        // 恢复其他控件的可见性
        findViewById<View>(R.id.ecgViewHeading).visibility = View.VISIBLE
        findViewById<View>(R.id.hr).visibility = View.VISIBLE
        findViewById<View>(R.id.rr).visibility = View.VISIBLE
        findViewById<View>(R.id.info).visibility = View.VISIBLE
        findViewById<View>(R.id.bottom_navigation).visibility = View.VISIBLE

        val plot = findViewById<XYPlot>(R.id.plot)
        val ecgPlot = findViewById<XYPlot>(R.id.ecg_plot)

        // 设置原始布局参数，高度和边距都转换为像素
        val plotParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            dpToPx(150)  // 转换高度值
        ).apply {
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomMargin = dpToPx(264)  // 转换底部边距
        }

        val ecgPlotParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            dpToPx(100)  // 转换高度值
        ).apply {
            topToBottom = R.id.plot
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dpToPx(4)  // 转换顶部边距
        }

        plot.layoutParams = plotParams
        ecgPlot.layoutParams = ecgPlotParams
    }



}

