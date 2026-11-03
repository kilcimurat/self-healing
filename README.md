# Self-Healing

Self-Healing catches the crashes that reach Sentry and has a coding agent fix them. You choose the agent in `config.yml`: Claude Code, Codex or a custom CLI. It then tries to reproduce each crash on the **release build** it happened in, on an Android emulator and an iOS simulator. It builds and tests the fixed app on **both platforms** and opens a pull request. The PR gets a single crash video made from both platforms' recordings.

| Module | What it does |
|---|---|
| `core` | The engine: Sentry webhook/polling, harness, git, device management, verification, PR (KMP, JVM) |
| `desktop` | Compose Multiplatform desktop app (macOS, Windows, Linux) |
| `server` | Headless server mode: the same engine, with logs written to stdout |

## Screen

```
┌──────────────────────────────┬────────────────────────────┐
│ Crashes (1/3)                │                            │
├──────────────┬───────────────┤        Terminal            │
│   Android    │      iOS      │  (harness / build / git)   │
│   emulator   │   simulator   │                            │
│   (2/3)      │               │                            │
└──────────────┴───────────────┴────────────────────────────┘
```

When you press **Başlat** (Start), the Android emulator (`android.avd`) and the iOS simulator (`ios.simulator`) start in the background without their own windows. Their screens appear live in the lower-left area. You can tap and swipe on them: through `adb` on Android and through [AXe](https://github.com/cameroncooke/AXe) on iOS. Back / Home / Recents buttons sit under the screens.

## Flow

```
Sentry ──webhook/polling──► crash
  1. Prepare     : clone into projects/<repo> → isolated git worktree + new branch
  2. Analyze     : the harness finds the root cause, fixes it, writes result.json and a Maestro flow (repro.yaml)
  3. Reproduce   : the crash's version (e.g. v1.1.0) is installed on both devices and the flow runs → "does it crash on Android / iOS?"
                   Android: the APK from the GitHub release · iOS: the tag is built from source for the simulator
                   Both screens are recorded from app launch until a few seconds after the crash
  4. Verify      : the fixed build is installed on both devices, the same flow is recorded the same way,
                   then a background/foreground round trip → no crash?
  5. Video       : ffmpeg combines the 4 recordings into one video: Android left, iOS right; release on top, fix below.
                   Every point Maestro tapped is marked with a ring
  6. PR          : if both platforms pass, a draft PR is opened; only the files the harness changed are committed
                   (Package.resolved and similar files rewritten by the build are reverted). The video (MP4 + GIF preview)
                   goes to a separate `self-healing-assets` branch and is added to the PR as a comment
```

If a step fails, no PR is opened and the crash stays `FAILED`. The full log of each crash is written to `workspace/artifacts/<crash-id>/run.log`.

When a run ends, whether a PR was opened or it failed, its leftovers are deleted:

- The worktree under `workspace/runs/` is deleted together with its build output.
- The local branch is deleted; the PR branch stays on GitHub.
- The release checkout under `workspace/releases/` is deleted. If another crash of the same version is still running, it is kept until that one finishes too.
- The APK, videos and screenshots are deleted. After a PR, only `run.log`, `result.json` and `repro.yaml` remain. After a failed run, the screenshots and Maestro's output at the failing step (`maestro-*`) are kept as well, so the failure can be investigated.

The video and screenshots stay available in the PR. **Tekrar dene** (Retry) in the app starts a run again.

If the flow fails without a crash (for example, on a busy machine the keyboard doesn't close in time and covers a button), it is retried once. A crash is never retried.

## Requirements

### Always
| Tool | Why | Install |
|---|---|---|
| JDK 17+ | Runs the app | `brew install --cask temurin@17` |
| git | Cloning, worktrees, pushing | Comes with the Xcode Command Line Tools on macOS |
| Harness CLI | Fixes the crash | Claude Code: `npm install -g @anthropic-ai/claude-code`, then sign in with `claude` · Codex: `npm install -g @openai/codex`, then `codex login` |
| **Maestro** | Runs the harness-written crash flow on both devices | `curl -fsSL "https://get.maestro.mobile.dev" \| bash` (installs into `~/.maestro/bin`, where the app also looks) |
| **ffmpeg** | Combines the recordings into one video and makes the GIF preview for the PR | `brew install ffmpeg` · Ubuntu: `sudo apt install ffmpeg` · Windows: `winget install ffmpeg` |

Without Maestro the flow step is skipped, and verification falls back to "the app launches and doesn't crash". Without ffmpeg the recordings are not combined, and the PR comment gets a table of screenshots instead.

### Android
| Tool | Why | Install |
|---|---|---|
| Android SDK (platform-tools, emulator) | Builds, `adb`, emulator | Comes with Android Studio. Found through `ANDROID_HOME` or the default location (`~/Library/Android/sdk`, `~/Android/Sdk`, `%LOCALAPPDATA%\Android\Sdk`) |
| An AVD | The emulator the tests run on | Android Studio → Device Manager. Put its name in `android.avd` (e.g. `Pixel_10`) |

### iOS (macOS only)
| Tool | Why | Install |
|---|---|---|
| Xcode + iOS Simulator runtime | Builds and the simulator | Xcode from the App Store, then Settings → Components. Put the simulator name in `ios.simulator` |
| **AXe** | Tapping and swiping on the iOS screen | `brew install cameroncooke/axe/axe` |

Without AXe the iOS screen is view-only. If you need to tap by hand, `openSimulatorApp: true` also opens the Simulator.app window.

### Optional
| Tool | Why | Install |
|---|---|---|
| cloudflared / ngrok | Lets the Sentry webhook reach your machine. Without it, 60-second polling is used | `brew install cloudflared` |
| gh | Checking GitHub by hand | `brew install gh` |

## Tokens

| Field | Where from | Permissions |
|---|---|---|
| `sentry.authToken` | Sentry → User Settings → **Personal Tokens**. The token starts with `sntryu_`. Organization tokens (`sntrys_`) don't work | Project: Read & Write · Issue & Event: Read · Organization: Read |
| `github.token` | GitHub → Settings → Developer settings → **Fine-grained token**, limited to the relevant repositories | Contents, Pull requests, Issues: Read & Write |

For organizations in Sentry's EU region, use `sentry.baseUrl: https://de.sentry.io`. The service hook feature the webhook needs is only on Sentry's Business plan. On other plans the app works by polling; see the [config section](#filling-in-configyml) for details.

## Test account

If the path to the crash requires signing in, enter a test account in `repos[].testAccount`:

```yaml
testAccount:
  username: "qa@example.com"
  password: "${TEST_ACCOUNT_PASSWORD}"   # or plain text; config.yml is never committed
  loginHint: "Continue with email → Email / Password → Sign in"
```

The password is **never sent** to the harness (the LLM). The harness only writes `${TEST_USERNAME}` and `${TEST_PASSWORD}` into the flow, and Maestro receives the real values at run time through `-e`. The password is masked as `***` in logs. Use an account meant only for testing.

## Crash video

- **Recording:** `adb shell screenrecord` on Android (3 minutes at most), `xcrun simctl io recordVideo` on iOS.
- **Taps:** an emulator or simulator running without a window doesn't show taps on screen. So the tap coordinates Maestro logs (`workspace/artifacts/<crash-id>/maestro-*`) are drawn onto the video as rings when it is combined.
- **Layout:** a grid with Android on the left and iOS on the right, the release build on top and the fixed build below. A shorter recording holds on its last frame (for example, the moment of the crash).
- **PR comment:** GitHub doesn't play an MP4 from a repository inside a comment, so the comment shows a GIF preview and links to the full-quality MP4.

The test account's username (email) is visible on the sign-in screen in the video. The password field is masked. This is another reason to use an account meant only for testing.

## Reproducing on the release

When `repos[].release` is enabled, the Sentry release (`app@1.1.0+110`) is turned into a git tag with the `tag` template (`v{version}`):

- **Android:** the APK matching the `androidAsset` pattern is downloaded from the GitHub release. Without one, the tag is built from source.
- **iOS:** the IPA in a release is a device build and doesn't run on the simulator. So the tag is checked out under `workspace/releases/` and built for the simulator.

## Filling in config.yml

Everything the app needs comes from a single file: tokens, repositories, devices, harness and model. Make a copy of the example:

```bash
cp config.example.yml config.yml
```

`config.yml` is in **`.gitignore` and must never be committed**; it will hold your personal tokens.

### General rules

- **Environment variables:** a value written as `${VARIABLE}` is read from the environment, and `${VARIABLE:-default}` falls back to the default when the variable isn't set. Passing tokens this way is safer than writing them into the file:
  ```bash
  export SENTRY_AUTH_TOKEN=sntryu_...
  export GITHUB_TOKEN=github_pat_...
  ```
  `${...}` inside comments is left untouched.
- **Relative paths:** paths such as `./workspace` are resolved against the folder `config.yml` is in.
- **Checking:** after filling it in, run `./gradlew :core:jvmTest`. One of the tests reads your local `config.yml` and reports mistakes.

### 1. `sentry` — where the crashes come from

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

| Field | What to put |
|---|---|
| `baseUrl` | `https://sentry.io` for the US region, `https://de.sentry.io` for the EU region, or the address of your own server. If your organization's address contains `de.sentry.io`, it is in the EU. |
| `authToken` | Sentry → User Settings → **Personal Tokens** → Create New Token. Permissions: Project **Read & Write**, Issue & Event **Read**, Organization **Read**. The token must start with `sntryu_`; organization tokens (`sntrys_`) can't read the issue list. |
| `organization` | The organization's short name (slug). It appears in the address bar as `sentry.io/organizations/<slug>/`. |
| `projects` | One line per Sentry project to watch. `slug` is the project's short name, and `repo` is an `id` from the `repos` list below. For an app with both platforms in one repository, both projects point to the same `repo`. |

**How crashes are received:**

```yaml
  webhook:
    enabled: true
    path: /sentry/webhook
  polling:
    enabled: true
    intervalSeconds: 60
```

- **Polling (recommended):** every `intervalSeconds`, it asks Sentry whether new crashes arrived since Start. It works on every plan and every network. The only drawback is that a crash can arrive up to `intervalSeconds` late.
- **Webhook:** Sentry sends the crash immediately. This has two requirements:
  1. **Business plan:** Sentry's service hook feature is only on this plan.
  2. **Reachable address:** your machine needs an address reachable from the internet. Put a tunnel address in `server.publicUrl`, for example the one `cloudflared tunnel --url http://localhost:8787` prints.

  If `publicUrl` is empty, the webhook is skipped and only polling is used.

**Which crashes are processed:**

```yaml
  filters:
    levels: [fatal, error]
    onlyUnhandled: true
    onlyNewIssues: true
```

- `levels`: only events at these levels are processed.
- `onlyUnhandled`: only unhandled exceptions, i.e. real crashes, are processed.
- `onlyNewIssues`: only crashes that reach Sentry after Start are processed. A new event on an old issue counts too. Android sends a crashed app's report on the app's next launch, and that report is caught as well.

### 2. `github` — where the PRs are opened

```yaml
github:
  apiUrl: https://api.github.com
  token: ${GITHUB_TOKEN}
  draftPr: true
  reviewers: [teammate]
  labels: [self-healing]
  assetsBranch: self-healing-assets
```

| Field | What to put |
|---|---|
| `apiUrl` | Leave as is for github.com. For GitHub Enterprise, `https://<server>/api/v3`. |
| `token` | GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate. *Resource owner*: the account or organization that owns the repositories. *Repository access*: only the repositories to be fixed. *Permissions*: Read and write for **Contents**, **Pull requests** and **Issues**. |
| `draftPr` | `true`: PRs are opened as drafts. You mark them "Ready for review" after reviewing. |
| `reviewers` | GitHub usernames assigned to the PR automatically; can be left empty. |
| `labels` | Labels added to the PR. |
| `assetsBranch` | A separate branch for videos and screenshots. It is created on the first PR and never appears in any PR's diff. |
| `commitAuthorName`, `commitAuthorEmail` | Optional: the author of the fix commits. Defaults to "Self-Healing Bot". |

### 3. `repos` — the apps to fix

Write one entry per repository. If a repository has both `android` and `ios` (Kotlin Multiplatform, for example), a crash from either platform is reproduced and verified on **both**. If Android and iOS live in separate repositories, write two entries.

```yaml
repos:
  - id: shop
    url: https://github.com/my-org/shop.git
    baseBranch: main
```

- `id`: a free-form name used in `sentry.projects[].repo`.
- `url`: the HTTPS clone address. The repository is cloned once under `pipeline.cloneDir`.
- `baseBranch`: the branch the fix branch starts from and the PR targets.

**Android:**

```yaml
    android:
      buildCommand: ./gradlew :app:installDebug
      applicationId: com.example.shop
      avd: Pixel_8
      dnsServers: [8.8.8.8, 1.1.1.1]
```

| Field | What to put |
|---|---|
| `buildCommand` | A command run from the repository root that builds the debug app and **installs it on the device**. In a multi-module project, name the module and variant, e.g. `./gradlew :app:installProdDebug`. |
| `applicationId` | The package name of the installed app. If the debug variant adds a suffix (such as `.debug`), include it. |
| `avd` | The emulator's name in Android Studio → Device Manager, also listed by `emulator -list-avds`. It is started on Start without a window and shown in the app. |
| `device` | The serial from `adb devices` if a real device should be used instead of an emulator. When empty, `avd` is used. |
| `launchActivity` | Leave empty; the app opens with its default launcher screen. |
| `cleanInstall` | `true`: the app is uninstalled before installing, so a store or release-signed install can't block the debug install. |
| `dnsServers` | The emulator's DNS servers. When empty, the emulator uses the host's DNS from boot time. That breaks when the network or VPN changes, and network calls such as sign-in hang. |
| `reproCommand` | Optional: your own flow command, used when the harness writes no Maestro flow. |

**iOS (macOS only):**

```yaml
    ios:
      buildCommand: >-
        xcodebuild -workspace Shop.xcworkspace -scheme Shop -configuration Debug
        -sdk iphonesimulator -derivedDataPath build/self-healing CODE_SIGNING_ALLOWED=NO build
      appPath: build/self-healing/Build/Products/Debug-iphonesimulator/Shop.app
      bundleId: com.example.shop
      simulator: iPhone 16
```

| Field | What to put |
|---|---|
| `buildCommand` | A command that builds for the simulator. `-project` or `-workspace` and `-scheme` depend on your project. `CODE_SIGNING_ALLOWED=NO` builds unsigned, so no certificate is needed. If you use CocoaPods, prefix it with `pod install && `. |
| `appPath` | The path of the built `.app`, relative to the repository root. It starts with the same folder as `-derivedDataPath`. |
| `bundleId` | The app's bundle identifier. |
| `simulator` | A simulator name or UDID from `xcrun simctl list devices`. |
| `openSimulatorApp` | `true`: also opens the Simulator.app window, for tapping by hand when AXe isn't installed. |

**Reproducing on the release:**

```yaml
    release:
      enabled: true
      tag: v{version}
      androidAsset: 'MyApp-.*\.apk'
      requireReproduction: false
```

- `enabled`: the crash is first reproduced on the version it happened in.
- `tag`: how the Sentry release (`com.example.shop@1.2.0+120`) maps to a git tag. `{version}` = `1.2.0`, `{build}` = `120`. If your tags look like `v1.2.0`, write `v{version}`.
- `androidAsset`: a pattern matching the APK file in GitHub Releases. Without a matching APK, the tag is built from source with `buildCommand`. iOS is always built from source.
- `requireReproduction`: when `true`, no PR is opened if the crash can't be reproduced on any platform.

**Test account** (when the path to the crash requires signing in):

```yaml
    testAccount:
      username: "qa@example.com"
      password: "${TEST_ACCOUNT_PASSWORD}"
      loginHint: "Continue with email → Email / Password → Sign in"
```

`loginHint` is a short hint telling the harness how to reach the sign-in screen. The password is not sent to the harness; see [Test account](#test-account) for details.

### 4. `harness` — the agent that writes the fix

```yaml
harness:
  type: claude-code
  model: claude-opus-5-5
  timeoutMinutes: 30
```

| Field | What to put |
|---|---|
| `type` | `claude-code`, `codex` or `custom`. The chosen CLI must be installed and signed in. |
| `model` | The model name passed to the CLI. |
| `env` | Optional: environment variables for the CLI, e.g. `ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}`. When empty, the CLI's own login is used. |
| `extraArgs` | Extra arguments for the CLI. |
| `command` | Only for `custom`: the command template. Available placeholders: `{model}`, `{promptFile}`, `{repoDir}`, `{resultFile}`. |
| `timeoutMinutes` | The longest time a fix may take. |

### 5. `pipeline` and `server`

```yaml
pipeline:
  workDir: ./workspace
  cloneDir: ./projects
  maxConcurrent: 1
  branchPrefix: self-healing/
  verifyWaitSeconds: 15
  buildTimeoutMinutes: 30
```

| Field | What to put |
|---|---|
| `workDir` | Working copies, logs and recordings. One run of a large project can take several GB; choose a disk with enough free space. Each run is cleaned up when it ends. |
| `cloneDir` | The folder repositories are cloned into (`projects/<repo>`). |
| `maxConcurrent` | How many crashes are processed at once. `1` is recommended, since the devices are shared. |
| `branchPrefix` | The prefix of fix branches. A branch is named `<prefix><crash-id>-<title>`. |
| `verifyWaitSeconds` | Without a flow, how long the app must stay open without crashing. |
| `buildTimeoutMinutes` | The longest time a build may take. |
| `maestroCommand` | The full path to Maestro if it isn't on the PATH. |

The `server` section is only needed for the webhook: `port` is the port to listen on, and `publicUrl` is the address Sentry reaches. With polling, it can be left as is.

### Smallest Android-only example

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

Fields left out use their defaults.

## Running

### Desktop

```bash
./gradlew :desktop:run --args="$PWD/config.yml"
```

On macOS you can also start it by double-clicking [SelfHealing.command](SelfHealing.command) in Finder. It opens Terminal and runs the command above in the project folder.

To build an installer (macOS `.dmg`, Windows `.msi`, Linux `.deb`):

```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

### Server

```bash
./gradlew :server:installDist
```

```bash
server/build/install/server/bin/server --config /path/to/config.yml
```

For Android, a Linux machine with KVM is enough on the server; the emulator runs without a window (`-no-window`). iOS verification requires the server to run macOS.

## Security

- The harness only runs inside an isolated worktree. Commits, pushes and PRs are made by the app, not by the harness.
- Tokens are masked as `***` in logs. Git authenticates through an HTTP header; the token is never written into the remote URL.
- Videos and screenshots never enter the PR's diff; they live on the separate `self-healing-assets` branch.
- PRs are opened as **drafts** by default. There is no automatic merge.
- Builds and installs go only to the device in the config (`ANDROID_SERIAL`); phones connected to the computer are left alone.
