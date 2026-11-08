package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    val context: Context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

    companion object {
        const val PROVIDER_MOVIEBOX = "moviebox"
        const val PROVIDER_ANIFAM = "anifam"
        const val KEY_ACTIVE_PROVIDER = "active_provider"
        const val KEY_SP_CODE = "sp_code"
        const val KEY_LOCAL_ISO = "custom_local_iso"
        const val KEY_LOCAL_COUNTRY = "custom_local_country"
        const val KEY_COUNTRY_CODE = "custom_country_code"
        const val KEY_IS_DEVELOPER = "is_developer"
        const val KEY_MOCK_HOST = "mock_host_key"
        const val KEY_SESSION_ID_COOKIE = "session_id_cookie"
        const val KEY_ANIFAM_AUTH_TOKEN = "anifam_auth_token"
        const val KEY_ANIFAM_USER_INFO = "anifam_user_info"
    }

    var activeProvider: String
        get() = prefs.getString(KEY_ACTIVE_PROVIDER, PROVIDER_MOVIEBOX) ?: PROVIDER_MOVIEBOX
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROVIDER, value).apply()

    val isAniFam: Boolean
        get() = activeProvider == PROVIDER_ANIFAM

    fun getActiveCategories(provider: String = activeProvider): String? {
        val key = "active_categories_$provider"
        return prefs.getString(key, null) ?: if (provider == PROVIDER_MOVIEBOX) prefs.getString("active_categories", null) else null
    }

    fun setActiveCategories(categories: String?, provider: String = activeProvider) {
        val key = "active_categories_$provider"
        prefs.edit().putString(key, categories).apply()
        if (provider == PROVIDER_MOVIEBOX) {
            prefs.edit().putString("active_categories", categories).apply()
        }
    }

    var anifamAuthToken: String?
        get() = prefs.getString(KEY_ANIFAM_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ANIFAM_AUTH_TOKEN, value).apply()

    var anifamUserInfoJson: String?
        get() = prefs.getString(KEY_ANIFAM_USER_INFO, null)
        set(value) = prefs.edit().putString(KEY_ANIFAM_USER_INFO, value).apply()

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
        get() = getActiveCategories()
        set(value) = setActiveCategories(value)

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
