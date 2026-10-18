# BT Chat — two Android phones, Bluetooth only

One HTML file holds the whole interface and all chat logic (`index.html`). A web page cannot open Bluetooth Classic sockets, so a small piece of native Android code (`BtBridge.kt`) does the Bluetooth and hands the page an object called `BTBridge`.

## What has and has not been checked

| Checked | How |
|---|---|
| Interface, chat logic, saved history, failure and disconnect handling, splash screen | 42 automated checks in a real browser against a **simulated** `BTBridge` (`test/ui_test.py`) |
| Kotlin code compiles | **Not checked.** The environment this was written in could not download the Android build tools. |
| GitHub Actions build works | **Not checked.** |
| Two real phones exchange messages over Bluetooth | **Not checked. Nothing here has run on a phone.** Treat Bluetooth as unproven until you finish the test list in section 4. |
| Median specifics | Read from Median's public docs only; nothing was built with Median (section 5). |

## 1. Can Median do this?

From Median's docs (docs.median.co):

- Its public Native Plugins list has **no Bluetooth plugin** (not Classic, not BLE). The nearest item is iBeacon.
- The JavaScript bridge is `median.module.function()` plus `median://` URLs. No Bluetooth module is listed.
- Median mentions private plugins for external hardware such as Bluetooth devices. That is a custom build by Median's team, not something in the standard product. Their FAQ says to engage them for custom development.
- Median lets you **download the Android source** (App Studio → Build & Deploy → Android Source Code, an `android_source.tar.gz`), and its FAQ says the licence permits modifying it. The docs do **not** explain where to add your own native code or how their bridge is implemented, so I can't tell you which file to edit.
- The quickstart requires a **public website URL** as the app's content. The only documented local-file use is `file:///offline.html` for the offline page. I could not confirm a full local-HTML app.

So Median alone cannot do Bluetooth chat. You need custom native code, which is `BtBridge.kt`. Building from Median's source needs Android Studio.

## 2. How the HTML and the native code talk

Page → native (each returns a JSON string): `info()`, `requestPermission()`, `pairedDevices()`, `listen()`, `connect(address)`, `send(ref, line)`, `close()`, `openBluetoothSettings()`.
Native → page: `window.onBtEvent(jsonString)` with `state`, `line`, `write` or `perm` events.
These names are mine, not Median's. The full contract is in the comment at the top of the script in `index.html`.

Chat messages are one JSON object per line over a Bluetooth RFCOMM socket. Ticks: `…` sending, `✓` written to the Bluetooth link, `✓✓` the other phone confirmed it. A message that fails to write shows "Not sent — tap to retry".

## 3. Build the APK without Android Studio (GitHub Actions)

You can do all of this from a phone browser.

1. Make a free account at github.com and create a new **empty repository**.
2. Upload everything **inside the `android` folder** of this package (so `settings.gradle` and the `.github` folder sit at the top level of the repository). Use "Add file → Upload files" and drag the contents in. If the `.github` folder doesn't upload, use "Add file → Create new file", type `.github/workflows/build.yml` as the name, and paste in that file's text.
3. Open the **Actions** tab, choose **Build APK**, press **Run workflow**. It takes a few minutes.
4. When it turns green, open the run and download the **bt-chat-apk** artifact (a zip containing `app-debug.apk`).
5. Copy the APK to both phones, unzip, and install it. Android will ask you to allow installing from that source.

If the build goes red, open the failed step and read the error. That will be the first real compile check of the Kotlin code, so fixes may be needed.

The APK is signed with a debug key. That is fine for installing on your own phones, not for the Play Store.

## 4. Test it (do these in order, with Wi‑Fi and mobile data OFF on both phones)

1. Pair the phones: Android Settings → Bluetooth, pair each with the other.
2. Open BT Chat on both. Splash plays, then the home screen. "Bluetooth app" must say **Found**. If it says Missing, the native bridge isn't attached.
3. If "Permission" says Needed, tap **Allow Bluetooth access** and allow it.
4. Phone A: **Host a chat**. Phone B: **Join a chat**, tap Phone A in the list.
5. Both should say **Connected**. Send a message each way. Look for the `✓✓` on the sender.
6. Turn Bluetooth off on one phone mid-chat. The other should say Disconnected within a short time with a reason, and unsent messages show "Not sent".
7. Close and reopen the app. The chat should still be listed under Saved chats.
8. Try Join while nobody is hosting. You should get "Could not connect…", not a spinner forever.

Report which step fails and the exact text on screen. For debug builds you can also inspect the page from desktop Chrome at `chrome://inspect`.

## 5. Using Median's source instead (not verified)

1. Download your app's Android source from Median App Studio and open it in Android Studio. This needs a version of Windows newer than 7, as far as I know, or another computer.
2. Copy `BtBridge.kt` into the project. Add the permissions from `AndroidManifest.xml` to Median's manifest.
3. Find where Median creates its WebView (search the source for `WebView` and `addJavascriptInterface`) and add `webView.addJavascriptInterface(BtBridge(activity, webView, askPermissions, keepAwake), "BTBridge")`. You must supply the permission launcher and the keep-awake callback, as `MainActivity.kt` does.
4. Make Median load your HTML as its content. Only load a page you fully control, because it will have access to Bluetooth.

I could not verify any file names or hook points in Median's source. Median's team can build this as a private plugin if you'd rather pay for that.

## 6. Limits to know about

- Phones must be **paired first** in Android settings. One connection at a time.
- Keep the app open on both phones. If Android stops the app in the background, the connection drops. The screen is kept on while connected.
- Bluetooth range is roughly a room to ~10 m.
- Chat history is saved only on the phone that sent or received it, in the app's local storage. Deleting a chat deletes it from that phone only.
- Messages travel over the paired Bluetooth link. The app adds no encryption of its own.
- Android 6 or newer (minSdk 23). Portrait only.
- iPhones are not supported.

## Files

- `index.html` — the single-file app (also copied to `android/app/src/main/assets/`). Edit the copy in `assets` when building.
- `android/` — minimal Android project: `BtBridge.kt`, `MainActivity.kt`, manifest, Gradle files, GitHub Actions workflow.
- `test/ui_test.py` — the browser tests with a simulated bridge, plus screenshots.
