package com.kotarov.autoclock.media

/**
 * One primary visual style plus one optional information arrangement.
 * Pure Kotlin so the view hierarchy and selection rules are unit-testable.
 */
enum class ClockFamily(val id: String, val label: String) {
    ANALOG("analog", "Аналогов часовник"),
    DIGITAL("digital", "Цифров часовник"),
    WEATHER("weather", "Времето");

    companion object {
        fun fromId(id: String?): ClockFamily? = entries.firstOrNull { it.id == id }
    }
}

enum class ClockPreset(
    val id: String,
    val family: ClockFamily,
    val detail: String,
    val label: String,
    val queueId: Long,
    val usesWeather: Boolean
) {
    ANALOG_TIME("analog_time", ClockFamily.ANALOG, "time", "Аналогов · час", 1L, false),
    ANALOG_TIME_WEATHER("analog_time_weather", ClockFamily.ANALOG, "time_weather", "Аналогов · час и време", 2L, true),
    ANALOG_WEATHER("analog_weather", ClockFamily.ANALOG, "weather", "Аналогов · само време", 3L, true),

    DIGITAL_TIME("digital_time", ClockFamily.DIGITAL, "time", "Цифров · час", 4L, false),
    DIGITAL_TIME_WEATHER("digital_time_weather", ClockFamily.DIGITAL, "time_weather", "Цифров · час и време", 5L, true),

    WEATHER_ONLY("weather_only", ClockFamily.WEATHER, "only", "Времето · без часовник", 6L, true),
    WEATHER_WITH_TIME("weather_with_time", ClockFamily.WEATHER, "with_time", "Времето · с цифров час", 7L, true);

    companion object {
        const val KEY_FAMILY = "display_family"
        const val KEY_ANALOG_DETAIL = "analog_detail"
        const val KEY_DIGITAL_DETAIL = "digital_detail"
        const val KEY_WEATHER_DETAIL = "weather_detail"

        fun options(family: ClockFamily): List<ClockPreset> =
            entries.filter { it.family == family }

        fun fromId(id: String?): ClockPreset = when (id) {
            "digital", "clock" -> DIGITAL_TIME
            "analog" -> ANALOG_TIME
            "weather" -> WEATHER_ONLY
            "both" -> ANALOG_TIME_WEATHER
            else -> entries.firstOrNull { it.id == id } ?: ANALOG_TIME
        }

        fun detailKey(family: ClockFamily): String = when (family) {
            ClockFamily.ANALOG -> KEY_ANALOG_DETAIL
            ClockFamily.DIGITAL -> KEY_DIGITAL_DETAIL
            ClockFamily.WEATHER -> KEY_WEATHER_DETAIL
        }

        fun selected(
            familyId: String?,
            analogDetail: String?,
            digitalDetail: String?,
            weatherDetail: String?,
            legacyMode: String? = null
        ): ClockPreset {
            val family = ClockFamily.fromId(familyId)
                ?: return fromId(legacyMode)
            val detail = when (family) {
                ClockFamily.ANALOG -> analogDetail ?: "time"
                ClockFamily.DIGITAL -> digitalDetail ?: "time"
                ClockFamily.WEATHER -> weatherDetail ?: "only"
            }
            return options(family).firstOrNull { it.detail == detail }
                ?: options(family).first()
        }
    }
}
