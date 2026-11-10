package com.sudomastery.tvremote.data

import android.content.Context
import android.util.Base64
import com.sudomastery.atv.ClientIdentity
import org.json.JSONArray
import org.json.JSONObject

data class SavedTv(val name: String, val host: String, val pinSha256: ByteArray?) {
    override fun equals(other: Any?) = other is SavedTv && other.host == host && other.name == name &&
        (other.pinSha256 contentEquals pinSha256)
    override fun hashCode() = host.hashCode()
}

/** Small key-value persistence. SharedPreferences keeps startup fast and the APK small. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun loadIdentity(): ClientIdentity? {
        val key = prefs.getString(KEY_PRIVATE, null) ?: return null
        val cert = prefs.getString(KEY_CERT, null) ?: return null
        return runCatching { ClientIdentity.fromEncoded(key.decode(), cert.decode()) }.getOrNull()
    }

    fun saveIdentity(identity: ClientIdentity) {
        prefs.edit()
            .putString(KEY_PRIVATE, identity.privateKeyEncoded.encode())
            .putString(KEY_CERT, identity.certificateEncoded.encode())
            .apply()
    }

    var tvs: List<SavedTv>
        get() {
            val raw = prefs.getString(KEY_TVS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SavedTv(o.getString("name"), o.getString("host"), o.optString("pin").takeIf { it.isNotEmpty() }?.decode())
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(JSONObject().put("name", it.name).put("host", it.host).put("pin", it.pinSha256?.encode() ?: "")) }
            prefs.edit().putString(KEY_TVS, arr.toString()).apply()
        }

    var selectedHost: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    var irMode: Boolean
        get() = prefs.getBoolean(KEY_IR, false)
        set(value) = prefs.edit().putBoolean(KEY_IR, value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    private fun ByteArray.encode() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decode() = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_PRIVATE = "identity_key"
        const val KEY_CERT = "identity_cert"
        const val KEY_TVS = "tvs"
        const val KEY_SELECTED = "selected"
        const val KEY_HAPTICS = "haptics"
        const val KEY_IR = "ir_mode"
    }
}
