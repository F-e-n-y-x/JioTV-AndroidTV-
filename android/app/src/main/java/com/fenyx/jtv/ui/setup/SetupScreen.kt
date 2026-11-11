package com.fenyx.jtv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.fenyx.jtv.R
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.textStyle

/**
 * First-boot setup chooser. Presents the ways to sign in as large, focusable, labelled cards:
 *  - **Access code** — JTV Server, no URL needed.
 *  - **Phone** — the existing OTP login on this device.
 *  - **Server** — pull shared credentials from a self-hosted JTV proxy server (log in once, every TV).
 */
@Composable
fun SetupScreen(
    onChoosePhone: () -> Unit,
    onChooseServer: () -> Unit,
    onChooseJtv: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val isPhone = Jtv.form == FormFactor.Phone
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstCard.requestFocus() } }

    val options = listOf(
        Triple(stringResource(R.string.setup_code_title), stringResource(R.string.setup_code_card_subtitle), Icons.Default.Lock) to onChooseJtv,
        Triple(stringResource(R.string.setup_phone_title), stringResource(R.string.setup_phone_card_subtitle), Icons.Default.Phone) to onChoosePhone,
        Triple(stringResource(R.string.setup_own_server_title), stringResource(R.string.setup_server_subtitle), Icons.Default.Build) to onChooseServer,
    )

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .then(if (isPhone) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 24.dp),
            horizontalAlignment = if (isPhone) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = if (isPhone) Arrangement.Top else Arrangement.Center
        ) {
            Text(stringResource(R.string.setup_welcome), style = textStyle(if (isPhone) 30.sp else 36.sp, FontWeight.Bold), color = c.tx)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_how_sign_in), style = textStyle(20.sp), color = c.t2)
            Spacer(Modifier.height(if (isPhone) 24.dp else 32.dp))

            if (isPhone) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEachIndexed { i, (o, onClick) ->
                        SetupCard(o.first, o.second, o.third, onClick,
                            Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstCard) else Modifier), compact = true)
                    }
                }
            } else {
                // fillMaxWidth + weight(1f) so the three cards SHARE the row width equally and always fit any
                // screen — fixed widths overflowed narrow TVs and clipped the third card.
                Row(modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    options.forEachIndexed { i, (o, onClick) ->
                        SetupCard(o.first, o.second, o.third, onClick,
                            Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(firstCard) else Modifier), compact = false)
                    }
                }
            }
            if (isTv) {
                Spacer(Modifier.height(32.dp))
                KeyHint(listOf(stringResource(R.string.setup_hint_left_right) to stringResource(R.string.setup_hint_choose), stringResource(R.string.common_ok) to stringResource(R.string.setup_hint_continue)))
            }
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    compact: Boolean,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = modifier.then(if (compact) Modifier.heightIn(min = 96.dp) else Modifier.height(220.dp)),
        container = c.s1,
        focusedScale = 1.03f,
    ) { focused ->
        val fg = if (focused) c.invTx else c.tx
        val fg2 = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2
        if (compact) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(icon, contentDescription = null, tint = if (focused) c.invTx else c.acc, modifier = Modifier.size(32.dp))
                Column {
                    Text(title, style = textStyle(20.sp, FontWeight.SemiBold), color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = textStyle(16.sp), color = fg2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, tint = if (focused) c.invTx else c.acc, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(16.dp))
                // Two lines reserved for title and subtitle so every card lines up.
                Text(title, style = textStyle(22.sp, FontWeight.Bold), color = fg, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = textStyle(16.sp), color = fg2, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
