package com.example.motonotify1.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.motonotify1.BleManager.BleManagerProvider

class NotificationService : NotificationListenerService(){

    private var lastMessage = ""
    private var lastMessageTime = 0L
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName



        if(packageName != "com.whatsapp"){
            return;
        }

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?:return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        val message = "WA:$title:$text"


        val now = System.currentTimeMillis()
        if(message == lastMessage && now-lastMessageTime < 3000){
            return
        }
        lastMessage = message
        lastMessageTime = now
        BleManagerProvider.bleManager.log(message)
        BleManagerProvider.bleManager.sendText(message)

    }
}