# TCM Sessions (Android)

Android client for in-clinic Muse S EEG sessions, talking to the FastAPI
server in `server/`. Practitioners record sessions; a patient signing in gets
a read-only view of their own record. Design decisions and invariants are in
the repo's [`ARCHITECTURE.md`](../../ARCHITECTURE.md).

## Getting the Muse SDK (gitignored — not committed)

The Interaxon license forbids redistributing the SDK, so there is no mirror
repo. Get `libmuse_android_8.0.9.tar.gz` from the team's shared Google Drive
folder (`RDK-...`; ask whoever shared it with you), or the current release
from Interaxon's SDK page (the link is in `Muse SDK.docx` in that folder; it
changes over time, so it isn't reproduced here).

Extract it **outside the repo** (so a `git add .` can never pick it up), then
copy the binaries in from the repo root:

```sh
SDK=$(mktemp -d)
tar -xzf <path to>/libmuse_android_8.0.9.tar.gz -C "$SDK" libmuse_android_8.0.9/libs
cp "$SDK"/libmuse_android_8.0.9/libs/libmuse_android.jar android/TcmSessionApp/app/libs/
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  mkdir -p android/TcmSessionApp/app/src/main/jniLibs/$abi
  cp "$SDK"/libmuse_android_8.0.9/libs/$abi/libmuse_android.so android/TcmSessionApp/app/src/main/jniLibs/$abi/
done
```

Then open `android/TcmSessionApp/` in Android Studio and let Gradle sync.
`app/build.gradle.kts` already references `libs/libmuse_android.jar`, and the
Android Gradle plugin picks up the `.so` files under `src/main/jniLibs/<abi>/`
with no extra config.

The SDK's own example app
(`libmuse_android_8.0.9/examples/TestLibMuseAndroid/`) is the reference this
project's headband connection (`SessionRecorder.kt`) was adapted from. Keep it
open side by side when debugging the connect or data-listener path.

## Build

Needs Android Studio, which bundles JDK 17 and the Android SDK. From a
shell, point `JAVA_HOME` at Android Studio's bundled JBR.

```sh
./gradlew testDebugUnitTest assembleDebug
```

The unit tests run locally only: CI can't build the app, because the Muse
SDK it links against may not be put on a build server.

## Running against the server

On first launch, the login screen asks for the server address (for example
`http://192.168.1.20:8000`, your dev machine's LAN address) and saves it.
Scanning the QR code on the server's `/pair` page fills it in. A build can
also start with an address filled in: set `defaultServerUrl` in
`gradle.properties`.

Sign in with the seeded practitioner, `dr.rivera@example.com`, whose password
is `DEV_PRACTITIONER_PASSWORD` in `server/seed.py`, or use "Create account" to
register a new one.

Bluetooth needs a physical Android phone (API 26+) and a physical Muse S;
there is no emulator path.
