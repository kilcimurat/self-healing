# Self-Healing

Sentry'ye düşen crash'leri yakalar ve `config.yml`'da seçilen kodlama ajanına (Claude Code, Codex ya da özel bir CLI) düzelttirir. Ardından crash'i, crash'in oluştuğu **release sürümünde** Android emülatörde ve iOS simülatörde yeniden üretmeye çalışır. Düzeltilmiş uygulamayı **iki platformda da** derleyip test eder ve repoya PR açar. PR'a iki platformun kayıtlarından oluşan tek bir crash videosu eklenir.

| Modül | Ne işe yarar |
|---|---|
| `core` | Motor: Sentry webhook/polling, harness, git, cihaz yönetimi, doğrulama, PR (KMP, JVM) |
| `desktop` | Compose Multiplatform masaüstü uygulaması (macOS, Windows, Linux) |
| `server` | Arayüzsüz sunucu modu: aynı motor, loglar stdout'a yazılır |

## Ekran

```
┌──────────────────────────────┬────────────────────────────┐
│ Crash'ler (1/3)              │                            │
├──────────────┬───────────────┤        Terminal            │
│   Android    │      iOS      │  (harness / build / git)   │
│  emülatörü   │  simülatörü   │                            │
│   (2/3)      │               │                            │
└──────────────┴───────────────┴────────────────────────────┘
```

**Başlat**'a basıldığında Android emülatörü (`android.avd`) ve iOS simülatörü (`ios.simulator`) ayrı pencere açmadan arka planda başlar ve ekranları sol alttaki alanda canlı görünür. Ekranlara tıklayıp kaydırabilirsiniz: Android'de bu `adb` ile, iOS'ta [AXe](https://github.com/cameroncooke/AXe) ile yapılır. Ekranların altında Geri / Ana ekran / Son butonları bulunur.

## Akış

```
Sentry ──webhook/polling──► crash
  1. Hazırlık     : projects/<repo> klonu → izole git worktree + yeni branch
  2. Analiz       : harness kök nedeni bulur, düzeltir, result.json ve Maestro senaryosu (repro.yaml) yazar
  3. Tekrar üret  : crash'in sürümü (örn. v1.1.0) iki cihaza kurulur, senaryo çalışır → "crash Android'de / iOS'ta var mı?"
                    Android: GitHub release'teki APK · iOS: tag simülatör için kaynak koddan derlenir
                    Uygulama açılışından crash'ten birkaç saniye sonrasına kadar iki ekran da videoya kaydedilir
  4. Doğrulama    : düzeltilmiş sürüm iki cihaza kurulur, aynı senaryo aynı şekilde kaydedilir,
                    ardından arka plan/ön plan turu → crash yok mu?
  5. Video        : 4 kayıt ffmpeg ile tek videoda birleşir: solda Android, sağda iOS; üstte release, altta düzeltme.
                    Maestro'nun dokunduğu her nokta videoda halka olarak görünür
  6. PR           : iki platform da geçtiyse draft PR açılır; yalnızca harness'ın değiştirdiği dosyalar commit'lenir
                    (build'in güncellediği Package.resolved vb. geri alınır). Video (MP4 + önizleme GIF) ayrı
                    `self-healing-assets` branch'ine gider ve PR'a yorum olarak eklenir
```

Bir adım başarısız olursa PR açılmaz ve crash `FAILED` durumunda kalır. Her crash'in tüm logu `workspace/artifacts/<crash-id>/run.log` dosyasına yazılır.

Çalışma bittiğinde (PR açılsa da hata alsa da) o çalışmanın kalıntıları silinir:

- `workspace/runs/` altındaki worktree, build çıktılarıyla birlikte silinir.
- Yerel branch silinir; PR branch'i GitHub'da kalır.
- `workspace/releases/` altındaki release checkout'u silinir. Aynı sürümü kullanan başka bir crash hâlâ çalışıyorsa, o da bitene kadar tutulur.
- APK, video ve ekran görüntüleri silinir. PR açıldıysa klasörde yalnızca `run.log`, `result.json` ve `repro.yaml` kalır. Çalışma başarısız olduysa, hatayı inceleyebilmek için ekran görüntüleri ve Maestro'nun hata anındaki çıktısı (`maestro-*`) da saklanır.

Video ve ekran görüntüleri PR'da durmaya devam eder. Arayüzdeki "Tekrar dene" ile çalışma yeniden başlatılabilir.

Senaryo crash olmadan başarısız olursa (örneğin yoğun makinede klavye zamanında kapanmayıp bir butonu örterse) bir kez daha denenir. Crash olursa yeniden denenmez.

## Gereksinimler

### Her zaman
| Araç | Neden | Kurulum |
|---|---|---|
| JDK 17+ | Uygulamayı çalıştırmak için | `brew install --cask temurin@17` |
| git | Klonlama, worktree, push | macOS'ta Xcode Command Line Tools ile gelir |
| Harness CLI | Crash'i düzeltir | Claude Code: `npm install -g @anthropic-ai/claude-code`, ardından `claude` ile giriş · Codex: `npm install -g @openai/codex`, ardından `codex login` |
| **Maestro** | Harness'ın yazdığı crash senaryosunu iki cihazda çalıştırır | `curl -fsSL "https://get.maestro.mobile.dev" \| bash` (`~/.maestro/bin` altına kurulur, uygulama orada da arar) |
| **ffmpeg** | Kayıtları tek videoda birleştirir, PR için GIF önizleme üretir | `brew install ffmpeg` · Ubuntu: `sudo apt install ffmpeg` · Windows: `winget install ffmpeg` |

Maestro kurulu değilse senaryo adımı atlanır. Bu durumda doğrulama yalnızca "uygulama açılıyor ve çökmüyor" kontrolüne iner. ffmpeg yoksa video birleştirilmez ve PR yorumuna ekran görüntüleri tablosu eklenir.

### Android
| Araç | Neden | Kurulum |
|---|---|---|
| Android SDK (platform-tools, emulator) | Build, `adb`, emülatör | Android Studio ile gelir. Konum `ANDROID_HOME` ya da varsayılan konumdan bulunur (`~/Library/Android/sdk`, `~/Android/Sdk`, `%LOCALAPPDATA%\Android\Sdk`) |
| Bir AVD | Testin yapılacağı emülatör | Android Studio → Device Manager. Adını `android.avd`'ye yazın (örn. `Pixel_10`) |

### iOS (yalnızca macOS)
| Araç | Neden | Kurulum |
|---|---|---|
| Xcode + iOS Simulator runtime | Build ve simülatör | App Store'dan Xcode, ardından Settings → Components. Simülatör adını `ios.simulator`'a yazın |
| **AXe** | iOS ekranına dokunma ve kaydırma | `brew install cameroncooke/axe/axe` |

AXe kurulu değilse iOS ekranı yalnızca izlenebilir. Elle dokunmanız gerekirse `openSimulatorApp: true` ile Simulator.app penceresini de açabilirsiniz.

### İsteğe bağlı
| Araç | Neden | Kurulum |
|---|---|---|
| cloudflared / ngrok | Sentry webhook'unun makinenize ulaşması. Yoksa 60 saniyelik polling kullanılır | `brew install cloudflared` |
| gh | GitHub işlemlerini elle kontrol etmek | `brew install gh` |

## Token'lar

| Alan | Nereden | Yetkiler |
|---|---|---|
| `sentry.authToken` | Sentry → User Settings → **Personal Tokens**. Token `sntryu_` ile başlar. Organization token'ları (`sntrys_`) çalışmaz | Project: Read & Write · Issue & Event: Read · Organization: Read |
| `github.token` | GitHub → Settings → Developer settings → **Fine-grained token**, yalnızca ilgili repolara | Contents, Pull requests, Issues: Read & Write |

Sentry'nin AB bölgesindeki organizasyonlarda `sentry.baseUrl: https://de.sentry.io` kullanın. Webhook için gereken service hook özelliği yalnızca Sentry'nin Business planında var. Diğer planlarda uygulama sorgulama (polling) ile çalışır; ayrıntılar [config bölümünde](#configyml-doldurma).

## Test hesabı

Crash'e giden yol giriş gerektiriyorsa (örneğin Recoup'ta Ayarlar ekranı), `repos[].testAccount` ayarına bir test hesabı girin:

```yaml
testAccount:
  username: "qa@example.com"
  password: "${TEST_ACCOUNT_PASSWORD}"   # ya da düz metin; config.yml git'e eklenmez
  loginHint: "Continue with email → Email / Password → Sign in"
```

Şifre harness'a (LLM'e) **hiç gönderilmez**. Harness senaryoya yalnızca `${TEST_USERNAME}` ve `${TEST_PASSWORD}` yazar; gerçek değerleri Maestro çalışma anında `-e` ile alır. Şifre loglarda `***` olarak maskelenir. Bunun için yalnızca test amaçlı bir hesap kullanın.

## Crash videosu

- **Kayıt:** Android'de `adb shell screenrecord` kullanılır (en fazla 3 dakika). iOS'ta `xcrun simctl io recordVideo` kullanılır.
- **Dokunuşlar:** pencere olmadan çalışan emülatör ve simülatör dokunuşları ekranda göstermez. Bu yüzden Maestro'nun logladığı dokunuş koordinatları (`workspace/artifacts/<crash-id>/maestro-*`) birleştirme sırasında videoya halka olarak çizilir.
- **Birleştirme:** ızgarada solda Android, sağda iOS; üstte release, altta düzeltilmiş sürüm yer alır. Kısa kalan kayıt, son karesinde (örneğin crash anında) durur.
- **PR yorumu:** GitHub, repodaki MP4'ü yorumun içinde oynatmaz. Bu yüzden yorumda GIF önizleme gösterilir, tam kalite MP4'e link verilir.

Videoda test hesabının kullanıcı adı (e-posta) giriş ekranında görünür. Şifre alanı maskelidir. Bu nedenle yalnızca test amaçlı bir hesap kullanın.

## Release'te tekrar üretme

`repos[].release` ayarı açıksa, Sentry'deki sürüm (`app@1.1.0+110`) `tag` şablonuyla (`v{version}`) tag'e çevrilir:

- **Android:** GitHub release'inden `androidAsset` desenine uyan APK indirilir. APK yoksa tag kaynak koddan derlenir.
- **iOS:** release'teki IPA cihaz build'i olduğu için simülatörde çalışmaz. Bu yüzden tag `workspace/releases/` altında açılıp simülatör için derlenir.

## config.yml doldurma

Uygulamanın ihtiyaç duyduğu her şey tek bir dosyadan okunur: token'lar, repolar, cihazlar, harness ve model. Örnek dosyadan bir kopya oluşturun:

```bash
cp config.example.yml config.yml
```

`config.yml` **`.gitignore`'dadır ve asla commit'lenmemelidir**; içinde kişisel token'larınız olacak.

### Genel kurallar

- **Ortam değişkenleri:** bir değer `${DEGISKEN}` biçiminde yazılırsa ortam değişkeninden okunur, `${DEGISKEN:-varsayılan}` biçiminde yazılırsa değişken yoksa varsayılan kullanılır. Token'ları dosyaya yazmak yerine böyle vermek daha güvenlidir:
  ```bash
  export SENTRY_AUTH_TOKEN=sntryu_...
  export GITHUB_TOKEN=github_pat_...
  ```
  Yorum satırlarındaki `${...}` ifadeleri değiştirilmez.
- **Göreli yollar:** `./workspace` gibi yollar, `config.yml`'ın bulunduğu klasöre göre çözülür.
- **Kontrol:** dosyayı doldurduktan sonra `./gradlew :core:jvmTest` çalıştırın. Testlerden biri yerel `config.yml`'ınızı okur ve yazım hatalarını gösterir.

### 1. `sentry` — crash'lerin geldiği yer

```yaml
sentry:
  baseUrl: https://de.sentry.io
  authToken: ${SENTRY_AUTH_TOKEN}
  organization: my-org
  projects:
    - slug: shop-android
      repo: shop
    - slug: shop-ios
      repo: shop
```

| Alan | Ne yazılır |
|---|---|
| `baseUrl` | ABD bölgesi için `https://sentry.io`, AB bölgesi için `https://de.sentry.io`, kendi sunucunuz için onun adresi. Organizasyonunuzun adresinde `de.sentry.io` görüyorsanız AB'dir. |
| `authToken` | Sentry → User Settings → **Personal Tokens** → Create New Token. Yetkiler: Project **Read & Write**, Issue & Event **Read**, Organization **Read**. Token `sntryu_` ile başlamalı; organization token'ları (`sntrys_`) issue listesine erişemez. |
| `organization` | Organizasyonun kısa adı (slug). Adres çubuğunda `sentry.io/organizations/<slug>/` olarak görünür. |
| `projects` | İzlenecek her Sentry projesi için bir satır. `slug` projenin kısa adı, `repo` ise aşağıdaki `repos` listesindeki `id`. İki platformu tek repoda olan bir uygulamada iki proje aynı `repo`'yu gösterir. |

**Crash'lerin nasıl alınacağı:**

```yaml
  webhook:
    enabled: true
    path: /sentry/webhook
  polling:
    enabled: true
    intervalSeconds: 60
```

- **Polling (sorgulama, önerilen):** her `intervalSeconds` saniyede bir Sentry'ye "Başlat'tan sonra gelen yeni crash var mı?" diye sorar. Her planda ve her ağda çalışır. Tek eksiği, crash'in en fazla `intervalSeconds` saniye gecikmeyle gelmesi.
- **Webhook:** Sentry crash'i anında gönderir. Bunun için iki şart var:
  1. **Business planı:** Sentry'nin service hook özelliği yalnızca bu planda var.
  2. **Erişilebilir adres:** makinenizin internetten erişilebilir bir adresi olmalı. `server.publicUrl`'a bir tünel adresi yazılır; örneğin `cloudflared tunnel --url http://localhost:8787` komutunun verdiği adres.

  `publicUrl` boşsa webhook atlanır ve yalnızca polling kullanılır.

**Hangi crash'lerin işleneceği:**

```yaml
  filters:
    levels: [fatal, error]
    onlyUnhandled: true
    onlyNewIssues: true
```

- `levels`: yalnızca bu seviyedeki olaylar işlenir.
- `onlyUnhandled`: yalnızca yakalanmamış istisnalar, yani gerçek crash'ler işlenir.
- `onlyNewIssues`: yalnızca Başlat'tan sonra Sentry'ye ulaşan crash'ler işlenir. Eski bir issue'ya yeni olay gelmesi de sayılır. Android, çöken uygulamanın raporunu uygulama bir sonraki açılışında gönderir; bu rapor da yakalanır.

### 2. `github` — PR'ların açılacağı yer

```yaml
github:
  apiUrl: https://api.github.com
  token: ${GITHUB_TOKEN}
  draftPr: true
  reviewers: [ekip-arkadasi]
  labels: [self-healing]
  assetsBranch: self-healing-assets
```

| Alan | Ne yazılır |
|---|---|
| `apiUrl` | github.com için olduğu gibi bırakın. GitHub Enterprise için `https://<sunucu>/api/v3`. |
| `token` | GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate. *Resource owner*: repoların sahibi olan hesap ya da organizasyon. *Repository access*: yalnızca düzeltilecek repolar. *Permissions*: **Contents**, **Pull requests** ve **Issues** için Read and write. |
| `draftPr` | `true`: PR'lar taslak olarak açılır. İnceleme sonrası siz "Ready for review" yaparsınız. |
| `reviewers` | PR'a otomatik atanacak GitHub kullanıcı adları; boş bırakılabilir. |
| `labels` | PR'a eklenecek etiketler. |
| `assetsBranch` | Videoların ve ekran görüntülerinin konduğu ayrı branch. İlk PR'da kendiliğinden oluşur ve PR'ların diff'ine hiç girmez. |
| `commitAuthorName`, `commitAuthorEmail` | İsteğe bağlı: düzeltme commit'lerinin yazar bilgisi. Varsayılan "Self-Healing Bot". |

### 3. `repos` — düzeltilecek uygulamalar

Her repo için bir girdi yazılır. Bir repo hem `android` hem `ios` içeriyorsa (örneğin Kotlin Multiplatform), crash hangi platformdan gelirse gelsin **iki platformda da** tekrar üretilir ve doğrulanır. Android ve iOS ayrı repolardaysa iki ayrı girdi yazılır.

```yaml
repos:
  - id: shop
    url: https://github.com/my-org/shop.git
    baseBranch: main
```

- `id`: `sentry.projects[].repo` alanında kullanılacak serbest bir ad.
- `url`: HTTPS klon adresi. Repo `pipeline.cloneDir` altına bir kez klonlanır.
- `baseBranch`: düzeltme branch'inin açılacağı ve PR'ın hedefleyeceği branch.

**Android:**

```yaml
    android:
      buildCommand: ./gradlew :app:installDebug
      applicationId: com.example.shop
      avd: Pixel_8
      dnsServers: [8.8.8.8, 1.1.1.1]
```

| Alan | Ne yazılır |
|---|---|
| `buildCommand` | Repo kökünde çalışan ve debug uygulamayı **cihaza kuran** komut. Çok modüllü projede modülü ve varyantı yazın, örneğin `./gradlew :app:installProdDebug`. |
| `applicationId` | Kurulan uygulamanın paket adı. Debug varyantında sonek varsa (`.debug` gibi) onunla birlikte yazın. |
| `avd` | Android Studio → Device Manager'daki emülatörün adı. `emulator -list-avds` komutuyla da görülebilir. Başlat'ta pencere açılmadan başlatılır ve uygulamada gösterilir. |
| `device` | Emülatör yerine gerçek bir cihaz kullanılacaksa `adb devices`'taki seri numarası. Boşsa `avd` kullanılır. |
| `launchActivity` | Boş bırakın; uygulama varsayılan başlatıcı ekranıyla açılır. |
| `cleanInstall` | `true`: kurulumdan önce uygulama kaldırılır. Mağaza ya da release imzalı bir kurulum debug kurulumunu engellemesin diye gerekir. |
| `dnsServers` | Emülatörün DNS sunucuları. Boş bırakılırsa emülatör bilgisayarın açılış anındaki DNS'ini kullanır. Ağ ya da VPN değişince bu bozulur ve giriş gibi ağ istekleri takılır. |
| `reproCommand` | İsteğe bağlı: harness Maestro senaryosu yazmazsa kullanılacak kendi senaryo komutunuz. |

**iOS (yalnızca macOS):**

```yaml
    ios:
      buildCommand: >-
        xcodebuild -workspace Shop.xcworkspace -scheme Shop -configuration Debug
        -sdk iphonesimulator -derivedDataPath build/self-healing CODE_SIGNING_ALLOWED=NO build
      appPath: build/self-healing/Build/Products/Debug-iphonesimulator/Shop.app
      bundleId: com.example.shop
      simulator: iPhone 16
```

| Alan | Ne yazılır |
|---|---|
| `buildCommand` | Simülatör için derleyen komut. `-project` ya da `-workspace` ile `-scheme` projenize göre değişir. `CODE_SIGNING_ALLOWED=NO` imzasız derler, böylece sertifika gerekmez. CocoaPods kullanılıyorsa başına `pod install && ` ekleyin. |
| `appPath` | Derlenen `.app`'in repo köküne göre yolu. `-derivedDataPath` ile aynı klasörden başlar. |
| `bundleId` | Uygulamanın bundle identifier'ı. |
| `simulator` | `xcrun simctl list devices` çıktısındaki bir simülatör adı ya da UDID'si. |
| `openSimulatorApp` | `true`: Simulator.app penceresini de açar. AXe kurulu değilse elle dokunmak için kullanılır. |

**Release'te tekrar üretme:**

```yaml
    release:
      enabled: true
      tag: v{version}
      androidAsset: 'MyApp-.*\.apk'
      requireReproduction: false
```

- `enabled`: crash önce, oluştuğu sürümde tekrar üretilir.
- `tag`: Sentry'deki sürümün (`com.example.shop@1.2.0+120`) git tag'ine nasıl çevrileceği. `{version}` = `1.2.0`, `{build}` = `120`. Tag'leriniz `v1.2.0` ise `v{version}` yazın.
- `androidAsset`: GitHub Releases'taki APK dosyasına uyan desen. Uygun APK yoksa tag kaynak koddan `buildCommand` ile derlenir. iOS her zaman kaynaktan derlenir.
- `requireReproduction`: `true` ise crash hiçbir platformda tekrar üretilemezse PR açılmaz.

**Test hesabı** (crash'e giden yol giriş gerektiriyorsa):

```yaml
    testAccount:
      username: "qa@example.com"
      password: "${TEST_ACCOUNT_PASSWORD}"
      loginHint: "Continue with email → Email / Password → Sign in"
```

`loginHint`, harness'a giriş ekranına nasıl gidileceğini anlatan kısa bir ipucudur. Şifre harness'a gönderilmez; ayrıntılar için [Test hesabı](#test-hesabı) bölümüne bakın.

### 4. `harness` — düzeltmeyi yapan ajan

```yaml
harness:
  type: claude-code
  model: claude-opus-5-5
  timeoutMinutes: 30
```

| Alan | Ne yazılır |
|---|---|
| `type` | `claude-code`, `codex` ya da `custom`. Seçilen CLI'nin kurulu ve giriş yapılmış olması gerekir. |
| `model` | CLI'ye verilecek model adı. |
| `env` | İsteğe bağlı: CLI'ye verilecek ortam değişkenleri, örneğin `ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}`. Boşsa CLI'nin kendi oturumu kullanılır. |
| `extraArgs` | CLI'ye eklenecek ek argümanlar. |
| `command` | Yalnızca `custom` için komut şablonu. Kullanılabilen yer tutucular: `{model}`, `{promptFile}`, `{repoDir}`, `{resultFile}`. |
| `timeoutMinutes` | Bir düzeltme için tanınan en uzun süre. |

### 5. `pipeline` ve `server`

```yaml
pipeline:
  workDir: ./workspace
  cloneDir: ./projects
  maxConcurrent: 1
  branchPrefix: self-healing/
  verifyWaitSeconds: 15
  buildTimeoutMinutes: 30
```

| Alan | Ne yazılır |
|---|---|
| `workDir` | Çalışma kopyaları, loglar ve kayıtlar. Büyük projelerde bir çalışma birkaç GB tutabilir; yeterli boş alanı olan bir disk seçin. Her çalışma bitince temizlenir. |
| `cloneDir` | Repoların klonlandığı klasör (`projects/<repo>`). |
| `maxConcurrent` | Aynı anda işlenecek crash sayısı. Cihazlar ortak olduğu için `1` önerilir. |
| `branchPrefix` | Düzeltme branch'lerinin öneki. Branch adı `<önek><crash-id>-<başlık>` olur. |
| `verifyWaitSeconds` | Senaryo yoksa uygulamanın çökmeden açık kalması gereken süre. |
| `buildTimeoutMinutes` | Bir build için en uzun süre. |
| `maestroCommand` | Maestro PATH'te değilse tam yolu. |

`server` bölümü yalnızca webhook için gereklidir: `port` dinlenecek port, `publicUrl` ise Sentry'nin ulaşacağı adres. Polling kullanılıyorsa olduğu gibi bırakılabilir.

### Yalnızca Android olan en küçük örnek

```yaml
sentry:
  baseUrl: https://sentry.io
  authToken: ${SENTRY_AUTH_TOKEN}
  organization: my-org
  projects:
    - slug: my-android-app
      repo: app

github:
  token: ${GITHUB_TOKEN}

repos:
  - id: app
    url: https://github.com/me/my-app.git
    baseBranch: main
    android:
      buildCommand: ./gradlew :app:installDebug
      applicationId: com.me.app
      avd: Pixel_8
      dnsServers: [8.8.8.8, 1.1.1.1]

harness:
  type: claude-code
  model: claude-opus-5-5
```

Yazılmayan alanlar varsayılan değerleriyle çalışır.

## Çalıştırma

### Masaüstü

```bash
./gradlew :desktop:run --args="$PWD/config.yml"
```

macOS'ta Finder'dan [SelfHealing.command](SelfHealing.command) dosyasına çift tıklayarak da başlatabilirsiniz. Bu dosya Terminal'i açar ve yukarıdaki komutu proje klasöründe çalıştırır.

Kurulum paketi (macOS `.dmg`, Windows `.msi`, Linux `.deb`) için:

```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

### Sunucu

```bash
./gradlew :server:installDist
```

```bash
server/build/install/server/bin/server --config /path/to/config.yml
```

Sunucuda Android için KVM destekli bir Linux makine yeterli; emülatör ekransız (`-no-window`) çalışır. iOS doğrulaması için sunucunun macOS olması gerekir.

## Güvenlik

- Harness yalnızca izole bir worktree içinde çalışır. Commit, push ve PR işlerini harness değil uygulama yapar.
- Token'lar loglarda `***` olarak maskelenir. Git kimlik doğrulaması HTTP header ile yapılır, token remote URL'ine yazılmaz.
- Video ve ekran görüntüleri PR'ın diff'ine girmez; ayrı `self-healing-assets` branch'inde tutulur.
- PR'lar varsayılan olarak **draft** açılır. Otomatik merge yoktur.
- Build ve kurulum yalnızca config'deki cihaza yapılır (`ANDROID_SERIAL`); bilgisayara bağlı telefonlara dokunulmaz.
