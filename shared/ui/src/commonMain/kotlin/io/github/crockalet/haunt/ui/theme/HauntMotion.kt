package io.github.crockalet.haunt.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * Spring presets (named after Morphlet's: default / smooth / snappy / bouncy). Every Haunt
 * animation uses one of these so motion feels like one system.
 */
object HauntMotion {
    /** Layout and container morphs (cards growing, screens, search pill → search field). */
    fun <T> smooth(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 380f)

    /** Small state changes (selection tints, fades). */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1400f)

    /** Things that should feel physical (press feedback, knob springing back). */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.62f, stiffness = 520f)
}
