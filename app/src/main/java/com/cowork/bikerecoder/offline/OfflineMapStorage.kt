package com.cowork.bikerecoder.offline

/** What the settings screen needs to know about, and do with, the map data stored for trips. */
interface OfflineMapStorage {
    /** Total size in bytes of all offline map regions this app created for trips. */
    suspend fun totalBytes(): Long

    /**
     * Cancels running map downloads and deletes every offline map region this app created for trips,
     * together with its reference. Throws if some region could not be deleted (the others are still removed).
     */
    suspend fun deleteAll()
}
