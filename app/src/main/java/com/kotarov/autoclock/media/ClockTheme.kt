package com.kotarov.autoclock.media

enum class ClockTheme(val id: String) {
    DARK("dark"), LIGHT("light");

    companion object {
        const val KEY = "clock_theme"
        fun fromId(id: String?): ClockTheme = entries.firstOrNull { it.id == id } ?: DARK
    }
}
