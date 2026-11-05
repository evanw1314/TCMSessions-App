# iOS build setup (TCMSessionApp)

The Xcode project already exists and is configured. Open
`ios/TCMSessionApp/TCMSessionApp.xcodeproj`; don't regenerate it.

## 1. Prerequisites

- Xcode from the App Store. If the CLI points at Command Line Tools only:
  `sudo xcode-select -s /Applications/Xcode.app`.
- `Muse.framework` in `ios/Vendor/`; see [`Vendor/README.md`](Vendor/README.md).
  The project won't compile without it.

## 2. Build and run

1. **Signing & Capabilities** → set your own team.
2. Start the server (see the root `README.md`).
3. Build & Run on a physical iPhone. The headband needs Bluetooth, which the
   simulator doesn't have; the simulator is only useful for UI that doesn't
   touch the headband.
4. On the login screen, enter the server address (or scan the QR code on the
   server's `/pair` page) and sign in.

Already configured in the project: Bluetooth, camera, microphone and speech
usage strings; the ATS `NSAllowsLocalNetworking` exception, the Google
Sign-In client ID and its URL scheme (via `ios/Support/Info.plist`);
GoogleSignIn-iOS via Swift Package Manager.

## 3. Google Sign-In

The consent screen is in Testing mode, so Google returns 403 unless your
address is a listed test user; ask a maintainer. The client ID lives in
`ios/Support/Info.plist`, `server/.env` and `server/.env.example`. Moving to a
different Google Cloud project means changing it in those three files.
