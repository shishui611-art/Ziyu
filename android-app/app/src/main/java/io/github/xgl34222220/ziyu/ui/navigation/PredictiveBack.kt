package io.github.xgl34222220.ziyu.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
import kotlin.math.abs

@Stable
internal class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
    var committing by mutableStateOf(false)
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
        // An interrupted cancellation keeps its visible position. Its old job must
        // never reset the new gesture to zero from a finally block.
        state.reset?.cancelAndJoin()
        state.reset = null
        val startOffset = state.direction * state.progress
        if (!currentlyEnabled) {
            events.collect { }
        } else try {
            events.collect { event ->
                state.active = true
                val edgeDirection = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                val progress = event.progress.coerceIn(0f, 1f)
                val offset = startOffset * (1f - progress) + edgeDirection * progress
                state.direction = if (offset == 0f) edgeDirection else if (offset > 0f) 1f else -1f
                state.progress = abs(offset)
            }
            val hadGesture = state.active
            if (hadGesture) {
                val remainingMillis = (220f * (1f - state.progress)).roundToInt().coerceIn(80, 220)
                animate(state.progress, 1f, animationSpec = tween(remainingMillis, easing = LinearOutSlowInEasing)) { value, _ -> state.progress = value }
            }
            if (hadGesture) {
                // Once navigation starts, finish its short frame handoff even if
                // another gesture arrives. Never leave pager and history half committed.
                withContext(NonCancellable) {
                    state.committing = true
                    callback(true)
                    withFrameNanos { }
                    withFrameNanos { }
                    state.progress = 0f
                    state.active = false
                    state.committing = false
                }
            } else {
                callback(false)
            }
        } catch (cancelled: CancellationException) {
            if (state.active) state.reset = scope.launch {
                state.committing = false
                animate(state.progress, 0f, animationSpec = tween(180, easing = LinearOutSlowInEasing)) { value, _ -> state.progress = value }
                state.active = false
                state.progress = 0f
            }
            throw cancelled
        } catch (error: Throwable) {
            state.active = false
            state.committing = false
            state.progress = 0f
            throw error
        }
    }
    return state
}

internal fun Modifier.predictiveBackMotion(state: PredictiveBackState): Modifier = graphicsLayer {
    val progress = state.progress
    // Keep the current page opaque while exposing the real destination under
    // its moving edge. Fading a mostly stationary page only dissolves it.
    translationX = state.direction * size.width * progress
    alpha = if (state.committing) 0f else 1f
    shape = RoundedCornerShape(28.dp * progress)
    clip = state.active
}

internal fun Modifier.predictiveBackPreviewMotion(state: PredictiveBackState): Modifier = graphicsLayer {
    alpha = if (state.active) 1f else 0f
    translationX = -state.direction * size.width * .25f * (1f - state.progress)
}

/** Destination previews are presentation only: neither touch nor accessibility can trigger actions. */
internal fun Modifier.backDestinationPreview(): Modifier = clearAndSetSemantics { }.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
