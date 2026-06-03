package com.fenyx.jtv.ui.login

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.tv.material3.Surface
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
    modifier: Modifier = Modifier
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
                        modifier = Modifier.size(64.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
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
fun LoginScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    val focusManager = LocalFocusManager.current

    var mobileNumber by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) } // 1: Mobile, 2: OTP
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                    
                    OutlinedTextField(
                        value = mobileNumber,
                        onValueChange = { }, // Handled by numpad
                        readOnly = true, // Prevent OS keyboard
                        label = { Text("Mobile Number") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
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
                    
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { }, // Handled by numpad
                        readOnly = true, // Prevent OS keyboard
                        label = { Text("OTP") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
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
            
            // Right side: Numpad
            TvNumpad(
                onNumberClick = onNumberClick,
                onBackspace = onBackspace,
                onSubmit = onSubmit
            )
        }
    }
}
