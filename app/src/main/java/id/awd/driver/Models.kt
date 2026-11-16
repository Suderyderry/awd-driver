package id.awd.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class DriverRow(
    @SerialName("user_id") val userId: String,
    val kendaraan: String? = null,
    val plat: String? = null,
    val nik: String? = null,
    val alamat: String? = null,
    @SerialName("foto_diri") val fotoDiri: String? = null,
    val status: String = "Menunggu verifikasi",
    val online: Boolean = false,
    val rating: Double = 5.0,
)

@Serializable
data class ProfilRow(val id: String, val nama: String? = null, val telepon: String? = null)

@Serializable
data class Pesanan(
    val id: Long,
    val kode: String,
    @SerialName("pelanggan_nama") val pelangganNama: String? = null,
    @SerialName("merchant_nama") val merchantNama: String? = null,
    @SerialName("alamat_ambil") val alamatAmbil: String? = null,
    @SerialName("alamat_antar") val alamatAntar: String? = null,
    val catatan: String? = null,
    @SerialName("driver_id") val driverId: String? = null,
    val status: String,
    val ongkir: Long = 0,
    val komisi: Long = 0,
    val total: Long = 0,
    @SerialName("metode_bayar") val metodeBayar: String? = null,
    @SerialName("ambil_lat") val ambilLat: Double? = null,
    @SerialName("ambil_lng") val ambilLng: Double? = null,
    @SerialName("antar_lat") val antarLat: Double? = null,
    @SerialName("antar_lng") val antarLng: Double? = null,
)

@Serializable
data class ChatMsg(
    val id: Long,
    @SerialName("driver_id") val driverId: String,
    val pengirim: String,
    val isi: String,
)

/** Tanpa nilai default agar `pengirim` selalu ikut terkirim (dibutuhkan aturan RLS). */
@Serializable
data class ChatBaru(
    @SerialName("driver_id") val driverId: String,
    val pengirim: String,
    val isi: String,
)

@Serializable
data class PengaturanRow(val kunci: String, val data: JsonObject = JsonObject(emptyMap()))

/**
 * Tampilan header, diatur dari panel admin (AWD Admin > Aplikasi Driver).
 * Nama field mengikuti `pengaturan.tampilan_driver` di Supabase.
 */
data class Tampilan(
    val tpl: String = "gradien",
    val logo: String = "",
    val bg: String = "",
    val so: Boolean = true,
    val h: Int = 140,
    val ls: Int = 64,
    val lp: String = "kiri",
    val ns: Int = 24,
    val nf: String = "bulat",
    val sr: Boolean = false,
    val nama: String = "AWD Driver",
    val sl: String = "Antar cepat, aman, terpercaya",
    val c1: String = "#15803d",
    val c2: String = "#22c55e",
    val nc: String = "#ffffff",
    val ph: String = "Cari pesanan...",
) {
    companion object {
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.b(k: String) =
            (this[k] as? JsonPrimitive)?.contentOrNull?.let { it == "1" || it == "true" }

        fun dari(o: JsonObject): Tampilan {
            val d = Tampilan()
            return Tampilan(
                tpl = o.s("tpl") ?: d.tpl, logo = o.s("logo") ?: d.logo, bg = o.s("bg") ?: d.bg,
                so = o.b("so") ?: d.so, h = o.i("h") ?: d.h, ls = o.i("ls") ?: d.ls,
                lp = o.s("lp") ?: d.lp, ns = o.i("ns") ?: d.ns, nf = o.s("nf") ?: d.nf,
                sr = o.b("sr") ?: d.sr, nama = o.s("nama") ?: d.nama, sl = o.s("sl") ?: d.sl,
                c1 = o.s("c1") ?: d.c1, c2 = o.s("c2") ?: d.c2, nc = o.s("nc") ?: d.nc,
                ph = o.s("ph") ?: d.ph,
            )
        }
    }
}

// ---------- Konfigurasi dari AWD Admin (tabel `pengaturan`) ----------
private fun JsonObject.jS(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.jD(k: String) = jS(k)?.trim()?.toDoubleOrNull()
private fun JsonObject.jL(k: String) = jD(k)?.toLong()
private fun JsonObject.jI(k: String) = jD(k)?.toInt()
private fun JsonObject.jB(k: String) = jS(k)?.let { it == "1" || it == "true" }

data class Layanan(val id: String, val nama: String, val ikon: String)

data class Spanduk(
    val judul: String, val deskripsi: String, val gambar: String,
    val tautan: String, val mulai: String, val selesai: String,
)

/** Tayang bila hari ini berada di rentang mulai-selesai (format YYYY-MM-DD; kosong = tanpa batas). */
fun Spanduk.tayang(hari: String) = (mulai.isBlank() || mulai <= hari) && (selesai.isBlank() || selesai >= hari)

data class Tarif(val dasar: Long?, val jarakDasarKm: Double?, val perKm: Long?)
data class Ketentuan(val tawaranDetik: Int?, val minCair: Long?, val biayaCair: Long?)
data class Sistem(val komisiPersen: Double?, val jam: String?, val daftarDriver: Boolean, val pemeliharaan: Boolean)

data class Konfigurasi(
    val tampilan: Tampilan = Tampilan(),
    val layanan: List<Layanan> = emptyList(),
    val spanduk: List<Spanduk> = emptyList(),
    val tarif: Tarif = Tarif(null, null, null),
    val ketentuan: Ketentuan = Ketentuan(null, null, null),
    val sistem: Sistem = Sistem(null, null, daftarDriver = true, pemeliharaan = false),
) {
    companion object {
        private fun items(o: JsonObject?) = (o?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        /** Memetakan baris `pengaturan` (kunci -> data jsonb) ke tipe aplikasi. Kunci yang tidak ada = nilai kosong, bukan data contoh. */
        fun dari(m: Map<String, JsonObject>): Konfigurasi {
            val kosong = JsonObject(emptyMap())
            val t = m["tarif"] ?: kosong
            val k = m["ketentuan_driver"] ?: kosong
            val s = m["sistem"] ?: kosong
            return Konfigurasi(
                tampilan = Tampilan.dari(m["tampilan_driver"] ?: kosong),
                layanan = items(m["layanan"]).filter { it.jB("on") != false }
                    .mapNotNull { o -> o.jS("n")?.takeIf { it.isNotBlank() }?.let { Layanan(o.jS("id").orEmpty(), it, o.jS("ic").orEmpty()) } },
                spanduk = items(m["spanduk"])
                    .filter { it.jB("on") != false && it.jS("app") in listOf("Driver", "Semua") && !it.jS("img").isNullOrBlank() }
                    .map { Spanduk(it.jS("n").orEmpty(), it.jS("ds").orEmpty(), it.jS("img").orEmpty(), it.jS("link").orEmpty(), it.jS("st").orEmpty(), it.jS("en").orEmpty()) },
                tarif = Tarif(t.jL("dasar"), t.jD("jarak"), t.jL("per_km")),
                ketentuan = Ketentuan(k.jI("tawaran"), k.jL("min_cair"), k.jL("biaya_cair")),
                sistem = Sistem(s.jD("kom"), s.jS("jam"), s.jB("reg_d") ?: true, s.jB("mt") ?: false),
            )
        }
    }
}
