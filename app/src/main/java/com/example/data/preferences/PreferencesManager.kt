package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_SP_CODE = "sp_code"
        const val KEY_LOCAL_ISO = "custom_local_iso"
        const val KEY_LOCAL_COUNTRY = "custom_local_country"
        const val KEY_COUNTRY_CODE = "custom_country_code"
        const val KEY_IS_DEVELOPER = "is_developer"
        const val KEY_MOCK_HOST = "mock_host_key"
        const val KEY_SESSION_ID_COOKIE = "session_id_cookie"
    }

    var spCode: String
        get() = prefs.getString(KEY_SP_CODE, "40401") ?: "40401"
        set(value) = prefs.edit().putString(KEY_SP_CODE, value).apply()

    var localIso: String
        get() = prefs.getString(KEY_LOCAL_ISO, "in") ?: "in"
        set(value) = prefs.edit().putString(KEY_LOCAL_ISO, value).apply()

    var localCountry: String
        get() = prefs.getString(KEY_LOCAL_COUNTRY, "India") ?: "India"
        set(value) = prefs.edit().putString(KEY_LOCAL_COUNTRY, value).apply()
        
    var activeCategories: String?
        get() = prefs.getString("active_categories", null)
        set(value) = prefs.edit().putString("active_categories", value).apply()

    var countryCode: String
        get() = prefs.getString(KEY_COUNTRY_CODE, "91") ?: "91"
        set(value) = prefs.edit().putString(KEY_COUNTRY_CODE, value).apply()

    var isDeveloper: Boolean
        get() = prefs.getBoolean(KEY_IS_DEVELOPER, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_DEVELOPER, value).apply()

    var mockHost: String
        get() {
            val saved = prefs.getString(KEY_MOCK_HOST, "http://127.0.0.1:3000") ?: "http://127.0.0.1:3000"
            return if (saved.contains("asia-southeast1.run.app") || saved.contains("ais-dev-") || saved.isEmpty()) {
                "http://127.0.0.1:3000"
            } else {
                saved
            }
        }
        set(value) = prefs.edit().putString(KEY_MOCK_HOST, value).apply()

    var sessionIdCookie: String
        get() = prefs.getString(KEY_SESSION_ID_COOKIE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_ID_COOKIE, value).apply()

    var defaultStreamQuality: String
        get() = prefs.getString("default_stream_quality", "1080p") ?: "1080p"
        set(value) = prefs.edit().putString("default_stream_quality", value).apply()

    var defaultDownloadQuality: String
        get() = prefs.getString("default_download_quality", "Highest") ?: "Highest"
        set(value) = prefs.edit().putString("default_download_quality", value).apply()

    fun clearDeveloperBypass() {
        prefs.edit()
            .remove(KEY_SP_CODE)
            .remove(KEY_LOCAL_ISO)
            .remove(KEY_LOCAL_COUNTRY)
            .remove(KEY_COUNTRY_CODE)
            .putBoolean(KEY_IS_DEVELOPER, false)
            .apply()
    }

    fun applyDeveloperBypass() {
        prefs.edit()
            .putString(KEY_SP_CODE, "40401")
            .putString(KEY_LOCAL_ISO, "in")
            .putString(KEY_LOCAL_COUNTRY, "India")
            .putString(KEY_COUNTRY_CODE, "91")
            .putBoolean(KEY_IS_DEVELOPER, true)
            .apply()
    }
}
