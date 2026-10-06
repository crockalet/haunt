package io.github.crockalet.haunt.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import io.github.crockalet.haunt.ui.theme.HauntMotion

/**
 * Scopes for [morph]: the app's `SharedTransitionLayout` and the `AnimatedContent` the current
 * screen lives in. Null outside them (previews, screenshots of a single screen), where [morph] is a no-op.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class MorphScope(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalMorphScope = compositionLocalOf<MorphScope?> { null }

/**
 * Morphs this element into the element with the same [key] on the next screen (like Morphlet's
 * `Tray.Morph` / `morph` trigger): bounds spring from one to the other while the contents cross-fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.morph(key: Any): Modifier {
    val scope = LocalMorphScope.current ?: return this
    return with(scope.shared) {
        this@morph.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = scope.visibility,
            enter = fadeIn(HauntMotion.snappy()),
            exit = fadeOut(HauntMotion.snappy()),
            boundsTransform = MorphBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val MorphBounds = BoundsTransform { _: Rect, _: Rect -> HauntMotion.smooth() }

/** Springy press feedback: shrinks slightly while [interaction] is pressed. Put it first in the chain. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressedScale: Float = 0.94f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, HauntMotion.bouncy(), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
