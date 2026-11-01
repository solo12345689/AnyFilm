package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserProfile
import com.example.data.network.MovieBoxRetrofitClient
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)

    var isLoggedIn by mutableStateOf(false)
        internal set

    var currentUserProfile by mutableStateOf<UserProfile?>(null)
        internal set

    var authErrorMsg by mutableStateOf("")
        internal set

    var isAuthLoading by mutableStateOf(false)
        internal set

    init {
        checkUserSession()
    }

    fun checkUserSession() {
        viewModelScope.launch {
            try {
                val api = MovieBoxRetrofitClient.getApi(prefs)
                val response = api.getUserInfo()
                isLoggedIn = response.logged_in
                currentUserProfile = response.user
            } catch (e: Exception) {
                // Ignore session check failure
            }
        }
    }

    fun requestOtp(email: String, isRegister: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authErrorMsg = ""
            try {
                val api = MovieBoxRetrofitClient.getApi(prefs)
                val type = if (isRegister) 1 else 2
                val response = api.requestOtp(com.example.data.model.OtpApiRequest(account = email, type = type))
                if (response.status == "success") {
                    onSuccess()
                } else {
                    authErrorMsg = response.message.ifEmpty { "Failed to request OTP" }
                    onError(authErrorMsg)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Network error")
            } finally {
                isAuthLoading = false
            }
        }
    }

    fun loginWithPassword(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authErrorMsg = ""
            try {
                val api = MovieBoxRetrofitClient.getApi(prefs)
                val response = api.login(com.example.data.model.LoginApiRequest(account = email, password = password))
                if (response.status == "success") {
                    isLoggedIn = true
                    currentUserProfile = response.user
                    authErrorMsg = ""
                    onSuccess()
                } else {
                    onError(response.message.ifEmpty { "Invalid credentials" })
                }
            } catch (e: Exception) {
                onError(e.message ?: "Authentication error")
            } finally {
                isAuthLoading = false
            }
        }
    }

    fun registerAccount(email: String, password: String, otp: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authErrorMsg = ""
            try {
                val api = MovieBoxRetrofitClient.getApi(prefs)
                val response = api.register(com.example.data.model.RegisterApiRequest(account = email, password = password, otp = otp))
                if (response.status == "success") {
                    isLoggedIn = true
                    currentUserProfile = response.user
                    authErrorMsg = ""
                    onSuccess()
                } else {
                    onError(response.message.ifEmpty { "Registration failed" })
                }
            } catch (e: Exception) {
                onError(e.message ?: "Registration error")
            } finally {
                isAuthLoading = false
            }
        }
    }

    fun logoutUser(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val api = MovieBoxRetrofitClient.getApi(prefs)
                api.logout()
            } catch (e: Exception) {
                // Ignore
            } finally {
                isLoggedIn = false
                currentUserProfile = null
                prefs.sessionIdCookie = ""
                onSuccess()
            }
        }
    }
}
