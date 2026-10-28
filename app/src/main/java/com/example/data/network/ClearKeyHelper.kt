package com.example.data.network

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object ClearKeyHelper {

    /**
     * Converts hex keyId and key into W3C ClearKey JSON byte array
     * format: {"keys":[{"kty":"oct","k":"<base64>","kid":"<base64>"}],"type":"temporary"}
     */
    fun createClearKeyJson(keyIdHex: String, keyHex: String): ByteArray {
        return createClearKeyJson(mapOf(keyIdHex to keyHex))
    }

    /**
     * Converts a map of hex keyId to hex key into W3C ClearKey JSON byte array
     */
    fun createClearKeyJson(keysMap: Map<String, String>): ByteArray {
        val jsonArray = JSONArray()
        for ((keyIdHex, keyHex) in keysMap) {
            val cleanKeyId = keyIdHex.replace("-", "").trim()
            val cleanKey = keyHex.replace("-", "").trim()
            if (cleanKeyId.isNotEmpty() && cleanKey.isNotEmpty()) {
                val kidBase64 = hexToBase64Url(cleanKeyId)
                val keyBase64 = hexToBase64Url(cleanKey)
                val keyObj = JSONObject().apply {
                    put("kty", "oct")
                    put("kid", kidBase64)
                    put("k", keyBase64)
                }
                jsonArray.put(keyObj)
            }
        }

        val rootObj = JSONObject().apply {
            put("keys", jsonArray)
            put("type", "temporary")
        }

        return rootObj.toString().toByteArray(Charsets.UTF_8)
    }

    private fun hexToBase64Url(hexString: String): String {
        val length = hexString.length
        val bytes = ByteArray(length / 2) { i ->
            hexString.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
