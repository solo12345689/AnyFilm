package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)

    private val defaultCategories = listOf(
        Pair(2, "Home"),
        Pair(8, "Anime"),
        Pair(18, "Asian"),
        Pair(19, "Western"),
        Pair(13, "Short TV"),
        Pair(23, "Kids"),
        Pair(3, "Education"),
        Pair(4, "Music"),
        Pair(28, "Nollywood"),
        Pair(11, "Game")
    )

    private val _activeCategories = MutableStateFlow<List<Pair<Int, String>>>(defaultCategories)
    val activeCategories: StateFlow<List<Pair<Int, String>>> = _activeCategories.asStateFlow()

    var isDeveloper by mutableStateOf(prefs.isDeveloper)
        internal set

    var customMcc by mutableStateOf(prefs.spCode.ifEmpty { "40401" })
        internal set

    var customCountryIso by mutableStateOf(prefs.localIso.ifEmpty { "in" })
        internal set

    var mockHost by mutableStateOf(prefs.mockHost)
        internal set

    init {
        loadActiveCategories()
    }

    fun loadActiveCategories() {
        val saved = prefs.activeCategories
        if (!saved.isNullOrEmpty()) {
            val list = saved.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val id = parts[0].toIntOrNull()
                    if (id != null) Pair(id, parts[1]) else null
                } else null
            }
            if (list.isNotEmpty()) {
                _activeCategories.value = list
                return
            }
        }
        _activeCategories.value = defaultCategories
    }

    fun addCategory(id: Int, name: String) {
        val current = _activeCategories.value.toMutableList()
        if (current.none { it.first == id }) {
            current.add(Pair(id, name))
            _activeCategories.value = current
            saveActiveCategories(current)
        }
    }

    fun removeCategory(id: Int) {
        val current = _activeCategories.value.toMutableList()
        if (current.size > 1) {
            current.removeAll { it.first == id }
            _activeCategories.value = current
            saveActiveCategories(current)
        }
    }

    private fun saveActiveCategories(list: List<Pair<Int, String>>) {
        val serialized = list.joinToString(",") { "${it.first}:${it.second}" }
        prefs.activeCategories = serialized
    }

    fun applyCustomSandbox(mcc: String, iso: String) {
        customMcc = mcc
        customCountryIso = iso
        prefs.spCode = mcc
        prefs.localIso = iso
    }

    fun resetSandbox() {
        applyCustomSandbox("40401", "in")
    }

    fun updateMockHost(host: String) {
        mockHost = host
        prefs.mockHost = host
    }
}
