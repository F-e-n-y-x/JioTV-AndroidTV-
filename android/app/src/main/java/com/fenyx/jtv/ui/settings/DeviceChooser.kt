package com.fenyx.jtv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.R
import com.fenyx.jtv.theme.DeviceKind
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.textStyle

/**
 * First start on a device that isn't certainly Android TV (#6): "What are you watching on?" with
 * TV / Phone / Tablet. The best guess has focus, so a remote user just presses OK.
 */
@Composable
fun DeviceChooser(onPick: (String) -> Unit) {
    val c = Jtv.colors
    val ctx = LocalContext.current
    val guess = remember { if (DeviceKind.guessTv(ctx)) DeviceKind.TV else if (ctx.resources.configuration.smallestScreenWidthDp < 600) DeviceKind.PHONE else DeviceKind.TABLET }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(c.bg).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.device_question), style = textStyle(28.sp, FontWeight.Bold), color = c.tx, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.device_question_desc), style = textStyle(16.sp), color = c.t2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(DeviceKind.TV to R.string.device_tv, DeviceKind.PHONE to R.string.device_phone, DeviceKind.TABLET to R.string.device_tablet)
                    .forEach { (type, label) ->
                        JtvButton(
                            stringResource(label), { onPick(type) },
                            modifier = if (type == guess) Modifier.focusRequester(focus) else Modifier,
                            primary = type == guess, fontSize = 20.sp, minHeight = 56.dp,
                        )
                    }
            }
        }
    }
}
