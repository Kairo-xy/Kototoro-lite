package org.skepsun.kototoro.core.ui.gestures

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Mouse grab scrolling for Compose lists/pagers, whose built-in drag handler accepts touch but excludes mouse. */
@Composable
fun Modifier.mouseDragScroll(
    state: ScrollableState,
    orientation: Orientation,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
): Modifier {
    if (!enabled) return this
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, orientation, reverseScrolling)
    fun Offset.axis() = if (orientation == Orientation.Horizontal) x else y
    fun Offset.crossAxis() = if (orientation == Orientation.Horizontal) y else x
    fun Float.scrollDirection() = if (reverse) -this else this
    return pointerInput(state, orientation, reverse, flingBehavior) {
        coroutineScope {
            val gestureScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var travel = Offset.Zero
                var deltas: Channel<Float>? = null
                var drag: Job? = null
                var releaseVelocity: Float? = null
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            released = true
                            releaseVelocity = tracker.calculateVelocity().let {
                                if (orientation == Orientation.Horizontal) it.x else it.y
                            }.scrollDirection()
                            break
                        }
                        if (!event.buttons.isPrimaryPressed) break
                        val delta = change.position - change.previousPosition
                        travel += delta
                        var amount = delta.axis()
                        if (drag == null) {
                            if (abs(travel.axis()) <= viewConfiguration.touchSlop &&
                                abs(travel.crossAxis()) <= viewConfiguration.touchSlop) continue
                            // Let an orthogonal parent/child scroll handler take this gesture.
                            if (abs(travel.crossAxis()) > abs(travel.axis())) break
                            if (abs(travel.axis()) <= viewConfiguration.touchSlop) continue
                            amount = travel.axis() - sign(travel.axis()) * viewConfiguration.touchSlop
                            val stream = Channel<Float>(Channel.UNLIMITED)
                            deltas = stream
                            drag = gestureScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                // One mutation owns the whole drag: a grab cancels a previous fling immediately.
                                state.scroll(MutatePriority.UserInput) {
                                    for (distance in stream) scrollBy(distance.scrollDirection())
                                }
                                releaseVelocity?.let { velocity ->
                                    state.scroll { with(flingBehavior) { performFling(velocity) } }
                                }
                            }
                        }
                        if (drag?.isActive != true) break
                        deltas?.trySend(amount)
                        // Only motion beyond slop is consumed; clicks and long presses keep their normal handlers.
                        change.consume()
                    }
                } finally {
                    deltas?.close()
                    if (!released) drag?.cancel()
                }
            }
        }
    }
}
