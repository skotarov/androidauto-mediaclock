package com.kotarov.autoclock.car

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import com.kotarov.autoclock.MainActivity

/** A user-requested launch on the phone display, never an unlock or overlay. */
internal object PhonePanelLauncher {
    fun open(context: Context) {
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
            Log.i("TravelToAwaken", "Phone panel request skipped: phone is locked")
            return
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = if (Build.VERSION.SDK_INT >= 26)
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        else null
        runCatching { context.startActivity(intent, options) }
            .onSuccess { Log.i("TravelToAwaken", "Phone panel launch requested (host/OS decides)") }
            .onFailure { Log.w("TravelToAwaken", "Phone panel launch failed", it) }
    }
}
