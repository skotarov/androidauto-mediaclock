package com.kotarov.autoclock.media

internal object FolderNavigation {
    private const val PLAY = "folderplay:"
    private const val RESUME = "foldercontinue:"
    data class Choice(val id: String, val title: String)
    data class Request(val folderId: String, val resume: Boolean)

    fun choices(folderId: String) = listOf(
        Choice(PLAY + folderId, "Play all"),
        Choice(RESUME + folderId, "Continue")
    )

    fun parse(id: String?): Request? {
        val value = id ?: return null
        val resume = value.startsWith(RESUME)
        val prefix = if (resume) RESUME else PLAY
        if (!value.startsWith(prefix)) return null
        val folder = value.removePrefix(prefix)
        return folder.takeIf { it.startsWith("folder:") && it.length > 7 }?.let { Request(it, resume) }
    }

    fun target(ids: List<String>, remembered: String?): String? =
        remembered?.takeIf { it in ids } ?: ids.firstOrNull()
}
