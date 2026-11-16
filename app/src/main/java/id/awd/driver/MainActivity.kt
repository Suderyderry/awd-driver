package id.awd.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alert.buatChannel(this)
        // Polling di layar hanya saat aplikasi terlihat; saat online OrderService menjaga dering di latar belakang.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF15803D))) { App(vm) }
        }
    }
}

@Composable
fun App(vm: AppVm) {
    val s by vm.s.collectAsState()
    Surface(Modifier.fillMaxSize()) {
        when {
            s.loading -> Memuat(s, vm)
            s.konfig.sistem.pemeliharaan -> Pemeliharaan(s)
            !s.loggedIn -> LoginScreen(s, vm)
            !s.sudahMuat -> Memuat(s, vm)
            !s.lengkap() -> BiodataScreen(s, vm, edit = false) {}
            else -> Utama(s, vm)
        }
    }
}

@Composable
fun Memuat(s: UiState, vm: AppVm) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        if (s.error == null) CircularProgressIndicator() else {
            Text(s.error, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Button(onClick = vm::muatUlang, modifier = Modifier.padding(top = 12.dp)) { Text("Coba lagi") }
            TextButton(onClick = vm::keluar) { Text("Keluar") }
        }
    }
}

@Composable
fun Utama(s: UiState, vm: AppVm) {
    var nav by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var petaId by remember { mutableStateOf<Long?>(null) }
    val belumDibaca = s.chat.count { it.pengirim == "admin" && it.id > s.chatDilihat }
    LaunchedEffect(nav, s.chat.size) { if (nav == 2) vm.tandaiChatDibaca() }

    if (edit) {
        BiodataScreen(s, vm, edit = true) { edit = false }
        return
    }

    val petaOrder = petaId?.let { id -> s.pesanan.find { it.id == id } }
    if (petaOrder != null) {
        PetaOrder(petaOrder, s, vm) { petaId = null }
        return
    }

    val menu = listOf("🏠" to "Beranda", "📋" to "Aktivitas", "💬" to "Chat", "👤" to "Profil")
    val lencana = listOf(s.pool().size, 0, belumDibaca, 0)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (nav) {
                0 -> Beranda(s, vm) { petaId = it.id }
                1 -> Aktivitas(s, vm) { petaId = it.id }
                2 -> ChatScreen(s, vm)
                else -> Profil(s, vm) { edit = true }
            }
        }
        NavigationBar {
            menu.forEachIndexed { i, (ikon, label) ->
                NavigationBarItem(
                    selected = nav == i,
                    onClick = { nav = i },
                    icon = {
                        BadgedBox(badge = { if (lencana[i] > 0) Badge { Text("${lencana[i]}") } }) {
                            Text(ikon, fontSize = 20.sp)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}
