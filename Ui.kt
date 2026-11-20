package com.example.pdftoolkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        web -> WebScreen()
        c != null -> ToolScreen(c)
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
        Text("أدوات PDF", style = MaterialTheme.typography.headlineMedium)
        Text("النتائج تُحفظ في: ${Storage.where(ctx)}")
        Row {
            TextButton(onClick = { treePicker.launch(null) }) { Text("📁 تغيير مجلد الحفظ") }
            if (custom) {
                TextButton(onClick = {
                    Storage.setTree(ctx, null)
                    custom = false
                }) { Text("الافتراضي") }
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(Tool.values().toList()) { t -> ToolCard(t.emoji, t.title) { onTool(t) } }
            items(listOf(0)) { ToolCard("🌐", "صفحة ويب إلى PDF", onWeb) }
        }
    }
}

@Composable
fun ToolCard(emoji: String, title: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().height(110.dp).clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 30.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ToolScreen(tool: Tool) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pages by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var angle by remember { mutableStateOf(90) }
    var quality by remember { mutableStateOf(1) }
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
        Text("${tool.emoji} ${tool.title}", style = MaterialTheme.typography.headlineSmall)
        Text(tool.hint)

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
                value = text, onValueChange = { text = it },
                label = { Text("نص العلامة المائية") }, modifier = Modifier.fillMaxWidth()
            )
        }
        if (Need.PASSWORD in tool.needs) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
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

        Button(
            enabled = files.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                status = "جارٍ المعالجة..."
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) {
                            PdfOps.run(ctx, tool, files, pages, text, angle, quality)
                        }
                    } catch (e: Throwable) {
                        "❌ " + (e.message ?: e.toString())
                    }
                    busy = false
                }
            }
        ) { Text("تنفيذ") }

        if (status.isNotEmpty()) Text(status)
    }
}

@Composable
fun WebScreen() {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("https://") }
    val holder = remember { arrayOfNulls<WebView>(1) }

    fun load() {
        val u = url.trim()
        holder[0]?.loadUrl(if (u.startsWith("http")) u else "https://$u")
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("رابط الصفحة") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { load() }),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { load() }) { Text("فتح") }
            Button(onClick = {
                val wv = holder[0] ?: return@Button
                val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print("page", wv.createPrintDocumentAdapter("page"), PrintAttributes.Builder().build())
            }) { Text("حفظ كـ PDF") }
        }
        Text("بعد الضغط على «حفظ كـ PDF» اختر «حفظ بصيغة PDF» من نافذة الطباعة.")
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    holder[0] = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}
