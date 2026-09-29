package com.xq.hdcwifi

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

object HdcNotifications {

    fun info(content: String) = notify(content, NotificationType.INFORMATION)

    fun warn(content: String) = notify(content, NotificationType.WARNING)

    fun error(content: String) = notify(content, NotificationType.ERROR)

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("HDC Wi-Fi")
            .createNotification("HDC Wi-Fi", content, type)
            .notify(null)
    }
}
