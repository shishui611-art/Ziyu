package io.github.xgl34222220.ziyu.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Stable
internal class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
    var direction by mutableFloatStateOf(1f)
    var reset: Job? = null
}

/** Commit navigation only after the gesture flow completes; cancellation restores the page. */
@Composable
internal fun rememberPredictiveBack(
    enabled: Boolean,
    animateGesture: Boolean,
    onBack: suspend (gesture: Boolean) -> Unit,
): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onBack)
    val currentlyEnabled by rememberUpdatedState(enabled)
    val predictive = animateGesture && Build.VERSION.SDK_INT >= 34
    BackHandler(enabled && !predictive) { scope.launch { callback(false) } }
    PredictiveBackHandler(enabled && predictive) { events ->
        state.reset?.cancelAndJoin()
        if (!currentlyEnabled) {
            events.collect { }
        } else try {
            events.collect { event ->
                state.active = true
                state.direction = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                state.progress = event.progress.coerceIn(0f, 1f)
            }
            val hadGesture = state.active
            if (hadGesture) animate(state.progress, 1f, animationSpec = tween(100)) { value, _ -> state.progress = value }
            callback(hadGesture)
            state.progress = 0f
            state.active = false
        } catch (cancelled: CancellationException) {
            state.reset = scope.launch {
                try {
                    animate(state.progress, 0f, animationSpec = tween(160)) { value, _ -> state.progress = value }
                } finally {
                    state.active = false
                    state.progress = 0f
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            state.active = false
            state.progress = 0f
            throw error
        }
    }
    return state
}

internal fun Modifier.predictiveBackMotion(state: PredictiveBackState): Modifier = graphicsLayer {
    val progress = state.progress
    scaleX = 1f - .08f * progress
    scaleY = scaleX
    translationX = state.direction * size.width * .16f * progress
    alpha = 1f - progress * progress
    shape = RoundedCornerShape(28.dp * progress)
    clip = state.active
}

/** Destination previews are presentation only: neither touch nor accessibility can trigger actions. */
internal fun Modifier.backDestinationPreview(): Modifier = clearAndSetSemantics { }.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
