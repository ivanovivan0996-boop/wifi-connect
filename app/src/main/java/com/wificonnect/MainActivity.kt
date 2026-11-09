package com.wificonnect

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors

data class Device(
    val ip: String,
    val services: List<Int>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WiFiApp(this) }
    }
}

@Composable
fun WiFiApp(context: Context) {
    var devices by remember { mutableStateOf(listOf<Device>()) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Подключитесь к Wi-Fi и начните поиск") }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("WiFi Connect", style = MaterialTheme.typography.headlineLarge)
                Text("Устройства вашей Wi-Fi сети")

                Button(
                    onClick = {
                        scope.launch {
                            scanning = true
                            devices = emptyList()
                            message = "Ищем устройства..."
                            val result = withContext(Dispatchers.IO) {
                                NetworkScanner.scan(context)
                            }
                            devices = result
                            message = if (result.isEmpty())
                                "Устройства с доступными службами не найдены"
                            else "Найдено устройств: ${result.size}"
                            scanning = false
                        }
                    },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (scanning) "Поиск..." else "Найти устройства")
                }

                Text(message)

                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(devices) { device ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(device.ip, style = MaterialTheme.typography.titleMedium)
                                Text("Доступные порты: ${device.services.joinToString()}")
                            }
                        }
                    }
                }
            }
        }
    }
}

object NetworkScanner {
    private val ports = listOf(22, 80, 443, 445, 554, 3389, 5900, 8080, 8443)

    fun scan(context: Context): List<Device> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        val network = cm.activeNetwork ?: return emptyList()
        val links = cm.getLinkProperties(network) ?: return emptyList()

        val address = links.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: return emptyList()

        val local = address.address as Inet4Address
        val prefix = address.prefixLength
        if (prefix !in 16..30) return emptyList()

        val localBytes = local.address.map { it.toInt() and 255 }
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val localInt = ((localBytes[0] shl 24) or
                (localBytes[1] shl 16) or
                (localBytes[2] shl 8) or localBytes[3])
        val networkInt = localInt and mask
        val count = (1 shl (32 - prefix)) - 2
        if (count !in 1..512) return emptyList()

        val pool = Executors.newFixedThreadPool(40)
        val results = java.util.Collections.synchronizedList(mutableListOf<Device>())
        try {
            val tasks = (1..count).map { offset ->
                pool.submit {
                    val target = networkInt + offset
                    val ip = listOf(
                        (target ushr 24) and 255,
                        (target ushr 16) and 255,
                        (target ushr 8) and 255,
                        target and 255
                    ).joinToString(".")
                    if (ip == local.hostAddress) return@submit

                    val found = ports.filter { port ->
                        try {
                            Socket().use { socket ->
                                socket.connect(
                                    java.net.InetSocketAddress(ip, port), 180
                                )
                            }
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (found.isNotEmpty()) results.add(Device(ip, found))
                }
            }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        return results.sortedBy { it.ip }
    }
}
