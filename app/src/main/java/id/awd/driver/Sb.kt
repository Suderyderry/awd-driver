package id.awd.driver

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** Satu-satunya pintu ke Supabase. Perubahan data lewat fungsi (RPC) yang dijaga di database. */
/* Nama tabel/view mengikuti database AWD (supabase/2_pasang_database.sql, BAGIAN F):
   drv_driver, drv_profil, drv_pesanan, drv_pengaturan adalah view khusus driver yang sedang login. */
object Sb {
    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY,
    ) {
        install(Auth)
        install(Postgrest)
        install(Realtime)
    }

    val uid: String? get() = client.auth.currentUserOrNull()?.id
    val token: String? get() = client.auth.currentAccessTokenOrNull()

    suspend fun sesiAda(): Boolean {
        client.auth.awaitInitialization()
        return client.auth.currentSessionOrNull() != null
    }

    /** Login memakai nomor HP: akun Supabase dibuat dari nomor HP (08xxxx@driver.awd.app). */
    fun hpKeEmail(hp: String): String {
        var d = hp.filter { it.isDigit() }
        d = if (d.startsWith("62")) "0" + d.drop(2) else if (d.isNotEmpty() && !d.startsWith("0")) "0$d" else d
        return "$d@driver.awd.app"
    }

    /** Nomor HP dari akun yang sedang login (kosong bila bukan akun nomor HP). */
    fun hpLogin(): String {
        val e = client.auth.currentUserOrNull()?.email ?: return ""
        return if (e.endsWith("@driver.awd.app")) e.removeSuffix("@driver.awd.app") else ""
    }

    suspend fun masuk(email: String, sandi: String) {
        client.auth.signInWith(Email) { this.email = email; this.password = sandi }
    }

    /** true bila langsung login; false bila perlu konfirmasi email dulu. */
    suspend fun daftar(email: String, sandi: String): Boolean {
        client.auth.signUpWith(Email) { this.email = email; this.password = sandi }
        return client.auth.currentSessionOrNull() != null
    }

    suspend fun keluar() = client.auth.signOut()

    suspend fun driver(): DriverRow? {
        val id = uid ?: return null
        return client.from("drv_driver").select { filter { eq("user_id", id) } }.decodeSingleOrNull<DriverRow>()
    }

    suspend fun profil(): ProfilRow? {
        val id = uid ?: return null
        return client.from("drv_profil").select { filter { eq("id", id) } }.decodeSingleOrNull<ProfilRow>()
    }

    /** Semua pengaturan dari AWD Admin yang dipakai aplikasi: kunci -> data (jsonb). */
    suspend fun pengaturan(): Map<String, JsonObject> =
        client.from("drv_pengaturan").select { filter { isIn("kunci", Konfig.KUNCI) } }
            .decodeList<PengaturanRow>().associate { it.kunci to it.data }

    /** RLS membatasi: order baru (bila online) + order milik driver ini. */
    suspend fun pesanan(): List<Pesanan> = client.from("drv_pesanan").select {
        order("dibuat_pada", Order.DESCENDING)
        limit(100)
    }.decodeList<Pesanan>()

    suspend fun chat(): List<ChatMsg> = client.from("chat_driver").select {
        order("id", Order.DESCENDING)
        limit(100)
    }.decodeList<ChatMsg>().reversed()

    suspend fun kirimChat(isi: String) {
        val id = uid ?: error("Belum login")
        client.from("chat_driver").insert(ChatBaru(id, "driver", isi.trim()))
    }

    private suspend fun rpc(fn: String, isi: JsonObjectBuilder.() -> Unit = {}) {
        client.postgrest.rpc(fn, buildJsonObject(isi))
    }

    suspend fun setOnline(on: Boolean) = rpc("set_online") { put("p_online", on) }
    suspend fun terima(id: Long) = rpc("terima_pesanan") { put("p_id", id) }
    suspend fun ubahStatus(id: Long, status: String) = rpc("ubah_status_pesanan") {
        put("p_id", id); put("p_status", status)
    }
    suspend fun lokasi(lat: Double, lng: Double) = rpc("perbarui_lokasi") {
        put("p_lat", lat); put("p_lng", lng)
    }
    suspend fun simpanBiodata(
        nama: String, nik: String, telepon: String, alamat: String,
        kendaraan: String, plat: String, foto: String,
    ) = rpc("simpan_biodata") {
        put("p_nama", nama); put("p_nik", nik); put("p_telepon", telepon); put("p_alamat", alamat)
        put("p_kendaraan", kendaraan); put("p_plat", plat); put("p_foto", foto)
    }

    /** Unggah foto wajah ke bucket privat `foto-driver`; mengembalikan path-nya. */
    suspend fun unggahFoto(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val id = uid ?: error("Belum login")
        val tk = token ?: error("Sesi berakhir, silakan masuk ulang")
        val path = "$id/wajah-${System.currentTimeMillis()}.jpg"
        val c = URL("${BuildConfig.SUPABASE_URL}/storage/v1/object/foto-driver/$path")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            c.setRequestProperty("Authorization", "Bearer $tk")
            c.setRequestProperty("Content-Type", "image/jpeg")
            c.outputStream.use { it.write(bytes) }
            val kode = c.responseCode
            if (kode !in 200..299) {
                val pesan = c.errorStream?.bufferedReader()?.readText().orEmpty()
                error("Unggah foto gagal ($kode) $pesan")
            }
        } finally {
            c.disconnect()
        }
        path
    }

    fun urlFoto(path: String) = "${BuildConfig.SUPABASE_URL}/storage/v1/object/authenticated/foto-driver/$path"
}
