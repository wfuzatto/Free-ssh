package br.com.freessh

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The terminal shortcut row already calculates the correct IME displacement,
 * but the original modifier order applied that offset inside the row after its
 * background. That moved the buttons while leaving the row/background in its
 * old position, producing the large empty strip above the Android keyboard.
 *
 * MainActivity uses a star import for foundation.layout, so this same-package
 * extension intentionally takes precedence for the offset call. It prepends
 * the translation to the existing modifier chain, moving the complete row
 * (background + buttons) as a single unit.
 */
fun Modifier.offset(x: Dp = 0.dp, y: Dp = 0.dp): Modifier {
    val existing = this
    val translation = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(x.roundToPx(), y.roundToPx())
        }
    }
    return translation.then(existing)
}
