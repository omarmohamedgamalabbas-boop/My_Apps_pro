package com.myapps.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyAppsApp(this) }
    }
}

private enum class Tab { HOME, SEND, RECEIVE, FILES, COMPUTER, SETTINGS }

@Composable
private fun MyAppsApp(context: Context) {
    var dark by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    val scope = rememberCoroutineScope()
    var senderFiles by remember { mutableStateOf<List<TransferProtocol.SelectedFile>>(emptyList()) }
    var status by remember { mutableStateOf("جاهز") }
    var progress by remember { mutableIntStateOf(0) }
    var receiverInfo by remember { mutableStateOf<ReceiverInfo?>(null) }
    var server by remember { mutableStateOf<ServerSocket?>(null) }
    var selfDestruct by remember { mutableIntStateOf(0) }
    val computerServer = remember { ComputerShareServer(context) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            senderFiles = uris.map { TransferProtocol.queryFile(context, it) }
            status = "تم اختيار ${senderFiles.size} ملف"
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val files = TransferProtocol.queryTreeFiles(context, uri)
                scope.launch(Dispatchers.Main) {
                    senderFiles = files
                    status = if (files.isEmpty()) "المجلد فارغ" else "تم تجهيز ${files.size} ملف من المجلد"
                }
            }
        }
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        try {
            val uri = Uri.parse(result.contents)
            if (uri.scheme != "myapps" || uri.host != "connect") error("QR")
            val host = uri.getQueryParameter("host") ?: error("host")
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: error("port")
            val secret = uri.getQueryParameter("secret") ?: error("secret")
            if (senderFiles.isEmpty()) { status = "اختر الملفات أولًا"; return@rememberLauncherForActivityResult }
            status = "جارٍ الاتصال..."
            scope.launch(Dispatchers.IO) {
                try {
                    TransferProtocol.send(context, TransferProtocol.ShareTarget(host, port, secret, "My Apps"), senderFiles, selfDestruct) { p, s ->
                        scope.launch(Dispatchers.Main) { progress = p; status = s }
                    }
                    scope.launch(Dispatchers.Main) { progress = 100; status = "اكتمل النقل والتحقق من سلامة الملفات ✓" }
                } catch (e: Exception) {
                    scope.launch(Dispatchers.Main) { status = "انقطع الاتصال؛ أعد الإرسال وسيستأنف الملف الجزئي تلقائيًا" }
                }
            }
        } catch (_: Exception) { status = "QR غير صالح" }
    }

    val scheme = if (dark) darkColorScheme(primary = Color(0xFF49A6FF), secondary = Color(0xFF36D58A), background = Color(0xFF07121F), surface = Color(0xFF0D1C2C))
    else lightColorScheme(primary = Color(0xFF086DCC), secondary = Color(0xFF0A9F66))

    DisposableEffect(Unit) { onDispose { server?.close(); computerServer.stop() } }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("My Apps", fontWeight = FontWeight.Bold) }, actions = { IconButton({ dark = !dark }) { Icon(Icons.Default.DarkMode, "الوضع الليلي") } }) },
            bottomBar = {
                NavigationBar {
                    NavItem(tab == Tab.HOME, { tab = Tab.HOME }, "الرئيسية", Icons.Default.SwapHoriz)
                    NavItem(tab == Tab.SEND, { tab = Tab.SEND }, "إرسال", Icons.Default.Send)
                    NavItem(tab == Tab.RECEIVE, { tab = Tab.RECEIVE }, "استقبال", Icons.Default.QrCode2)
                    NavItem(tab == Tab.FILES, { tab = Tab.FILES }, "ملفاتي", Icons.Default.Folder)
                    NavItem(tab == Tab.COMPUTER, { tab = Tab.COMPUTER }, "كمبيوتر", Icons.Default.Computer)
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.HOME -> HomeScreen({ tab = Tab.SEND }, { tab = Tab.RECEIVE }, { tab = Tab.COMPUTER })
                    Tab.SEND -> SendScreen(senderFiles, status, progress, selfDestruct, { selfDestruct = it }, { pickFiles.launch(arrayOf("*/*")) }, { pickFolder.launch(null) }, {
                        if (senderFiles.isEmpty()) status = "اختر الملفات أولًا" else scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("امسح QR الخاص بالمستقبل"))
                    })
                    Tab.RECEIVE -> ReceiveScreen(receiverInfo, progress, status, {
                        if (receiverInfo == null) scope.launch(Dispatchers.IO) {
                            try {
                                val ss = ServerSocket(0)
                                val host = TransferProtocol.localIpv4() ?: "0.0.0.0"
                                val secret = newSecret()
                                scope.launch(Dispatchers.Main) { server = ss; receiverInfo = ReceiverInfo(host, ss.localPort, secret); status = "جاهز للاستقبال — اعرض QR" }
                                TransferProtocol.receive(context, ss, secret) { p, s -> scope.launch(Dispatchers.Main) { progress = p; status = s } }
                            } catch (_: Exception) { scope.launch(Dispatchers.Main) { status = "تعذر تشغيل الاستقبال" } }
                        }
                    }, { server?.close(); server = null; receiverInfo = null; status = "تم إيقاف الاستقبال" })
                    Tab.FILES -> FilesScreen(context)
                    Tab.COMPUTER -> ComputerScreen(context, computerServer)
                    Tab.SETTINGS -> SettingsScreen(context, dark) { dark = it }
                }
            }
        }
    }
}

@Composable private fun NavItem(selected: Boolean, onClick: () -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) = NavigationBarItem(selected, onClick, icon = { Icon(icon, label) }, label = { Text(label) })

@Composable private fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit, onComputer: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("My Apps", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Share More .. Without Limits", style = MaterialTheme.typography.titleMedium)
                Text("نقل ملفات وألعاب ضخمة محليًا، مشفرًا، مع استئناف تلقائي والتحقق من سلامة الملف.")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSend, Modifier.weight(1f).height(56.dp)) { Icon(Icons.Default.Send, null); Spacer(Modifier.size(6.dp)); Text("إرسال") }
            Button(onClick = onReceive, Modifier.weight(1f).height(56.dp)) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.size(6.dp)); Text("استقبال") }
        }
        OutlinedButton(onClick = onComputer, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Computer, null); Spacer(Modifier.size(8.dp)); Text("ربط الكمبيوتر") }
        FeatureCard(Icons.Default.Gamepad, "الألعاب الضخمة", "اختيار مجلد اللعبة ونقله مع الحفاظ على هيكل الملفات.")
        FeatureCard(Icons.Default.QrCode2, "QR سريع", "المستقبل يعرض رمزًا واحدًا والمرسل يمسحه.")
        FeatureCard(Icons.Default.UploadFile, "استئناف + تحقق", "الملف الجزئي يستكمل، ثم SHA-256 للتأكد أن الملف وصل سليمًا.")
    }
}

@Composable private fun FeatureCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) = Card(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(14.dp)); Column { Text(title, fontWeight = FontWeight.Bold); Text(body, style = MaterialTheme.typography.bodySmall) } } }

@Composable
private fun SendScreen(files: List<TransferProtocol.SelectedFile>, status: String, progress: Int, selfDestruct: Int, setSelfDestruct: (Int) -> Unit, onPick: () -> Unit, onFolder: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("إرسال", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onPick, Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.size(8.dp)); Text("اختيار ملفات") }
        OutlinedButton(onClick = onFolder, Modifier.fillMaxWidth()) { Icon(Icons.Default.Gamepad, null); Spacer(Modifier.size(8.dp)); Text("اختيار مجلد لعبة / مجلد كامل") }
        if (files.isNotEmpty()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("جاهز للإرسال: ${files.size} ملف", fontWeight = FontWeight.Bold); files.take(6).forEach { Text("• ${it.relativePath} (${formatBytes(it.size)})", style = MaterialTheme.typography.bodySmall) }; if (files.size > 6) Text("+ ${files.size - 6} ملفات") } }
        SelfDestructPicker(selfDestruct, setSelfDestruct)
        Button(onClick = onScan, Modifier.fillMaxWidth().height(56.dp), enabled = files.isNotEmpty()) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.size(8.dp)); Text("مسح QR والبدء") }
        ProgressCard(progress, status)
    }
}

@Composable private fun SelfDestructPicker(value: Int, setValue: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (value) { 10 -> "بعد 10 دقائق"; 60 -> "بعد ساعة"; 1440 -> "بعد يوم"; else -> "بدون حذف تلقائي" }
    Box { OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text("التدمير الذاتي: $label") }; DropdownMenu(open, { open = false }) { listOf(0 to "بدون حذف", 10 to "10 دقائق", 60 to "ساعة", 1440 to "يوم").forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { setValue(v); open = false }) } } }
}

@Composable private fun ReceiveScreen(info: ReceiverInfo?, progress: Int, status: String, onStart: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("استقبال", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (info == null) { Text("الجهازان يجب أن يكونا على نفس الشبكة المحلية في هذه النسخة."); Button(onClick = onStart, Modifier.fillMaxWidth().height(56.dp)) { Text("تشغيل الاستقبال") } }
        else {
            val data = "myapps://connect?host=${Uri.encode(info.host)}&port=${info.port}&secret=${Uri.encode(info.secret)}&name=My%20Apps"
            val bitmap = remember(data) { qrBitmap(data, 620) }
            Card(shape = RoundedCornerShape(24.dp)) { Image(bitmap.asImageBitmap(), null, Modifier.size(285.dp).padding(10.dp)) }
            Text("امسح هذا QR من جهاز الإرسال.", fontWeight = FontWeight.SemiBold)
            Text("${info.host}:${info.port}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("إيقاف الاستقبال") }
        }
        ProgressCard(progress, status)
    }
}

@Composable private fun ProgressCard(progress: Int, status: String) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(status, fontWeight = FontWeight.SemiBold); LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, Modifier.fillMaxWidth()); Text("$progress%", style = MaterialTheme.typography.labelMedium) } }

@Composable private fun FilesScreen(context: Context) {
    var files by remember { mutableStateOf(listReceivedFiles(context)) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("ملفاتي", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); IconButton({ files = listReceivedFiles(context) }) { Icon(Icons.Default.Folder, "تحديث") } }
        if (files.isEmpty()) Text("لا توجد ملفات مستلمة بعد.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(files) { f -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null); Spacer(Modifier.size(10.dp)); Column { Text(f.name, fontWeight = FontWeight.SemiBold); Text(formatBytes(f.length()), style = MaterialTheme.typography.bodySmall) } } } } }
    }
}

@Composable private fun ComputerScreen(context: Context, server: ComputerShareServer) {
    var running by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ربط الكمبيوتر", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("افتح الرابط على الكمبيوتر المتصل بنفس الشبكة. لا يحتاج الكمبيوتر لتثبيت برنامج My Apps.")
        Button(onClick = { running = server.start(); host = TransferProtocol.localIpv4() }, Modifier.fillMaxWidth(), enabled = !running) { Icon(Icons.Default.Computer, null); Spacer(Modifier.size(8.dp)); Text("تشغيل رابط الكمبيوتر") }
        if (running && host != null) {
            val url = "http://${host}:${server.port}/?token=${Uri.encode(server.token)}"
            val qr = remember(url) { qrBitmap(url, 620) }
            Text(url, fontWeight = FontWeight.SemiBold)
            Card(shape = RoundedCornerShape(22.dp)) { Image(qr.asImageBitmap(), null, Modifier.size(270.dp).padding(10.dp)) }
            Text("يمكن رفع الملفات من الكمبيوتر إلى الهاتف، وتنزيل ملفات My Apps من الصفحة.")
            OutlinedButton(onClick = { server.stop(); running = false }, Modifier.fillMaxWidth()) { Text("إيقاف الرابط") }
        }
    }
}

@Composable private fun SettingsScreen(context: Context, dark: Boolean, setDark: (Boolean) -> Unit) {
    var backupStatus by remember { mutableStateOf("") }
    val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) backupStatus = "تم منح الصلاحيات — اضغط زر النسخ المطلوب"
        else backupStatus = "بعض الصلاحيات لم تُمنح"
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("الوضع الليلي", fontWeight = FontWeight.Bold); Text("نهاري / ليلي") }; Switch(checked = dark, onCheckedChange = setDark) } }
        Text("النسخ الاحتياطي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { permissionLauncher.launch(permissions) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Backup, null); Spacer(Modifier.size(8.dp)); Text("السماح بقراءة بيانات النسخ الاحتياطي") }
        BackupButton("نسخ جهات الاتصال", backupStatus) { backupStatus = runBackup(context, Manifest.permission.READ_CONTACTS) { BackupManager.exportContacts(context) } }
        BackupButton("نسخ رسائل SMS", backupStatus) { backupStatus = runBackup(context, Manifest.permission.READ_SMS) { BackupManager.exportSms(context) } }
        BackupButton("نسخ سجل المكالمات", backupStatus) { backupStatus = runBackup(context, Manifest.permission.READ_CALL_LOG) { BackupManager.exportCallLog(context) } }
        OutlinedButton(onClick = { shareAppApk(context) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.size(8.dp)); Text("دعوة My Apps بدون إنترنت") }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("My Apps 0.2.0", fontWeight = FontWeight.Bold); Text("نقل ملفات/مجلدات مشفر + QR + استئناف + تحقق SHA-256 + تدمير ذاتي + ربط كمبيوتر.") } }
    }
}

@Composable private fun BackupButton(title: String, status: String, action: () -> Unit) { OutlinedButton(onClick = action, Modifier.fillMaxWidth()) { Icon(Icons.Default.Backup, null); Spacer(Modifier.size(8.dp)); Text(title) } }

private fun shareAppApk(context: Context) {
    try {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val apk = File(sharedDir, "My_Apps.apk")
        File(context.applicationInfo.sourceDir).inputStream().use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
        val uri = FileProvider.getUriForFile(context, "com.myapps.app.fileprovider", apk)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "دعوة My Apps بدون إنترنت"))
    } catch (_: Exception) { }
}

private fun runBackup(context: Context, permission: String, action: () -> File): String {
    return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) try { "تم الحفظ: ${action().name}" } catch (e: Exception) { "تعذر النسخ: ${e.message ?: "خطأ"}" } else "امنح الصلاحية أولًا"
}

data class ReceiverInfo(val host: String, val port: Int, val secret: String)
private fun newSecret(): String = android.util.Base64.encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
private fun qrBitmap(text: String, size: Int): Bitmap { val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size); val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE); return bmp }
private fun listReceivedFiles(context: Context): List<File> = File(context.getExternalFilesDir("Received"), "My Apps").let { dir -> dir.listFiles()?.flatMap { f -> if (f.isDirectory) f.walk().filter { it.isFile && !it.name.endsWith(".part") }.toList() else listOf(f) }?.sortedByDescending { it.lastModified() } ?: emptyList() }
private fun formatBytes(bytes: Long): String = when { bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024); bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024); bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0); else -> "$bytes B" }
