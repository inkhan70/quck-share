# QuickShare

Fast, server-less, device-to-device file transfer over the local Wi-Fi network.

No cloud. No Firebase. No account. No internet connection required. Two devices on the same Wi-Fi
network find each other and transfer files directly, socket to socket.

**Created by Cluster_Lab.**

---

## Quick start (3 steps)

1. **Connect both devices to the same Wi-Fi** (or turn on one phone's hotspot and join it from the
   other), and open QuickShare on both. On the receiving device keep **Receive files** switched on.
2. **Send:** on the *Nearby* tab tap the other device, tap **Choose files**, then **Send**. A pairing
   code appears on your screen.
3. **Receive:** the other device shows the same code. Check it matches, tap **Accept**, review the file
   list and accept again. Files land in the *QuickShare* folder inside the folder you picked.

Device not showing up? Use **Show my QR code** on one phone and **Scan QR code** on the other, or
**Connect by IP address**. The same guide is built into the app (*Nearby* tab → **How to use
QuickShare**, or *Settings* → **How to use QuickShare**), and the full manual is in
[`USER-MANUAL.md`](USER-MANUAL.md).

---

## What it does

* **Automatic discovery** of nearby devices using UDP broadcast on the local subnet, with a manual
  IP-address fallback for networks that block broadcast (guest Wi-Fi, some enterprise APs, and
  Android hotspots are the usual offenders).
* **One-tap pairing** with a shared confirmation code, so a transfer can only start when both people
  agree. The code is compared in constant time.
* **Any file type** — images, videos, documents, APKs, ZIPs, and anything else the picker can reach —
  multiple files at once, with per-file and overall progress.
* **Streaming I/O throughout.** No file is ever read into memory. Buffers are 64 KB and a 4 GB video
  costs the same RAM as a 4 KB text file.
* **Live progress**: percentage, transferred/total, throughput and estimated time remaining, with
  cancel and retry.
* **Checksum verification** of every completed file, so "sent" and "arrived intact" are different
  claims and the app can tell you which one happened.
* **Transfer history**, persisted across launches, with the verification outcome per transfer.
* **QR pairing** (optional) — show a code, or scan one with the system camera via a `quickshare://`
  deep link.
* Survives **Wi-Fi changes, disconnects and backgrounding** — transfers run in a foreground service.

---

## Requirements

| | |
|---|---|
| Android | 7.0 (API 24) and above |
| Target | API 34 |
| Language | Kotlin 1.9.24 / Java 17 |
| Build | Gradle 8.7, Android Gradle Plugin 8.4.0 (JDK 17 or newer) |

No location permission is required on any Android version, because discovery uses UDP broadcast and
plain interface enumeration rather than the platform's Wi-Fi scan APIs.

---

## Project layout

```
QuickShare/
├── core/                        Pure-Kotlin module: no Android dependencies at all
│   └── src/main/kotlin/com/quickshare/core/
│       ├── data/                Settings + transfer history models and repositories
│       ├── net/                 Discovery (UDP broadcast), peers, network utilities
│       ├── pairing/             Pairing codes and the QR payload format
│       ├── protocol/            Wire format: framing, message types, codec
│       ├── transfer/            The transfer engine: server, client, coordinator
│       └── util/                Checksums, byte formatting, MIME mapping, safe paths
├── app/                         Android module: UI, platform glue, services
│   └── src/main/java/com/quickshare/app/
│       ├── core/                ServiceLocator (composition root)
│       ├── data/                SAF sinks, space provider, preferences stores
│       ├── service/             Receive/Send foreground services
│       └── ui/                  Compose UI
│           ├── theme/           Material 3 colour scheme
│           └── screens/         Nearby, Send, History, Settings
└── .github/workflows/build.yml  CI: tests, signed release APK
```

The split matters: `core` holds every decision that can be made without a device (protocol, framing,
checksum, path safety, transfer state machine), which is why it carries the bulk of the test suite and
runs on a plain JVM.

---

## Building

```bash
# 1. Point the build at your Android SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2. Debug build (installs alongside a release build; the debug id is .debug)
./gradlew :app:assembleDebug

# 3. Run the tests
./gradlew :core:test :app:testDebugUnitTest

# 4. Release build
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

### Signing

Release signing reads `keystore.properties` from the project root. It is **not** committed — create it
from your own keystore:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=********
keyAlias=your-alias
keyPassword=********
```

If the file is absent, the release build falls back to the debug key so that `assembleRelease` still
produces something installable — but an APK signed with the debug key cannot be installed over a
release-signed one, so set this up before distributing.

### Building in a low-memory container

The checked-in `gradle.properties` is tuned to build in a **2 GB** container, which is the practical
floor for this project:

* the Kotlin compiler runs **in-process** (`kotlin.compiler.execution.strategy=in-process`), because a
  separate Kotlin daemon is a second JVM that does not fit alongside the Gradle daemon;
* `org.gradle.workers.max=1`, so dexing and AAPT2 do not peak at the same time;
* **R8 minification is disabled** (see the comment in `app/build.gradle.kts`). It is the single largest
  allocation in the build and is killed on a 2 GB container, which yields no APK at all. Re-enable
  `isMinifyEnabled`/`isShrinkResources` if you build on a normal machine — it will cut the download
  size roughly by a third.

If you see `Gradle build daemon disappeared unexpectedly`, the container ran out of memory; check
`/sys/fs/cgroup/memory.events` for `oom_kill`.

---

## How a transfer works

```
Sender                                    Receiver
  │  Hello (device name, protocol version)   │
  ├─────────────────────────────────────────►│
  │  HelloAck (device id, name)              │
  │◄─────────────────────────────────────────┤
  │  PairRequest (code)                      │
  ├─────────────────────────────────────────►│  user confirms the code
  │  PairAccept / PairReject                 │
  │◄─────────────────────────────────────────┤
  │  Offer (file list + total size)          │
  ├─────────────────────────────────────────►│  user accepts, folder chosen
  │  OfferAccept                              │
  │◄─────────────────────────────────────────┤
  │  FileStart (name, size, mime, checksum)  │
  ├─────────────────────────────────────────►│
  │  FileChunk × N  (64 KB, streamed)        │
  ├─────────────────────────────────────────►│
  │  FileEnd (checksum)                       │
  │◄──────── FileAck (verified true/false) ───┤
  └─ repeat per file, then TransferComplete
```

Every frame is length-prefixed and validated before use. File names arriving from the network are
sanitised and re-uniquified against the destination folder, so a peer cannot write outside the folder
the user chose (see `core/util/SafePaths.kt` and `core/transfer/`).

---

## Running the tests

```bash
./gradlew :core:test              # 204 tests — protocol, engine, checksums, path safety
./gradlew :app:testDebugUnitTest  #  47 tests — UI decision logic, prompt controller
```

`core` tests include a real loopback end-to-end transfer: two sockets on `127.0.0.1`, real framing,
real checksums, and a deliberately corrupted payload to assert the verification failure path.

---

## What has NOT been tested

Honest scope statement, so nobody assumes more coverage than exists:

* **A real transfer between two physical phones over Wi-Fi.** No two devices were available. The
  substitute is the loopback end-to-end test in `core`, which exercises the same engine, framing and
  checksum code but not the Wi-Fi driver, multicast behaviour, or a real packet lossy link.
* **Broadcast discovery between two real devices.** Substituted with unit tests over the beacon
  encode/decode and the peer registry's TTL eviction.
* **SAF interaction with a real storage provider** (the tree picker, `DocumentFile` writes, free-space
  reporting). Substituted with tests over the naming/uniqueness and space-arithmetic logic; the
  `ContentResolver` calls themselves need a device.
* **Notification behaviour under Doze / battery optimisation.** The foreground-service code paths are
  exercised only by compilation.
* **QR scanning with a real camera.** The payload encode/decode is unit-tested; the capture activity
  needs hardware.

---

## CI

`.github/workflows/build.yml` runs the full test suite, builds the signed release APK, renames it to
`QuickShare-Release.apk`, verifies the signature with `apksigner`, prints size and SHA-256, and uploads
the APK plus the test reports. On a `v*` tag it also attaches the APK to a GitHub release.

Signing in CI needs four repository secrets — `QUICKSHARE_KEYSTORE_BASE64`,
`QUICKSHARE_STORE_PASSWORD`, `QUICKSHARE_KEY_ALIAS`, `QUICKSHARE_KEY_PASSWORD`. The workflow **fails
loudly** when they are missing rather than quietly producing a debug-signed APK.
# quck-share
