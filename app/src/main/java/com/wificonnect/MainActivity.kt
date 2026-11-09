package com.wificonnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

data class Device(
    val ip: String,
    val ports: List<Int>
) {
    val type: String
        get() = when {
            445 in ports || 139 in ports -> "SMB — общие папки"
            3389 in ports -> "Windows Remote Desktop"
            22 in ports -> "SSH — удалённый доступ"
            554 in ports -> "Медиаслужба / RTSP"
            8009 in ports -> "Google Cast"
            80 in ports || 443 in ports || 8080 in ports || 8443 in ports ->
                "Веб-интерфейс"
            else -> "Сетевое устройство"
        }
}

enum class Tab(val title: String) {
    NETWORK("Сеть"),
    FILES("Файлы"),
    REMOTE("Управление"),
    SAVED("Избранное")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WiFiConnectApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiConnectApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.NETWORK) }
    var devices by remember { mutableStateOf(emptyList<Device>()) }
    var trusted by remember { mutableStateOf(loadTrusted(context)) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Подключитесь к Wi-Fi и нажмите «Найти устройства».")
    }
    var selected by remember { mutableStateOf<Device?>(null) }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true
            status = "Сканирование локальной сети…"
            val result = withContext(Dispatchers.IO) {
                NetworkScanner.scan(context)
            }
            devices = result
            status = if (result.isEmpty()) {
                "Устройства с доступными службами не обнаружены. Проверьте Wi-Fi и настройки устройств."
            } else {
                "Поиск завершён. Найдено устройств: ${result.size}"
            }
            scanning = false
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF1769AA),
            secondary = androidx.compose.ui.graphics.Color(0xFF3977A8)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("WiFi Connect", fontWeight = FontWeight.Bold)
                            Text(
                                "Управление локальной сетью",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.values().forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                Text(
                                    when (item) {
                                        Tab.NETWORK -> "⌂"
                                        Tab.FILES -> "▤"
                                        Tab.REMOTE -> "▣"
                                        Tab.SAVED -> "★"
                                    }
                                )
                            },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    Tab.NETWORK -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SummaryCard("Найдено", devices.size.toString(), Modifier.weight(1f))
                            SummaryCard("Доверенных", trusted.size.toString(), Modifier.weight(1f))
                        }

                        Button(
                            onClick = { scan() },
                            enabled = !scanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (scanning) "Поиск устройств…" else "Найти устройства")
                        }

                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (devices.isEmpty() && !scanning) {
                            EmptyCard(
                                "Устройства пока не найдены",
                                "Запустите поиск. Устройство появится в списке, если в сети обнаружится доступная служба."
                            )
                        }

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(devices, key = { it.ip }) { device ->
                                DeviceCard(
                                    device,
                                    trusted.contains(device.ip)
                                ) { selected = device }
                            }
                        }
                    }

                    Tab.FILES -> {
                        PageTitle(
                            "Файлы",
                            "Подключение к разрешённым общим папкам и сетевым хранилищам."
                        )
                        InfoCard(
                            "Windows и NAS — SMB",
                            "Позволяет работать с общими папками после включения SMB на целевом устройстве и ввода разрешённых учётных данных."
                        )
                        InfoCard(
                            "WebDAV и SFTP",
                            "Подключение к серверам по адресу, имени пользователя и паролю или ключу."
                        )
                        InfoCard(
                            "Android и Apple",
                            "Для доступа к файлам телефона потребуется разрешённый обмен или компонент-компаньон."
                        )
                        Text(
                            "Файловый клиент ещё не подключён. Этот раздел не имитирует доступ к файлам.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Tab.REMOTE -> {
                        PageTitle(
                            "Удалённое управление",
                            "Доступ зависит от служб, включённых владельцем устройства."
                        )
                        InfoCard(
                            "Windows",
                            "Для удалённого рабочего стола нужен включённый RDP и разрешённая учётная запись."
                        )
                        InfoCard(
                            "macOS и Linux",
                            "Для терминального управления нужен SSH-сервер и авторизация."
                        )
                        InfoCard(
                            "Android и Apple",
                            "Для управления экраном и действиями потребуется приложение-компаньон с выданными разрешениями."
                        )
                        InfoCard(
                            "Smart TV",
                            "Возможности зависят от модели: управление, Cast, DLNA или фирменный протокол."
                        )
                    }

                    Tab.SAVED -> {
                        PageTitle(
                            "Избранные устройства",
                            "Сохранённые устройства этой сети."
                        )

                        val savedDevices = devices.filter { trusted.contains(it.ip) }

                        if (trusted.isEmpty()) {
                            EmptyCard(
                                "Список пуст",
                                "Найдите устройство в разделе «Сеть» и добавьте его в избранное."
                            )
                        }

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(savedDevices, key = { it.ip }) { device ->
                                DeviceCard(device, true) { selected = device }
                            }
                        }

                        if (trusted.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    saveTrusted(context, emptySet())
                                    trusted = emptySet()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Очистить избранное")
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { device ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Устройство ${device.ip}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Тип: ${device.type}")
                    Text("IP: ${device.ip}")
                    Text("Обнаруженные порты: ${device.ports.joinToString()}")

                    if (445 in device.ports) {
                        Text("Обнаружен SMB. Для доступа нужны имя пользователя и пароль.")
                    }
                    if (22 in device.ports) {
                        Text("Обнаружен SSH. Требуется авторизация.")
                    }

                    val webPort = device.ports.firstOrNull {
                        it == 443 || it == 80 || it == 8443 || it == 8080
                    }

                    if (webPort != null) {
                        TextButton(onClick = {
                            val scheme = if (webPort == 443 || webPort == 8443) "https" else "http"
                            val url = "$scheme://${device.ip}:$webPort"
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }
                        }) {
                            Text("Открыть веб-интерфейс")
                        }
                    }

                    TextButton(onClick = {
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("IP", device.ip)
                        )
                    }) {
                        Text("Скопировать IP")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = trusted.toMutableSet()
                    if (next.contains(device.ip)) next.remove(device.ip)
                    else next.add(device.ip)
                    saveTrusted(context, next)
                    trusted = next
                }) {
                    Text(
                        if (trusted.contains(device.ip)) "Убрать из избранного"
                        else "Добавить в избранное"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@Composable
fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun PageTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun EmptyCard(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun InfoCard(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun DeviceCard(device: Device, isTrusted: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    device.type,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(device.ip, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Порты: ${device.ports.joinToString()}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (isTrusted) {
                Text("★", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

fun loadTrusted(context: Context): Set<String> {
    return context.getSharedPreferences("wifi_connect", Context.MODE_PRIVATE)
        .getStringSet("trusted_ips", emptySet())?.toSet() ?: emptySet()
}

fun saveTrusted(context: Context, values: Set<String>) {
    context.getSharedPreferences("wifi_connect", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("trusted_ips", values.toSet())
        .apply()
}

object NetworkScanner {
    private val ports = listOf(
        22, 80, 443, 445, 554, 139,
        3389, 5900, 8009, 8080, 8443
    )

    fun scan(context: Context): List<Device> {
        val cm = context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val network = cm.activeNetwork ?: return emptyList()
        val capabilities = cm.getNetworkCapabilities(network) ?: return emptyList()

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return emptyList()
        }

        val links = cm.getLinkProperties(network) ?: return emptyList()
        val address = links.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress
        } ?: return emptyList()

        val local = address.address as Inet4Address
        val prefix = address.prefixLength
        if (prefix !in 16..30) return emptyList()

        val bytes = local.address.map { it.toInt() and 255 }
        val localInt =
            (bytes[0] shl 24) or
            (bytes[1] shl 16) or
            (bytes[2] shl 8) or bytes[3]

        val mask = -1 shl (32 - prefix)
        val networkInt = localInt and mask
        val count = (1 shl (32 - prefix)) - 2

        if (count !in 1..512) return emptyList()

        val pool = Executors.newFixedThreadPool(40)
        val found = Collections.synchronizedList(mutableListOf<Device>())

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

                    val open = ports.filter { port ->
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(ip, port), 220)
                            }
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }

                    if (open.isNotEmpty()) found.add(Device(ip, open))
                }
            }

            tasks.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        return found.sortedBy { it.ip }
    }
}
