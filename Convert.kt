package com.example.pdftoolkit

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.*
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.util.Xml
import android.webkit.WebView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.min

object Convert {
    private const val FLAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    // ---------- Direct WebView -> PDF ----------

    suspend fun saveWebViewDirectly(ctx: Context, webView: WebView): String {
        val (bmp, title) = withContext(Dispatchers.Main) {
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(webView.width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val contentH = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(webView.height)
            val w = webView.width.coerceAtLeast(720)
            val h = contentH.coerceAtMost(14000) // Prevent OOM on extremely long pages
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            val pageTitle = (webView.title ?: "WebPage").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
            bitmap to pageTitle
        }

        return withContext(Dispatchers.IO) {
            try {
                val pageHeightPx = (bmp.width * 1.414f).toInt() // A4 Aspect Ratio
                val pdf = PdfDocument()
                var offsetY = 0
                var pageNum = 1
                while (offsetY < bmp.height) {
                    val sliceH = min(pageHeightPx, bmp.height - offsetY)
                    val slice = Bitmap.createBitmap(bmp, 0, offsetY, bmp.width, sliceH)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
                    val destRect = Rect(0, 0, 595, (595f * sliceH / bmp.width).toInt())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(slice, null, destRect, null)
                    pdf.finishPage(page)
                    slice.recycle()
                    offsetY += sliceH
                    pageNum++
                }
                val saved = Storage.save(ctx, "${title}_${System.currentTimeMillis() % 10000}.pdf", "application/pdf") { os ->
                    pdf.writeTo(os)
                }
                pdf.close()
                "✅ تم حفظ صفحة الويب في ${Storage.where(ctx)}\n$saved"
            } finally {
                bmp.recycle()
            }
        }
    }

    // ---------- Local Documents (DOCX with images/tables, TXT, HTML, MD, CSV) -> PDF ----------

    fun wordToPdf(ctx: Context, uri: Uri, out: String): String {
        val name = PdfOps.displayName(ctx, uri).lowercase()
        return when {
            name.endsWith(".docx") -> convertDocxRich(ctx, uri, out)
            name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") -> {
                val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")
                val raw = ins.use { String(it.readBytes(), Charsets.UTF_8) }
                if (raw.isBlank()) throw IllegalArgumentException("الملف فارغ")
                Storage.save(ctx, out, "application/pdf") { renderTextOnly(raw, it) }
            }
            name.endsWith(".html") || name.endsWith(".htm") -> {
                val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")
                val html = ins.use { String(it.readBytes(), Charsets.UTF_8) }
                val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                Storage.save(ctx, out, "application/pdf") { renderTextOnly(spanned, it) }
            }
            name.endsWith(".doc") ->
                throw IllegalArgumentException("صيغة doc القديمة غير مدعومة محلياً. استخدم أداة (التحويل الاحترافي السحابي) أو احفظها بصيغة docx.")
            else -> throw IllegalArgumentException("صيغة غير مدعومة محلياً. المدعوم: docx, txt, md, csv, html")
        }
    }

    private fun convertDocxRich(ctx: Context, uri: Uri, out: String): String {
        val sb = SpannableStringBuilder()
        val embeddedImages = mutableListOf<Bitmap>()

        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")
        ZipInputStream(ins).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val eName = entry.name
                if (eName == "word/document.xml") {
                    val bytes = zis.readBytes()
                    parseDocumentXml(bytes, sb)
                } else if (eName.startsWith("word/media/")) {
                    val imgBytes = zis.readBytes()
                    BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)?.let { bmp ->
                        embeddedImages.add(bmp)
                    }
                }
                entry = zis.nextEntry
            }
        }

        if (sb.isBlank() && embeddedImages.isEmpty()) {
            throw IllegalArgumentException("لم يتم العثور على محتوى داخل ملف Word")
        }

        return Storage.save(ctx, out, "application/pdf") { os ->
            PDDocument().use { doc ->
                // 1. Render formatted text and tables into pages
                if (sb.isNotBlank()) {
                    val pw = 595
                    val ph = 842
                    val margin = 42
                    val width = pw - 2 * margin
                    val avail = ph - 2 * margin
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 12f
                        color = Color.BLACK
                    }
                    val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(3f, 1.1f)
                        .setIncludePad(false)
                        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                        .build()

                    var line = 0
                    while (line < layout.lineCount) {
                        val top = layout.getLineTop(line)
                        var end = line
                        while (end < layout.lineCount && layout.getLineBottom(end) - top <= avail) end++
                        if (end == line) end = line + 1

                        val bmp = Bitmap.createBitmap(pw * 2, ph * 2, Bitmap.Config.ARGB_8888)
                        val c = Canvas(bmp)
                        c.drawColor(Color.WHITE)
                        c.scale(2f, 2f)
                        c.translate(margin.toFloat(), margin.toFloat())
                        c.clipRect(0f, 0f, width.toFloat(), avail.toFloat())
                        c.translate(0f, -top.toFloat())
                        layout.draw(c)

                        val page = PDPage(PDRectangle.A4)
                        doc.addPage(page)
                        val pdImg = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                        PDPageContentStream(doc, page).use { cs ->
                            cs.drawImage(pdImg, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                        }
                        bmp.recycle()
                        line = end
                    }
                }

                // 2. Append embedded images from the Word document
                for (imgBmp in embeddedImages) {
                    val page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    val pw = PDRectangle.A4.width
                    val ph = PDRectangle.A4.height
                    val margin = 36f
                    val scale = min((pw - 2 * margin) / imgBmp.width, (ph - 2 * margin) / imgBmp.height)
                    val dw = imgBmp.width * scale
                    val dh = imgBmp.height * scale
                    val pdImg = JPEGFactory.createFromImage(doc, imgBmp, 0.92f)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(pdImg, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
                    }
                    imgBmp.recycle()
                }
                doc.save(os)
            }
        }
    }

    private fun parseDocumentXml(xmlBytes: ByteArray, sb: SpannableStringBuilder) {
        val p = Xml.newPullParser()
        p.setInput(xmlBytes.inputStream(), "UTF-8")
        var paraStart = 0
        var heading = 0
        var list = false
        var bold = false
        var inTable = false
        var ev = p.eventType

        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "w:tbl" -> {
                        inTable = true
                        sb.append("\n────────────────────────\n")
                    }
                    "w:tc" -> {
                        if (inTable) sb.append(" | ")
                    }
                    "w:p" -> {
                        paraStart = sb.length
                        heading = 0
                        list = false
                    }
                    "w:pStyle" -> {
                        val v = p.getAttributeValue(null, "w:val") ?: ""
                        if (v.startsWith("Heading", ignoreCase = true)) {
                            heading = v.filter { it.isDigit() }.toIntOrNull() ?: 1
                        } else if (v.equals("Title", ignoreCase = true)) {
                            heading = 1
                        }
                    }
                    "w:numPr" -> list = true
                    "w:r" -> bold = false
                    "w:b" -> {
                        val v = p.getAttributeValue(null, "w:val")
                        if (v != "0" && v != "false") bold = true
                    }
                    "w:t" -> {
                        val t = p.nextText()
                        val s = sb.length
                        sb.append(t)
                        if (bold && t.isNotEmpty()) sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, FLAG)
                    }
                    "w:tab" -> sb.append("    ")
                    "w:br", "w:cr" -> sb.append('\n')
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "w:p" -> {
                        if (list) sb.insert(paraStart, "• ")
                        if (heading > 0 && sb.length > paraStart) {
                            val size = when (heading) { 1 -> 20; 2 -> 17; else -> 15 }
                            sb.setSpan(StyleSpan(Typeface.BOLD), paraStart, sb.length, FLAG)
                            sb.setSpan(AbsoluteSizeSpan(size, false), paraStart, sb.length, FLAG)
                        }
                        if (!inTable) sb.append('\n')
                    }
                    "w:tr" -> {
                        if (inTable) sb.append(" |\n")
                    }
                    "w:tbl" -> {
                        inTable = false
                        sb.append("────────────────────────\n")
                    }
                }
            }
            ev = p.next()
        }
    }

    private fun renderTextOnly(text: CharSequence, os: OutputStream) {
        val pw = 595
        val ph = 842
        val margin = 48
        val width = pw - 2 * margin
        val avail = ph - 2 * margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            color = Color.BLACK
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1.1f)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .build()
        val pdf = PdfDocument()
        try {
            var line = 0
            var no = 1
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= avail) end++
                if (end == line) end = line + 1
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, no).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                c.save()
                c.translate(margin.toFloat(), margin.toFloat())
                c.clipRect(0f, 0f, width.toFloat(), avail.toFloat())
                c.translate(0f, -top.toFloat())
                layout.draw(c)
                c.restore()
                pdf.finishPage(page)
                line = end
                no++
            }
            pdf.writeTo(os)
        } finally {
            pdf.close()
        }
    }

    // ---------- High-Fidelity Cloud Conversion (ConvertAPI) ----------

    fun cloudConvert(ctx: Context, uri: Uri, secret: String, targetExt: String, base: String): String {
        if (secret.isBlank()) {
            throw IllegalArgumentException("أدخل مفتاح ConvertAPI Secret أولاً (يمكنك الحصول على مفتاح مجاني من موقع convertapi.com).")
        }
        val fileName = PdfOps.displayName(ctx, uri)
        val sourceExt = fileName.substringAfterLast('.', "").lowercase()
        if (sourceExt.isEmpty()) throw IllegalArgumentException("تعذر معرفة امتداد الملف")
        if (sourceExt == targetExt) throw IllegalArgumentException("صيغة الملف هي نفسها الصيغة المطلوبة ($targetExt)")

        val inputBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("تعذر قراءة الملف")
        val base64Data = Base64.encodeToString(inputBytes, Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("Parameters", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("Name", "File")
                    put("FileValue", JSONObject().apply {
                        put("Name", fileName)
                        put("Data", base64Data)
                    })
                })
                put(JSONObject().apply {
                    put("Name", "StoreFile")
                    put("Value", false)
                })
            })
        }

        val endpoint = URL("https://v2.convertapi.com/convert/$sourceExt/to/$targetExt?Secret=$secret")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 90000
        }

        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val respStream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = respStream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""

        if (code !in 200..299) {
            throw IOException("فشل التحويل السحابي ($code): $respText")
        }

        val json = JSONObject(respText)
        val filesArray = json.getJSONArray("Files")
        if (filesArray.length() == 0) throw IOException("لم يُرجع الخادم أي ملف")

        val firstFile = filesArray.getJSONObject(0)
        val outDataBase64 = firstFile.getString("FileData")
        val outBytes = Base64.decode(outDataBase64, Base64.DEFAULT)

        val outMime = when (targetExt) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }

        return Storage.save(ctx, "${base}_converted.$targetExt", outMime) { os ->
            os.write(outBytes)
        }
    }

    // ---------- PDF -> JPG images ----------

    fun pdfToImages(ctx: Context, uri: Uri, spec: String, base: String): String {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("تعذر فتح الملف")
        var count = 0
        try {
            pfd.use {
                PdfRenderer(it).use { r ->
                    val idx = if (spec.isBlank()) (0 until r.pageCount).toList() else PdfOps.parsePages(spec, r.pageCount)
                    for (i in idx) {
                        r.openPage(i).use { p ->
                            val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            Storage.save(ctx, "${base}_page${i + 1}.jpg", "image/jpeg") { os ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
                            }
                            bmp.recycle()
                            count++
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            throw IllegalArgumentException("الملف محمي بكلمة مرور. أزل الحماية أولاً ثم أعد المحاولة.")
        }
        return "$count صورة"
    }

    // ---------- Extract embedded images ----------

    fun extractImages(ctx: Context, uri: Uri, spec: String, base: String): String {
        var n = 0
        PdfOps.open(ctx, uri).use { doc ->
            val idx = if (spec.isBlank()) (0 until doc.numberOfPages).toList() else PdfOps.parsePages(spec, doc.numberOfPages)
            for (i in idx) {
                val res = doc.getPage(i).resources ?: continue
                for (name in res.xObjectNames) {
                    val x = res.getXObject(name)
                    if (x is PDImageXObject) {
                        val bmp = x.image
                        if (bmp.width < 40 || bmp.height < 40) {
                            bmp.recycle()
                            continue
                        }
                        n++
                        Storage.save(ctx, "${base}_img$n.png", "image/png") { os ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                        }
                        bmp.recycle()
                    }
                }
            }
        }
        if (n == 0) throw IllegalArgumentException("لا توجد صور داخل الملف")
        return "$n صورة"
    }

    // ---------- PDF -> Word (with text & extracted images) ----------

    fun pdfToWord(ctx: Context, uri: Uri, out: String): String {
        val body = StringBuilder()
        val mediaImages = mutableListOf<Pair<String, ByteArray>>()
        var anyContent = false

        PdfOps.open(ctx, uri).use { doc ->
            val stripper = PDFTextStripper()
            val total = doc.numberOfPages
            var imgIndex = 1

            for (p in 1..total) {
                stripper.startPage = p
                stripper.endPage = p
                val t = stripper.getText(doc)
                if (t.isNotBlank()) anyContent = true
                t.split(Regex("\\r?\\n")).filter { it.isNotBlank() }.forEach { body.append(paragraph(it)) }

                // Extract page images and embed into Word document
                val res = doc.getPage(p - 1).resources
                if (res != null) {
                    for (name in res.xObjectNames) {
                        val x = res.getXObject(name)
                        if (x is PDImageXObject) {
                            val bmp = x.image
                            if (bmp.width >= 60 && bmp.height >= 60) {
                                val baos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
                                val rId = "rIdImg$imgIndex"
                                val mediaName = "image$imgIndex.png"
                                mediaImages.add(rId to baos.toByteArray())
                                body.append(imageParagraph(rId, imgIndex, bmp.width, bmp.height))
                                imgIndex++
                                anyContent = true
                            }
                            bmp.recycle()
                        }
                    }
                }

                if (p < total) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        if (!anyContent) throw IllegalArgumentException("لا يوجد نص أو صور قابلة للاستخراج")
        val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        return Storage.save(ctx, out, mime) { os -> writeDocxWithImages(body.toString(), mediaImages, os) }
    }

    private fun paragraph(line: String): String {
        val rtl = line.any { it in '\u0600'..'\u06FF' }
        val esc = line.filter { it.code >= 32 || it == '\t' }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ppr = if (rtl) "<w:pPr><w:bidi/></w:pPr>" else ""
        val rpr = if (rtl) "<w:rPr><w:rtl/></w:rPr>" else ""
        return "<w:p>$ppr<w:r>$rpr<w:t xml:space=\"preserve\">$esc</w:t></w:r></w:p>"
    }

    private fun imageParagraph(rId: String, idx: Int, wPx: Int, hPx: Int): String {
        val maxCx = 5000000L // ~14cm in EMUs
        val scale = if (wPx > 0) min(1.0, maxCx.toDouble() / (wPx * 9525.0)) else 1.0
        val cx = (wPx * 9525.0 * scale).toLong().coerceAtLeast(952500L)
        val cy = (hPx * 9525.0 * scale).toLong().coerceAtLeast(952500L)
        return """
            <w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
            <wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$idx" name="Picture $idx"/>
            <a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
            <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:nvPicPr><pic:cNvPr id="$idx" name="image$idx.png"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="$rId" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
            <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
            </pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
        """.trimIndent()
    }

    private fun writeDocxWithImages(body: String, images: List<Pair<String, ByteArray>>, os: OutputStream) {
        val zip = ZipOutputStream(os)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        fun putStr(name: String, content: String) = put(name, content.toByteArray(Charsets.UTF_8))

        putStr(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        )
        putStr(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        )

        val docRels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        images.forEachIndexed { i, (rId, bytes) ->
            val idx = i + 1
            docRels.append("""<Relationship Id="$rId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image$idx.png"/>""")
            put("word/media/image$idx.png", bytes)
        }
        docRels.append("</Relationships>")
        putStr("word/_rels/document.xml.rels", docRels.toString())

        putStr(
            "word/document.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>"""
        )
        zip.finish()
    }
}
