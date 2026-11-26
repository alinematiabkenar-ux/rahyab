package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.i18n.LocalAppStrings
import com.example.i18n.stringsFor
import com.example.navigation.LaneTargetSide

fun formatLaneSideHint(side: LaneTargetSide, strings: AppStrings): String = when (side) {
    LaneTargetSide.RIGHT -> strings.laneHintUseRightLanes
    LaneTargetSide.LEFT -> strings.laneHintUseLeftLanes
    LaneTargetSide.MIDDLE -> strings.laneHintUseMiddleLanes
}

fun formatCarLaneSideHint(side: LaneTargetSide, language: AppLanguage): String =
    formatLaneSideHint(side, stringsFor(language))

@Composable
fun LaneSideHintBanner(
    side: LaneTargetSide,
    isNightMode: Boolean,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val hintText = formatLaneSideHint(side, strings)
    val icon = when (side) {
        LaneTargetSide.RIGHT -> Icons.Filled.TurnRight
        LaneTargetSide.LEFT -> Icons.Filled.TurnLeft
        LaneTargetSide.MIDDLE -> Icons.Filled.Straight
    }
    val dividerColor = if (isNightMode) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.30f)

    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lane_side_hint_banner")
    ) {
        HorizontalDivider(thickness = 1.dp, color = dividerColor)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = hintText,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ReportWrongLaneFlagButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White.copy(alpha = 0.88f)
        ),
        modifier = modifier
            .size(48.dp)
            .testTag("report_wrong_lane_button")
    ) {
        Icon(
            imageVector = Icons.Filled.Flag,
            contentDescription = strings.reportWrongLaneDesc,
            modifier = Modifier.size(20.dp)
        )
    }
}
