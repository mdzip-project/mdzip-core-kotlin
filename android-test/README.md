# Testing mdzip-core on Android

This folder is a small Android test project. It runs the library's checks on a real Android device or emulator: the spec fixtures (v1.2.1), plus the parts that can behave differently between Android versions (decompression, Unicode normalization, regular expressions, date formatting, saving files, the Turkish locale). The library itself stays a plain Kotlin/JVM library; this project only uses it.

There are three ways to run it:

- **Automatically on GitHub**: every push to `main` runs it on emulators for Android 5.0, 7.0, 10 and 15 (`.github/workflows/android.yml`). Nothing to install.
- **On your own phone**: the steps below.
- **On an emulator on your PC**: the same steps, using an emulator created in Android Studio instead of a phone (see [Using an emulator instead](#using-an-emulator-instead)).

The tests install a test app on the phone, run for about a minute, and leave your other apps and data alone.

## Test on your Android phone

### 1. Install Android Studio (once)

1. Download Android Studio from <https://developer.android.com/studio> and run the installer with the default options.
2. On first start, choose **Standard** setup. It downloads the Android SDK and accepts its licenses (you'll be shown them). This takes a while and about 3 GB of disk space.

Android Studio includes the Java 17 that the Android build tools need, so the Java 11 already on this PC doesn't matter here.

### 2. Turn on USB debugging on the phone (once)

1. Open **Settings → About phone** and tap **Build number** seven times. (On Samsung: **Settings → About phone → Software information → Build number**; on Xiaomi: **MIUI version**.) The phone says "You are now a developer".
2. Go back to **Settings → System → Developer options** (on some phones, **Settings → Developer options**) and turn on **USB debugging**.
3. Xiaomi, Redmi and POCO phones also need **Install via USB** turned on in Developer options.

### 3. Open this project in Android Studio

1. In Android Studio choose **File → Open** and select this **`android-test`** folder (`mdzip-core-kotlin\android-test`), not the repository root.
2. Wait for the progress bar at the bottom to finish ("Gradle sync"). The first time it downloads the build tools and the test libraries.

### 4. Connect the phone

1. Plug the phone into the PC with a USB cable that carries data (some charging cables don't).
2. On the phone, a box asks **Allow USB debugging?** with the PC's key fingerprint. Tick **Always allow from this computer** and tap **Allow**.
3. In Android Studio's toolbar, the device menu (next to the green ▶) now shows your phone's name. Select it.

Wireless instead of a cable (Android 11 and later): turn on **Wireless debugging** in Developer options, then in Android Studio open the device menu → **Pair Devices Using Wi-Fi** and scan the QR code with the phone (Developer options → Wireless debugging → **Pair device with QR code**). The PC and phone must be on the same Wi-Fi.

### 5. Run the tests

Either:

- In the project panel open `src/androidTest/java/org/mdzip/core/androidtest/DeviceTests.kt`, click the green ▶ next to `class DeviceTests` and choose **Run 'DeviceTests'**; or
- In Android Studio's **Terminal** tab (at the bottom), run:

  ```
  .\gradlew connectedDebugAndroidTest
  ```

The phone may show the test app briefly; leave it alone until the run finishes.

### 6. Read the results

- **Run** panel: a green tick means every test passed. A red mark lists each failing test; click one to see what was expected and what came back.
- **From the terminal**: the report is at `android-test\build\reports\androidTests\connected\debug\index.html`. Open it in a browser.
- The first test, `device`, writes the phone's Android version and model to the log. In the **Logcat** tab, filter on `MdzDeviceTest` to see it.

To report results (to an agent or in an issue), give the phone model and Android version, and either "all passed" or the name and message of each failure. The HTML report has both.

### 7. Afterwards

- Running from the terminal removes the test app when it finishes. Running with the green ▶ leaves an app named `org.mdzip.core.androidtest.test`; remove it under **Settings → Apps** if you like.
- You can turn **USB debugging** off again in Developer options.

## Using an emulator instead

In Android Studio open **Tools → Device Manager → +  (Create Virtual Device)**, pick a phone (for example Pixel 6), then a system image. To test the oldest supported version, choose **API 21** (Android 5.0) from the **Other Images** tab; for a current one, the top entry. Start it with ▶, then select it in the device menu and run the tests as in step 5.

## Troubleshooting

| Problem | What to do |
|---|---|
| The phone doesn't appear in the device menu | Use another cable or USB port; check the **Allow USB debugging?** box on the phone (unlock the phone first); in Developer options tap **Revoke USB debugging authorizations** and reconnect. Some brands need their own Windows USB driver (Samsung: "Samsung Android USB Driver"; Google Pixel: **Tools → SDK Manager → SDK Tools → Google USB Driver**). |
| The device shows as **unauthorized** | Unlock the phone and accept the **Allow USB debugging?** box. |
| `INSTALL_FAILED_USER_RESTRICTED` | Xiaomi/Redmi/POCO: turn on **Install via USB** in Developer options. |
| `JAVA_HOME is set to an invalid directory` in the terminal | This PC's `JAVA_HOME` points at a JDK that isn't there. In the terminal run `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` and try again. |
| Gradle sync fails with an SDK location error | Open **File → Settings → Languages & Frameworks → Android SDK**, note the SDK path, and make sure the SDK was installed in step 1. |
| A test fails | That's what this is for. Note the test name and message (step 6) and file an issue in `mdzip-core-kotlin`. |

## What the tests cover

| Test | Checks |
|---|---|
| `device` | Logs the phone's Android version and model |
| `fixturesArriveUnchanged` | The fixture files on the phone match the spec tag's checksums |
| `consumerArchives` | T-001 to T-005, T-007, T-014, T-015, T-017 |
| `zipBombsStopEarly` | T-018 |
| `entryPointCases` | T-006, T-008 |
| `turkishLocale` | T-010 |
| `pathMatchingAndNames` | T-009, T-013 |
| `manifestCases` | T-012 |
| `linkCases` | T-011 (links from documents inside an archive) |
| `roundTripKeepsEverything` | P-004, P-005 (edits saved twice) |
| `createAndReadLargeAndUnicodeContent` | P-001, P-007, 3 MB entries through the phone's own compression code |
| `symlinkPolicies` | P-008 |
| `fileSavesReplaceAtomically` | A failed save leaves the file untouched; a good one replaces it |
| `errorsHaveCodes` | Error codes on the device |

The full test suite (48 tests) runs on the JVM with `./gradlew test` from the repository root.
