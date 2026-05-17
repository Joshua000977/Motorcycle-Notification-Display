package com.example.motonotify1.service.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.example.motonotify1.BleManager.BleManagerProvider

class CallStateReceiver : BroadcastReceiver() {
    private var lastCallMessage = ""
    private var lastCallTime = 0L
    override fun onReceive(context: Context, intent : Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        if(state == TelephonyManager.EXTRA_STATE_RINGING){
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
            val contactName = getContactName(context, number)
            val caller = contactName ?: number
            val message = "C:$caller"
            val now = System.currentTimeMillis()

            if (
                message == lastCallMessage &&
                now - lastCallTime < 5000
            ) {
                return
            }

            lastCallMessage = message
            lastCallTime = now
            BleManagerProvider.bleManager.log(message)
            BleManagerProvider.bleManager.sendText(message)
        }

    }

    private fun getContactName(context: Context, phoneNumber: String):String?{
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber))

        val cursor = context.contentResolver.query(uri, arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME
        ), null, null, null)
        cursor?.use{
            if(it.moveToFirst()){
                return it.getString(0)
            }
        }

        return null

    }
}