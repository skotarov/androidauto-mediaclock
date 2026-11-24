package com.kotarov.autoclock.media

enum class ClockFamily(val id: String, val label: String) {
    ANALOG("analog", "Аналогов часовник"),
    DIGITAL("digital", "Цифров часовник");
    companion object {
        fun fromId(id: String?): ClockFamily? = entries.firstOrNull { it.id == id }
    }
}

enum class ClockPreset(val id: String, val family: ClockFamily, val usesWeather: Boolean) {
    ANALOG_PURE("analog_pure", ClockFamily.ANALOG, false),
    ANALOG_WEATHER("analog_weather", ClockFamily.ANALOG, true),
    DIGITAL_TIME("digital_time", ClockFamily.DIGITAL, false),
    DIGITAL_TIME_WEATHER("digital_time_weather", ClockFamily.DIGITAL, true);

    val hasAnalog: Boolean get() = family == ClockFamily.ANALOG

    companion object {
        const val KEY_FAMILY = "display_family"
        const val KEY_ADD_WEATHER = "add_weather_both_clocks"

        // Legacy IDs are migration aliases only, not separate render modes.
        fun fromId(id: String?): ClockPreset = when (id) {
            "digital", "clock", "digital_analog" -> DIGITAL_TIME
            "digital_analog_weather" -> DIGITAL_TIME_WEATHER
            "both", "analog_time_weather", "weather", "weather_only",
            "weather_with_time", "weather_analog", "weather_analog_time" -> ANALOG_WEATHER
            else -> entries.firstOrNull { it.id == id } ?: ANALOG_PURE
        }
    }
}
