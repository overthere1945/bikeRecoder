package com.cowork.bikerecoder.share

/**
 * One file handed to another app (spec §3.2, §5): its content [uri] (a card in the share cache through the FileProvider,
 * or a gallery item), its [mimeType] and its size in bytes ([sizeBytes]; null: unknown).
 */
data class ShareFile(val uri: String, val mimeType: String, val sizeBytes: Long?)
