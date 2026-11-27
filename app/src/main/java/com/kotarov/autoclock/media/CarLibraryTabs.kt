package com.kotarov.autoclock.media

/** Car tabs are a projection of the library, not a filter on its contents. */
internal object CarLibraryTabs {
    const val MAX_TABS = 4
    const val LIBRARY_ID = "car_full_library_v144"
    private const val ROOT_PREFIX = "car_tabs_v144:"
    fun rootId(limit: Int): String = ROOT_PREFIX + limit.coerceIn(1, MAX_TABS)
    fun rootLimit(id: String): Int? =
        if (id.startsWith(ROOT_PREFIX)) id.removePrefix(ROOT_PREFIX).toIntOrNull()?.takeIf { it in 1..MAX_TABS }
        else null

    fun eligible(station: StreamLibrary.Station): Boolean =
        station.browsable || (station.textFile && BookNavigation.mayHaveContents(station.name))

    fun pinned(stations: List<StreamLibrary.Station>, limit: Int): List<StreamLibrary.Station> =
        stations.filter { it.pinned && eligible(it) }.take(limit.coerceIn(1, MAX_TABS) - 1)

    fun toggle(stations: List<StreamLibrary.Station>, id: String): List<StreamLibrary.Station>? {
        val station = stations.firstOrNull { it.id == id } ?: return stations
        if (!eligible(station)) return stations
        if (!station.pinned && pinned(stations, MAX_TABS).size >= MAX_TABS - 1) return null
        return stations.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
    }
}
