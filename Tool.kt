package com.example.pdftoolkit

enum class Need { PAGES, PAGES_OPTIONAL, ANGLE, TEXT, PASSWORD, QUALITY }

enum class Tool(
    val emoji: String,
    val title: String,
    val hint: String,
    val multi: Boolean = false,
    val images: Boolean = false,
    val needs: Set<Need> = emptySet(),
    val mimes: List<String>? = null
) {
    MERGE("➕", "دمج PDF", "اختر ملفين أو أكثر. ترتيب القائمة هو ترتيب الدمج.", multi = true),
    IMAGES("🖼️", "صور إلى PDF", "اختر صورة أو أكثر، كل صورة تصبح صفحة.", multi = true, images = true),
    SPLIT("🪓", "تقسيم PDF", "اتركه فارغاً لتقسيم كل صفحة إلى ملف، أو اكتب مجموعات مثل: 1-3,4,5-8", needs = setOf(Need.PAGES_OPTIONAL)),
    DELETE("🗑️", "حذف صفحات", "اكتب الصفحات المراد حذفها مثل: 2,5-7", needs = setOf(Need.PAGES)),
    EXTRACT("📤", "استخراج صفحات", "اكتب الصفحات المراد استخراجها مثل: 1,3-4", needs = setOf(Need.PAGES)),
    ROTATE("🔄", "تدوير صفحات", "اختر الزاوية. اترك الصفحات فارغة لتدوير الكل.", needs = setOf(Need.ANGLE, Need.PAGES_OPTIONAL)),
    WATERMARK("🖋️", "علامة مائية", "اكتب نص العلامة المائية (يدعم العربية).", needs = setOf(Need.TEXT)),
    LOCK("🔒", "إضافة كلمة مرور", "اكتب كلمة المرور التي ستحمي الملف.", needs = setOf(Need.PASSWORD)),
    UNLOCK("🔓", "إزالة كلمة المرور", "اكتب كلمة مرور الملف الحالية.", needs = setOf(Need.PASSWORD)),
    COMPRESS("💾", "ضغط PDF", "يحوّل الصفحات إلى صور مضغوطة، فيصغر الحجم لكن لا يبقى النص قابلاً للتحديد.", needs = setOf(Need.QUALITY)),
    WORD("📄", "Word إلى PDF", "يدعم docx وtxt: النصوص والعناوين والخط العريض والقوائم. الصور والجداول المعقدة لا تُنقل. صيغة doc القديمة غير مدعومة.", mimes = listOf("*/*")),
    PDF_TO_IMAGES("📷", "PDF إلى صور", "كل صفحة تُحفظ كصورة JPG. اترك الصفحات فارغة لتحويل الكل. الملف المحمي بكلمة مرور أزل حمايته أولاً.", needs = setOf(Need.PAGES_OPTIONAL)),
    REORDER("↕️", "ترتيب الصفحات", "اكتب الترتيب الجديد مثل: 3,1,2 (الصفحات غير المذكورة تُضاف في النهاية بترتيبها).", needs = setOf(Need.PAGES)),
    EXTRACT_IMAGES("🏞️", "استخراج الصور", "يحفظ الصور الموجودة داخل الملف كما هي (PNG). اترك الصفحات فارغة للكل.", needs = setOf(Need.PAGES_OPTIONAL)),
    PDF_TO_WORD("📝", "PDF إلى Word", "يستخرج النص إلى ملف docx (بدون الصور والتنسيق). الملفات الممسوحة ضوئياً لا تعمل، والعربية قد تحتاج مراجعة.")
}
