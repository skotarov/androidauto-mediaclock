package com.kotarov.autoclock.media

/** All fields that can change the displayed media metadata. */
internal data class MediaPublicationKey(val artwork: String, val title: String, val subtitle: String, val sourceId: String = "")
