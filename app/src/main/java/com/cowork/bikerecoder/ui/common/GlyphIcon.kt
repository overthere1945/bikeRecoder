package com.cowork.bikerecoder.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics

/** Text glyph used as an icon (no icon-library dependency); [description] is read by TalkBack. */
@Composable
fun GlyphIcon(glyph: String, description: String?, modifier: Modifier = Modifier) {
    Text(
        glyph,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.semantics {
            if (description != null) contentDescription = description else invisibleToUser()
        },
    )
}
