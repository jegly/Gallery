package com.gal.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * Spring-based motion specs following M3 Expressive's motion-physics system (May 2025).
 *
 * `MaterialExpressiveTheme` and `MotionScheme.expressive` are still internal in
 * androidx.compose.material3 1.4.0 (BOM 2026.03.01), so we recreate the spec set by
 * instantiating springs with the published Expressive damping/stiffness values directly.
 *
 * **Spatial** springs overshoot and bounce — use for movement, position, scale, rotation,
 * corner radius. They mirror real-world object physics so motion feels alive.
 *
 * **Effects** springs are critically damped — use for color, opacity, elevation. Overshoot
 * on these properties looks like a glitch, not motion.
 *
 * Speed pick:
 * - `default` — most cases
 * - `fast`    — small or peripheral elements (switches, chips, button presses)
 * - `slow`    — large or full-screen transitions
 *
 * Numbers from material.io/design/motion (Expressive motion tokens).
 */
object Motion {

    // ── Spatial (springs that overshoot naturally) ───────────────────────────

    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.8f,
        stiffness    = 380f,
    )

    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.6f,
        stiffness    = 800f,
    )

    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.8f,
        stiffness    = 200f,
    )

    // ── Effects (critically damped — no overshoot) ───────────────────────────

    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 1.0f,
        stiffness    = 1600f,
    )

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 1.0f,
        stiffness    = 3800f,
    )

    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 1.0f,
        stiffness    = 800f,
    )
}
