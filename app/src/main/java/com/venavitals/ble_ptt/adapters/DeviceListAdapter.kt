package com.venavitals.ble_ptt.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import com.venavitals.ble_ptt.R


class DeviceListAdapter(context: Context, private val deviceIds: List<String>, private val unpairDevice: (String) -> Unit, private val connectDevice: (String) -> Unit) :
    ArrayAdapter<String>(context, 0, deviceIds) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val deviceId = getItem(position)

        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.paired_device_list_item, parent, false)

        val deviceNameTextView = view.findViewById<TextView>(R.id.device_name)
        val connectButton = view.findViewById<Button>(R.id.connect_button)
        val unpairButton = view.findViewById<Button>(R.id.unpair_button)

        deviceNameTextView.text = deviceId

        connectButton.setOnClickListener {
            connectDevice(deviceId!!)
        }

        unpairButton.setOnClickListener {
            unpairDevice(deviceId!!)
        }

        return view
    }
}
