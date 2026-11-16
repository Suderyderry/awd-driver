package id.awd.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale

// ---------- Util ----------
fun rp(n: Long) = "Rp " + String.format(Locale("id", "ID"), "%,d", n)

fun warna(hex: String, cadangan: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(cadangan)

fun bukaMaps(ctx: Context, alamat: String?) {
    if (alamat.isNullOrBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(alamat)))) }
}

/** Kecilkan (maks 1600 px), putar sesuai EXIF, kompres JPEG. */
fun siapkanFoto(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val cr = ctx.contentResolver
    val batas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, batas) }
    var skala = 1
    while (batas.outWidth / skala > 1600 || batas.outHeight / skala > 1600) skala *= 2
    val bmp = cr.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = skala })
    }!!
    val orientasi = cr.openInputStream(uri)!!.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    val derajat = when (orientasi) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val akhir = if (derajat != 0f)
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(derajat) }, true)
    else bmp
    ByteArrayOutputStream().also { akhir.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}.getOrNull()

fun fontDari(k: String): FontFamily = when (k) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

// ---------- Komponen umum ----------
@Composable
fun Kredit() {
    Text(
        "by. Wong Arjawinangun 2026",
        color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
fun PesanBar(s: UiState, vm: AppVm) {
    val e = s.error
    val i = s.info
    if (e == null && i == null) return
    val salah = e != null
    Row(
        Modifier.fillMaxWidth()
            .background(if (salah) MaterialTheme.colorScheme.errorContainer else Color(0xFFDCFCE7))
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e ?: i.orEmpty(), Modifier.weight(1f), color = if (salah) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF14532D))
        TextButton(onClick = vm::hapusPesan) { Text("Tutup") }
    }
}

/** Gambar dari URL (https) atau data URI base64 (format yang disimpan panel admin). */
@Composable
fun GambarJauh(src: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    if (src.startsWith("data:")) {
        val bmp by produceState<ImageBitmap?>(null, src) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val b = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
        bmp?.let { Image(it, null, modifier, contentScale = scale) }
    } else {
        AsyncImage(model = src, contentDescription = null, modifier = modifier, contentScale = scale)
    }
}

/** Foto wajah dari bucket privat Supabase (butuh token login). */
@Composable
fun FotoWajah(path: String?, ukuran: Int) {
    val m = Modifier.size(ukuran.dp).clip(CircleShape).background(Color(0xFFE2E8F0))
    if (path.isNullOrBlank()) {
        Box(m, Alignment.Center) { Text("👤", fontSize = (ukuran / 2).sp) }
        return
    }
    val ctx = LocalContext.current
    val req = remember(path) {
        ImageRequest.Builder(ctx).data(Sb.urlFoto(path))
            .addHeader("apikey", BuildConfig.SUPABASE_KEY)
            .addHeader("Authorization", "Bearer ${Sb.token}")
            .build()
    }
    AsyncImage(model = req, contentDescription = "Foto wajah", modifier = m, contentScale = ContentScale.Crop)
}

/** Header mengikuti pengaturan panel admin: template, logo, gambar banner, warna, ukuran, huruf, pencarian. */
@Composable
fun HeaderAwd(
    t: Tampilan,
    query: String = "",
    onQuery: ((String) -> Unit)? = null,
    lonceng: (@Composable () -> Unit)? = null,
) {
    val c1 = warna(t.c1, Color(0xFF15803D))
    val c2 = warna(t.c2, Color(0xFF22C55E))
    val tx = warna(t.nc, Color.White)
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = t.h.coerceIn(100, 260).dp)
            .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)),
    ) {
        when {
            t.tpl == "polos" -> Box(Modifier.matchParentSize().background(c1))
            t.tpl == "banner" && t.bg.isNotBlank() -> {
                GambarJauh(t.bg, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(c1.copy(alpha = .7f), c2.copy(alpha = .7f)))))
            }
            else -> Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(c1, c2))))
        }
        val logo: @Composable () -> Unit = {
            val u = t.ls.coerceIn(32, 120)
            if (t.logo.isNotBlank()) {
                GambarJauh(t.logo, Modifier.size(u.dp).clip(RoundedCornerShape(14.dp)), ContentScale.Fit)
            } else {
                Box(Modifier.size(u.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .25f)), Alignment.Center) {
                    Text("🛵", fontSize = (u / 2).sp)
                }
            }
        }
        val nama: @Composable () -> Unit = {
            Column {
                Text(t.nama, color = tx, fontSize = t.ns.coerceIn(16, 40).sp, fontWeight = FontWeight.Bold, fontFamily = fontDari(t.nf))
                if (t.so) Text(t.sl, color = tx.copy(alpha = .85f), fontSize = 12.sp)
            }
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val pad = Modifier.padding(end = if (lonceng != null) 44.dp else 0.dp)
            when (t.lp) {
                "tengah" -> Column(Modifier.fillMaxWidth().then(pad), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) { logo(); nama() }
                "kanan" -> Row(Modifier.fillMaxWidth().then(pad), Arrangement.spacedBy(12.dp, Alignment.End), Alignment.CenterVertically) { nama(); logo() }
                else -> Row(Modifier.fillMaxWidth().then(pad), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) { logo(); nama() }
            }
            if (t.sr && onQuery != null) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text(t.ph) },
                    textStyle = TextStyle(color = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (lonceng != null) Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { lonceng() }
    }
}

@Composable
fun Lonceng(jumlah: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f)).clickable(onClick = onClick),
        Alignment.Center,
    ) {
        Text("🔔", fontSize = 20.sp)
        if (jumlah > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(1.dp).defaultMinSize(18.dp, 18.dp)
                    .clip(CircleShape).background(Color(0xFFDC2626)),
                Alignment.Center,
            ) { Text(if (jumlah > 9) "9+" else "$jumlah", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun AksiOrder(p: Pesanan, busy: Boolean, vm: AppVm, modifier: Modifier = Modifier) {
    val me = Sb.uid
    when {
        p.status == "Baru" && p.driverId == null ->
            Button({ vm.terima(p.id) }, modifier, enabled = !busy) { Text("Terima pesanan") }
        p.driverId == me && p.status == "Diproses" ->
            Button({ vm.ubahStatus(p.id, "Diantar") }, modifier, enabled = !busy) { Text("Sudah ambil, mulai antar") }
        p.driverId == me && p.status == "Diantar" ->
            Button({ vm.ubahStatus(p.id, "Selesai") }, modifier, enabled = !busy) { Text("Selesai diantar") }
    }
}

@Composable
fun OrderCard(p: Pesanan, busy: Boolean, vm: AppVm, onPeta: (Pesanan) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.kode, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(p.status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Text("${p.merchantNama ?: "-"} → ${p.pelangganNama ?: "-"}")
            Text("A  Ambil: ${p.alamatAmbil ?: "-"}")
            Text("B  Antar: ${p.alamatAntar ?: "-"}")
            if (!p.catatan.isNullOrBlank()) Text("Catatan: ${p.catatan}", color = Color.Gray)
            Text("Total ${rp(p.total)} (${p.metodeBayar ?: "-"}) · Pendapatan Anda ${rp(p.ongkir - p.komisi)}")
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { onPeta(p) }, modifier = Modifier.fillMaxWidth()) { Text("🗺 Lihat peta titik ambil dan antar") }
            AksiOrder(p, busy, vm, Modifier.fillMaxWidth())
        }
    }
}

// ---------- Konfigurasi dari AWD Admin ----------
fun fmtAngka(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** Banner promosi dari AWD Admin (target Driver/Semua). Tidak tampil bila admin belum mengisi. */
@Composable
fun BannerPromo(daftar: List<Spanduk>) {
    if (daftar.isEmpty()) return
    val ctx = LocalContext.current
    val pager = rememberPagerState(pageCount = { daftar.size })
    LaunchedEffect(daftar.size) {
        while (daftar.size > 1) {
            delay(4_000)
            pager.animateScrollToPage((pager.currentPage + 1) % daftar.size)
        }
    }
    HorizontalPager(
        state = pager, contentPadding = PaddingValues(horizontal = 12.dp), pageSpacing = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) { i ->
        val b = daftar[i]
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(14.dp))
                .clickable(enabled = b.tautan.startsWith("https://")) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.tautan))) }
                },
        ) {
            GambarJauh(b.gambar, Modifier.matchParentSize())
            if (b.judul.isNotBlank()) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(10.dp),
            ) {
                Column {
                    Text(b.judul, color = Color.White, fontWeight = FontWeight.Bold)
                    if (b.deskripsi.isNotBlank()) Text(b.deskripsi, color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                }
            }
        }
    }
}

/** Ketentuan yang ditetapkan admin. Hanya menampilkan yang sudah diisi admin (tidak ada angka bawaan). */
@Composable
fun KartuKetentuan(k: Konfigurasi) {
    val baris = buildList {
        if (k.layanan.isNotEmpty()) add("Layanan aktif" to k.layanan.joinToString(", ") { it.nama })
        k.sistem.komisiPersen?.let { add("Komisi platform" to "${fmtAngka(it)}%") }
        k.sistem.jam?.takeIf { it.isNotBlank() }?.let { add("Jam operasional" to it) }
        k.tarif.dasar?.let { add("Tarif dasar" to rp(it) + (k.tarif.jarakDasarKm?.let { j -> " (${fmtAngka(j)} km pertama)" } ?: "")) }
        k.tarif.perKm?.let { add("Tarif per km berikutnya" to rp(it)) }
        k.ketentuan.tawaranDetik?.let { add("Batas waktu terima order" to "$it detik") }
        k.ketentuan.minCair?.let { add("Minimum pencairan" to rp(it)) }
        k.ketentuan.biayaCair?.let { add("Biaya pencairan" to rp(it)) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ketentuan dari admin", fontWeight = FontWeight.Bold)
            if (baris.isEmpty()) Text("Belum ada ketentuan yang ditetapkan admin.", color = Color.Gray, fontSize = 13.sp)
            baris.forEach { (l, v) -> BarisProfil(l, v) }
        }
    }
}

@Composable
fun Pemeliharaan(s: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeaderAwd(s.tampilan)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sistem sedang dalam pemeliharaan", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Mohon coba lagi beberapa saat. Aplikasi akan terbuka otomatis setelah pemeliharaan selesai.", color = Color.Gray)
        }
        Kredit()
    }
}

// ---------- Login ----------
@Composable
fun LoginScreen(s: UiState, vm: AppVm) {
    var hp by remember { mutableStateOf("") }
    var sandi by remember { mutableStateOf("") }
    var daftarMode by remember { mutableStateOf(false) }
    val bolehDaftar = s.konfig.sistem.daftarDriver
    val daftar = daftarMode && bolehDaftar
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeaderAwd(s.tampilan)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (daftar) "Daftar sebagai driver" else "Masuk sebagai driver", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(hp, { hp = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, label = { Text("Nomor HP") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sandi, { sandi = it }, label = { Text(if (daftar) "Kata sandi (min. 6 karakter)" else "Kata sandi") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { if (daftar) vm.daftar(hp, sandi) else vm.masuk(hp, sandi) },
                enabled = !s.busy && hp.count { it.isDigit() } >= 9 && sandi.length >= 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (s.busy) "Memproses..." else if (daftar) "Daftar" else "Masuk") }
            if (bolehDaftar) TextButton(onClick = { daftarMode = !daftarMode }) { Text(if (daftar) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar") }
            else Text("Pendaftaran driver baru sedang ditutup oleh admin.", color = Color.Gray, fontSize = 13.sp)
            PesanBar(s, vm)
            if (daftar) Text("Setelah mendaftar, Anda wajib mengisi biodata dan foto wajah. Akun aktif setelah diverifikasi admin.", color = Color.Gray)
        }
        Kredit()
    }
}

// ---------- Biodata + foto wajah (wajib) ----------
@Composable
fun BiodataScreen(s: UiState, vm: AppVm, edit: Boolean, onTutup: () -> Unit) {
    val ctx = LocalContext.current
    val d = s.driver
    val p = s.profil
    var nama by remember { mutableStateOf(p?.nama.orEmpty()) }
    var nik by remember { mutableStateOf(d?.nik.orEmpty()) }
    var telepon by remember { mutableStateOf(p?.telepon.takeUnless { it.isNullOrBlank() } ?: Sb.hpLogin()) }
    var alamat by remember { mutableStateOf(d?.alamat.orEmpty()) }
    var kendaraan by remember { mutableStateOf(d?.kendaraan.orEmpty()) }
    var plat by remember { mutableStateOf(d?.plat.orEmpty()) }
    var foto by remember { mutableStateOf<ByteArray?>(null) }
    var errFoto by remember { mutableStateOf<String?>(null) }

    val berkas = remember { File(File(ctx.cacheDir, "foto").apply { mkdirs() }, "wajah.jpg") }
    val uriKamera = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", berkas) }
    val kamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) { foto = siapkanFoto(ctx, uriKamera); errFoto = if (foto == null) "Foto tidak bisa dibaca, coba lagi." else null }
    }
    val galeri = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { foto = siapkanFoto(ctx, uri); errFoto = if (foto == null) "Foto tidak bisa dibaca, coba lagi." else null }
    }
    val pratinjau = remember(foto) { foto?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val adaFoto = foto != null || !d?.fotoDiri.isNullOrBlank()
    val valid = nama.isNotBlank() && telepon.length >= 9 && nik.length == 16 && alamat.isNotBlank() &&
        kendaraan.isNotBlank() && plat.isNotBlank() && adaFoto

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeaderAwd(s.tampilan)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (edit) "Ubah biodata" else "Lengkapi biodata", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!edit) Text("Biodata dan foto wajah wajib diisi sebelum Anda bisa memakai aplikasi dan menerima order.", color = Color.Gray)

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pratinjau != null) Image(pratinjau, "Foto wajah", Modifier.size(120.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else FotoWajah(d?.fotoDiri, 120)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { kamera.launch(uriKamera) }) { Text("Ambil foto wajah") }
                    OutlinedButton(onClick = { galeri.launch("image/*") }) { Text("Pilih dari galeri") }
                }
                Text(
                    errFoto ?: if (adaFoto) "Foto siap. Pastikan wajah terlihat jelas." else "Foto wajah wajib (wajah terlihat jelas, tanpa masker/kacamata hitam).",
                    color = if (errFoto != null || !adaFoto) MaterialTheme.colorScheme.error else Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }

            val lebar = Modifier.fillMaxWidth()
            OutlinedTextField(nama, { nama = it }, label = { Text("Nama lengkap (sesuai KTP)") }, singleLine = true, modifier = lebar)
            OutlinedTextField(nik, { nik = it.filter(Char::isDigit).take(16) }, label = { Text("NIK (16 digit)") }, singleLine = true,
                isError = nik.isNotEmpty() && nik.length != 16, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = lebar)
            OutlinedTextField(telepon, { telepon = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, label = { Text("Nomor HP / WhatsApp") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = lebar)
            OutlinedTextField(alamat, { alamat = it }, label = { Text("Alamat lengkap") }, minLines = 2, modifier = lebar)
            OutlinedTextField(kendaraan, { kendaraan = it }, label = { Text("Kendaraan (mis. Honda Beat)") }, singleLine = true, modifier = lebar)
            OutlinedTextField(plat, { plat = it.uppercase() }, label = { Text("Plat nomor") }, singleLine = true, modifier = lebar)

            PesanBar(s, vm)
            Button(
                onClick = { vm.simpanBiodata(nama, nik, telepon, alamat, kendaraan, plat, foto) { onTutup() } },
                enabled = valid && !s.busy, modifier = lebar,
            ) { Text(if (s.busy) "Menyimpan..." else if (edit) "Simpan perubahan" else "Simpan dan lanjutkan") }
            if (edit) TextButton(onClick = onTutup, modifier = lebar) { Text("Batal") }
            else TextButton(onClick = vm::keluar, modifier = lebar) { Text("Keluar") }
        }
        Kredit()
    }
}

// ---------- Beranda ----------
@Composable
fun Beranda(s: UiState, vm: AppVm, onPeta: (Pesanan) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    val pool = s.pool()
    val d = s.driver
    val minta = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Izin.lokasi(ctx)) vm.setOnline(true)
        else vm.tampilError("Izin lokasi diperlukan agar Anda bisa online dan posisi terkirim ke admin.")
    }
    val daftar = pool.filter { o ->
        q.isBlank() || listOf(o.kode, o.merchantNama, o.pelangganNama, o.alamatAmbil, o.alamatAntar).any { it?.contains(q, true) == true }
    }
    val banner = s.konfig.spanduk.filter { it.tayang(LocalDate.now().toString()) }
    val tugasAktif = s.pesanan.count { it.driverId == d?.userId && it.status in listOf("Diproses", "Diantar") }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HeaderAwd(s.tampilan, q, { q = it }) {
                Lonceng(pool.size) { Alert.hentikan(ctx); dialog = true }
            }
        }
        if (banner.isNotEmpty()) item { BannerPromo(banner) }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        when {
                            d == null -> Text("Akun belum terdaftar sebagai driver.")
                            d.status != "Aktif" -> {
                                Text("Menunggu verifikasi admin", fontWeight = FontWeight.SemiBold)
                                Text("Status: ${d.status}. Anda bisa online setelah akun diaktifkan.", color = Color.Gray, fontSize = 13.sp)
                            }
                            else -> {
                                Text(if (d.online) "Online, siap menerima order" else "Offline", fontWeight = FontWeight.SemiBold)
                                Text("${d.kendaraan ?: "-"} · ${d.plat ?: "-"} · ★ ${d.rating}", color = Color.Gray, fontSize = 13.sp)
                                Text("Tugas aktif: $tugasAktif · Order baru: ${pool.size}", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                    if (d != null && d.status == "Aktif") {
                        Switch(
                            checked = d.online, enabled = !s.busy,
                            onCheckedChange = { on ->
                                if (!on) vm.setOnline(false)
                                else if (Izin.lokasi(ctx) && Izin.notif(ctx)) vm.setOnline(true)
                                else minta.launch(buildList {
                                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                }.toTypedArray())
                            },
                        )
                    }
                }
            }
        }
        item { PesanBar(s, vm) }
        item { Text("Order masuk (${daftar.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        if (daftar.isEmpty()) item {
            Text(
                if (d?.online == true) "Belum ada order baru. Anda akan diberi nada dering saat order masuk." else "Aktifkan status Online untuk menerima order.",
                color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(daftar, key = { it.id }) { OrderCard(it, s.busy, vm, onPeta, Modifier.padding(horizontal = 12.dp)) }
        item { Kredit() }
    }

    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        confirmButton = { TextButton(onClick = { dialog = false }) { Text("Tutup") } },
        title = { Text("Notifikasi order") },
        text = {
            if (pool.isEmpty()) Text("Belum ada order baru.")
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pool.take(5).forEach { Text("${it.kode} · ${it.merchantNama ?: "-"} → ${it.alamatAntar ?: "-"}") }
                if (pool.size > 5) Text("dan ${pool.size - 5} order lainnya", color = Color.Gray)
            }
        },
    )
}

// ---------- Aktivitas ----------
@Composable
fun Aktivitas(s: UiState, vm: AppVm, onPeta: (Pesanan) -> Unit) {
    val ctx = LocalContext.current
    val me = Sb.uid
    var sub by remember { mutableIntStateOf(0) }
    val tugas = s.pesanan.filter { it.driverId == me && it.status in listOf("Diproses", "Diantar") }
    val riwayat = s.pesanan.filter { it.driverId == me && it.status in listOf("Selesai", "Dibatalkan") }
    val pendapatan = riwayat.filter { it.status == "Selesai" }.sumOf { it.ongkir - it.komisi }
    Column(Modifier.fillMaxSize()) {
        Text("Aktivitas", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        TabRow(selectedTabIndex = sub) {
            listOf("Tugas saya (${tugas.size})", "Riwayat (${riwayat.size})").forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t, maxLines = 1) })
            }
        }
        val daftar = if (sub == 0) tugas else riwayat
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            if (sub == 1) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Total pendapatan (order selesai)", color = Color.Gray)
                        Text(rp(pendapatan), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (daftar.isEmpty()) item { Text(if (sub == 0) "Belum ada tugas aktif." else "Belum ada riwayat.", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
            items(daftar, key = { it.id }) { OrderCard(it, s.busy, vm, onPeta) }
            item { Kredit() }
        }
    }
}

// ---------- Chat ----------
@Composable
fun ChatScreen(s: UiState, vm: AppVm) {
    var teks by remember { mutableStateOf("") }
    val ls = rememberLazyListState()
    LaunchedEffect(s.chat.size) { if (s.chat.isNotEmpty()) ls.scrollToItem(s.chat.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp)) {
            Text("Chat dengan Admin", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Hubungi admin untuk kendala order atau akun.", color = Color.Gray, fontSize = 13.sp)
        }
        PesanBar(s, vm)
        LazyColumn(Modifier.weight(1f), state = ls, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.chat.isEmpty()) item { Text("Belum ada pesan. Tulis pesan pertama Anda.", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
            items(s.chat, key = { it.id }) { m ->
                val saya = m.pengirim == "driver"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (saya) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (saya) MaterialTheme.colorScheme.primary else Color(0xFFE5E7EB),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(m.isi, Modifier.padding(10.dp), color = if (saya) Color.White else Color(0xFF111827)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(teks, { teks = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Tulis pesan…") }, maxLines = 3)
            Button(onClick = { vm.kirimChat(teks); teks = "" }, enabled = teks.isNotBlank() && !s.busy) { Text("Kirim") }
        }
        Text("by. Wong Arjawinangun 2026", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
    }
}

// ---------- Profil ----------
@Composable
fun Profil(s: UiState, vm: AppVm, onUbah: () -> Unit) {
    val d = s.driver
    val p = s.profil
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Profil", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        FotoWajah(d?.fotoDiri, 110)
        Text(p?.nama ?: "-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Status: ${d?.status ?: "-"} · ★ ${d?.rating ?: 5.0}", color = if (d?.status == "Aktif") Color(0xFF15803D) else Color(0xFF92400E), fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BarisProfil("Nomor HP", p?.telepon)
                BarisProfil("NIK", d?.nik?.let { "************" + it.takeLast(4) })
                BarisProfil("Alamat", d?.alamat)
                BarisProfil("Kendaraan", d?.kendaraan)
                BarisProfil("Plat nomor", d?.plat)
            }
        }
        KartuKetentuan(s.konfig)
        Button(onClick = onUbah, modifier = Modifier.fillMaxWidth()) { Text("Ubah biodata dan foto") }
        OutlinedButton(onClick = vm::keluar, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
        PesanBar(s, vm)
        Text("AWD Driver v${BuildConfig.VERSION_NAME}", color = Color.Gray, fontSize = 12.sp)
        Kredit()
    }
}

@Composable
private fun BarisProfil(label: String, nilai: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color.Gray, modifier = Modifier.weight(0.4f))
        Text(nilai?.ifBlank { "-" } ?: "-", modifier = Modifier.weight(0.6f))
    }
}
