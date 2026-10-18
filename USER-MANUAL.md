# QuickShare — User Manual

**Fast device-to-device file transfer over Wi-Fi.**
Version 1.0.0 · Android 7.0 and newer

---

## 1. What QuickShare is

QuickShare moves files **directly between two devices on the same Wi-Fi network**. Nothing is uploaded to a server, nothing is stored in the cloud, and no account is needed. One device sends, the other receives, and the bytes travel over your local network only.

It is built for the case where you want to hand a file to the phone next to you — a video too big for a messaging app, a folder of photos, an APK you just built, a ZIP of documents — without going through the internet, a cable, or a cloud account.

**What it does well**

- Sends any file type: images, videos, documents, APKs, ZIPs, and anything else you can pick in the system file chooser.
- Sends several files at once, and very large files, without running out of memory.
- Shows live progress, transfer speed and the estimated time remaining, and lets you cancel or retry.
- Verifies every completed transfer with a SHA-256 checksum, so you know the file arrived intact.
- Remembers what you transferred, and whether each one verified.

**What it deliberately does not do**

- It has no cloud, no account, no sign-in, no telemetry, and needs no internet connection.
- It does not request your location, your contacts, or full storage access.
- It does not run over the internet — the two devices must be on the same local network.

---

## 2. What you need

| Requirement | Detail |
| --- | --- |
| **Android version** | 7.0 (Nougat) or newer |
| **Both devices** | On the **same Wi-Fi network**, with Wi-Fi switched on |
| **QuickShare** | Installed and open on **both** devices during a transfer |
| **Free storage** | Roughly the size of the files you are receiving — plus a little headroom |
| **Screen** | Best kept awake during a transfer (QuickShare can do this for you) |

> **Tip:** If you have no Wi-Fi router available, turn on one phone's **Wi-Fi hotspot** and join it from the other phone. QuickShare works over a hotspot exactly as it does over a normal network — this is the fastest way to transfer between two phones with no infrastructure.

---

## 3. Installing QuickShare

QuickShare is distributed as an APK file (`QuickShare-Release.apk`), not through an app store.

### Install on the phone

1. Copy `QuickShare-Release.apk` to the phone (download it, or send it over a cable).
2. Open the file from your **Files** app or your browser's **Downloads**.
3. Android will say something like *"For your security, your phone is not allowed to install unknown apps from this source."* Tap **Settings**, and turn on **Allow from this source** for the app you are opening the file with (your browser, your file manager, or your messaging app — the setting is per-app).
4. Go back and tap **Install**.
5. If Google Play Protect shows a warning such as *"Unsafe app blocked"*, tap **More details** → **Install anyway**. This warning appears for any app that was not distributed through the Play Store; it is not a virus scan result.

> **Why the warnings?** Android shows the "unknown sources" prompt and Play Protect notice for every sideloaded app. QuickShare has no Play Store listing, so both appear once. After the first install they do not come back for updates.

### Install from a computer (optional)

With USB debugging enabled and the phone connected:

```bash
adb install -r QuickShare-Release.apk
```

`-r` reinstalls over an existing copy while keeping your settings and history.

### Updating

Install a newer APK over the old one — your settings and transfer history are kept. If Android refuses with *"App not installed"*, an older copy signed with a different key is present; uninstall it first (this clears settings and history).

---

## 4. First run

When you open QuickShare for the first time it:

1. Creates a **device ID** for this phone (a stable random identifier, shown in **Settings → This device**).
2. Gives itself a **device name** so the other side can recognise it. Change it in **Settings → Device name**.
3. Turns the **Receiver** on, so this phone is immediately visible to nearby devices.
4. Starts **looking for devices** on your Wi-Fi network.

You land on the **Nearby** tab. There is nothing to sign into and nothing to configure before your first transfer.

### The three tabs

| Tab | What it is for |
| --- | --- |
| **Nearby** | The home screen: your receiver switch, pairing helpers, active transfers, and the list of discovered devices. This is where every transfer starts. |
| **History** | Everything you have sent and received, with its checksum verification result. |
| **Settings** | Device name, save folder, pairing and notification preferences, theme. |

---

## 5. Permissions QuickShare asks for, and why

QuickShare asks for as little as possible. This is the complete list.

### On first launch (Android 13 and newer)

**Notifications**
- *Why:* to show transfer progress and the "incoming files" prompt outside the app, and to keep the transfer running while the app is in the background (Android requires a visible notification for that).
- *If you deny it:* the app works fully, but transfer progress is only visible **inside** the app and you will not see a notification when someone wants to send you files. QuickShare tells you this in plain language: *"Notifications are off, so transfer progress will only be visible inside the app."* You can grant it later in **Android Settings → Apps → QuickShare → Notifications**.

### While the app runs

**Nearby Wi-Fi / network access**
- *Why:* to broadcast and listen for discovery beacons on your local network, and to open the socket that carries the files.
- *If it is unavailable:* the device list stays empty and QuickShare shows *"Allow network access to find nearby devices."* You can still connect using **Connect by IP address** (§11).
- *Note:* QuickShare does **not** use the platform's Wi-Fi P2P APIs and therefore needs **no location permission and no Wi-Fi scan permission** on any Android version — a deliberate privacy choice.

### The first time you receive a file

**Save folder (Storage Access Framework)**
- *Why:* QuickShare needs somewhere to write the files it receives.
- *What you grant:* exactly **one folder**, chosen by you in Android's own folder picker. QuickShare can only ever write inside that folder — it never receives access to your whole storage.
- *If you skip it:* when a transfer arrives and no folder is set, QuickShare asks you to choose one (*"Choose a folder to save received files into."*). Nothing is written until you pick one.

### Camera (only when you use QR scanning)

Tapping **Scan QR code** opens a camera view. Android asks for camera access at that moment. It is only used to read the pairing QR code; nothing is recorded or stored. If you never scan a QR code, you are never asked.

> **What QuickShare never asks for:** location, contacts, phone state, SMS, microphone, or broad storage access.

---

## 6. The primary flow, step by step

```
Open  →  Discover nearby device  →  Tap  →  Accept  →  Select files  →  Transfer
```

### Step 1 — Open QuickShare on both devices

Both phones must have QuickShare open (or running in the background from a recent transfer) and both must be on the same Wi-Fi network. The **Receive files** switch at the top of the **Nearby** tab must be **on** on the receiving device — it reads **"Visible to nearby devices"**.

### Step 2 — Wait for discovery

Under the heading **Nearby devices**, the other device appears within a second or two as a card showing its name and its address, such as `192.168.1.42:8988`. While the list is empty you will see **"No devices found yet"** with the hint *"Make sure QuickShare is open on the other device and both are on the same Wi-Fi network."* and a progress bar reading **"Looking for devices on your Wi-Fi…"**. That bar is a listening indicator, not a loading spinner — discovery keeps running.

### Step 3 — Tap the device

Tap the other device's card to open the **Send** screen for it.

> If a device shows **"Needs a newer version of QuickShare"** it is on the network but running a version whose protocol this build cannot talk to. Update it and it becomes tappable. (Cards for incompatible devices are greyed out and cannot be tapped.)

### Step 4 — Confirm pairing

A **pairing code** is shown on the sending device in the **Pairing code** card. On the receiving device a dialog appears:

> **Confirm pairing**
> *"<Device name> wants to connect. Check that the code on the other device is:"*
> **Pair and continue**   **Decline**

Compare the code with the one shown on the sender and tap **Pair and continue**. If the codes do not match, tap **Decline** and do not proceed — the message on screen reads *"The code did not match. Ask the other device to try again."*

If nobody answers within the time limit the request expires and shows *"The pairing request timed out."*

> You can switch pairing confirmation off in **Settings → Require pairing code**, but leaving it on is what stops an unknown device on your network from pushing files at you.

### Step 5 — Select files

On the **Send** screen, tap **Choose files**. Android's own document picker opens — the same one every app uses, with your recent files, Downloads, Google Drive, and everything else you have configured. Pick as many files as you like; you can tap **Add more** afterwards to add more.

Each selected file is listed with its icon, name and size, above a total such as **"3 file(s) · 412.6 MB"**. Remove one with the ✕ on its row, or clear everything with the **Clear all** button.

### Step 6 — Send

Tap **Send**. On the receiving device an **Incoming files** dialog appears:

> *"<Device name> wants to send you 3 file(s) (412.6 MB)."*
> **Accept**   **Decline**

Tap **Accept** and the transfer begins. Both devices now show live progress.

That is the whole flow. If anything goes wrong at any step, the Troubleshooting section (§14) has the specific symptom.

---

## 7. Sending files

### Single or multiple files

There is no difference in the steps — pick one file or twenty. Use **Add more** to add further batches; duplicates are ignored, so re-picking a file you already selected does not send it twice.

### Any file type

The picker accepts **all file types**. There is no filtering, so photos, videos, music, PDFs, Office documents, APKs, ZIPs, ROMs, project archives and anything else all transfer identically. QuickShare does not care what is inside a file.

### Large files

QuickShare streams files in small chunks and never loads a whole file into memory. A 4 GB video transfers with the same memory footprint as a 4 KB text file — the app will not run out of memory or be killed by Android for using too much. You are limited only by storage space on the receiving side and by time.

### While a transfer runs

- Progress is shown on the **Nearby** tab (in the *This device* section) and on the **Send** screen.
- You can **leave the app** — the transfer continues in the background, and a notification keeps you updated. The screen says so: *"You can leave QuickShare — the transfer keeps running."*
- If you set **Settings → Keep screen awake while transferring** (on by default), the screen stays on so you can watch progress.

---

## 8. Receiving files

### Being discoverable

The **Receive files** switch on the **Nearby** tab must be on — the card reads **"Visible to nearby devices"**. Turn it off and it reads **"Not accepting files"**, and other devices will not see you. This switch is the on/off control for incoming transfers.

### Accepting

When a paired device offers files, the **Incoming files** dialog shows who is sending, how many files, and the total size. Tap **Accept**. If you do not respond within the time limit the offer expires (*"The incoming transfer timed out."*) and the sender is told the transfer was declined.

### Where the files are saved

Received files are written **only inside the folder you chose** in **Settings → Save received files to**. QuickShare cannot write anywhere else — that is enforced by Android's Storage Access Framework, not by convention.

- If you have not chosen a folder yet, QuickShare asks you to pick one when the first transfer arrives, with the message *"Choose a folder to save received files into."*
- The current folder's name is shown under **Save received files to**; if none is set it reads **"Not chosen yet"**.
- To change it, tap the row and pick a different folder. The old folder keeps whatever was already written to it.

Received files are written into the chosen folder; subfolders are not created.

### Images in the gallery

With **Settings → Add received images to the gallery** on (the default), received photos and videos that **passed checksum verification** are also registered in your system gallery. A file whose checksum did not match is deliberately **not** published to the gallery — you would otherwise end up with a corrupt image in your photo roll. Turn this off if you would rather only ever see received images in the folder itself.

---

## 9. Reading the progress screen

Each active transfer shows a card with:

| Element | Meaning |
| --- | --- |
| **Sending** / **Receiving** | The direction of this transfer |
| **File name** | Which file is moving right now |
| **Progress bar** | How far along. It is indeterminate (a moving bar with no fixed fill) during the short negotiation before any bytes move |
| **Percentage and bytes** | For example `47%  ·  188.4 MB / 412.6 MB` |
| **Speed and time remaining** | For example `12.4 MB/s  ·  18 s left` |
| **File counter** | For a multi-file transfer, e.g. *File 2 of 3* |

### Cancel

The **✕** button on the progress card (or **Cancel** on the full transfer view) stops the transfer immediately. Both devices are told, and the record appears in History as *"Transfer cancelled"*.

A cancelled transfer is **not** offered a Retry button — you cancelled it deliberately, so QuickShare does not second-guess you. Start it again from the Send screen if you change your mind.

### Retry

**Retry** appears for transfers that failed for a reason worth another attempt — a dropped connection, an interrupted transfer, or a failed checksum verification. You will find it on the failed entry in **History** (the ↻ button).

Retrying starts a fresh attempt with a new transfer identity, so a retry can never be confused with a replay of the original.

### When it finishes

- **"Transfer complete"** — all files arrived.
- **"Verified with SHA-256 checksum"** — and they arrived intact. This is the message you want.
- **"Some files failed checksum verification"** — the bytes that arrived do not match the original. Do not trust those files; delete them and transfer again, ideally after checking your Wi-Fi signal.

---

## 10. Transfer history and checksum verification

The **History** tab lists every send and receive.

At the top, a summary strip shows three figures: **files sent**, **files received**, and the **total size successfully transferred**.

Each row shows:

- **"Sent to &lt;device&gt;"** or **"Received from &lt;device&gt;"**
- The file count and total size
- A status pill:
  - **"Checksum verified"** — completed and the SHA-256 of the received data matches the original exactly.
  - **"Transfer complete"** — completed, but without checksum verification (for example an older peer that did not offer one).
  - **"Not verified"** — no checksum comparison was made.
  - **"Transfer failed"** / **"Transfer cancelled"** / **"Some files failed checksum verification"** — as described above.
- If the transfer failed, the technical reason is printed in red underneath.
- A **↻ Retry** button when retrying makes sense, and an **✕** to remove just that row.

### Clearing history

Tap the **Clear history** icon in the top bar. A dialog asks *"Delete all history records?"* and explains *"This removes the list only. Files already transferred are not affected."* — clearing history never deletes any file.

### What a checksum actually proves

Every completed transfer is checked with **SHA-256**. The sender computes the digest of the file as it streams; the receiver computes the digest of what it actually wrote to disk; the two are compared. If a single bit was flipped in transit — a dropped packet, a flaky router, a Wi-Fi hiccup — the digests differ and the transfer is marked as failed verification.

This is why **"Checksum verified"** is a real guarantee rather than a hopeful message: the receiving device re-read the bytes it stored and compared them against the original.

---

## 11. Manual IP connection (when discovery fails)

Sometimes broadcasts do not get through — many routers and guest networks have **client isolation** or block multicast, and some Wi-Fi extenders do not relay discovery traffic. The two devices can still talk directly if you tell QuickShare where to find the other one.

1. On the **receiving** device, find its address. Open **Settings → This device**; the **Device ID** line identifies the phone. Alternatively, when discovery *is* working the address is printed on the device card as `192.168.1.42:8988`.
2. On the **sending** device, tap **Connect by IP address** on the **Nearby** tab.
3. Type the receiving device's IPv4 address into the field (its label shows the example `192.168.1.42`).
4. Tap **Connect**.

Accepted formats:

| You type | Meaning |
| --- | --- |
| `192.168.1.42` | Address, default port used |
| `192.168.1.42:8988` | Address with an explicit port |
| `http://192.168.1.42` | Scheme is tolerated and discarded |

On success you will see **"Added 192.168.1.42. Tap it in the device list to send files."** — the device now appears in the **Nearby devices** list exactly as if it had been discovered, and you continue with the normal flow.

If the address is not a usable one, the field is marked as an error and shows **"Enter a valid IPv4 address, for example 192.168.1.42"**. Addresses like `0.0.0.0` and `255.255.255.255` are rejected here specifically — they are valid *literals* but not addresses you can dial.

> **Finding the IP address of the other phone:** open its Wi-Fi details in Android Settings — the address is listed there as "IP address". QuickShare's **Settings → This device** shows the Device ID; the address shown on a discovery card is the authoritative one when discovery works.

---

## 12. QR pairing (optional)

Typing an IP address is fine, but a QR code is faster and less error-prone.

**On the device that will receive:**
1. Tap **Show my QR code** on the **Nearby** tab.
2. A QR code appears encoding this device's address, control port, device name and a fresh one-time pairing code.

**On the device that will send:**
1. Tap **Scan QR code**.
2. Point the camera at the code. Grant camera access if asked.
3. The device is added automatically, pre-filled, and you continue with the normal send flow.

The QR code contains a **one-time pairing code**, so a code that has been photographed and reused later does not grant access. If you scan the code with the **system camera app** instead, the link opens QuickShare directly with the peer pre-filled.

QR pairing is entirely optional — discovery and manual IP both work without it.

---

## 13. Settings reference

| Setting | Default | What it does |
| --- | --- | --- |
| **Device name** | Auto-generated | The name other devices see in their list. Type a new one and tap to save. |
| **Device ID** | Generated once | This device's stable identifier. Shown for troubleshooting. |
| **Save received files to** | *Not chosen yet* | The one folder QuickShare may write into. Opens Android's folder picker. |
| **Require pairing code** | **On** | Ask for the code before accepting a connection. Leave on unless you have a reason not to. |
| **Auto-accept from known devices** | Off | Skip the prompt for devices you have successfully received from before. |
| **Add received images to the gallery** | **On** | Register verified received photos and videos with the system gallery. |
| **Keep screen awake while transferring** | **On** | Stop the screen sleeping mid-transfer. |
| **Show transfer notifications** | **On** | Progress and completion notifications. Requires notification permission on Android 13+. |
| **Theme** | **Follow system** | **Follow system**, **Light**, or **Dark**. |
| **About QuickShare** | — | What the app does, how transfers work, and which permissions it uses. |
| **Reset settings** | — | Restores every setting above to its default. **Your transfer history and your files are not affected.** |

---

## 14. Troubleshooting

### The other device never appears in the list

1. Check the **Receive files** switch on the *other* device — it must read **"Visible to nearby devices"**.
2. Check both devices are on the **same** Wi-Fi network. Two phones on "Home" and "Home-5G" are usually on the same network; a phone on mobile data is not.
3. Make sure QuickShare is actually **open** on the other device, not just installed.
4. Tap **Refresh** (the ↻ icon, top right of the Nearby tab).
5. Check for a **"Not connected to Wi-Fi"** card. If you see it, connect both devices to Wi-Fi — or turn on one device's hotspot and join it from the other.
6. If the network blocks broadcast (common on guest networks, hotels, and some routers with "AP isolation"), use **Connect by IP address** (§11) or **QR pairing** (§12).

### "Could not reach 192.168.1.42. Check that both devices are on the same Wi-Fi network."

The address was accepted but nothing answered. Usually:

- The address is right but the other device is no longer at it (phones get new addresses from DHCP when they rejoin). Re-check it on the other device and try again.
- The other device's **Receive files** switch is off, so nothing is listening.
- A firewall or "AP isolation" setting on the router is blocking the connection between clients.

### The transfer fails partway through

- **Wi-Fi changed or dropped.** If you switched networks, lost Wi-Fi, or walked out of range, the connection is torn down and the transfer is recorded as **interrupted**. Reconnect both devices and use **Retry** (↻) on the History entry, or send again.
- **The app was force-stopped or the receiving phone rebooted.** Same outcome — retry.
- **The other device locked and killed the app.** Both devices must keep QuickShare alive during a transfer. Enable **Settings → Keep screen awake while transferring**, and avoid swiping QuickShare out of the recent-apps list while a transfer runs.

### Some files "failed checksum verification"

The bytes that arrived do not match what was sent — a genuine corruption in transit, not a display quirk. Delete those files and transfer them again. If it keeps happening, the Wi-Fi link is unreliable: move closer to the router, or use a hotspot directly between the two phones.

### Two files with the same name

QuickShare never overwrites an existing file. If you receive `holiday.jpg` and one is already in your save folder, the new one is written under a distinct name. Nothing is ever silently replaced.

### "Not enough free storage for this transfer."

The receiving device does not have room for the incoming files. Free up space, or receive to a different folder (one on a device with more room), then retry. QuickShare checks the available space before accepting a transfer, so this usually appears early rather than after a long wait.

### "Choose a folder to save received files into."

No save folder has been chosen yet. Tap the prompt and pick a folder. This only happens once — QuickShare remembers your choice.

### Install blocked / "App not installed"

- **Unknown sources:** enable **Allow from this source** for the app you are installing from (§3).
- **Play Protect:** tap **More details → Install anyway**.
- **"App not installed" over an existing copy:** the installed copy was signed with a different key. Uninstall the old one first (this clears settings and history).

### Transfers keep stopping when I leave the app

Check **Settings → Show transfer notifications** is on, and grant notification permission when Android asks. On some phones you must also exempt QuickShare from battery optimisation: **Android Settings → Apps → QuickShare → Battery → Unrestricted**. Aggressive vendors (Xiaomi, Huawei, Oppo, Vivo, Samsung) restrict background apps more than stock Android.

### "Notifications are off, so transfer progress will only be visible inside the app."

You declined the notification permission on Android 13+. Transfers still work — you just lose the progress notification and the prompt that tells you someone wants to send you files while you are in another app. To fix it: **Android Settings → Apps → QuickShare → Notifications → Allow**.

### "Allow network access to find nearby devices."

Android is blocking network access for the app. Check **Android Settings → Apps → QuickShare** for anything disabled, or that a system-wide data restriction or a VPN is not blocking local traffic. Manual IP connection may still work.

### The QR code will not scan

- Grant camera access when prompted.
- Hold the phone steady and fill the frame with the code; brighten the screen showing the code.
- The code expires — if it fails, close and reopen **Show my QR code** to generate a fresh one.

### The app shows a device as "Needs a newer version of QuickShare"

The other device is running an incompatible version. Update it to 1.0.0 or newer. This is a safety check: two mismatched protocol versions would otherwise fail confusingly partway through a transfer.

---

## 15. Privacy and security

**Local network only.** Files travel directly between your two devices over your Wi-Fi. Nothing is uploaded, and no server is involved at any point. QuickShare works with the internet completely unavailable.

**No account, no cloud, no tracking.** There is no sign-in, no cloud storage, no analytics, and no advertising identifier. The app makes no network connections beyond your local subnet.

**Minimal permissions.** QuickShare requests only network access and notifications, plus one folder you choose through Android's own picker. It does not ask for location, contacts, or broad storage access — unusually little for an app of this kind, because it does not use the platform's Wi-Fi P2P APIs.

**Unauthorized connections are refused.** A device must be **paired** — with a one-time code you compare and confirm on screen — before it can send you anything. An unknown device on your network cannot push files at you, and cannot even complete a connection without your acceptance. A pairing prompt nobody answers **expires to a refusal** rather than waiting indefinitely, and a prompt that a later device has superseded is failed closed rather than left dangling.

**Received filenames are sanitised.** Every incoming filename is checked before it is written. Names containing path separators or `..` are neutralised, so a malicious sender cannot use a crafted filename to write outside your chosen folder. The receiving device can only ever write inside the folder you granted.

**Nothing is overwritten.** An incoming file that would collide with an existing name is written under a new one. A transfer can never destroy data you already have.

**Every transfer is verified.** SHA-256 digests are compared on completion, so corruption is detected rather than silently accepted. A file that fails verification is not published to your gallery.

**Cancellation and consent are explicit.** You see who is connecting, you see the code, and you decide. A user-initiated cancel is honoured as final — QuickShare does not offer to retry something you stopped.

---

## 16. FAQ

**Does QuickShare need the internet?**
No. Both devices need to be on the same local Wi-Fi network, but that network needs no internet connection at all.

**Can I use it with no router?**
Yes. Turn on one phone's Wi-Fi hotspot and connect the other phone to it. This works well and is often faster than going through a router.

**Does it work over Bluetooth or a cable?**
No. It is Wi-Fi only, by design — that is what makes it fast for large files.

**Can I transfer between Android and an iPhone or a computer?**
This build is Android-to-Android. The protocol is plain TCP with a documented framing format, so other clients could be written, but this app is an Android client.

**How large a file can I send?**
There is no size limit in the app. The only real constraints are the receiving device's free space and the time the transfer takes. Files are streamed in chunks and never held in memory.

**Where do received files go?**
Into the single folder you chose in **Settings → Save received files to**. QuickShare cannot write anywhere else.

**Does it drain the battery?**
A large transfer uses the Wi-Fi radio continuously, which costs battery, as any large transfer would. QuickShare does not poll in the background or hold a wake lock when nothing is happening.

**Is a transfer encrypted?**
QuickShare runs over your local network and does not add a separate encryption layer; it relies on the security of your Wi-Fi network. Pairing prevents unauthorised senders, and checksums detect corruption, but on an untrusted network you should treat the transfer as you would any other local traffic on it.

**What happens if I close the app mid-transfer?**
If you swipe it away from the recent-apps list, the transfer stops and is recorded as interrupted — you can retry it from History. Merely switching to another app is fine: the transfer continues in the background.

**Can two people send to me at the same time?**
Prompts are handled one at a time. A request that arrives while another is on screen supersedes the earlier one safely rather than corrupting either.

**Does it keep a log of my files?**
The **History** tab lists transfers (device, file count, size, verification result). You can clear it at any time, and clearing it never deletes any file. **Reset settings** does not touch history.

**Why do I need to keep both devices' screens on?**
You do not have to — but Android aggressively suspends background apps. Keeping the screen on (the default) is the simplest way to guarantee a long transfer completes. Alternatively, exempt QuickShare from battery optimisation.

---

## 17. Technical notes

For readers who want to know what is happening underneath.

- **Discovery** uses UDP broadcast beacons on the local network plus enumeration of the device's own network interfaces. No cloud service, no mDNS dependency, no account.
- **Transport** is plain TCP. Each file is streamed in **64 KB chunks** with length-prefixed framing, so memory use is bounded and independent of file size.
- **Port** defaults to **8988** for the control connection.
- **Integrity** uses **SHA-256**, computed while streaming on both sides and compared at the end.
- **Storage** goes through the **Storage Access Framework**, which is why QuickShare only ever has access to the single folder you pick and needs no broad storage permission.
- **Background operation** uses a **foreground service** of type `dataSync` with a progress notification, which is the correct Android model for a user-initiated transfer and the reason the notification is required.
- **Filenames** are sanitised against path separators and `..` before writing, and collisions are resolved to a new unique name.
- **Compatibility** is negotiated on connect: `minSdk 24` (Android 7.0), `targetSdk 34`.

---

## 18. About QuickShare

**QuickShare was created by Cluster_Lab.**

You can read this inside the app at any time: **Settings → About → About us**. The in-app
**How to use QuickShare** guide (**Nearby** tab, or **Settings → How to use QuickShare**) is a short
version of sections 5 to 14 of this manual.

---

*QuickShare 1.0.0 — device-to-device file transfer over your local Wi-Fi network. No cloud, no account, no internet required. Created by Cluster_Lab.*
