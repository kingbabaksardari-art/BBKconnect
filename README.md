# BBK CONNECT — Android

اپلیکیشن اندرویدی برای اتصال به سرور VPN Bridge (نسخه ۳.x). این برنامه پورت نسخه‌ی پایتون کلاینت روی موبایل است و یک **پروکسی محلی HTTP/HTTPS** روی گوشی اجرا می‌کند که از طریق پل cPanel ترافیک را به سرور Ubuntu می‌رساند.

---

## ویژگی‌ها

- ✅ ایمپورت مستقیم فایل `config.json` (همان فایلی که از پنل ادمین دانلود می‌شود — بدون نیاز به تبدیل)
- ✅ پشتیبانی از `multi_dn` (پولینگ گروهی) برای کاهش مصرف باتری و افزایش سرعت
- ✅ Workaround برای فشرده‌سازی اجباری LiteSpeed/gzip
- ✅ سرویس Foreground با نوتیفیکیشن همیشگی + نگه‌داشتن CPU بیدار
- ✅ نمایش زنده‌ی آمار (سشن‌های فعال، بایت بالا/پایین) و لاگ‌ها
- ✅ رابط فارسی + Material 3 + پشتیبانی از تم پویا (Material You)
- ⚠️ فقط TCP — UDP/QUIC/WebRTC پشتیبانی نمی‌شود (محدودیت ذاتی پروتکل پل)

---

## ساخت APK

سه راه دارید. ساده‌ترین = **GitHub Actions** (بدون نصب چیزی روی کامپیوتر).

### راه ۱: GitHub Actions  (پیشنهادی، رایگان، بدون نصب)

1. در [github.com](https://github.com) یک repo جدید بسازید (Private هم می‌تواند باشد).
2. محتویات این پروژه را در repo آپلود کنید (می‌توانید از طریق وب با drag-and-drop یا با `git push`).
3. وقتی push انجام شد، اتوماتیک workflow توی تب **Actions** اجرا می‌شود.
4. حدود ۳–۵ دقیقه صبر کنید تا کامل بشود (سبز شدن تیک).
5. روی run کلیک کنید، پایین صفحه قسمت **Artifacts** یک فایل zip به نام `BBK-CONNECT-APKs` می‌بینید — دانلود و باز کنید، داخلش `BBK-CONNECT-debug.apk` و `BBK-CONNECT-release-unsigned.apk` است.

فایل workflow از قبل در `.github/workflows/build.yml` آماده است و نیازی به ویرایش ندارد.

### راه ۲: Android Studio روی کامپیوتر

پیش‌نیازها:
- Android Studio **Koala (2024.1.1)** یا جدیدتر
- JDK 17 (همراه Android Studio نصب می‌شود)
- Android SDK Platform 34
- اتصال اینترنت برای دانلود وابستگی‌های Gradle

مراحل:
1. پوشه‌ی `vpn-android-app` را در Android Studio با گزینه‌ی **Open** باز کنید.
2. منتظر بمانید تا Gradle Sync کامل شود (بار اول حدود ۲ تا ۵ دقیقه).
3. از منوی **Build → Build Bundle(s) / APK(s) → Build APK(s)** خروجی APK بگیرید.
4. فایل APK در مسیر `app/build/outputs/apk/debug/app-debug.apk` ساخته می‌شود.
5. آن را روی گوشی نصب کنید (نصب از منابع نامعتبر باید فعال باشد).

از خط فرمان:
```bash
./gradlew :app:assembleDebug      # خروجی: app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # خروجی: app/build/outputs/apk/release/ (unsigned)
```

### راه ۳: Termux روی گوشی  (پیچیده‌تر)

اگر کامپیوتر در دسترس نیست:
```bash
pkg install openjdk-17 gradle git
cd vpn-android-app
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
```
حدود ۳۰۰ مگابایت دانلود + حدود نیم ساعت زمان روی گوشی متوسط.

### حداقل اندروید
- **Android 8.0 (API 26)** و بالاتر

---

## نحوه‌ی استفاده

### ۱. ایمپورت کانفیگ
دو راه دارید:
- **دکمه‌ی «انتخاب فایل کانفیگ»** → فایل `config.json` که از پنل ادمین گرفته‌اید را انتخاب کنید.
- یا روی فایل `config.json` در فایل‌منیجر گوشی بزنید و این برنامه را به‌عنوان بازکننده انتخاب کنید.

نمونه‌ی محتویات فایل:
```json
{
  "listen_host": "127.0.0.1",
  "listen_port": 8080,
  "bridge_url": "https://your-cpanel-domain.com/r.php",
  "bridge_token": "...",
  "user_token": "...",
  "verify_ssl": true,
  "use_multi_dn": true,
  "multi_dn_wait_ms": 5000
}
```

### ۲. اتصال
روی دکمه‌ی **«اتصال»** بزنید. وضعیت در بالای صفحه به **«متصل»** تغییر می‌کند و یک نوتیفیکیشن دائم نشان می‌دهد که پروکسی محلی فعال است.

پروکسی روی `127.0.0.1:8080` (یا هر پورتی که در کانفیگ نوشته‌اید) گوش می‌دهد.

### ۳. هدایت ترافیک گوشی به پروکسی
اندروید برخلاف ویندوز/لینوکس، تنظیم سراسری «پروکسی HTTP سیستمی» را به‌خوبی به همه‌ی برنامه‌ها اعمال نمی‌کند. سه گزینه برای استفاده دارید:

#### الف) فقط مرورگر — ساده‌ترین راه
- **Firefox for Android** + افزونه‌ی **FoxyProxy** را نصب کنید.
- یک پروفایل با `HTTP Proxy = 127.0.0.1` و `Port = 8080` بسازید.
- فعالش کنید — همه‌ی ترافیک مرورگر از پل عبور می‌کند.
- مرورگرهای کرومی‌بِیس (Chrome/Edge/Brave) متأسفانه افزونه را قبول نمی‌کنند.

#### ب) همه‌ی برنامه‌ها — با NekoBox / Husi
- **NekoBox for Android** یا **Husi** را از GitHub نصب کنید.
- یک Outbound از نوع **HTTP** بسازید:
  - Server: `127.0.0.1`
  - Port: `8080`
  - نام دلخواه
- یک Profile ساده با همین Outbound بسازید و Start کنید.
- NekoBox یک VPN محلی می‌سازد (TUN) و کل ترافیک TCP گوشی را از پروکسی محلی این برنامه عبور می‌دهد.
- ⚠️ مطمئن شوید این برنامه (VPN Bridge Client) **قبل از** NekoBox متصل شده باشد و در لیست **Bypass / App Exemption** نِکوباکس قرار گرفته باشد، وگرنه لوپ ایجاد می‌شود.

#### ج) ترمینال / SSH / curl
- Termux یا هر ترمینالی → از متغیر محیطی `http_proxy=http://127.0.0.1:8080` استفاده کنید.

---

## محدودیت‌های مهم

1. **فقط TCP**: پروتکل پل HTTP-tunneling است، پس UDP/QUIC رد نمی‌شود. سایت‌های HTTP/3 خودکار به HTTP/2 برمی‌گردند ولی برخی بازی‌های آنلاین کار نخواهند کرد.
2. **تأخیر**: هر بسته از مسیر `گوشی → cPanel → Ubuntu → مقصد` می‌گذرد. تأخیر طبیعی ۳۰۰ تا ۸۰۰ میلی‌ثانیه است.
3. **مصرف باتری**: سرویس Foreground و WakeLock باعث می‌شود اندروید برنامه را Doze نکند. در عوض، مصرف باتری در حالت اتصال بالاتر از حالت idle است. توصیه: فقط زمان نیاز متصل شوید.
4. **WebSocket طولانی**: اگر cPanel timeout اعمال کند (معمولاً ۳۰ تا ۱۲۰ ثانیه)، اتصال WebSocket قطع و دوباره وصل می‌شود.
5. **بدون VpnService فعلاً**: این نسخه TUN ندارد و خودش نمی‌تواند کل ترافیک سیستم را Capture کند. برای این کار با NekoBox جفت کنید.

---

## فاز بعدی (Roadmap)

نسخه‌ی فعلی **فاز ۱** است — فقط Local Proxy. فاز ۲ شامل:
- VpnService داخلی با tun2socks یا hev-socks5-tunnel برای Capture سراسری
- پشتیبانی per-app routing
- DNS over HTTPS داخل پروتکل پل

این کار به دلیل نیاز به کتابخانه‌ی native (`.so`) و تست روی چندین دستگاه، فعلاً انجام نشده.

---

## رفع اشکال

| مشکل | راه‌حل |
|---|---|
| در لاگ: `health check failed: 401` | `bridge_token` یا `user_token` در کانفیگ اشتباه است. از پنل ادمین دوباره دانلود کنید. |
| `SSLHandshakeException` | اگر دامنه‌ی cPanel گواهی self-signed دارد، `verify_ssl: false` کنید (در شبکه‌ی ناامن استفاده نکنید). |
| `Address already in use` | پورت ۸۰۸۰ پر است. در کانفیگ به ۸۸۸۸ یا غیره تغییر دهید. |
| اتصال برقرار می‌شود ولی مرورگر چیزی لود نمی‌کند | تنظیم پروکسی در مرورگر/NekoBox درست نیست. مطمئن شوید روی `127.0.0.1:8080` (یا پورت کانفیگ شما) ست شده. |
| نوتیفیکیشن نشان داده نمی‌شود (Android 13+) | اجازه‌ی **POST_NOTIFICATIONS** را بدهید — برنامه در اولین اجرا می‌پرسد. |

---

## ساختار پروژه

```
vpn-android-app/
├── app/
│   └── src/main/
│       ├── java/com/vpnbridge/client/
│       │   ├── App.kt                     # Application class
│       │   ├── BridgeConfig.kt            # مدل JSON کانفیگ
│       │   ├── ConfigStore.kt             # ذخیره‌سازی با DataStore
│       │   ├── LogBuffer.kt               # بافر حلقوی لاگ
│       │   ├── ServiceState.kt            # State سراسری
│       │   ├── VpnBridgeService.kt        # Foreground Service
│       │   ├── bridge/
│       │   │   ├── BridgeClient.kt        # OkHttp wrapper برای endpoints پل
│       │   │   ├── BridgeSession.kt       # یک تونل
│       │   │   └── MultiPoller.kt         # پولینگ گروهی /multi_dn
│       │   ├── proxy/
│       │   │   └── ProxyServer.kt         # سرور HTTP/CONNECT محلی
│       │   └── ui/
│       │       ├── MainActivity.kt
│       │       ├── MainScreen.kt          # Compose UI
│       │       └── theme/
│       └── res/                           # آیکن‌ها، رنگ‌ها، تم
└── build.gradle.kts
```

---

## لایسنس

این پروژه روی همان لایسنس پروژه‌ی اصلی VPN Bridge منتشر می‌شود.
