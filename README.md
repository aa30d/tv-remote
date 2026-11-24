<div dir="rtl" lang="fa">

# کنترل هوشمند (Smart Remote Native)

[![وضعیت بیلد](https://github.com/aa30d/tv-remote/actions/workflows/build.yml/badge.svg)](https://github.com/aa30d/tv-remote/actions/workflows/build.yml)
[![انتشار](https://img.shields.io/github/v/release/aa30d/tv-remote)](https://github.com/aa30d/tv-remote/releases)

اپلیکیشن اندرویدی «کنترل هوشمند» با رابط کاربری فارسی و راست‌به‌چپ، که گوشی را به یک کنترل‌ remote مادون‌قرمز (IR) برای تلویزیون و گیرندهٔ کابلی تبدیل می‌کند و هم‌زمان امکان پخش زندهٔ شبکه‌های تلویزیون را فراهم می‌دهد.

این پروژه از نظر معماری، تابع مستقیمی نیست؛ بخش عمدهٔ پایگاه کدهای مادون‌قرمز و موتور تفسیر سیگنال در آن اقتباس (پورت) شده از پروژهٔ متن‌باز [IRRemote](#منابع-و-اعتبارات) است که خود از کار اصلیٔ Twinone مشتق می‌شود.

## ویژگی‌ها

- **رابط کاربری فارسی و RTL** با فونت [وزیرمتن](#منابع-و-اعتبارات).
- **کنترل مادون‌قرمز (IR)** با استفاده از `ConsumerIrManager` اندروید:
  - پایگاه کد برای **۳۷ برند و مدل** تلویزیون و گیرندهٔ کابلی (**۱۱۵۱** کد دکمه).
  - پشتیبانی از قالب‌های کد **Pronto Hex** و **GlobalCache**.
  - دو صفحهٔ کنترل درون یک ViewPager: **صفحهٔ عددی (Numpad)** و **پد ناوبری (D-Pad)**.
  - کلیدهای قدرت، صدا ( +/- )، کانال ( +/- )، قطع/وصل صدا، منو، خانه، راهنما، خروج و ...
  - **لمس کوتاه** برای ارسال یک‌بارهٔ فرمان و **نگه‌داشتن** برای تکرار خودکار (با بازخورد لرزشی).
  - **شماره‌گیری خودکار**: وارد کردن شمارهٔ کانال، ارسال پیوستهٔ ارقام با وقفهٔ بین هر رقم.
  - انتخاب و ذخیرهٔ مدل تلویزیون؛ ارسال کد تست قدرت و نمایش وضعیت فرستندهٔ IR.
- **پخش زندهٔ شبکه‌ها** با ExoPlayer/Media3 و پروتکل HLS:
  - فهرست شبکه‌ها از **API سپهر** (`sepehrtv.ir`) با کش آفلاین و فهرست پیش‌فرض داخل برنامه.
  - انتخاب بین استریم **سپهر** و **تلوبیون** برای هر شبکه.
  - انتخاب کیفیت: **تطبیقی (ABR)**، **بالاترین**، **پایین‌ترین** یا کیفیت دلخواه؛ تنظیم کیفیت پیش‌فرض برای همهٔ شبکه‌ها.
  - تمام‌صفحه، قطع/وصل صدا، جابه‌جایی بین شبکه‌ها و نمایش عنوان برنامهٔ در حال پخش.
- **دریافت اولین فریم استریم** (ذخیرهٔ تصویر برای استفاده به‌عنوان تصویر کانال یا پیش‌نمایش).
- **وارد/صادر کردن کدهای کانال‌ها** در قالب JSON (با انتخاب فایل از طریق SAF).
- **به‌روزرسانی لیست شبکه‌ها** با یک دکمه و نمایش پیام‌های وضعیت (Toast) به فارسی.

## پیش‌نیازها

- **JDK 17** (Temurin پیشنهاد می‌شود) و **Android SDK 33** (`compileSdk 33`).
- **Gradle 7.6 یا جدیدتر** (پیشنهاد: 7.6.3) — پوشهٔ `gradle-wrapper` در این مخزن نیست، بنابراین از نسخهٔ سراسری Gradle استفاده می‌شود.
- برای امکان کنترل واقعی، گوشی باید فرستندهٔ مادون‌قرمز داشته باشد (`android.hardware.consumerir`). در غیر این صورت فقط بخش پخش زنده کار می‌کند.

## ساخت و اجرا

```bash
# بیلد نسخهٔ دیباگ
gradle --no-daemon :app:assembleDebug

# بیلد نسخهٔ ریلیز
gradle --no-daemon :app:assembleRelease
```

مسیر خروجی‌ها:

- دیباگ: `app/build/outputs/apk/debug/app-debug.apk`
- ریلیز: `app/build/outputs/apk/release/app-release-unsigned.apk`

نسخهٔ ریلیز به‌صورت پیش‌فرض **امضا نشده** است؛ برای توزیع روی گوشی، آن را با ابزارهای اندروید امضا کنید یا پیکربندی امضای Gradle را به `app/build.gradle` اضافه کنید.

همچنین می‌توانید پروژه را در **Android Studio** باز کنید و از منوی *Build → Build APK(s)* استفاده کنید.

## دانلود

نسخه‌های آماده در صفحهٔ [Releases](https://github.com/aa30d/tv-remote/releases) منتشر می‌شوند. هر انتشار شامل نسخهٔ **release** و **debug** است.

## ساخت خودکار (GitHub Actions)

دو شیوهٔ (workflow) در پوشهٔ `.github/workflows` تعریف شده است:

| شیوه | مسیر | وظیفه |
| --- | --- | --- |
| Build | `.github/workflows/build.yml` | روی هر `push`/`pull_request` به شاخهٔ `main` و همچنین اجرای دستی (`workflow_dispatch`): بیلد **debug** و **release** و بارگذاری APKها به‌عنوان Artifact. |
| Release | `.github/workflows/release.yml` | با پوش زدن تگ `v*` (مثلاً `v2.0.0`) یا اجرای دستی: ساخت APKها و ایجاد یک GitHub Release با پیوست کردن فایل‌ها. |

برای انتشار نسخهٔ جدید:

```bash
git tag v2.0.0
git push origin v2.0.0
```

> نکته: برای فعال‌شدن شیوهٔ Release، ارجاع `contents: write` لازم است که در فایل شیوه تنظیم شده است.

## مجوز

- کد این برنامه به‌دلیل استفاده از کدهای اقتباس‌شده از [IRRemote](#منابع-و-اعتبارات) تحت **مجوز GNU GPL نسخهٔ ۳** منتشر می‌شود (فایل `LICENSE`).
- فونت **وزیرمتن** تحت **مجوز SIL Open Font License 1.1** است (فایل `licenses/OFL-Vazirmatn.txt`).

## منابع و اعتبارات

بخش عمدهٔ این پروژه (پایگاه کدهای IR، کلاس‌های `Signal`، `SignalFactory`، `SignalCorrector` و منطق ارسال و تکرار) اقتباس شده از پروژه‌های زیر است:

| منبع | نقش در پروژه | مجوز | لینک |
| --- | --- | --- | --- |
| **IRRemote** (Divested-Mobile) | پایگاه کدهای IR و موتور تفسیر سیگنال | GPLv3 | <https://github.com/Divested-Mobile/IRRemote> |
| **IRRemote** (Twinone) | نسخهٔ اصلیٔ مرجع | GPLv3 | <https://github.com/twinone/IRRemote> |
| **Vazirmatn** (rastikerdar) | فونت فارسی رابط کاربری | OFL-1.1 | <https://github.com/rastikerdar/vazirmatn> |
| **سپهر** (Sepehr TV) | API فهرست شبکه‌ها و استریم زنده | — | <https://www.sepehrtv.ir/> |
| **تلوبیون** (Telewebion) | استریم زندهٔ شبکه‌ها | — | <https://telewebion.com/> |

مراجع فنی مورد استفاده در پیاده‌سازی:

- `ConsumerIrManager` — <https://developer.android.com/reference/android/hardware/ConsumerIrManager>
- ExoPlayer / Media3 – HLS — <https://developer.android.com/media/media3>
- قالب Pronto Hex (CCF) — <http://www.harctoolbox.org/Glossary.html> (مرجع تعریف قالب Pronto)
- Android Gradle Plugin — <https://developer.android.com/build>
- مجوز GPLv3 — <https://www.gnu.org/licenses/gpl-3.0.html>

## سلب مسئولیت

این پروژه فقط با هدف کنترل دستگاه‌ها و پخش محتوای در دسترس توسط کاربر توسعه داده شده است. نام و لوگوی شبکه‌ها، برندها و سرویس‌ها (سپهر، تلوبیون و ...) متعلق به صاحبان مربوطه هستند؛ این مخزن هیچ دخالتی در تولید محتوا ندارد و فهرست شبکه‌ها از API عمومی آن سرویس‌ها دریافت می‌شود. استفاده از این برنامه بر عهدهٔ کاربر است.

</div>
