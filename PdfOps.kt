package com.example.pdftoolkit

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

data class WatermarkOptions(
    val size: Float = 48f,
    val alpha: Float = 0.35f,
    val colorInt: Int = Color.GRAY,
    val angle: Int = 45
)

data class CreatePdfOptions(
    val title: String = "",
    val content: String = "",
    val fontSize: Float = 14f,
    val isRtl: Boolean = true
)

object Storage {
    private const val PREFS = "pdfix_prefs"
    private const val KEY = "out_tree"
    private const val API_KEY = "convert_api_secret"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasTree(ctx: Context): Boolean = prefs(ctx).getString(KEY, null) != null

    fun setTree(ctx: Context, uri: Uri?) {
        val e = prefs(ctx).edit()
        if (uri == null) e.remove(KEY) else e.putString(KEY, uri.toString())
        e.apply()
    }

    fun getApiKey(ctx: Context): String = prefs(ctx).getString(API_KEY, "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(API_KEY, key).apply()
    }

    fun where(ctx: Context): String = if (hasTree(ctx)) "المجلد الذي اخترته" else "التنزيلات/PDFix"

    fun save(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): String {
        val tree = prefs(ctx).getString(KEY, null)
        if (tree != null) {
            try {
                return saveToTree(ctx, Uri.parse(tree), name, mime, write)
            } catch (e: SecurityException) {
                setTree(ctx, null)
            } catch (e: java.io.FileNotFoundException) {
                setTree(ctx, null)
            }
        }
        return saveToDownloads(ctx, name, mime, write)
    }

    private fun saveToTree(ctx: Context, tree: Uri, name: String, mime: String, write: (OutputStream) -> Unit): String {
        val cr = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, mime, name)
            ?: throw IOException("تعذر الإنشاء في المجلد المختار")
        try {
            val os = cr.openOutputStream(doc) ?: throw IOException("تعذر الكتابة")
            os.use { write(it) }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            throw e
        }
        return name
    }

    private fun saveToDownloads(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): String {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PDFix")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذر إنشاء الملف")
        try {
            val os = cr.openOutputStream(uri) ?: throw IOException("تعذر الكتابة")
            os.use { write(it) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Throwable) {
            cr.delete(uri, null, null)
            throw e
        }
        return name
    }
}

object PdfOps {
    private const val PDF = "application/pdf"

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "file"
        }
        return "file"
    }

    private fun baseName(name: String) = name.substringBeforeLast('.', name)

    fun open(ctx: Context, uri: Uri, password: String = ""): PDDocument {
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")
        ins.use {
            return PDDocument.load(it, password, MemoryUsageSetting.setupMixed(50L * 1024 * 1024))
        }
    }

    private fun normalize(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '،' -> ','
                else -> c
            }
        )
    }

    fun parsePages(spec: String, total: Int): List<Int> {
        val result = linkedSetOf<Int>()
        normalize(spec).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val r = part.split("-").map { it.trim() }
            val a = r[0].toIntOrNull() ?: throw IllegalArgumentException("نطاق غير صحيح: $part")
            val b = if (r.size > 1) (r[1].toIntOrNull() ?: throw IllegalArgumentException("نطاق غير صحيح: $part")) else a
            if (a < 1 || b < a || b > total) throw IllegalArgumentException("الصفحات خارج النطاق (1-$total): $part")
            for (i in a..b) result.add(i - 1)
        }
        if (result.isEmpty()) throw IllegalArgumentException("لم تُحدَّد صفحات")
        return result.toList()
    }

    fun run(
        ctx: Context,
        tool: Tool,
        files: List<Uri>,
        pages: String,
        text: String,
        angle: Int,
        quality: Int,
        wmOptions: WatermarkOptions = WatermarkOptions(),
        createOptions: CreatePdfOptions = CreatePdfOptions(),
        apiKey: String = "",
        targetFormat: String = "pdf"
    ): String {
        if (tool == Tool.CREATE_PDF) {
            if (createOptions.content.isBlank() && createOptions.title.isBlank()) {
                throw IllegalArgumentException("اكتب عنواناً أو نصاً لإنشاء الملف")
            }
            val fileName = (createOptions.title.trim().ifEmpty { "PDFix_Document_${System.currentTimeMillis()}" })
                .replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf"
            val saved = createNewPdf(ctx, createOptions, fileName)
            return "✅ تم إنشاء وحفظ الملف في ${Storage.where(ctx)}\n$saved"
        }

        val first = files.first()
        val base = baseName(displayName(ctx, first))
        if (Need.PAGES in tool.needs && pages.isBlank()) throw IllegalArgumentException("أدخل أرقام الصفحات")
        if ((Need.TEXT in tool.needs || Need.PASSWORD in tool.needs) && text.isBlank())
            throw IllegalArgumentException("الحقل مطلوب")

        val result = when (tool) {
            Tool.CREATE_PDF -> ""
            Tool.MERGE -> {
                if (files.size < 2) throw IllegalArgumentException("اختر ملفين على الأقل")
                merge(ctx, files, "PDFix_merged.pdf")
            }
            Tool.IMAGES -> imagesToPdf(ctx, files, "PDFix_images.pdf")
            Tool.SPLIT -> split(ctx, first, pages, base)
            Tool.DELETE -> deletePages(ctx, first, pages, "${base}_edited.pdf")
            Tool.EXTRACT -> extractPages(ctx, first, pages, "${base}_extract.pdf")
            Tool.ROTATE -> rotate(ctx, first, pages, angle, "${base}_rotated.pdf")
            Tool.WATERMARK -> watermark(ctx, first, text, wmOptions, "${base}_watermark.pdf")
            Tool.LOCK -> lock(ctx, first, text, "${base}_locked.pdf")
            Tool.UNLOCK -> unlock(ctx, first, text, "${base}_unlocked.pdf")
            Tool.COMPRESS -> compress(ctx, first, quality, "${base}_compressed.pdf")
            Tool.WORD -> Convert.wordToPdf(ctx, first, "$base.pdf")
            Tool.CLOUD_CONVERT -> Convert.cloudConvert(ctx, first, apiKey, targetFormat, base)
            Tool.PDF_TO_IMAGES -> Convert.pdfToImages(ctx, first, pages, base)
            Tool.REORDER -> reorder(ctx, first, pages, "${base}_reordered.pdf")
            Tool.EXTRACT_IMAGES -> Convert.extractImages(ctx, first, pages, base)
            Tool.PDF_TO_WORD -> Convert.pdfToWord(ctx, first, "$base.docx")
        }
        return "✅ تم الحفظ في ${Storage.where(ctx)}\n$result"
    }

    private fun createNewPdf(ctx: Context, opts: CreatePdfOptions, out: String): String {
        val pw = 595
        val ph = 842
        val margin = 48
        val width = pw - 2 * margin
        val avail = ph - 2 * margin

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = opts.fontSize + 6f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = opts.fontSize
            color = Color.DKGRAY
        }
        val dir = if (opts.isRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR

        val fullText = buildString {
            if (opts.title.isNotBlank()) {
                append(opts.title.trim())
                append("\n\n")
            }
            append(opts.content)
        }

        val layout = StaticLayout.Builder.obtain(fullText, 0, fullText.length, bodyPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.15f)
            .setIncludePad(false)
            .setTextDirection(dir)
            .build()

        return Storage.save(ctx, out, PDF) { os ->
            val pdf = PdfDocument()
            try {
                var line = 0
                var pageNum = 1
                while (line < layout.lineCount) {
                    val top = layout.getLineTop(line)
                    var end = line
                    while (end < layout.lineCount && layout.getLineBottom(end) - top <= avail) end++
                    if (end == line) end = line + 1

                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNum).create())
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
                    pageNum++
                }
                pdf.writeTo(os)
            } finally {
                pdf.close()
            }
        }
    }

    private fun merge(ctx: Context, uris: List<Uri>, out: String): String {
        val streams = uris.map { ctx.contentResolver.openInputStream(it) ?: throw IOException("تعذر فتح ملف") }
        try {
            val merger = PDFMergerUtility()
            streams.forEach { merger.addSource(it) }
            return Storage.save(ctx, out, PDF) { os ->
                merger.destinationStream = os
                merger.mergeDocuments(MemoryUsageSetting.setupMixed(50L * 1024 * 1024))
            }
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
    }

    private fun split(ctx: Context, uri: Uri, spec: String, base: String): String {
        open(ctx, uri).use { src ->
            val total = src.numberOfPages
            val groups: List<List<Int>> = if (spec.isBlank()) {
                (0 until total).map { listOf(it) }
            } else {
                normalize(spec).split(",").filter { it.isNotBlank() }.map { parsePages(it, total) }
            }
            groups.forEachIndexed { n, g ->
                PDDocument().use { dst ->
                    g.forEach { dst.importPage(src.getPage(it)) }
                    Storage.save(ctx, "${base}_part${n + 1}.pdf", PDF) { dst.save(it) }
                }
            }
            return "${groups.size} ملف"
        }
    }

    private fun extractPages(ctx: Context, uri: Uri, spec: String, out: String): String {
        open(ctx, uri).use { src ->
            val idx = parsePages(spec, src.numberOfPages)
            PDDocument().use { dst ->
                idx.forEach { dst.importPage(src.getPage(it)) }
                return Storage.save(ctx, out, PDF) { dst.save(it) }
            }
        }
    }

    private fun reorder(ctx: Context, uri: Uri, spec: String, out: String): String {
        open(ctx, uri).use { src ->
            val total = src.numberOfPages
            val first = parsePages(spec, total)
            val order = first + (0 until total).filter { it !in first }
            PDDocument().use { dst ->
                order.forEach { dst.importPage(src.getPage(it)) }
                return Storage.save(ctx, out, PDF) { dst.save(it) }
            }
        }
    }

    private fun deletePages(ctx: Context, uri: Uri, spec: String, out: String): String {
        open(ctx, uri).use { doc ->
            val idx = parsePages(spec, doc.numberOfPages)
            if (idx.size >= doc.numberOfPages) throw IllegalArgumentException("لا يمكن حذف كل الصفحات")
            idx.sortedDescending().forEach { doc.removePage(it) }
            return Storage.save(ctx, out, PDF) { doc.save(it) }
        }
    }

    private fun rotate(ctx: Context, uri: Uri, spec: String, angle: Int, out: String): String {
        open(ctx, uri).use { doc ->
            val idx = if (spec.isBlank()) (0 until doc.numberOfPages).toList() else parsePages(spec, doc.numberOfPages)
            idx.forEach { i ->
                val p = doc.getPage(i)
                p.rotation = (p.rotation + angle) % 360
            }
            return Storage.save(ctx, out, PDF) { doc.save(it) }
        }
    }

    private fun textBitmap(text: String, colorInt: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            textSize = 180f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        var w = paint.measureText(text)
        if (w > 3200f) {
            paint.textSize = paint.textSize * 3200f / w
            w = paint.measureText(text)
        }
        val bw = max(100, w.toInt() + 80)
        val bh = max(60, (paint.textSize * 1.6f).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val y = bh / 2f - (paint.ascent() + paint.descent()) / 2f
        Canvas(bmp).drawText(text, bw / 2f, y, paint)
        return bmp
    }

    private fun watermark(ctx: Context, uri: Uri, text: String, opts: WatermarkOptions, out: String): String {
        open(ctx, uri).use { doc ->
            val bmp = textBitmap(text, opts.colorInt)
            val img = LosslessFactory.createFromImage(doc, bmp)
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = opts.alpha.coerceIn(0.05f, 1.0f)

            // Scale width proportional to selected font size (16..110 -> 0.25..1.15 of page width)
            val widthRatio = (opts.size / 65f).coerceIn(0.22f, 1.25f)

            for (page in doc.pages) {
                val box = page.mediaBox
                val w = box.width
                val h = box.height
                val tw = widthRatio * min(w, h)
                val th = tw * bmp.height / bmp.width
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.setGraphicsStateParameters(gs)
                    cs.transform(Matrix.getRotateInstance(Math.toRadians(opts.angle.toDouble()), w / 2f, h / 2f))
                    cs.drawImage(img, -tw / 2f, -th / 2f, tw, th)
                    cs.restoreGraphicsState()
                }
            }
            bmp.recycle()
            return Storage.save(ctx, out, PDF) { doc.save(it) }
        }
    }

    private fun lock(ctx: Context, uri: Uri, password: String, out: String): String {
        open(ctx, uri).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            return Storage.save(ctx, out, PDF) { doc.save(it) }
        }
    }

    private fun unlock(ctx: Context, uri: Uri, password: String, out: String): String {
        open(ctx, uri, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            return Storage.save(ctx, out, PDF) { doc.save(it) }
        }
    }

    private fun compress(ctx: Context, uri: Uri, level: Int, out: String): String {
        val (jpegQ, scale) = when (level) {
            0 -> 0.80f to 2.0f
            1 -> 0.65f to 1.5f
            else -> 0.45f to 1.1f
        }
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("تعذر فتح الملف")
        pfd.use {
            PdfRenderer(it).use { renderer ->
                PDDocument().use { doc ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { p ->
                            val pw = p.width.toFloat()
                            val ph = p.height.toFloat()
                            val bmp = Bitmap.createBitmap(
                                max(1, (pw * scale).toInt()), max(1, (ph * scale).toInt()), Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val page = PDPage(PDRectangle(pw, ph))
                            doc.addPage(page)
                            val img = JPEGFactory.createFromImage(doc, bmp, jpegQ)
                            PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, pw, ph) }
                            bmp.recycle()
                        }
                    }
                    return Storage.save(ctx, out, PDF) { os -> doc.save(os) }
                }
            }
        }
    }

    private fun decodeImage(ctx: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = max(info.size.width, info.size.height)
            if (m > 2500) {
                val f = 2500f / m
                dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        src.recycle()
        return flat
    }

    private fun imagesToPdf(ctx: Context, uris: List<Uri>, out: String): String {
        PDDocument().use { doc ->
            for (u in uris) {
                val bmp = decodeImage(ctx, u)
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) 842f else 595f
                val ph = if (landscape) 595f else 842f
                val margin = 20f
                val s = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, (pw - w) / 2f, (ph - h) / 2f, w, h) }
                bmp.recycle()
            }
            return Storage.save(ctx, out, PDF) { doc.save(it) }
        }
    }
}
