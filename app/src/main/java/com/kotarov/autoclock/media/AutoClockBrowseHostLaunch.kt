package com.kotarov.autoclock.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Test 1: host browse deep link, documented for Android Automotive OS.
 * Android Auto phone projection may not provide such a system handler.
 * Only a user click or host-invoked session activity can launch it.
 */
object AutoClockBrowseHostLaunch {
    const val ACTION_MEDIA_TEMPLATE_V2 =
        "androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V2"
    const val EXTRA_KEY_MEDIA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT"
    const val EXTRA_KEY_MEDIA_ID = "androidx.car.app.mediaextensions.extra.KEY_MEDIA_ID"
    enum class Outcome { SENT, UNSUPPORTED, FAILED }

    fun targetIntent(context: Context): Intent =
        Intent(ACTION_MEDIA_TEMPLATE_V2).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(EXTRA_KEY_MEDIA_COMPONENT,
                ComponentName(context, AutoClockMediaService::class.java).flattenToString())
            putExtra(EXTRA_KEY_MEDIA_ID, AutoClockMediaService.ROOT_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun supportsSystemHost(context: Context): Boolean = try {
        context.packageManager.queryIntentActivities(
            Intent(ACTION_MEDIA_TEMPLATE_V2),
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        ).isNotEmpty()
    } catch (error: RuntimeException) {
        Log.w("AutoClockBrowseTest", "Cannot query media host", error)
        false
    }

    fun tryOpen(context: Context): Outcome {
        if (!supportsSystemHost(context)) return Outcome.UNSUPPORTED
        return try {
            context.startActivity(targetIntent(context))
            // Dispatched intent != confirmed rendering by Android Auto.
            Outcome.SENT
        } catch (error: ActivityNotFoundException) {
            Log.w("AutoClockBrowseTest", "No media host handler", error)
            Outcome.UNSUPPORTED
        } catch (error: RuntimeException) {
            Log.w("AutoClockBrowseTest", "Media host refused browse intent", error)
            Outcome.FAILED
        }
    }
}

class AutoClockBrowseTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled = getSharedPreferences(
            AutoClockMediaService.PREFERENCES, Context.MODE_PRIVATE
        ).getBoolean(AutoClockMediaService.KEY_TEST_DIRECT_BROWSE, false)
        if (enabled) {
            val outcome = AutoClockBrowseHostLaunch.tryOpen(this)
            val message = when (outcome) {
                AutoClockBrowseHostLaunch.Outcome.SENT ->
                    "Изпратена заявка за почасовия списък"
                AutoClockBrowseHostLaunch.Outcome.UNSUPPORTED ->
                    "Не се поддържа директно отваряне на списъка"
                AutoClockBrowseHostLaunch.Outcome.FAILED ->
                    "Системният хост отказа отварянето"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
