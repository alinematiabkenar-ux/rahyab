package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Water
import androidx.compose.ui.graphics.vector.ImageVector

/** D5: Maps USGS GNIS `feature_class` values to clear Material icons for search rows. */
fun gnisIconForFeatureClass(featureClass: String): ImageVector = when (featureClass.trim().lowercase()) {
    "summit", "gap" -> Icons.Filled.Terrain
    "valley" -> Icons.Filled.Landscape
    "lake", "reservoir", "stream", "falls", "spring", "dam" -> Icons.Filled.Water
    "park" -> Icons.Filled.Park
    "trail" -> Icons.Filled.Hiking
    "bridge" -> Icons.Filled.AltRoute
    "beach", "island" -> Icons.Filled.BeachAccess
    "populated place", "locale" -> Icons.Filled.LocationCity
    else -> Icons.Filled.Place
}
