package id.awd.driver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Layanan latar belakang selama driver Online: memeriksa order baru tiap 8 detik (berdering walau aplikasi
 * ditutup/layar mati) dan mengirim lokasi ke admin.
 */
class OrderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Alert.buatChannel(this)
        val notif = NotificationCompat.Builder(this, Alert.CH_LAYANAN)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("AWD Driver online")
            .setContentText("Menunggu order masuk")
            .setOngoing(true)
            .setContentIntent(Alert.bukaApp(this))
            .build()
        try {
            ServiceCompat.startForeground(this, 1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        berjalan = true
        if (job == null) {
            job = scope.launch {
                var tick = 0
                while (isActive) {
                    try {
                        if (!Sb.sesiAda()) { stopSelf(); break }
                        val d = Sb.driver()
                        if (d == null || !d.online) { stopSelf(); break }
                        Alert.cek(this@OrderService, Sb.pesanan().filter { it.status == "Baru" && it.driverId == null })
                        if (tick % 3 == 0) Lokasi.kirim(this@OrderService, this)
                    } catch (_: Exception) { /* jaringan putus: coba lagi */ }
                    tick++
                    delay(8_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        berjalan = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var berjalan = false

        fun mulai(ctx: Context) {
            if (!Izin.lokasi(ctx)) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, OrderService::class.java)) }
        }

        fun henti(ctx: Context) {
            ctx.stopService(Intent(ctx, OrderService::class.java))
        }
    }
}
