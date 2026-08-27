هذا المجلد يجب أن يحتوي على ملفين قبل بناء التطبيق (غير مضمّنين في المشروع بسبب الحجم والترخيص):

1) ara.traineddata
2) eng.traineddata

طريقة الحصول عليهما (نسخة "fast" المناسبة لـ Tesseract 4.9 / OEM_LSTM_ONLY):
- المصدر الرسمي: مستودع tessdata_fast من tesseract-ocr على GitHub.
- روابط التنزيل المباشرة (بدّل الاسم فقط):
  https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata
  https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata
- ضع الملفين هنا كما هما (بدون تغيير الاسم) في:
  app/src/main/assets/tessdata/

ملاحظة: بدون الملفين، ستفشل `OcrEngine.prepare()` بأمان (سيتم رفض كل قراءة ولن يتم اللصق أبدًا)
بدل تعطّل التطبيق — راجع OcrEngine.kt لسلوك الفشل الآمن.
