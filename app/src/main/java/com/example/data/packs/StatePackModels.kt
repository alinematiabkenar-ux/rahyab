package com.example.data.packs

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Single chunk of a large GitHub Release asset that exceeded the 2 GiB asset limit
 * and was split automatically into `.part001`, `.part002`, etc. (Phase 5).
 */
@JsonClass(generateAdapter = true)
data class PackFilePart(
    val filename: String,
    val url: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = ""
)

@JsonClass(generateAdapter = true)
data class PackFileEntry(
    val filename: String,
    val url: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val parts: List<PackFilePart> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StateManifestEntry(
    val stateCode: String,
    val name: String,
    val version: String,
    val buildVersion: String = "",
    val parentStateCode: String? = null,
    val totalSizeBytes: Long,
    val pmtiles: PackFileEntry,
    val valhalla: PackFileEntry,
    val places: PackFileEntry
) {
    val effectiveBuildVersion: String
        get() = buildVersion.ifBlank { version }
}

@JsonClass(generateAdapter = true)
data class PackManifest(
    @Json(name = "schemaVersion") val schemaVersion: Int = 2,
    val generatedAt: String = "",
    val buildVersion: String = "",
    val baseUrl: String = "",
    val states: List<StateManifestEntry> = emptyList()
)

data class StatePackUiItem(
    val stateCode: String,
    val abbr: String,
    val nameEn: String,
    val nameFa: String,
    val parentStateCode: String? = null,
    val status: com.example.data.local.PackInstallStatus,
    val installedVersion: String,
    val installedBuildVersion: String = "",
    val availableVersion: String,
    val totalSizeBytes: Long,
    val downloadedBytes: Long,
    val installedBytesOnDisk: Long,
    val hasPmtiles: Boolean,
    val hasValhalla: Boolean,
    val hasPlacesDb: Boolean,
    val hasManifestEntry: Boolean,
    val isCurrentLocationState: Boolean = false,
    val isQueued: Boolean = false,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalSizeBytes > 0L) (downloadedBytes.toFloat() / totalSizeBytes.toFloat()).coerceIn(0f, 1f) else 0f
}
