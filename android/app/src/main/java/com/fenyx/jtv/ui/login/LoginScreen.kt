package com.fenyx.jtv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.data.JioApiClient
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.textStyle
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.fenyx.jtv.R
import com.fenyx.jtv.i18n.UiText
import com.fenyx.jtv.i18n.text

/** On-screen number pad (remote, mouse and touch). Keys are words, not symbols: "Delete" and "OK". */
@Composable
fun TvNumpad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    // Applied to the "1" key so the caller can land initial remote focus on the numpad.
    firstKeyModifier: Modifier = Modifier
) {
    val c = Jtv.colors
    val keySize = if (Jtv.form == FormFactor.Phone) 76.dp else 72.dp
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(KEY_DELETE, "0", KEY_OK)
    )

    Column(
        modifier = modifier
            .background(c.s1, RoundedCornerShape(10.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val isWord = key == KEY_DELETE || key == KEY_OK
                    JtvClickable(
                        onClick = {
                            when (key) {
                                KEY_DELETE -> onBackspace()
                                KEY_OK -> onSubmit()
                                else -> onNumberClick(key)
                            }
                        },
                        modifier = (if (key == "1") firstKeyModifier else Modifier).size(keySize),
                        container = if (key == KEY_OK) c.inv else c.s2,
                        focusedContainer = if (key == KEY_OK) c.acc else c.inv,
                        focusedScale = 1.04f,
                    ) { focused ->
                        val fg = when {
                            key == KEY_OK && focused -> c.accTx
                            key == KEY_OK -> c.invTx
                            focused -> c.invTx
                            else -> c.tx
                        }
                        Text(
                            text = when (key) {
                                KEY_DELETE -> stringResource(R.string.login_key_delete)
                                KEY_OK -> stringResource(R.string.common_ok)
                                else -> key
                            },
                            modifier = Modifier.align(Alignment.Center),
                            style = if (isWord) textStyle(16.sp, FontWeight.SemiBold) else numberStyle(26.sp),
                            color = fg,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private const val KEY_DELETE = "Delete"
private const val KEY_OK = "OK"

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    // When set, shows a "use a different sign-in method" affordance that returns to the setup chooser.
    onChangeMethod: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val isPhone = Jtv.form == FormFactor.Phone

    var mobileNumber by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) } // 1: Mobile, 2: OTP
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<UiText?>(null) }

    // Hardware BACK: from the OTP step go back to the number step; from the number step return to the
    // setup chooser instead of exiting the app.
    androidx.activity.compose.BackHandler {
        if (step == 2) { step = 1; otp = ""; errorMessage = null }
        else onChangeMethod?.invoke()
    }

    val onNumberClick = { digit: String ->
        if (step == 1) {
            if (mobileNumber.length < 10) mobileNumber += digit
        } else {
            if (otp.length < 6) otp += digit
        }
    }

    val onBackspace = {
        if (step == 1) {
            if (mobileNumber.isNotEmpty()) mobileNumber = mobileNumber.dropLast(1)
        } else {
            if (otp.isNotEmpty()) otp = otp.dropLast(1)
        }
    }

    val onSubmit: () -> Unit = {
        if (step == 1) {
            if (mobileNumber.length >= 10) {
                isLoading = true
                errorMessage = null
                scope.launch {
                    val result = JioApiClient.sendOTP(mobileNumber)
                    isLoading = false
                    if (result.isSuccess) {
                        step = 2
                    } else {
                        // Jio's reply ("Failed to send OTP: 400") is technical; show a plain, translated message.
                        errorMessage = UiText.of(R.string.login_error_send_failed)
                    }
                }
            } else {
                errorMessage = UiText.of(R.string.login_error_number_digits)
            }
        } else {
            if (otp.length >= 4) {
                isLoading = true
                errorMessage = null
                scope.launch {
                    val result = JioApiClient.verifyOTP(mobileNumber, otp)
                    isLoading = false
                    if (result.isSuccess) {
                        val authData = result.getOrNull()
                        if (authData != null) {
                            settingsManager.setAuthMobile(mobileNumber)
                            settingsManager.saveAuthData(authData)
                        }
                    } else {
                        errorMessage = UiText.of(R.string.login_error_code_wrong)
                    }
                }
            } else {
                errorMessage = UiText.of(R.string.login_error_enter_code)
            }
        }
    }

    val form: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                stringResource(if (step == 1) R.string.login_title_number else R.string.login_title_code),
                style = textStyle(if (isPhone) 28.sp else 32.sp, FontWeight.Bold),
                color = c.tx
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (step == 1) stringResource(R.string.login_subtitle_number)
                else stringResource(R.string.login_subtitle_code, mobileNumber),
                style = textStyle(18.sp),
                color = c.t2
            )
            Spacer(Modifier.height(20.dp))

            if (errorMessage != null) {
                Text(errorMessage!!.text(), color = c.error, style = textStyle(18.sp, FontWeight.SemiBold))
                Spacer(Modifier.height(12.dp))
            }

            if (step == 1) {
                InputDisplay(value = mobileNumber, label = stringResource(R.string.login_mobile_number), placeholder = stringResource(R.string.login_mobile_number_hint))
            } else {
                InputDisplay(value = otp, label = stringResource(R.string.login_code), placeholder = stringResource(R.string.login_code_hint))
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JtvButton(
                    when {
                        step == 1 && isLoading -> stringResource(R.string.login_sending)
                        step == 1 -> stringResource(R.string.login_send_code)
                        isLoading -> stringResource(R.string.login_checking)
                        else -> stringResource(R.string.login_sign_in)
                    },
                    onSubmit,
                    primary = true,
                    fontSize = 18.sp,
                    minHeight = if (isTv) 48.dp else 56.dp
                )
                if (step == 2) {
                    JtvButton(
                        stringResource(R.string.login_change_number),
                        { step = 1; otp = ""; errorMessage = null },
                        fontSize = 18.sp,
                        minHeight = if (isTv) 48.dp else 56.dp
                    )
                }
            }
            if (onChangeMethod != null) {
                Spacer(Modifier.height(12.dp))
                JtvButton(stringResource(R.string.login_other_method), onChangeMethod, fontSize = 16.sp, minHeight = if (isTv) 44.dp else 56.dp)
            }
        }
    }

    // Right side (or below on phones): Numpad. Land initial remote focus on the "1" key so the user can
    // type immediately without hunting for focus.
    val numpadFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { numpadFocus.requestFocus() } }
    val numpad: @Composable () -> Unit = {
        TvNumpad(
            onNumberClick = onNumberClick,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            firstKeyModifier = Modifier.focusRequester(numpadFocus)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 16.dp)
            .onPreviewKeyEvent {
                // Hardware keyboard support
                if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    val digit = when (it.key) {
                        Key.Zero -> "0"; Key.One -> "1"; Key.Two -> "2"; Key.Three -> "3"
                        Key.Four -> "4"; Key.Five -> "5"; Key.Six -> "6"; Key.Seven -> "7"
                        Key.Eight -> "8"; Key.Nine -> "9"
                        else -> null
                    }
                    if (digit != null) {
                        onNumberClick(digit)
                        return@onPreviewKeyEvent true
                    }
                    if (it.key == Key.Backspace) {
                        onBackspace()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        if (isPhone) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                form()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { numpad() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(end = 32.dp)) { form() }
                    numpad()
                }
                if (isTv) KeyHint(listOf(stringResource(R.string.login_hint_number_keys) to stringResource(R.string.login_hint_type), stringResource(R.string.common_ok) to stringResource(R.string.login_hint_press_key), stringResource(R.string.common_back) to stringResource(R.string.login_hint_go_back)))
            }
        }
    }
}

/** A read-only input display (the actual entry happens via the on-screen numpad or number keys). */
@Composable
private fun InputDisplay(value: String, label: String, placeholder: String) {
    val c = Jtv.colors
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2)
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .widthIn(min = 320.dp)
                .background(c.s1, RoundedCornerShape(8.dp))
                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                // Only the entered digits use the wide number cut with spacing (for readability); the
                // placeholder uses the normal text face so it reads as the same UI font as the screen.
                style = if (value.isEmpty()) textStyle(22.sp) else numberStyle(28.sp).copy(letterSpacing = 3.sp),
                color = if (value.isEmpty()) c.t2 else c.tx,
                maxLines = 1
            )
        }
    }
}
