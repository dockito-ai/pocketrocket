package com.pocketrocket.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** An icon on the mockup's settings tile: 36 dp, radius 10, #232833, a 20 dp icon in the soft text colour. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(10.dp)).background(PrTheme.colors.iconTile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = PrTheme.colors.textSoft, modifier = Modifier.size(size * 0.55f))
    }
}
