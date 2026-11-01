package com.thelightphone.sdk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.thelightphone.sdk.ContextLightVibrator

/**
 * Will be periodically set based on server preferences, see:
 * [com.thelightphone.sdk.shared.LightServiceMethod.GetUserPreferences].
 */
val LocalHapticsEnabled = compositionLocalOf { false }

/**
 * Makes content clickable without displaying a visual press indication.
 */
fun Modifier.lightClickable(
    enabled: Boolean = true,
    // will be &&'ed with the user's global haptics preference
    hapticsEnabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val userHapticsEnabled = LocalHapticsEnabled.current
    val context = LocalContext.current
    val performHaptic = enabled && hapticsEnabled && userHapticsEnabled
    pointerInput(performHaptic) {
        if (!performHaptic) return@pointerInput
        awaitEachGesture {
            // Fire on finger-down like LightOS
            awaitFirstDown(requireUnconsumed = false)
            ContextLightVibrator(context).click()
        }
    }.clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

/**
 * Tap and long-press on the same target, without a press indication, matching
 * [lightClickable]. Long-press fires haptics itself rather than on finger-down,
 * so a short tap does not buzz twice.
 */
fun Modifier.lightTapAndLongPress(
    enabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    role: Role? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = composed {
    val userHapticsEnabled = LocalHapticsEnabled.current
    val context = LocalContext.current
    val performHaptic = enabled && hapticsEnabled && userHapticsEnabled
    combinedClickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        role = role,
        onLongClick = {
            if (performHaptic) ContextLightVibrator(context).click()
            onLongPress()
        },
        onClick = onTap,
    )
}
