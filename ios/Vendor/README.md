# Vendor frameworks

`Muse.framework` (Interaxon libmuse, 8.0.9) goes here. It is gitignored: the
Interaxon SDK license does not allow redistributing it, so it is never
committed and there is no mirror repo.

## Getting it

The SDK comes from the shared Google Drive folder (`RDK-...`; ask whoever
shared it with you), or from Interaxon's developer site. From the iOS SDK
download, copy the framework in:

```sh
cp -R "<libmuse ios download>/Muse.framework" ios/Vendor/
```

Keep the rest of the download outside this repo (`/SDK - RDK*` is gitignored
as a safety net, but it is ~2.3 GB).

## Wiring

Already done in `ios/TCMSessionApp/TCMSessionApp.xcodeproj`: the framework is
referenced at `../Vendor/Muse.framework`, `FRAMEWORK_SEARCH_PATHS` points
here, and `TCMSessionApp-Bridging-Header.h` imports `<Muse/Muse.h>` (the
framework ships no Swift module map). Nothing to drag in.
