package com.kotarov.autoclock.media

/** Car tabs are a projection of the library, not a filter on its contents. */
internal object CarLibraryTabs {
    const val MAX_TABS = 4
    const val LIBRARY_ID = "car_full_library_v144"
    const val KEY_ALERT_PIN = "car_alert_pin_v147"
    const val ALERT_TAB = "car_alert_tab_v147"
    private const val SOURCE_PREFIX = "car_source_tab_v147:"
    private const val ROOT_PREFIX = "car_tabs_v144:"
    fun rootId(limit: Int): String = ROOT_PREFIX + limit.coerceIn(1, MAX_TABS)
    fun rootLimit(id: String): Int? =
        if (id.startsWith(ROOT_PREFIX)) id.removePrefix(ROOT_PREFIX).toIntOrNull()?.takeIf { it in 1..MAX_TABS }
        else null

    fun eligible(station: StreamLibrary.Station): Boolean = station.id.isNotBlank()
    fun browsable(station: StreamLibrary.Station): Boolean =
        station.browsable || (station.textFile && BookNavigation.mayHaveContents(station.name))
    fun sourceTab(id: String): String = SOURCE_PREFIX + id
    fun sourceId(tab: String): String? =
        tab.takeIf { it.startsWith(SOURCE_PREFIX) }?.removePrefix(SOURCE_PREFIX)?.takeIf { it.isNotBlank() }

    fun pinned(stations: List<StreamLibrary.Station>, limit: Int, alertPinned: Boolean = false): List<StreamLibrary.Station> =
        stations.filter { it.pinned && eligible(it) }.take((limit.coerceIn(1, MAX_TABS) - 1 - if (alertPinned) 1 else 0).coerceAtLeast(0))

    fun toggle(stations: List<StreamLibrary.Station>, id: String, alertPinned: Boolean = false): List<StreamLibrary.Station>? {
        val station = stations.firstOrNull { it.id == id } ?: return stations
        if (!eligible(station)) return stations
        if (!station.pinned && stations.count { it.pinned } + (if (alertPinned) 1 else 0) >= MAX_TABS - 1) return null
        return stations.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
    }
}
