package com.nodaysidle.reverseuno.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "Dismiss" action from the clipboard notification.
 */
class ClipboardDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClipboardNotification.dismiss(context)
    }
}
