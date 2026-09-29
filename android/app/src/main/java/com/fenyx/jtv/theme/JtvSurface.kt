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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.compose.foundation.LocalIndication

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.mouseHoverToFocus(focusRequester: FocusRequester): Modifier = this
    .focusRequester(focusRequester)
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Enter) {
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
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
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
        onLongClick = onLongClick,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = interactionSource,
        content = content
    )
}
