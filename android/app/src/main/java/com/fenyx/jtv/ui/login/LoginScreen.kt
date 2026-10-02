package com.fenyx.jtv.ui.login

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.fenyx.jtv.theme.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.data.JioApiClient
import kotlinx.coroutines.launch

import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun TvNumpad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    // Applied to the "1" key so the caller can land initial remote focus on the numpad.
    firstKeyModifier: Modifier = Modifier
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "➡")
    )

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Surface(
                        onClick = {
                            when (key) {
                                "⌫" -> onBackspace()
                                "➡" -> onSubmit()
                                else -> onNumberClick(key)
                            }
                        },
                        modifier = (if (key == "1") firstKeyModifier else Modifier).size(72.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = com.fenyx.jtv.theme.TvDimens.FocusedScale),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    // When set, shows a "use a different sign-in method" affordance that returns to the setup chooser.
    onChangeMethod: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    val focusManager = LocalFocusManager.current

    var mobileNumber by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) } // 1: Mobile, 2: OTP
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to send OTP"
                    }
                }
            } else {
                errorMessage = "Please enter a valid mobile number"
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
                        errorMessage = result.exceptionOrNull()?.message ?: "Invalid OTP"
                    }
                }
            } else {
                errorMessage = "Please enter a valid OTP"
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Form
            Column(
                modifier = Modifier.weight(1f).padding(end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "JTV Login",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (onChangeMethod != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = onChangeMethod,
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            "← Use a different sign-in method",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                
                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (step == 1) {
                    Text(
                        "Enter your mobile number to receive an OTP",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    TvInputDisplay(value = mobileNumber, label = "Mobile Number", placeholder = "10-digit number")
                    Spacer(modifier = Modifier.height(32.dp))
                    Surface(
                        onClick = onSubmit,
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            if (isLoading) "Sending..." else "Send OTP",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                } else {
                    Text(
                        "Enter the OTP sent to $mobileNumber",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    TvInputDisplay(value = otp, label = "OTP", placeholder = "Enter OTP")
                    Spacer(modifier = Modifier.height(32.dp))
                    Surface(
                        onClick = onSubmit,
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            if (isLoading) "Verifying..." else "Login",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        onClick = { step = 1; otp = ""; errorMessage = null },
                        colors = ClickableSurfaceDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            "Change Number",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            
            // Right side: Numpad. Land initial remote focus on the "1" key so the user can type
            // immediately without hunting for focus.
            val numpadFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { numpadFocus.requestFocus() } }
            TvNumpad(
                onNumberClick = onNumberClick,
                onBackspace = onBackspace,
                onSubmit = onSubmit,
                firstKeyModifier = Modifier.focusRequester(numpadFocus)
            )
        }
    }
}

/** A TV-friendly read-only input display (the actual entry happens via the on-screen numpad). */
@Composable
private fun TvInputDisplay(value: String, label: String, placeholder: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .widthIn(min = 300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                // Only the entered digits get wide spacing (for readability). The placeholder uses
                // normal spacing so it reads as the same UI font as the rest of the screen.
                letterSpacing = if (value.isEmpty()) 0.sp else 4.sp,
                color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
