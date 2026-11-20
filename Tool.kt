package com.example.pdftoolkit

enum class Need {
    PAGES,
    PAGES_OPTIONAL,
    ANGLE,
    TEXT,
    PASSWORD,
    QUALITY,
    WATERMARK_PRO,
    CREATE_NEW,
    CLOUD_API
}

enum class Tool(
    val emoji: String,
    val title: String,
    val hint: String,
    val multi: Boolean = false,
    val images: Boolean = false,
    val needs: Set<Need> = emptySet(),
    val mimes: List<String>? = null,
    val requiresFile: Boolean = true
) {
    CREATE_PDF(
        "✨",
        "إنشاء PDF جديد",
        "اكتب العنوان والمحتوى لإنشاء مستند PDF جديد مقاس A4 مع دعم كامل للعربية.",
        needs = setOf(Need.CREATE_NEW),
        requiresFile = false
    ),
    MERGE("➕", "دمج PDF", "اختر ملفين أو أكثر. ترتيب القائمة هو ترتيب الدمج.", multi = true),
    IMAGES("🖼️", "صور إلى PDF", "اختر صورة أو أكثر، كل صورة تصبح صفحة.", multi = true, images = true),
    SPLIT("🪓", "تقسيم PDF", "اتركه فارغاً لتقسيم كل صفحة إلى ملف، أو اكتب مجموعات مثل: 1-3,4,5-8", needs = setOf(Need.PAGES_OPTIONAL)),
    DELETE("🗑️", "حذف صفحات", "اكتب الصفحات المراد حذفها مثل: 2,5-7", needs = setOf(Need.PAGES)),
    EXTRACT("📤", "استخراج صفحات", "اكتب الصفحات المراد استخراجها مثل: 1,3-4", needs = setOf(Need.PAGES)),
    ROTATE("🔄", "تدوير صفحات", "اختر الزاوية. اترك الصفحات فارغة لتدوير الكل.", needs = setOf(Need.ANGLE, Need.PAGES_OPTIONAL)),
    WATERMARK(
        "🖋️",
        "علامة مائية متقدمة",
        "تحكم كامل بنص العلامة المائية، حجمها، شفافيتها، لونها، وزاوية دورانها.",
        needs = setOf(Need.TEXT, Need.WATERMARK_PRO)
    ),
    WORD(
        "📄",
        "مستندات إلى PDF",
        "يدعم docx (مع الصور والجداول)، txt، html، md، و csv. يحوّلها محلياً إلى PDF.",
        mimes = listOf("*/*")
    ),
    CLOUD_CONVERT(
        "☁️",
        "تحويل احترافي (Word/Office ↔ PDF)",
        "للملفات المعقدة جداً (جداول متداخلة وتنسيق كامل). يدعم docx, xlsx, pptx إلى PDF والعكس عبر ConvertAPI.",
        needs = setOf(Need.CLOUD_API),
        mimes = listOf("*/*")
    ),
    PDF_TO_WORD(
        "📝",
        "PDF إلى Word / نص / HTML",
        "يحوّل PDF إلى مستند Word (docx) مع الصور المستخرجة والنصوص المنسقة."
    ),
    PDF_TO_IMAGES("📷", "PDF إلى صور", "كل صفحة تُحفظ كصورة JPG عالية الدقة. اترك الصفحات فارغة لتحويل الكل.", needs = setOf(Need.PAGES_OPTIONAL)),
    EXTRACT_IMAGES("🏞️", "استخراج الصور", "يحفظ الصور الموجودة داخل الملف كما هي (PNG). اترك الصفحات فارغة للكل.", needs = setOf(Need.PAGES_OPTIONAL)),
    COMPRESS("💾", "ضغط PDF", "يقلل حجم الملف عبر ضغط الصفحات بالجودة التي تختارها.", needs = setOf(Need.QUALITY)),
    REORDER("↕️", "ترتيب الصفحات", "اكتب الترتيب الجديد مثل: 3,1,2 (الصفحات غير المذكورة تُضاف في النهاية بترتيبها).", needs = setOf(Need.PAGES)),
    LOCK("🔒", "إضافة كلمة مرور", "اكتب كلمة المرور التي ستحمي الملف.", needs = setOf(Need.PASSWORD)),
    UNLOCK("🔓", "إزالة كلمة المرور", "اكتب كلمة مرور الملف الحالية.", needs = setOf(Need.PASSWORD))
}
