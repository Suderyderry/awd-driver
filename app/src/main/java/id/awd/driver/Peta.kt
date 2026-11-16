package id.awd.driver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Mencari koordinat dari teks alamat lewat OpenStreetMap Nominatim (gratis, tanpa API key).
 * Dibatasi 1 permintaan/detik sesuai aturan layanan dan hasilnya disimpan agar tidak diulang.
 */
object Geo {
    private val kunci = Mutex()
    private val gagal = HashSet<String>()
    private var terakhir = 0L

    suspend fun cari(ctx: Context, alamat: String?): GeoPoint? {
        val a = alamat?.trim().orEmpty()
        if (a.isEmpty()) return null
        val prefs = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE)
        prefs.getString(a, null)?.split(",")?.let {
            if (it.size == 2) return GeoPoint(it[0].toDouble(), it[1].toDouble())
        }
        if (a in gagal) return null
        val bagian = a.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // Coba alamat lengkap; bila tidak ketemu, coba desa/kecamatan saja (2 bagian terakhir).
        val hasil = permintaan(ctx, a) ?: if (bagian.size > 2) permintaan(ctx, bagian.takeLast(2).joinToString(", ")) else null
        if (hasil == null) { gagal.add(a); return null }
        prefs.edit().putString(a, "${hasil.latitude},${hasil.longitude}").apply()
        return hasil
    }

    private suspend fun permintaan(ctx: Context, q: String): GeoPoint? = kunci.withLock {
        val jeda = 1_100 - (System.currentTimeMillis() - terakhir)
        if (jeda > 0) delay(jeda)
        terakhir = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=id&q=" +
                    URLEncoder.encode(q, "UTF-8")
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.setRequestProperty("User-Agent", "AWDDriver/1.0 (${ctx.packageName})")
                    c.setRequestProperty("Accept-Language", "id")
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    if (c.responseCode !in 200..299) return@runCatching null
                    val arr = JSONArray(c.inputStream.bufferedReader().readText())
                    if (arr.length() == 0) null
                    else arr.getJSONObject(0).let { GeoPoint(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
        }
    }
}

fun jarakKm(a: GeoPoint, b: GeoPoint): Double {
    val r = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
    return r[0] / 1000.0
}

fun fmtKm(d: Double): String =
    if (d < 1) "${(d * 1000).toInt()} m" else String.format(Locale("id", "ID"), "%.1f km", d)

/** Buka navigasi (Google Maps bila ada, selain itu aplikasi peta apa pun). */
fun navigasi(ctx: Context, gp: GeoPoint) {
    val pilihan = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${gp.latitude},${gp.longitude}&mode=l"))
            .setPackage("com.google.android.apps.maps"),
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${gp.latitude},${gp.longitude}?q=${gp.latitude},${gp.longitude}")),
    )
    for (i in pilihan) if (runCatching { ctx.startActivity(i) }.isSuccess) return
}

private fun ikonPin(ctx: Context, huruf: String, warna: Int): Drawable {
    val d = ctx.resources.displayMetrics.density
    val w = (40 * d).toInt()
    val h = (52 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = warna
    c.drawCircle(w / 2f, w / 2f, w / 2f - 2 * d, p)
    val ekor = Path().apply {
        moveTo(w / 2f - 8 * d, w - 8 * d); lineTo(w / 2f, h.toFloat()); lineTo(w / 2f + 8 * d, w - 8 * d); close()
    }
    c.drawPath(ekor, p)
    p.color = android.graphics.Color.WHITE
    p.textAlign = Paint.Align.CENTER
    p.textSize = 20 * d
    p.isFakeBoldText = true
    c.drawText(huruf, w / 2f, w / 2f + 7 * d, p)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun ikonBulat(ctx: Context, warna: Int): Drawable {
    val d = ctx.resources.displayMetrics.density
    val u = (22 * d).toInt()
    val bmp = Bitmap.createBitmap(u, u, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(u / 2f, u / 2f, u / 2f, p)
    p.color = warna
    c.drawCircle(u / 2f, u / 2f, u / 2f - 3 * d, p)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun penanda(ctx: Context, map: MapView, gp: GeoPoint, ikon: Drawable, judul: String, sub: String?, pin: Boolean): Marker =
    Marker(map).apply {
        position = gp
        icon = ikon
        title = judul
        snippet = sub
        setAnchor(Marker.ANCHOR_CENTER, if (pin) Marker.ANCHOR_BOTTOM else Marker.ANCHOR_CENTER)
    }

/** Peta satu order: A = titik ambil (toko), B = titik antar (pelanggan), titik biru = posisi driver. */
@SuppressLint("MissingPermission")
@Composable
fun PetaOrder(p: Pesanan, s: UiState, vm: AppVm, onTutup: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onTutup)

    val ambilDb = remember(p.id, p.ambilLat, p.ambilLng) {
        if (p.ambilLat != null && p.ambilLng != null) GeoPoint(p.ambilLat, p.ambilLng) else null
    }
    val antarDb = remember(p.id, p.antarLat, p.antarLng) {
        if (p.antarLat != null && p.antarLng != null) GeoPoint(p.antarLat, p.antarLng) else null
    }
    var ambil by remember(p.id, ambilDb) { mutableStateOf(ambilDb) }
    var antar by remember(p.id, antarDb) { mutableStateOf(antarDb) }
    var mencari by remember(p.id) { mutableStateOf(false) }
    var saya by remember { mutableStateOf<GeoPoint?>(null) }

    LaunchedEffect(p.id, ambilDb, antarDb) {
        mencari = true
        if (ambil == null) ambil = Geo.cari(ctx, p.alamatAmbil)
        if (antar == null) antar = Geo.cari(ctx, p.alamatAntar)
        mencari = false
    }
    LaunchedEffect(Unit) {
        if (Izin.lokasi(ctx)) {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { l -> if (l != null) saya = GeoPoint(l.latitude, l.longitude) }
        }
    }

    val map = remember {
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(-6.7, 108.4))
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }

    LaunchedEffect(ambil, antar, saya) {
        val a = ambil
        val b = antar
        val me = saya
        map.overlays.clear()
        val titik = mutableListOf<GeoPoint>()
        if (a != null) {
            map.overlays.add(penanda(ctx, map, a, ikonPin(ctx, "A", android.graphics.Color.parseColor("#15803D")), "Titik ambil: ${p.merchantNama ?: "Toko"}", p.alamatAmbil, true))
            titik.add(a)
        }
        if (b != null) {
            map.overlays.add(penanda(ctx, map, b, ikonPin(ctx, "B", android.graphics.Color.parseColor("#DC2626")), "Titik antar: ${p.pelangganNama ?: "Pelanggan"}", p.alamatAntar, true))
            titik.add(b)
        }
        // Posisi driver ikut di tampilan hanya bila dekat (<25 km) agar peta tidak terlalu melebar.
        if (me != null) {
            map.overlays.add(penanda(ctx, map, me, ikonBulat(ctx, android.graphics.Color.parseColor("#2563EB")), "Posisi Anda", null, false))
            if (titik.isNotEmpty() && titik.minOf { jarakKm(me, it) } < 25) titik.add(me)
        }
        map.invalidate()
        if (titik.size >= 2 && jarakKm(titik.first(), titik.last()) > 0.05) {
            val box = BoundingBox.fromGeoPoints(titik)
            val pad = (60 * ctx.resources.displayMetrics.density).toInt()
            map.post { runCatching { map.zoomToBoundingBox(box, false, pad) } }
        } else if (titik.isNotEmpty()) {
            map.controller.setZoom(16.0)
            map.controller.setCenter(titik.first())
        }
    }

    val a = ambil
    val b = antar
    val berikutnyaAntar = p.status == "Diantar"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTutup) { Text("← Kembali") }
            Text("Peta ${p.kode}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(p.status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            if (mencari) Surface(
                Modifier.align(Alignment.TopCenter).padding(10.dp),
                shape = RoundedCornerShape(99.dp), color = Color(0xFF111827),
            ) { Text("Mencari lokasi dari alamat…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
        }
        Column(
            Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitikBaris(
                huruf = "A", warna = Color(0xFF15803D), judul = "Ambil · ${p.merchantNama ?: "Toko"}",
                alamat = p.alamatAmbil, titik = a, pasti = ambilDb != null, mencari = mencari,
                utama = !berikutnyaAntar, ctx = ctx,
            )
            TitikBaris(
                huruf = "B", warna = Color(0xFFDC2626), judul = "Antar · ${p.pelangganNama ?: "Pelanggan"}",
                alamat = p.alamatAntar, titik = b, pasti = antarDb != null, mencari = mencari,
                utama = berikutnyaAntar, ctx = ctx,
            )
            val info = buildList {
                if (saya != null && a != null) add("Anda ke titik ambil ±${fmtKm(jarakKm(saya!!, a))}")
                if (a != null && b != null) add("Ambil ke antar ±${fmtKm(jarakKm(a, b))}")
            }
            if (info.isNotEmpty()) Text(info.joinToString(" · ") + " (garis lurus)", color = Color.Gray, fontSize = 13.sp)
            if ((a != null && ambilDb == null) || (b != null && antarDb == null))
                Text("Titik bertanda perkiraan dari alamat. Konfirmasi lokasi ke toko/pelanggan bila perlu.", color = Color(0xFF92400E), fontSize = 13.sp)
            AksiOrder(p, s.busy, vm, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TitikBaris(
    huruf: String, warna: Color, judul: String, alamat: String?, titik: GeoPoint?,
    pasti: Boolean, mencari: Boolean, utama: Boolean, ctx: Context,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = warna, shape = RoundedCornerShape(99.dp)) {
            Text(huruf, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(judul + if (utama) " (tujuan berikutnya)" else "", fontWeight = if (utama) FontWeight.Bold else FontWeight.Normal)
            Text(alamat ?: "-", color = Color.Gray, fontSize = 13.sp)
            Text(
                when {
                    titik != null && pasti -> "Titik tepat dari data order"
                    titik != null -> "Titik perkiraan dari alamat"
                    mencari -> "Mencari lokasi…"
                    else -> "Lokasi tidak ditemukan dari alamat"
                },
                color = if (titik == null && !mencari) MaterialTheme.colorScheme.error else Color.Gray, fontSize = 12.sp,
            )
        }
        if (titik != null) {
            if (utama) Button(onClick = { navigasi(ctx, titik) }) { Text("Navigasi") }
            else OutlinedButton(onClick = { navigasi(ctx, titik) }) { Text("Navigasi") }
        } else if (!mencari) {
            OutlinedButton(onClick = { bukaMaps(ctx, alamat) }) { Text("Cari di Maps") }
        }
    }
}
