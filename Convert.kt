package com.example.pdftoolkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

object Convert {
    private const val FLAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    // ---------- Word (docx / txt) -> PDF ----------

    fun wordToPdf(ctx: Context, uri: Uri, out: String): String {
        val name = PdfOps.displayName(ctx, uri).lowercase()
        val text: CharSequence = when {
            name.endsWith(".docx") -> readDocx(ctx, uri)
            name.endsWith(".txt") -> {
                val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")
                ins.use { String(it.readBytes(), Charsets.UTF_8) }
            }
            name.endsWith(".doc") ->
                throw IllegalArgumentException("صيغة doc القديمة غير مدعومة. احفظ الملف بصيغة docx من Word ثم أعد المحاولة.")
            else -> throw IllegalArgumentException("اختر ملف docx أو txt")
        }
        if (text.isBlank()) throw IllegalArgumentException("لم أجد نصاً داخل الملف")
        return Storage.save(ctx, out, "application/pdf") { render(text, it) }
    }

    private fun readDocx(ctx: Context, uri: Uri): CharSequence {
        val sb = SpannableStringBuilder()
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")
        ZipInputStream(ins).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && entry.name != "word/document.xml") entry = zis.nextEntry
            if (entry == null) throw IOException("الملف ليس docx صالحاً")

            val p = Xml.newPullParser()
            p.setInput(zis, "UTF-8")
            var paraStart = 0
            var heading = 0
            var list = false
            var bold = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "w:p" -> { paraStart = sb.length; heading = 0; list = false }
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
                        "w:tab" -> sb.append('\t')
                        "w:br", "w:cr" -> sb.append('\n')
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "w:p") {
                    if (list) sb.insert(paraStart, "• ")
                    if (heading > 0 && sb.length > paraStart) {
                        val size = when (heading) { 1 -> 22; 2 -> 18; else -> 15 }
                        sb.setSpan(StyleSpan(Typeface.BOLD), paraStart, sb.length, FLAG)
                        sb.setSpan(AbsoluteSizeSpan(size, false), paraStart, sb.length, FLAG)
                    }
                    sb.append('\n')
                }
                ev = p.next()
            }
        }
        return sb
    }

    private fun render(text: CharSequence, os: OutputStream) {
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
            .setLineSpacing(3f, 1f)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
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

    // ---------- PDF -> Word (text only) ----------

    fun pdfToWord(ctx: Context, uri: Uri, out: String): String {
        val body = StringBuilder()
        var any = false
        PdfOps.open(ctx, uri).use { doc ->
            val stripper = PDFTextStripper()
            val total = doc.numberOfPages
            for (p in 1..total) {
                stripper.startPage = p
                stripper.endPage = p
                val t = stripper.getText(doc)
                if (t.isNotBlank()) any = true
                t.split(Regex("\\r?\\n")).filter { it.isNotBlank() }.forEach { body.append(paragraph(it)) }
                if (p < total) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        if (!any) throw IllegalArgumentException("لا يوجد نص قابل للاستخراج (قد يكون الملف ممسوحاً ضوئياً)")
        val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        return Storage.save(ctx, out, mime) { os -> writeDocx(body.toString(), os) }
    }

    private fun paragraph(line: String): String {
        val rtl = line.any { it in '\u0600'..'\u06FF' }
        val esc = line.filter { it.code >= 32 || it == '\t' }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ppr = if (rtl) "<w:pPr><w:bidi/></w:pPr>" else ""
        val rpr = if (rtl) "<w:rPr><w:rtl/></w:rPr>" else ""
        return "<w:p>$ppr<w:r>$rpr<w:t xml:space=\"preserve\">$esc</w:t></w:r></w:p>"
    }

    private fun writeDocx(body: String, os: OutputStream) {
        val zip = ZipOutputStream(os)
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        put(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        )
        put(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        )
        put(
            "word/document.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>"""
        )
        zip.finish()
    }
}
