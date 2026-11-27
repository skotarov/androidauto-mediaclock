package com.kotarov.autoclock.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import com.kotarov.autoclock.MainActivity

/** Host-managed settings entry, separate from media playback. */
class PhoneSettingsService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = PhoneSettingsScreen(carContext)
    }
}

private class PhoneSettingsScreen(context: CarContext) : Screen(context) {
    override fun onGetTemplate(): Template =
        ListTemplate.Builder().setTitle("Settings").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().addItem(
                Row.Builder().setTitle("Open on phone")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        val intent = Intent(carContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        runCatching { carContext.startActivity(intent) }.onFailure {
                            CarToast.makeText(carContext, "Open Travel to Awaken on your phone when parked", CarToast.LENGTH_LONG).show()
                        }
                    }).build()
            ).build()).build()
}
