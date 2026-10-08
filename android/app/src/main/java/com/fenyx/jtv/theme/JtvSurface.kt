package com.fenyx.jtv.theme

import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.composed
import kotlinx.coroutines.launch
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.compose.foundation.LocalIndication

/** Where the pointer was (window px) when hover last moved focus. One mouse, so one global. */
private var lastHoverFocusAt = androidx.compose.ui.geometry.Offset.Unspecified

/**
 * Hovering an item focuses it, but only when the pointer itself moved. Focusing scrolls the list,
 * which slides a new item under a still pointer and fires another Enter; following that one too
 * made lists scroll on their own (air mouse / mouse on TV).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.mouseHoverToFocus(focusRequester: FocusRequester): Modifier = composed {
    var coords by remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    this
        .focusRequester(focusRequester)
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Enter) continue
                    val c = coords?.takeIf { it.isAttached } ?: continue
                    val at = c.localToWindow(event.changes.first().position)
                    val last = lastHoverFocusAt
                    if (last.isSpecified && (at - last).getDistance() < 2f) continue // list moved, pointer didn't
                    lastHoverFocusAt = at
                    focusRequester.requestFocus()
                }
            }
        }
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    // pointerInput(Unit) never restarts, so read the LATEST callbacks through updated state — otherwise
    // a mouse/touch tap would call the lambda captured on first composition (stale toggles, wrong item).
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    // Hold OK opens the long-press menu; the RELEASE of that same press must not then "click" this row
    // (that opened the channel and closed the menu at once on remotes without a mouse).
    val swallowOkUp = remember { androidx.compose.runtime.mutableStateOf(false) }
    val longClick: (() -> Unit)? = onLongClick?.let { { swallowOkUp.value = true; currentOnLongClick?.invoke() } }
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && swallowOkUp.value) {
                    if (e.type == KeyEventType.KeyUp) swallowOkUp.value = false
                    true
                } else false
            }
            .mouseHoverToFocus(focusRequester)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        coroutineScope.launch {
                            interactionSource.emit(press)
                        }
                        val success = tryAwaitRelease()
                        coroutineScope.launch {
                            if (success) {
                                interactionSource.emit(PressInteraction.Release(press))
                            } else {
                                interactionSource.emit(PressInteraction.Cancel(press))
                            }
                        }
                    },
                    onTap = { currentOnClick() },
                    onLongPress = { currentOnLongClick?.invoke() }
                )
            }
            .indication(interactionSource, LocalIndication.current),
        onLongClick = longClick,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = interactionSource,
        content = content
    )
}
