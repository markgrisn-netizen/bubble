# فقاعة (Faqqa) — PanelTranslate AI (Android Native Edition)

تطبيق أندرويد أصلي (**Kotlin + Jetpack Compose + Material 3**) لترجمة المانغا والمانهوا والمانها والويب تون والكوميكس إلى العربية (RTL)، مع متصفح ويب حقيقي مدمج، واستيراد متعدد المصادر، واكتشاف فقاعات وتبييض دقيق عبر قناع الفقاعة الفعلي، وتنضيد عربي متصل بالخطوط المحلية، ومزودي ذكاء اصطناعي متعددين.

---

## المعمارية والملفات الأساسية

- **الواجهة والتجربة**: Jetpack Compose + Material 3 + Navigation Compose + Adaptive (`NavigationBar` / `NavigationRail`).
- **الخطوط العربية المدمجة محليًا**: `Cairo` (`res/font/cairo.ttf`), `Rubik` (`res/font/rubik.ttf`), `Noto Naskh Arabic` (`res/font/noto_naskh_arabic.ttf`).
- **المتصفح الحقيقي (`BrowserScreen.kt`)**: مبني على `android.webkit.WebView` أصلي يدعم JavaScript وDOM Storage وCookies و`DownloadListener` و`FaqqaJsBridge` لاستخراج صور DOM أو التقاط لقطة صفحة وإرسالها للمحرر.
- **مسارات الاستيراد الثلاثة المنفصلة**:
  - **المسار A (`SourceAdapterRegistry.kt`)**: جلب مباشر عبر رابط، يعيد `BLOCKED_BY_SOURCE` صراحةً عند الحجب (`403/429/503/Cloudflare/CAPTCHA`) دون محاولة تجاوز.
  - **المسار B (`MainActivity.kt` + `BrowserScreen.kt`)**: المتصفح المدمج + **Android Share Target** (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) لاستقبال الصور من أي متصفح خارجي (Chrome / Firefox) دون استدعاء `source_fetch`.
  - **المسار C (`FileImportEngine.kt`)**: استيراد `JPG / PNG / WEBP` (عبر Photo Picker دون أذونات تخزين عامة)، و`PDF` (عبر `PdfRenderer`)، و`ZIP / CBZ` مع حماية **Zip Slip** والتحقق من السحر الثنائي للصور وترتيب طبيعي للأرقام (`page_2` قبل `page_10`).
- **معالجة الصور والتبييض (`BubbleImageEngine.kt`)**:
  - الحفاظ على الصورة الأصلية غير قابلة للتعديل (`orig_*.png`).
  - استخراج قناع الفقاعة الدقيق عبر Flood-Fill أو Superellipse وتقليصه بمقدار `2–3px` لحماية الحدود السوداء والذيل.
  - أداة فرشاة تبييض يدوية، وعرض المقارنة (الأصلي / المترجم / قبل وبعد)، ووضع النص العربي المتصل عبر `StaticLayout` (`TextDirectionHeuristics.RTL`) مع **منع ظهور أي مربعات أو أرقام Debug** في الصورة النهائية أو القارئ أو التصدير.
- **مزودو الذكاء الاصطناعي والأمان (`TranslationProviderRegistry.kt` + `CryptoKeyStore.kt` + `AppCheckInit.kt`)**:
  - **Firebase AI Logic** (`gemini-3.8-flash` متعدد الوسائط + Firebase App Check دون مفتاح API مكشوف).
  - **DeepSeek / Groq / Gemini Direct / OpenAI / Anthropic / Ollama (محلي)** مع تشفير المفاتيح عبر `AndroidKeyStore (AES-256-GCM)` وزر فحص اتصال فعلي (`Test Connection`).
- **التخزين والمزامنة**:
  - **Firebase Firestore** (مع تسجيل الدخول بحساب Google عبر `CredentialManager`) للمشاريع والفصول والقاموس وسجل العمليات وسجل المصادر.
  - **Room Database** للتخزين المحلي وصفحات العمل والمناطق وإعدادات المزودين وتاريخ المتصفح للقراءة دون إنترنت.
- **التصدير (`ExportEngine.kt`)**: تصدير نظيف بصيغ `PNG / JPG / WEBP / PDF / CBZ`.

---

## تشغيل نماذج Ollama محليًا على Android / Termux / LAN

1. شغّل خادم Ollama محليًا على نفس الجهاز (Termux) أو على جهاز الحاسوب في الشبكة المحلية:
   ```bash
   ollama pull qwen2.5
   OLLAMA_HOST=0.0.0.0:11434 ollama serve
   ```
2. افتح شاشة **مزودو الذكاء الاصطناعي** في التطبيق واختر **Ollama (محلي / شبكة محلية)**:
   - على المحاكي: استخدم `http://10.0.2.2:11434`
   - محليًا على نفس الهاتف (Termux): استخدم `http://127.0.0.1:11434`
   - عبر الشبكة المحلية (LAN): أدخل عنوان IP للجهاز مثل `http://192.168.1.50:11434`
3. اضغط **فحص الاتصال الفعلي** للتحقق من `/api/tags` ووجود النموذج.
4. عند استخدام مزود نصي دون رؤية (مثل `Ollama qwen2.5` أو `DeepSeek`)، يستخرج التطبيق النصوص محليًا عبر **ML Kit Text Recognition** ثم يرسلها للترجمة العربية مع القاموس.

---

## الأوامر والاختبارات المحلية (JVM / Robolectric / Firestore Rules)

```bash
# تشغيل اختبارات القواعد الأمنية لـ Firestore
FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js

# تشغيل اختبارات الوحدة وRobolectric
gradle :app:testDebugUnitTest
```

> **ملاحظة بيئة التشغيل**: هذا المشروع مبني لبيئة **Android Native**. بدائل الويب وسطح المكتب (Next.js / FastAPI / Docker / Chrome MV3 Extension) غير قابلة للتشغيل في حاوية بناء أندرويد؛ لذا يُنفّذ تطبيق **فقاعة** المهام محليًا على الجهاز عبر WebView الأصلي، وShare Target بدل إضافة Chrome، ومعالجة الصور الأصلية بدل LaMa Python.
