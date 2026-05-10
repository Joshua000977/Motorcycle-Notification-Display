package com.example.motonotify1.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.motonotify1.BleManagerProvider

class NotificationService : NotificationListenerService(){

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName

        if(packageName != "com.whatsapp"){
            return;
        }

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?:return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        val message = "WA: $title: $text"

        BleManagerProvider.bleManager.log(message);
        BleManagerProvider.bleManager.sendText(message)
    }
}