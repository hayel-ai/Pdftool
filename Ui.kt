package com.example.pdftoolkit

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App() {
    var current by remember { mutableStateOf<Tool?>(null) }
    var web by remember { mutableStateOf(false) }
    BackHandler(enabled = current != null || web) {
        current = null
        web = false
    }
    val c = current
    when {
        web -> WebScreen(onBack = { web = false })
        c != null -> ToolScreen(c, onBack = { current = null })
        else -> Home(onTool = { current = it }, onWeb = { web = true })
    }
}

@Composable
fun Home(onTool: (Tool) -> Unit, onWeb: () -> Unit) {
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf(Storage.hasTree(ctx)) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Storage.setTree(ctx, uri)
            custom = true
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PDFix",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "أدوات PDF الشاملة",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "الحفظ في: ${Storage.where(ctx)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { treePicker.launch(null) }) { Text("📁 تغيير مجلد الحفظ") }
            if (custom) {
                TextButton(onClick = {
                    Storage.setTree(ctx, null)
                    custom = false
                }) { Text("الافتراضي") }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(Tool.values().toList()) { t -> ToolCard(t.emoji, t.title) { onTool(t) } }
            items(listOf(0)) { ToolCard("🌐", "صفحة ويب إلى PDF", onWeb) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "تم التطوير بواسطة Hayel alkouri",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ToolCard(emoji: String, title: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().height(108.dp).clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ToolScreen(tool: Tool, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pages by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var docTitle by remember { mutableStateOf("") }
    var newDocFontSize by remember { mutableStateOf(14f) }
    var isRtl by remember { mutableStateOf(true) }

    // Watermark states
    var wmSize by remember { mutableStateOf(48f) }
    var wmAlpha by remember { mutableStateOf(0.35f) }
    var wmAngle by remember { mutableStateOf(45) }
    var wmColor by remember { mutableStateOf(Color(0xFF555555)) }

    // Rotate angle
    var angle by remember { mutableStateOf(90) }
    var quality by remember { mutableStateOf(1) }

    // Cloud API key
    var apiKey by remember { mutableStateOf(Storage.getApiKey(ctx)) }
    var targetFormat by remember { mutableStateOf("pdf") }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val mime = tool.mimes?.toTypedArray() ?: if (tool.images) arrayOf("image/*") else arrayOf("application/pdf")
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) files = it
    }
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) files = listOf(it)
    }
    val names = remember(files) { files.associateWith { PdfOps.displayName(ctx, it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← رجوع") }
            Text("${tool.emoji} ${tool.title}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(tool.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (tool.requiresFile) {
            Button(onClick = { if (tool.multi) multiPicker.launch(mime) else singlePicker.launch(mime) }) {
                Text(if (tool.images) "اختيار الصور" else if (tool.multi) "اختيار الملفات" else "اختيار ملف")
            }

            files.forEachIndexed { i, u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}. ${names[u] ?: ""}",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (tool.multi && i > 0) {
                        TextButton(onClick = {
                            val l = files.toMutableList()
                            val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t
                            files = l
                        }) { Text("⬆") }
                    }
                }
            }
        }

        if (Need.CREATE_NEW in tool.needs) {
            OutlinedTextField(
                value = docTitle,
                onValueChange = { docTitle = it },
                label = { Text("عنوان المستند (اختياري)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("محتوى النص") },
                minLines = 6,
                maxLines = 15,
                modifier = Modifier.fillMaxWidth()
            )
            Text("حجم الخط: ${newDocFontSize.toInt()} pt")
            Slider(
                value = newDocFontSize,
                onValueChange = { newDocFontSize = it },
                valueRange = 10f..28f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRtl) Button(onClick = { isRtl = true }) { Text("عربي (يمين لليسار)") }
                else OutlinedButton(onClick = { isRtl = true }) { Text("عربي (يمين لليسار)") }

                if (!isRtl) Button(onClick = { isRtl = false }) { Text("English (LTR)") }
                else OutlinedButton(onClick = { isRtl = false }) { Text("English (LTR)") }
            }
        }

        if (Need.PAGES in tool.needs || Need.PAGES_OPTIONAL in tool.needs) {
            OutlinedTextField(
                value = pages,
                onValueChange = { pages = it },
                label = { Text(if (Need.PAGES in tool.needs) "الصفحات" else "الصفحات (اختياري)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (Need.ANGLE in tool.needs) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(90, 180, 270).forEach { a ->
                    if (angle == a) Button(onClick = { angle = a }) { Text("$a°") }
                    else OutlinedButton(onClick = { angle = a }) { Text("$a°") }
                }
            }
        }

        if (Need.TEXT in tool.needs) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("نص العلامة المائية") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (Need.WATERMARK_PRO in tool.needs) {
            Text("حجم الخط: ${wmSize.toInt()}", fontWeight = FontWeight.Medium)
            Slider(value = wmSize, onValueChange = { wmSize = it }, valueRange = 16f..110f)

            Text("الشفافية: ${(wmAlpha * 100).toInt()}%", fontWeight = FontWeight.Medium)
            Slider(value = wmAlpha, onValueChange = { wmAlpha = it }, valueRange = 0.08f..1.0f)

            Text("زاوية النص:", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "عادي (0°)", 45 to "مائل (45°)", 90 to "عمودي (90°)").forEach { (deg, label) ->
                    if (wmAngle == deg) Button(onClick = { wmAngle = deg }) { Text(label) }
                    else OutlinedButton(onClick = { wmAngle = deg }) { Text(label) }
                }
            }

            Text("لون النص:", fontWeight = FontWeight.Medium)
            val palette = listOf(
                Color(0xFF555555),
                Color(0xFF000000),
                Color(0xFFD32F2F),
                Color(0xFF1976D2),
                Color(0xFF388E3C),
                Color(0xFF7B1FA2),
                Color(0xFFF57C00)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEach { col ->
                    val selected = wmColor == col
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(col)
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                            .clickable { wmColor = col }
                    )
                }
            }
        }

        if (Need.CLOUD_API in tool.needs) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    Storage.setApiKey(ctx, it.trim())
                },
                label = { Text("مفتاح ConvertAPI Secret (مجاني من convertapi.com)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text("تحويل إلى صيغة:", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pdf" to "PDF", "docx" to "Word (docx)", "xlsx" to "Excel", "pptx" to "PowerPoint").forEach { (ext, label) ->
                    if (targetFormat == ext) Button(onClick = { targetFormat = ext }) { Text(label) }
                    else OutlinedButton(onClick = { targetFormat = ext }) { Text(label) }
                }
            }
        }

        if (Need.PASSWORD in tool.needs) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("كلمة المرور") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (Need.QUALITY in tool.needs) {
            val labels = listOf("جودة عالية", "متوسط", "أقصى ضغط")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    if (quality == i) Button(onClick = { quality = i }) { Text(l) }
                    else OutlinedButton(onClick = { quality = i }) { Text(l) }
                }
            }
        }

        val canRun = (!tool.requiresFile || files.isNotEmpty()) && !busy
        Button(
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                status = "جارٍ المعالجة..."
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) {
                            val wmOptions = WatermarkOptions(
                                size = wmSize,
                                alpha = wmAlpha,
                                colorInt = wmColor.toArgb(),
                                angle = wmAngle
                            )
                            val createOptions = CreatePdfOptions(
                                title = docTitle,
                                content = text,
                                fontSize = newDocFontSize,
                                isRtl = isRtl
                            )
                            PdfOps.run(
                                ctx = ctx,
                                tool = tool,
                                files = files,
                                pages = pages,
                                text = text,
                                angle = angle,
                                quality = quality,
                                wmOptions = wmOptions,
                                createOptions = createOptions,
                                apiKey = apiKey.trim(),
                                targetFormat = targetFormat
                            )
                        }
                    } catch (e: Throwable) {
                        "❌ " + (e.message ?: e.toString())
                    }
                    busy = false
                }
            }
        ) { Text("تنفيذ") }

        if (status.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://") }
    var loadingProgress by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    val holder = remember { arrayOfNulls<WebView>(1) }

    fun load() {
        val u = url.trim()
        if (u.isEmpty() || u == "https://") return
        val fullUrl = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
        url = fullUrl
        holder[0]?.loadUrl(fullUrl)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← رجوع") }
            Text("🌐 صفحة ويب إلى PDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("رابط الصفحة") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { load() }),
            modifier = Modifier.fillMaxWidth()
        )
        if (loadingProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { load() }) { Text("فتح الصفحة") }
            Button(onClick = {
                val wv = holder[0] ?: return@Button
                status = "جارٍ تجهيز الملف..."
                scope.launch {
                    status = try {
                        Convert.saveWebViewDirectly(ctx, wv)
                    } catch (e: Throwable) {
                        // Fallback to Android PrintManager if direct rendering fails
                        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        val jobName = "PDFix_Web_${System.currentTimeMillis()}"
                        pm.print(jobName, wv.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
                        "تم فتح نافذة الحفظ والطباعة، اختر (حفظ بصيغة PDF)."
                    }
                }
            }) { Text("حفظ مباشر كـ PDF") }
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        AndroidView(
            factory = { c ->
                WebView.enableSlowWholeDocumentDraw()
                WebView(c).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadingProgress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return false
                        }
                        override fun onPageStarted(view: WebView?, newUrl: String?, favicon: Bitmap?) {
                            if (newUrl != null) url = newUrl
                        }
                    }
                    holder[0] = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}
