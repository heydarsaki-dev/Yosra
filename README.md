<div dir="rtl">

# یسرا 🌸

**اپ خانوادگی مالی فارسی** — درآمد، خرج و بدهی‌های خانواده رو ساده و شفاف ثبت کن، گزارش بگیر و برنامه‌ریزی کن.

[![Release](https://img.shields.io/github/v/release/heydarsaki-dev/Yosra?style=flat-square)](https://github.com/heydarsaki-dev/Yosra/releases/latest)
[![APK v1.0.0](https://img.shields.io/badge/APK-v1.0.0-6C5CE7?style=flat-square)](https://github.com/heydarsaki-dev/Yosra/releases/download/v1.0.0/Yosra-v1.0.0.apk)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#)
[![minSdk](https://img.shields.io/badge/minSdk-24%2B-10B981?style=flat-square)](#)

> A Persian family finance app (RTL) for tracking income, expenses and debts — built with pure Kotlin, no frameworks.

---

## ⬇️ دانلود

آخرین نسخه رو از صفحه [Releases](https://github.com/heydarsaki-dev/Yosra/releases) دانلود کن:

**📥 [Yosra-v1.0.0.apk](https://github.com/heydarsaki-dev/Yosra/releases/download/v1.0.0/Yosra-v1.0.0.apk)**

> کافیه APK رو دانلود و نصب کنی (نصب از منابع ناشناس باید فعال باشه).

---

## ✨ امکانات

### 👨‍👩‍👧 اعضای خانواده
- ثبت چند عضو با ایموجی و رنگ آواتار دلخواه
- ویرایش و حذف کاربرها از تنظیمات
- نمایش خرج ماه هر عضو

### 💸 تراکنش‌ها
- ثبت سریع درآمد و خرج از صفحه اصلی با دکمه‌های جدا
- تاریخ شمسی با تقویم (امروز/دیروز/انتخاب روز)
- فیلتر درآمد/خرج/همه در لیست تراکنش‌ها
- ویرایش و حذف از صفحه اصلی و لیست (کلیک = ویرایش، نگه‌داشتن = منو)
- تراکنش‌های مربوط به بدهی قابل ویرایش نیستن (برای حفظ یکپارچگی قسط‌ها)
- سقف موجودی هنگام ثبت خرج کنترل می‌شه

### 🏦 بدهی و اقساط
- ثبت طلبکار با مبلغ کل و قسط ماهانه
- لیست قسط‌های ماهانه با وضعیت پرداخت/پرداخت‌نشده
- پرداخت یک‌کلیکی قسط — بدون نیاز به ورود دستی مبلغ
- با هر پرداخت، تراکنش خرج خودکار ثبت می‌شه
- لغو پرداخت = برگشت قسط به حالت پرداخت‌نشده
- حذف تراکنش قسطی از لیست = لغو خودکار پرداخت اون قسط

### 📊 گزارش‌ها
- بازه روزانه و ماهانه با جابه‌جایی قبلی/بعدی
- کارت درآمد / خرج / مانده
- نمودار روند روزانه ماه 📈
- چارت دایره‌ای به تفکیک دسته با سویچ خرج/درآمد 🍩
- «چه کسی خرج کرد؟ / چه کسی درآمد داشت؟» با سویچ خرج/درآمد
- لیست تراکنش‌های هر روز در حالت روزانه

### ⚙️ تنظیمات
- مدیریت کاربران (افزودن/ویرایش/حذف)
- مدیریت دسته‌بندی‌ها با **جابه‌جایی کشیدنی (drag & drop)** ترتیب
- بکاپ و بازیابی دیتابیس با فایل دلخواه

### 🎨 رابط کاربری
- کاملاً فارسی و راست‌چین، فونت **وزیرمتن**
- پالت بنفش با رنگ‌های معنایی (سبز = درآمد، صورتی = خرج)
- کارت‌های آمار وسط‌چین، مبالغ همیشه با واحد «تومان»

---

## 🛠 ساخت از سورس

پروژه با **Gradle** ساخته می‌شه، ولی همچنان **بدون هیچ کتابخانه خارجی** —
تنها وابستگی، `kotlin-stdlib` است. (بدون Jetpack Compose، بدون AndroidX، بدون Retrofit.)

### پیش‌نیازها
- **JDK 17**
- **Android SDK** با `compileSdk 36` و `build-tools 35.0.0`
- (اختیاری) متغیر محیطی `ANDROID_HOME` یا فایل `local.properties` با مسیر SDK

### بیلد

```bash
./gradlew assembleDebug      # خروجی دیباگ
./gradlew assembleRelease    # خروجی ریلیز + کپی در پوشه dist/
```

خروجی‌ها:
| مسیر | توضیح |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | نسخه دیباگ |
| `app/build/outputs/apk/release/app-release.apk` | نسخه ریلیز |
| `dist/Yosra-v<version>-<code>.apk` | کپی با نام خوانا |

### نصب روی گوشی

```bash
adb install -r dist/Yosra-v1.0.0-43.apk
```

### انتشار نسخه جدید

1. `app/build.gradle.kts` → `versionCode` و `versionName`
2. `app/src/main/res/layout/activity_settings.xml` → متن «نسخه ۲.x.x»
3. `./gradlew assembleRelease`

> هر دو نوع بیلد با کلید `app/debug.keystore` امضا می‌شوند تا نصب روی نسخه قبلی بدون
> خطای `INSTALL_FAILED_UPDATE_INCOMPATIBLE` انجام شود.

---

## 📁 ساختار پروژه

```
Yosra/
├── settings.gradle.kts           # تعریف ماژول‌ها و مخازن
├── build.gradle.kts              # کانفیگ ریشه
├── gradle/libs.versions.toml     # version catalog (AGP 8.11.0 / Kotlin 1.9.22)
├── gradlew                       # Gradle wrapper 8.14.3
└── app/
    ├── build.gradle.kts
    ├── debug.keystore            # کلید امضا
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/               # فونت وزیرمتن
        ├── java/
        │   ├── App.kt                   # Application
        │   ├── SplashActivity.kt        # صفحه شروع
        │   ├── LoginActivity.kt
        │   ├── HomeActivity.kt          # صفحه اصلی
        │   ├── TransactionsActivity.kt  # لیست تراکنش‌ها
        │   ├── AddTransactionActivity.kt # ثبت/ویرایش با تقویم شمسی
        │   ├── InstallmentsActivity.kt  # لیست بدهی‌ها
        │   ├── DebtDetailActivity.kt    # جزئیات قسط‌ها
        │   ├── ReportsActivity.kt       # گزارش‌ها
        │   ├── SettingsActivity.kt      # تنظیمات، کاربران، دسته‌ها، بکاپ
        │   ├── Db.kt                    # SQLite (نسخه ۱۱)
        │   ├── Sync.kt / Rt.kt / Ptr.kt  # سینک دوطرفه با داشبورد وب
        │   ├── BarChartView.kt          # نمودار میله‌ای سفارشی
        │   ├── DonutChartView.kt        # چارت دایره‌ای سفارشی
        │   └── U.kt                     # ابزارها (تومان، شمسی، فونت)
        └── res/
            ├── layout/           # لای‌اوت‌ها (RTL)
            ├── drawable/         # شکل‌ها، چیپ‌ها، دکمه‌ها
            ├── values/           # رنگ‌ها، استایل‌ها
            └── values-night/     # تم تاریک
```
