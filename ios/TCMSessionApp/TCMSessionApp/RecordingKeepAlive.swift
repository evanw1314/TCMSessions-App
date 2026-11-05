import UIKit

/// Keeps the app alive and ticking while any session is recording or saving —
/// the iOS counterpart of Android's RecordingService.
///
/// The two platforms get here very differently. Android needs an explicit
/// foreground service (plus the ongoing notification it charges for) and a
/// partial wake lock, because it will otherwise freeze or reclaim an app with no
/// screen showing. iOS instead grants a backgrounded app continued BLE delivery
/// through the `bluetooth-central` background mode, which is declared in the
/// build's Info.plist — so what's left for this type to do is narrower:
///
///  - hold the screen awake while recording, so the once-a-second tick (a
///    main-runloop timer, exactly like Android's) keeps running and the
///    practitioner can watch the session;
///  - hold a background task assertion across the transition to the background,
///    so an upload in flight when the app is backgrounded is given time to
///    finish rather than being suspended mid-request.
///
/// It holds no recording state of its own; MuseSessionService starts and stops
/// it through `update`, called on every recorder phase change. If the process
/// dies anyway, SessionJournal is what rescues the recording.
@MainActor
enum RecordingKeepAlive {

    private static var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private static var active = false

    /// Matches the keep-alive to the recorders: on while any patient is
    /// `recording` or `saving`, off when none is.
    static func update(recording: [String], saving: [String]) {
        let shouldBeActive = !recording.isEmpty || !saving.isEmpty
        guard shouldBeActive != active else { return }
        active = shouldBeActive
        if shouldBeActive { begin() } else { end() }
    }

    private static func begin() {
        // The tick is a main-runloop timer; letting the screen lock stops it.
        UIApplication.shared.isIdleTimerDisabled = true
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "TcmSessionRecording") {
            // iOS reclaiming the assertion is not a reason to stop recording —
            // BLE delivery continues under the bluetooth-central background mode
            // and the journal covers an outright kill. Just release the handle so
            // the app isn't terminated for holding an expired one.
            endBackgroundTask()
        }
    }

    private static func end() {
        UIApplication.shared.isIdleTimerDisabled = false
        endBackgroundTask()
    }

    private static func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}
