package com.pocketrocket.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketrocket.R
import com.pocketrocket.ui.theme.Brand

/** The launcher icon's rocket on its navy tile, for brand moments (welcome, empty states). */
@Composable
fun RocketMark(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brand.Navy),
        contentAlignment = Alignment.Center,
    ) {
        // The vector is drawn for a 108dp adaptive icon canvas; scale it so the rocket fills the tile.
        Image(painterResource(R.drawable.ic_pocketrocket_foreground), contentDescription = null, modifier = Modifier.size(size * 1.6f))
    }
}
