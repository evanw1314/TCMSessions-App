import SwiftUI
#if canImport(GoogleSignIn)
import GoogleSignIn
#endif

@main
struct TCMSessionAppApp: App {
    // The Muse recorder is owned at app scope so an in-progress recording (and its
    // BLE connection + buffer + upload) survives navigating between screens.
    @StateObject private var museSession = MuseSessionService()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(museSession)
                #if canImport(GoogleSignIn)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
                #endif
        }
    }
}

