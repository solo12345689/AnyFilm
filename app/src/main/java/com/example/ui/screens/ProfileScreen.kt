package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.ui.viewmodel.MovieViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheet(
    viewModel: MovieViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var email by remember(viewModel.isLoggedIn) { mutableStateOf("") }
    var password by remember(viewModel.isLoggedIn) { mutableStateOf("") }
    var otp by remember(viewModel.isLoggedIn) { mutableStateOf("") }
    var isRegisterMode by remember(viewModel.isLoggedIn) { mutableStateOf(false) }
    var errorMessage by remember(viewModel.isLoggedIn) { mutableStateOf("") }
    var statusMessage by remember(viewModel.isLoggedIn) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Close button to return to profile
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }

        // Brand Header
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "MovieBox Auth",
            tint = CinematicRed,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isRegisterMode) "Create Account" else "Welcome Back",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = if (isRegisterMode) "Sign up to sync your history & favorites" else "Sign in to access premium media content",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(containerColor = ObsidianCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = CinematicRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        color = AccentAmber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Email Address Input
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = "Email") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CinematicRed,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                        focusedLabelColor = CinematicRed,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // Password Input
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = "Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CinematicRed,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                        focusedLabelColor = CinematicRed,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // OTP Code Input (only in Register Mode)
                if (isRegisterMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = otp,
                            onValueChange = { otp = it },
                            label = { Text("OTP Code") },
                            leadingIcon = { Icon(Icons.Filled.Check, contentDescription = "OTP Code") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("otp_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CinematicRed,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                focusedLabelColor = CinematicRed,
                                unfocusedLabelColor = TextSecondary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                if (email.isEmpty()) {
                                    errorMessage = "Please enter your email to request OTP"
                                    return@Button
                                }
                                if (!email.contains("@") || !email.contains(".")) {
                                    errorMessage = "Please enter a valid email address"
                                    return@Button
                                }
                                viewModel.requestOtp(email, isRegister = true, onSuccess = {
                                    errorMessage = ""
                                    statusMessage = "OTP sent to your email successfully!"
                                }, onError = { err ->
                                    statusMessage = ""
                                    errorMessage = err
                                })
                            },
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("request_otp_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianDark),
                            border = BorderStroke(1.dp, CinematicRed),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !viewModel.isAuthLoading
                        ) {
                            Text("Get OTP", color = CinematicRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Submit Button
                Button(
                    onClick = {
                        if (email.isEmpty() || password.isEmpty()) {
                            errorMessage = "Email and Password cannot be empty"
                            return@Button
                        }
                        if (!email.contains("@") || !email.contains(".")) {
                            errorMessage = "Please enter a valid email address"
                            return@Button
                        }
                        if (isRegisterMode) {
                            if (otp.isEmpty()) {
                                errorMessage = "Please enter the OTP verification code"
                                return@Button
                            }
                            viewModel.registerAccount(email, password, otp, onSuccess = {
                                Toast.makeText(context, "Account created successfully!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }, onError = { err ->
                                errorMessage = err
                            })
                        } else {
                            viewModel.loginWithPassword(email, password, onSuccess = {
                                Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }, onError = { err ->
                                errorMessage = err
                            })
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("auth_submit_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !viewModel.isAuthLoading
                ) {
                    if (viewModel.isAuthLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = if (isRegisterMode) "Register & Sign In" else "Sign In",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Toggle Sign In vs Register
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isRegisterMode) "Already have an account?" else "Don't have an account?",
                color = TextSecondary,
                fontSize = 14.sp
            )
            TextButton(
                onClick = {
                    isRegisterMode = !isRegisterMode
                    errorMessage = ""
                    statusMessage = ""
                },
                modifier = Modifier.testTag("toggle_auth_mode")
            ) {
                Text(
                    text = if (isRegisterMode) "Sign In" else "Sign Up",
                    color = CinematicRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        if (isRegisterMode) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Powered by MovieBoxPro",
                color = TextSecondary.copy(alpha = 0.5f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
