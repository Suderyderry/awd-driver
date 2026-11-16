package id.awd.driver

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Kunci `pengaturan` yang dipakai AWD Driver + cache lokal agar aplikasi tetap tampil saat offline. */
object Konfig {
    val KUNCI = listOf("layanan", "tampilan_driver", "spanduk", "sistem", "tarif", "ketentuan_driver")
    private val json = Json { ignoreUnknownKeys = true }

    fun muatCache(ctx: Context): Map<String, JsonObject> = runCatching {
        val mentah = ctx.getSharedPreferences("konfig", Context.MODE_PRIVATE).getString("raw", null) ?: return emptyMap()
        json.parseToJsonElement(mentah).jsonObject.mapValues { it.value.jsonObject }
    }.getOrDefault(emptyMap())

    fun simpanCache(ctx: Context, m: Map<String, JsonObject>) {
        ctx.getSharedPreferences("konfig", Context.MODE_PRIVATE).edit().putString("raw", JsonObject(m).toString()).apply()
    }
}
