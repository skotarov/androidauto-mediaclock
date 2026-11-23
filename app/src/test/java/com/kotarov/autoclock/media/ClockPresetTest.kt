package com.kotarov.autoclock.media

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockPresetTest {
    @Test fun eachFamilyHasFourCheckboxStatesIncludingPure() {
        assertEquals(3, ClockFamily.entries.size)
        for (family in ClockFamily.entries) {
            assertEquals(4, ClockPreset.options(family).size)
            assertEquals(4, (listOf(false, true).flatMap { a ->
                listOf(false, true).map { b -> ClockPreset.fromAddons(family, a, b) }
            }).toSet().size)
        }
    }

    @Test fun pureModesHaveNoExtraContent() {
        val analog = ClockPreset.fromAddons(ClockFamily.ANALOG)
        assertEquals(ClockPreset.ANALOG_PURE, analog)
        assertTrue(analog.hasAnalog)
        assertFalse(analog.hasDigitalTime)
        assertFalse(analog.usesWeather)

        val digital = ClockPreset.fromAddons(ClockFamily.DIGITAL)
        assertEquals(ClockPreset.DIGITAL_TIME, digital)
        assertFalse(digital.hasAnalog)
        assertTrue(digital.hasDigitalTime)
        assertFalse(digital.usesWeather)

        val weather = ClockPreset.fromAddons(ClockFamily.WEATHER)
        assertEquals(ClockPreset.WEATHER_ONLY, weather)
        assertFalse(weather.hasAnalog)
        assertFalse(weather.hasDigitalTime)
        assertTrue(weather.usesWeather)
    }

    @Test fun checkboxesAreIndependentForEachFamily() {
        assertEquals(ClockPreset.ANALOG_TIME,
            ClockPreset.fromAddons(ClockFamily.ANALOG, first = true))
        assertEquals(ClockPreset.ANALOG_WEATHER,
            ClockPreset.fromAddons(ClockFamily.ANALOG, second = true))
        assertEquals(ClockPreset.ANALOG_TIME_WEATHER,
            ClockPreset.fromAddons(ClockFamily.ANALOG, first = true, second = true))

        assertEquals(ClockPreset.DIGITAL_ANALOG,
            ClockPreset.fromAddons(ClockFamily.DIGITAL, first = true))
        assertEquals(ClockPreset.DIGITAL_TIME_WEATHER,
            ClockPreset.fromAddons(ClockFamily.DIGITAL, second = true))
        assertEquals(ClockPreset.DIGITAL_ANALOG_WEATHER,
            ClockPreset.fromAddons(ClockFamily.DIGITAL, first = true, second = true))

        assertEquals(ClockPreset.WEATHER_ANALOG,
            ClockPreset.fromAddons(ClockFamily.WEATHER, first = true))
        assertEquals(ClockPreset.WEATHER_WITH_TIME,
            ClockPreset.fromAddons(ClockFamily.WEATHER, second = true))
        assertEquals(ClockPreset.WEATHER_ANALOG_TIME,
            ClockPreset.fromAddons(ClockFamily.WEATHER, first = true, second = true))
    }

    @Test fun activeAnalogSelectionHonorsBothSavedCheckboxes() {
        fun prefs(digital: Boolean, weather: Boolean): SharedPreferences {
            val flags = mapOf(
                ClockPreset.KEY_ANALOG_ADD_DIGITAL to digital,
                ClockPreset.KEY_ANALOG_ADD_WEATHER to weather
            )
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getBoolean" -> flags[args!![0] as String] ?: args[1] as Boolean
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        }

        assertEquals(ClockPreset.ANALOG_PURE,
            ClockSelectionStore.forFamily(prefs(false, false), ClockFamily.ANALOG))
        assertEquals(ClockPreset.ANALOG_TIME,
            ClockSelectionStore.forFamily(prefs(true, false), ClockFamily.ANALOG))
        assertEquals(ClockPreset.ANALOG_WEATHER,
            ClockSelectionStore.forFamily(prefs(false, true), ClockFamily.ANALOG))
        assertEquals(ClockPreset.ANALOG_TIME_WEATHER,
            ClockSelectionStore.forFamily(prefs(true, true), ClockFamily.ANALOG))
    }

    @Test fun analogStartsWithoutDigitalTimeWhenNoPreferenceIsSaved() {
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> args!![1] as Boolean
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
        assertEquals(ClockPreset.ANALOG_PURE,
            ClockSelectionStore.forFamily(prefs, ClockFamily.ANALOG))
    }

    @Test fun legacyForcedOnDigitalIsTurnedOffOnceAndLaterChoiceIsPreserved() {
        val values = mutableMapOf<String, Any>(
            ClockPreset.KEY_SCHEMA_V11 to true,
            "display_simplified_v13" to true,
            ClockPreset.KEY_ANALOG_ADD_DIGITAL to true
        )
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    values[args!![0] as String] = args[1] as Boolean
                    proxy
                }
                "apply" -> null
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args!![0] as String] as? Boolean ?: args[1] as Boolean
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        assertFalse(values[ClockPreset.KEY_ANALOG_ADD_DIGITAL] as Boolean)
        values[ClockPreset.KEY_ANALOG_ADD_DIGITAL] = true
        ClockSelectionStore.migrateSimplifiedIfNeeded(prefs)
        assertTrue(values[ClockPreset.KEY_ANALOG_ADD_DIGITAL] as Boolean)
    }

    @Test fun allTwelveCombinationsHaveStableUniqueIds() {
        assertEquals(12, ClockPreset.entries.size)
        assertEquals(12, ClockPreset.entries.map { it.id }.toSet().size)
        assertEquals(12, ClockPreset.entries.map { it.queueId }.toSet().size)
        ClockPreset.entries.forEach { assertEquals(it, ClockPreset.fromId(it.id)) }
    }

    @Test fun oldMediaIdsHaveSafeMappings() {
        assertEquals(ClockPreset.ANALOG_PURE, ClockPreset.fromId("analog"))
        assertEquals(ClockPreset.DIGITAL_TIME, ClockPreset.fromId("digital"))
        assertEquals(ClockPreset.WEATHER_ONLY, ClockPreset.fromId("weather"))
        assertEquals(ClockPreset.ANALOG_TIME_WEATHER, ClockPreset.fromId("both"))
    }
}
