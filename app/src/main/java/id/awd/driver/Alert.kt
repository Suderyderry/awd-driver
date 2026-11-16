package id.awd.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object Izin {
    fun lokasi(c: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    fun notif(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

object Lokasi {
    @SuppressLint("MissingPermission")
    fun kirim(ctx: Context, scope: CoroutineScope) {
        if (!Izin.lokasi(ctx)) return
        LocationServices.getFusedLocationProviderClient(ctx)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) scope.launch { runCatching { Sb.lokasi(loc.latitude, loc.longitude) } }
            }
    }
}

/** Dering + notifikasi saat ada order baru. Dipakai aplikasi (terlihat) dan OrderService (latar belakang). */
object Alert {
    const val CH_ORDER = "order_masuk"
    const val CH_LAYANAN = "layanan_online"
    private const val NID_ORDER = 2001
    private val dikenal = HashSet<Long>()

    fun buatChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val nada = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(
            NotificationChannel(CH_ORDER, "Order masuk", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nada dering saat ada order baru"
                setSound(nada, attr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LAYANAN, "Status online", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun bukaApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Bandingkan daftar order baru (pool) dengan yang sudah dikenal; bunyikan bila ada yang baru. */
    @Synchronized
    fun cek(ctx: Context, pool: List<Pesanan>) {
        val app = ctx.applicationContext
        val baru = pool.filter { dikenal.add(it.id) }
        dikenal.retainAll(pool.map { it.id }.toSet())
        if (pool.isEmpty()) { NotificationManagerCompat.from(app).cancel(NID_ORDER); return }
        if (baru.isNotEmpty()) bunyi(app, baru.first(), pool.size)
    }

    @SuppressLint("MissingPermission")
    private fun bunyi(ctx: Context, p: Pesanan, total: Int) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled() || !Izin.notif(ctx)) {
            // Cadangan bila notifikasi dimatikan: putar nada dering sekali.
            runCatching { RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.play() }
            return
        }
        val judul = if (total > 1) "$total order menunggu!" else "Order masuk!"
        val n = NotificationCompat.Builder(ctx, CH_ORDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(judul)
            .setContentText("${p.merchantNama ?: "Toko"} → ${p.alamatAntar ?: "-"}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .setContentIntent(bukaApp(ctx))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // dering berulang sampai dibuka / 30 detik
        nm.notify(NID_ORDER, n)
    }

    fun hentikan(ctx: Context) = NotificationManagerCompat.from(ctx.applicationContext).cancel(NID_ORDER)

    @Synchronized
    fun reset(ctx: Context) { dikenal.clear(); hentikan(ctx) }
}
