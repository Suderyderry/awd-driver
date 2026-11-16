package id.awd.driver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = true,
    val loggedIn: Boolean = false,
    val sudahMuat: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val driver: DriverRow? = null,
    val profil: ProfilRow? = null,
    val konfig: Konfigurasi = Konfigurasi(),
    val pesanan: List<Pesanan> = emptyList(),
    val chat: List<ChatMsg> = emptyList(),
    val chatDilihat: Long = 0,
) {
    val tampilan: Tampilan get() = konfig.tampilan
}

fun UiState.pool() = pesanan.filter { it.status == "Baru" && it.driverId == null }

/** Biodata + foto wajah wajib lengkap (aturan yang sama dicek lagi di database). */
fun UiState.lengkap(): Boolean {
    val d = driver ?: return false
    val p = profil ?: return false
    return listOf(p.nama, p.telepon, d.nik, d.alamat, d.kendaraan, d.plat, d.fotoDiri).all { !it.isNullOrBlank() }
}

class AppVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("awd", 0)
    private val _s = MutableStateFlow(
        UiState(chatDilihat = prefs.getLong("chat_dilihat", 0), konfig = Konfigurasi.dari(Konfig.muatCache(app))),
    )
    val s: StateFlow<UiState> = _s

    init {
        viewModelScope.launch {
            val ada = runCatching { Sb.sesiAda() }.getOrDefault(false)
            muatKonfig()
            _s.update { it.copy(loading = false, loggedIn = ada) }
            if (ada) refresh()
        }
        pantauKonfig()
    }

    /** Dipanggil dari lifecycle: hanya berjalan saat aplikasi terlihat. Saat online, OrderService melanjutkan di latar belakang. */
    suspend fun poll() {
        var tick = 0
        while (true) {
            if (tick % 12 == 0) muatKonfig() // cadangan bila Realtime terputus
            if (_s.value.loggedIn) {
                refresh()
                if (_s.value.driver?.online == true && !OrderService.berjalan && tick % 4 == 0) Lokasi.kirim(ctx, viewModelScope)
            }
            tick++
            delay(5_000)
        }
    }

    /** Ambil konfigurasi terbaru dari AWD Admin; gagal/offline = tetap memakai cache terakhir. */
    suspend fun muatKonfig() {
        try {
            val m = Sb.pengaturan()
            if (m.isEmpty()) return
            Konfig.simpanCache(ctx, m)
            _s.update { it.copy(konfig = Konfigurasi.dari(m)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Perubahan di AWD Admin langsung diterapkan lewat Supabase Realtime. */
    private fun pantauKonfig() = viewModelScope.launch {
        try {
            val ch = Sb.client.channel("konfigurasi")
            val aliran = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "pengaturan" }
            ch.subscribe()
            aliran.collect { muatKonfig() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Realtime tidak tersedia: polling 60 detik di poll() tetap menjaga sinkronisasi.
        }
    }

    suspend fun refresh() {
        try {
            val d = Sb.driver()
            val p = Sb.profil()
            val ps = if (d != null) Sb.pesanan() else emptyList()
            val ch = if (d != null) Sb.chat() else emptyList()
            _s.update { it.copy(driver = d, profil = p, pesanan = ps, chat = ch, sudahMuat = true, error = if (it.sudahMuat) it.error else null) }
            if (d?.online == true) {
                Alert.cek(ctx, ps.filter { it.status == "Baru" && it.driverId == null })
                if (!OrderService.berjalan) OrderService.mulai(ctx)
            }
        } catch (e: Exception) {
            if (!_s.value.sudahMuat) _s.update { it.copy(error = e.message ?: "Gagal memuat data") }
        }
    }

    fun muatUlang() = aksi { refresh() }

    fun masuk(hp: String, sandi: String) = aksi {
        try {
            Sb.masuk(Sb.hpKeEmail(hp), sandi)
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            throw Exception(if (m.contains("Invalid login", true) || m.contains("credentials", true)) "Nomor HP atau kata sandi salah" else m.ifBlank { "Gagal masuk" })
        }
        _s.update { it.copy(loggedIn = true, sudahMuat = false) }
        muatKonfig()
        refresh()
    }

    fun daftar(hp: String, sandi: String) = aksi {
        val berhasil = try {
            Sb.daftar(Sb.hpKeEmail(hp), sandi)
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            throw Exception(if (m.contains("already", true) || m.contains("registered", true)) "Nomor HP ini sudah terdaftar. Silakan masuk." else m.ifBlank { "Gagal mendaftar" })
        }
        if (berhasil) {
            _s.update { it.copy(loggedIn = true, sudahMuat = false) }
            refresh()
        } else {
            _s.update { it.copy(info = "Pendaftaran dibuat, tetapi server masih meminta konfirmasi email. Hubungi admin agar \"Confirm email\" dimatikan, lalu masuk.") }
        }
    }

    fun keluar() = aksi {
        runCatching { if (_s.value.driver?.online == true) Sb.setOnline(false) }
        OrderService.henti(ctx)
        Alert.reset(ctx)
        Sb.keluar()
        _s.update { UiState(loading = false, konfig = it.konfig, chatDilihat = prefs.getLong("chat_dilihat", 0)) }
    }

    fun simpanBiodata(
        nama: String, nik: String, telepon: String, alamat: String,
        kendaraan: String, plat: String, foto: ByteArray?, selesai: () -> Unit,
    ) = aksi {
        val path = if (foto != null) Sb.unggahFoto(foto)
        else _s.value.driver?.fotoDiri?.takeIf { it.isNotBlank() } ?: error("Foto wajah wajib diunggah")
        Sb.simpanBiodata(nama.trim(), nik.trim(), telepon.trim(), alamat.trim(), kendaraan.trim(), plat.trim(), path)
        refresh()
        selesai()
    }

    fun setOnline(on: Boolean) = aksi {
        Sb.setOnline(on)
        if (on) OrderService.mulai(ctx) else { OrderService.henti(ctx); Alert.reset(ctx) }
        refresh()
    }

    fun terima(id: Long) = aksi { Sb.terima(id); Alert.hentikan(ctx); refresh() }
    fun ubahStatus(id: Long, status: String) = aksi { Sb.ubahStatus(id, status); refresh() }

    fun kirimChat(teks: String) = aksi {
        Sb.kirimChat(teks)
        val ch = Sb.chat()
        _s.update { it.copy(chat = ch) }
    }

    fun tandaiChatDibaca() {
        val terakhir = _s.value.chat.maxOfOrNull { it.id } ?: return
        if (terakhir > _s.value.chatDilihat) {
            prefs.edit().putLong("chat_dilihat", terakhir).apply()
            _s.update { it.copy(chatDilihat = terakhir) }
        }
    }

    fun tampilError(pesan: String) = _s.update { it.copy(error = pesan) }
    fun hapusPesan() = _s.update { it.copy(error = null, info = null) }

    private fun aksi(blok: suspend () -> Unit) {
        viewModelScope.launch {
            _s.update { it.copy(busy = true, error = null, info = null) }
            try {
                blok()
            } catch (e: Exception) {
                _s.update { it.copy(error = e.message ?: "Terjadi kesalahan") }
            } finally {
                _s.update { it.copy(busy = false) }
            }
        }
    }
}
