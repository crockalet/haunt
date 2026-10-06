package io.github.crockalet.haunt.ui.platform

import androidx.compose.runtime.Composable

/** System back handling (Android back gesture); no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
