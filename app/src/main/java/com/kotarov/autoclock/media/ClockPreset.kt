package com.kotarov.autoclock.media

/** The main style is exclusive; the two add-ons for that style are independent. */
enum class ClockFamily(val id: String, val label: String) {
    ANALOG("analog", "Аналогов часовник"),
    DIGITAL("digital", "Цифров часовник"),
    WEATHER("weather", "Времето");

    companion object {
        fun fromId(id: String?): ClockFamily? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Every main style supports zero, one or two checked additions. This yields
 * four explicit choices per family, including a truly clean version of each.
 */
enum class ClockPreset(
    val id: String,
    val family: ClockFamily,
    val label: String,
    val queueId: Long,
    val hasAnalog: Boolean,
    val hasDigitalTime: Boolean,
    val usesWeather: Boolean
) {
    ANALOG_PURE("analog_pure", ClockFamily.ANALOG, "Само аналогов часовник", 1, true, false, false),
    ANALOG_TIME("analog_time", ClockFamily.ANALOG, "Аналогов + цифров час", 2, true, true, false),
    ANALOG_WEATHER("analog_weather", ClockFamily.ANALOG, "Аналогов + време", 3, true, false, true),
    ANALOG_TIME_WEATHER("analog_time_weather", ClockFamily.ANALOG, "Аналогов + час и време", 4, true, true, true),

    DIGITAL_TIME("digital_time", ClockFamily.DIGITAL, "Само цифров час", 5, false, true, false),
    DIGITAL_TIME_WEATHER("digital_time_weather", ClockFamily.DIGITAL, "Цифров + време", 6, false, true, true),
    DIGITAL_ANALOG("digital_analog", ClockFamily.DIGITAL, "Цифров + аналогов", 7, true, true, false),
    DIGITAL_ANALOG_WEATHER("digital_analog_weather", ClockFamily.DIGITAL, "Цифров + аналогов и време", 8, true, true, true),

    WEATHER_ONLY("weather_only", ClockFamily.WEATHER, "Само времето", 9, false, false, true),
    WEATHER_WITH_TIME("weather_with_time", ClockFamily.WEATHER, "Времето + цифров час", 10, false, true, true),
    WEATHER_ANALOG("weather_analog", ClockFamily.WEATHER, "Времето + аналогов", 11, true, false, true),
    WEATHER_ANALOG_TIME("weather_analog_time", ClockFamily.WEATHER, "Времето + аналогов и цифров", 12, true, true, true);

    companion object {
        const val KEY_FAMILY = "display_family"
        const val KEY_ANALOG_ADD_DIGITAL = "analog_add_digital"
        const val KEY_ANALOG_ADD_WEATHER = "analog_add_weather"
        const val KEY_DIGITAL_ADD_ANALOG = "digital_add_analog"
        const val KEY_DIGITAL_ADD_WEATHER = "digital_add_weather"
        const val KEY_WEATHER_ADD_ANALOG = "weather_add_analog"
        const val KEY_WEATHER_ADD_DIGITAL = "weather_add_digital"
        const val KEY_SCHEMA_V11 = "display_addons_v11"

        // Used only to migrate the v0.10 radio-mode details.
        const val KEY_OLD_ANALOG_DETAIL = "analog_detail"
        const val KEY_OLD_DIGITAL_DETAIL = "digital_detail"
        const val KEY_OLD_WEATHER_DETAIL = "weather_detail"

        fun options(family: ClockFamily): List<ClockPreset> = entries.filter { it.family == family }

        fun fromId(id: String?): ClockPreset = when (id) {
            "analog" -> ANALOG_PURE
            "digital", "clock" -> DIGITAL_TIME
            "weather" -> WEATHER_ONLY
            "both" -> ANALOG_TIME_WEATHER
            else -> entries.firstOrNull { it.id == id } ?: ANALOG_PURE
        }

        fun fromAddons(
            family: ClockFamily,
            first: Boolean = false,
            second: Boolean = false
        ): ClockPreset {
            // For each family, bits correspond to its two visible checkboxes.
            return when (family) {
                ClockFamily.ANALOG -> when {
                    first && second -> ANALOG_TIME_WEATHER
                    first -> ANALOG_TIME
                    second -> ANALOG_WEATHER
                    else -> ANALOG_PURE
                }
                ClockFamily.DIGITAL -> when {
                    first && second -> DIGITAL_ANALOG_WEATHER
                    first -> DIGITAL_ANALOG
                    second -> DIGITAL_TIME_WEATHER
                    else -> DIGITAL_TIME
                }
                ClockFamily.WEATHER -> when {
                    first && second -> WEATHER_ANALOG_TIME
                    first -> WEATHER_ANALOG
                    second -> WEATHER_WITH_TIME
                    else -> WEATHER_ONLY
                }
            }
        }

        fun firstKey(family: ClockFamily): String = when (family) {
            ClockFamily.ANALOG -> KEY_ANALOG_ADD_DIGITAL
            ClockFamily.DIGITAL -> KEY_DIGITAL_ADD_ANALOG
            ClockFamily.WEATHER -> KEY_WEATHER_ADD_ANALOG
        }

        fun secondKey(family: ClockFamily): String = when (family) {
            ClockFamily.ANALOG -> KEY_ANALOG_ADD_WEATHER
            ClockFamily.DIGITAL -> KEY_DIGITAL_ADD_WEATHER
            ClockFamily.WEATHER -> KEY_WEATHER_ADD_DIGITAL
        }
    }
}
