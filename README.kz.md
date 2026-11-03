[Русский](README.md) · **Қазақша** · [English](README.en.md)

# Univer · ҚазҰУ

[Univer ҚазҰУ](https://univer.kaznu.kz/) студенттік порталына арналған ресми емес Android қолданбасы. Ол сайтты кірістірілген WebView ішінде ашады, сақталған деректермен өзі кіреді және беттерді телефонға ыңғайлы етеді. Қолданбаның әл-Фараби атындағы ҚазҰУ-ға қатысы жоқ, оның өз сервері, аналитикасы мен жарнамасы жоқ.

## Мүмкіндіктер

- **Қайта енгізусіз кіру.** Логин мен құпия сөз тек телефонда шифрланған күйде сақталады. Сайттағы сессия аяқталғанда қолданба сайттың өз пішіні арқылы қайта кіреді.
- **Сайттың мобильді көрінісі.** Сабақ кестесі, үлгерім, аттестация, ПОӘК және қашықтан оқыту курстарының кестелері карточкалар түрінде көрсетіледі. «Түпнұсқа» ауыстырғышы сайттың бастапқы бетін өзгеріссіз қайтарады.
- **Жүктеулер және файлдарды қарау.** Курс материалдары телефонның «Жүктеулер» бумасына сақталады. PDF, Word (.docx, .doc, .rtf), Excel (.xlsx, .xls), PowerPoint (.pptx, .ppt), OpenDocument, веб-беттер, суреттер мен мәтін қолданбаның ішінде ашылады. Файл мәтіні бойынша іздеу бар.
- **Сайт хабарландырулары.** Қоңырау сайттың белгілерін жинайды, мысалы «Анкетирование студентов (2)» (студенттер сауалнамасы). Студент жауап берген сауалнамалар көрсетілмейді.
- **СДО Moodle** қолданбаның ішінде ашылады: Univer арқылы кіру, тапсырмаларға файлдар мен фото тіркеу.
- **GitHub-тан жаңарту.** Қолданба жаңа нұсқаларды өзі тексереді, кіргенде ұсынады және екі-үш басумен орнатады.
- **PIN-код және биометрия** (қалау бойынша), орыс, қазақ және ағылшын тілдері, ашық және қараңғы тақырып, мәтін өлшемі.

## Орнату

Талаптар:

- Android 7.0 (API 24) немесе жаңарағы;
- өзекті Android System WebView немесе Chrome: мобильді көрініс скрипттері Chromium 80+ үшін есептелген.

Қадамдар:

1. Осы репозиторийдің **Releases** бетінен `Simple-UniverKazNU.apk` жүктеңіз.
2. SHA-256 бақылау сомасын шығарылым сипаттамасында жарияланғанмен салыстырыңыз:
   ```powershell
   Get-FileHash .\Simple-UniverKazNU.apk -Algorithm SHA256   # Windows
   ```
   ```bash
   sha256sum Simple-UniverKazNU.apk                           # Linux, macOS: shasum -a 256
   ```
3. Файлды телефонда ашып, Android сұрағанда осы көзден орнатуға рұқсат беріңіз.
4. Алғаш іске қосқанда Univer логині мен құпия сөзін енгізіп, «Сақтау және кіру» батырмасын басыңыз.

**Қолтаңба.** Шығарылымдарға «SOX Team» кілтімен қол қойылған: сертификат `CN=SOX Team, O=SOX Team, C=KZ`, SHA-256 `0aa54a2d4714db17bc71b9a0a4d4bc7818cb57d91f10685200416bd2094732b9`. Оны Android SDK Build Tools құрамындағы `apksigner verify --print-certs Simple-UniverKazNU.apk` командасымен тексеруге болады: `certificate SHA-256 digest` жолы сәйкес келуі керек.

**Жаңарту.** Қолданба GitHub-тағы жаңа нұсқаларды өзі тексереді. Кіргенде «Кейінірек» және «Орнату» батырмалары бар терезе көрсетеді, ал баптауларда тексеру батырмасы бар «Жаңартулар» бөлімі бар. «Орнату» басылғаннан кейін файл жүктеліп, тексеріледі және Android орнатушысы ашылады; алғаш рет Android осы қолданбадан орнатуға рұқсат сұрайды. «Қайта көрсетпеу» белгісі терезені осы нұсқа үшін алып тастайды: тексеру жалғасады, нұсқа баптауларда қалады, ал келесі нұсқа қайта ұсынылады. Жаңа APK ескісінің үстінен орнатылады, деректер сақталады. Release орнатпас бұрын жөндеу құрастырмасын (`app-debug.apk`) жою керек: олардың қолтаңбалары әртүрлі. 2026 жылғы 4 қарашаға дейін шыққан құрастырмаларға бұрынғы кілтпен қол қойылған, жаңа нұсқа олардың үстінен орнатылмайды: Android қолданба бар пакетпен қайшылыққа байланысты орнатылмағанын хабарлайды. Ескі қолданбаны жойып, жаңасын орнатыңыз да, қайта кіріңіз. Сақталған кіруді, PIN-кодты және жүктеулер тізімін қайта баптау керек болады, жүктелген файлдар «Жүктеулер» ішінде қалады.

**Деректерді қалай жоюға болады.** «Баптаулар» → «Аккаунттан шығу» сайттың cookies, кэші мен тарихын тазартады. «Сақталған деректерді жою» шифрланған логин мен құпия сөзді де өшіреді. Қолданбаны жою бәрін алып тастайды.

## Қауіпсіздік және құпиялылық

- Логин мен құпия сөз Android Keystore кілтімен AES-GCM арқылы шифрланады және сақтық көшірмелерге түспейді. PIN-кодпен деректер PIN-нен алынған кілтпен қосымша жабылады; 10 қате әрекеттен кейін сақталған кіру өшіріледі.
- Қолданба тек `univer.kaznu.kz` мекенжайына және СДО Moodle-ға (`dl.kaznu.kz`, `dl.kaznu.edu.kz`), тек HTTPS арқылы жүгінеді. Жаңарту үшін логинсіз және cookies-сіз GitHub-қа (`github.com`, `*.githubusercontent.com`) жүгінеді. Сертификат тексеруі өшірілмейді.
- Автоматты әрекеттер (кіру, белгілі «оқыдым» хабарламасы және қосымша келісім) тек `https://univer.kaznu.kz` нақты мекенжайында орындалады. Басқа пішіндерді қолданба жібермейді.

Толығырақ: [құпиялылық саясаты](PRIVACY.kz.md) және [қауіпсіздік саясаты](SECURITY.kz.md). Қауіпсіздік саясатында осалдық туралы қалай хабарлау керек және кодта нені тексеру керек екені де сипатталған.

## Бастапқы кодтан құрастыру

Қажет:

- кірістірілген JDK (`jbr`) бар [Android Studio](https://developer.android.com/studio);
- Android SDK Platform 37 және Platform Tools (SDK Manager арқылы орнатылады).

Gradle 9.8.0 нұсқасын `gradlew` қабықшасы өзі жүктейді; Gradle демонына арналған JDK 25 автоматты түрде таңдалады ([gradle/gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties)). Android Gradle Plugin 9.5.0, Kotlin оған кірістірілген. Барлық тәуелділіктер [gradle/libs.versions.toml](gradle/libs.versions.toml) ішінде бекітілген: тек AndroidX, Material Components және kotlinx-coroutines.

**Android Studio-да:** File → Open → жоба бумасы, Gradle Sync аяқталуын күтіп, `app` конфигурациясын таңдап, Run басыңыз.

**Пәрмен жолынан** (жоба түбірінде):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

```bash
export JAVA_HOME="/opt/android-studio/jbr"   # орнатуыңыздағы jbr жолы
./gradlew :app:assembleDebug
```

Құрастыру нәтижелері:

| Тапсырма | Файл | Қолтаңба |
|---|---|---|
| `:app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | жөндеу кілті |
| `:app:assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | `keystore.properties` ішіндегі кілт, онсыз APK қол қойылмаған |
| `:app:bundleRelease` | `app/build/outputs/bundle/release/app-release.aab` | сол, Google Play үшін |
| `:app:assembleQa` | `app/build/outputs/apk/qa/app-qa.apk` | жөндеу кілтімен R8 бар release құрастырмасы: debug үстінен орнатылып, деректерді сақтайды |

**Release қолтаңбасы.** `settings.gradle.kts` жанында `keystore.properties` файлын жасаңыз (ол `.gitignore` ішінде және репозиторийге түспейді):

```properties
storeFile=C:/жол/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Release R8 арқылы құрастырылады (код пен ресурстарды қысқарту); ережелер [app/src/main/keepRules](app/src/main/keepRules) ішінде.

## Тексерулер

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Аспаптық тесттер үшін іске қосылған эмулятор немесе телефон қажет. Тесттер жергілікті синтетикалық беттермен жұмыс істейді және Univer-ге ештеңе жібермейді:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
# бір класс:
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.univerflow.kaznu.NoticesAndTablesQaTest"
```

Тірі сайттың TLS тексеруі тек анық қосылады: `NetworkSecurityConfigTest` үшін `-Pandroid.testInstrumentationRunnerArguments.univer.liveTls=true` аргументі. `app/src/androidTest/assets/documents` ішінде `deck-photos.*` файлдары болмаса (16 МБ, репозиторийге кірмейді), үлкен презентациялар тесті өткізіліп жіберіледі.

## Код қалай құрылған

Қолданба нағыз сайтты көрсетеді: беттер көшірілмейді және ауыстырылмайды. Мобильді көрініс сайттың DOM-ы үстінен құрылады және «Түпнұсқа» ауыстырғышымен толық алынып тасталады.

| Жол | Онда не бар |
|---|---|
| [MainActivity.kt](app/src/main/java/com/univerflow/kaznu/MainActivity.kt) | сайттың WebView-і, «Артқа» навигациясы, экранды сессиямен және автоматтандырумен байланыстыру |
| [UniverViewModel.kt](app/src/main/java/com/univerflow/kaznu/UniverViewModel.kt) | қолданба күйі (StateFlow), шығу, аккаунтты ауыстыру, құлыптау |
| [domain/](app/src/main/java/com/univerflow/kaznu/domain) | Android-сыз таза Kotlin: PIN, хабарландырулар, жүктеулер, басты бет плиткаларының ережелері |
| [data/](app/src/main/java/com/univerflow/kaznu/data) | сақтау: Keystore шифрлау ([CredentialVault.kt](app/src/main/java/com/univerflow/kaznu/data/CredentialVault.kt)), PIN ([LockVault.kt](app/src/main/java/com/univerflow/kaznu/data/LockVault.kt), [LockCrypto.kt](app/src/main/java/com/univerflow/kaznu/data/LockCrypto.kt)), баптаулар |
| [web/](app/src/main/java/com/univerflow/kaznu/web) | мекенжайлар саясаты ([SitePolicy.kt](app/src/main/java/com/univerflow/kaznu/web/SitePolicy.kt)), WebView баптаулары ([SecureWebSettings.kt](app/src/main/java/com/univerflow/kaznu/web/SecureWebSettings.kt)), кіруді автоматтандыру және оның шектеулері ([AutomationController.kt](app/src/main/java/com/univerflow/kaznu/web/AutomationController.kt), [SessionGuard.kt](app/src/main/java/com/univerflow/kaznu/web/SessionGuard.kt)), мобильді көрініс |
| [download/](app/src/main/java/com/univerflow/kaznu/download) | сервер жауабын тексере отырып, телефонның «Жүктеулер» бумасына жүктеу |
| [update/](app/src/main/java/com/univerflow/kaznu/update), [Updates.kt](app/src/main/java/com/univerflow/kaznu/domain/Updates.kt) | GitHub-тан жаңартуларды тексеру және орнату: нұсқалар, рұқсат етілген мекенжайлар, SHA-256 және қолтаңба |
| [preview/](app/src/main/java/com/univerflow/kaznu/preview) | қолданбаның өз құжат оқу құралдары: PDF, Word, Excel, PowerPoint, OpenDocument, HTML |
| [ui/](app/src/main/java/com/univerflow/kaznu/ui), [main/](app/src/main/java/com/univerflow/kaznu/main) | нативті экрандар: «Жүктеулер», файлдарды қарау, PIN, баптаулар, хабарландырулар |
| [assets/](app/src/main/assets) | сайт беттеріне арналған JavaScript: кіру ([univer-automation.js](app/src/main/assets/univer-automation.js)), мобильді көрініс, хабарландырулар ([univer-notices.js](app/src/main/assets/univer-notices.js)) |
| [res/xml/network_security_config.xml](app/src/main/res/xml/network_security_config.xml) | сенімді сертификаттар: жүйелік, сондай-ақ тек `univer.kaznu.kz` үшін Sectigo R46 және тек `*.githubusercontent.com` жаңарту файлдары үшін ISRG Root X1 |
| [app/src/test](app/src/test), [app/src/androidTest](app/src/androidTest) | синтетикалық беттері бар бірлік тесттері және эмулятордағы тесттер |

Кіру сайттың өз пішіні арқылы жүреді: скрипт `#login_frm` көрінетін өрістерін толтырып, оның батырмасын басады, сондықтан сайттың құпия сөзді шифрлауы мен өңдегіштері айналып өтілмейді. Әрекеттер саны шектелген; сайт құпия сөзді қабылдамаса, қолданба тоқтап, оны баптауларда түзеткенше немесе «Қайталау» басылғанша күтеді.

## Шектеулер

- Сайт CAPTCHA немесе екі факторлы тексеру енгізсе, оларды қолмен өту керек.
- Сайттың HTML-і айтарлықтай өзгерсе, мобильді көрініс немесе автоматты кіру қолданба жаңартылғанша жұмыс істемей қалуы мүмкін.
- Өте ескі WebView-де (Chromium 80-нен төмен) сайт ашылады, бірақ мобильді көрініс пен автоматтандыру жұмыс істемеуі мүмкін.

## Лицензия

Copyright (C) 2026 Univer · ҚазҰУ авторлары.

Код [GNU General Public License](LICENSE) лицензиясының 3-нұсқасы немесе, сіздің таңдауыңыз бойынша, кез келген кейінгі нұсқасы (GPL-3.0-or-later) бойынша таратылады. Оны пайдалануға, өзгертуге және таратуға болады, бірақ қолданбаның өзгертілген нұсқасын сол лицензиямен бастапқы кодымен бірге шығару керек.

Әл-Фараби атындағы ҚазҰУ атауы, логотипі мен материалдары лицензияға кірмейді және университетке тиесілі.
