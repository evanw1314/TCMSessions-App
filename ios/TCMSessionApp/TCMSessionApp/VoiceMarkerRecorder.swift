@preconcurrency import AVFoundation
import Foundation
import Speech

/// Voice-marker language toggle; `bcp47Tag` nil means English. Mandarin uses the
/// script-qualified tags (the server converts to Traditional characters for
/// cmn-Hant-TW, see server/app/transcription.py), matching Android's VoiceLanguage.
enum VoiceLanguage: CaseIterable {
    case english
    case mandarinSimplified
    case mandarinTraditional

    var buttonLabel: String {
        switch self {
        case .english: return "EN"
        case .mandarinSimplified: return "简体"
        case .mandarinTraditional: return "繁體"
        }
    }

    var bcp47Tag: String? {
        switch self {
        case .english: return nil
        case .mandarinSimplified: return "cmn-Hans-CN"
        case .mandarinTraditional: return "cmn-Hant-TW"
        }
    }

    /// The on-device recognizer's locale for live captions. Separate from
    /// `bcp47Tag`, which is what the SERVER's AssemblyAI transcription wants:
    /// SFSpeechRecognizer doesn't know the "cmn-" prefixed tags.
    var captionLocale: Locale {
        switch self {
        case .english: return Locale(identifier: "en-US")
        case .mandarinSimplified: return Locale(identifier: "zh-Hans-CN")
        case .mandarinTraditional: return Locale(identifier: "zh-Hant-TW")
        }
    }

    func next() -> VoiceLanguage {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

/// Records a short voice marker to a temp WAV file, with an amplitude-driven
/// silence auto-stop and live on-device captions while the practitioner speaks.
///
/// One microphone feeds both: an AVAudioEngine tap writes 16 kHz mono PCM to the
/// file that gets uploaded AND hands the same buffers to SFSpeechRecognizer for
/// the captions. (Android runs MediaRecorder and SpeechRecognizer side by side;
/// iOS won't give two clients the input, hence the single shared tap.)
///
/// The captions are DISPLAY ONLY — exactly as on Android. What actually becomes
/// the marker's text is the server's AssemblyAI transcription of the uploaded
/// clip (server/app/transcription.py), which is biased toward TCM terms and
/// converts to Traditional characters; the on-device recognizer is neither.
@MainActor
final class VoiceMarkerRecorder {
    // dBFS thresholds/timeouts for the silence auto-stop. Peak ~ -22 dB and up
    // reads as speech; a fan/AC hum sits below. Heuristic — the manual stop
    // button is the reliable path (same stance as Android).
    private static let speechThresholdDB: Float = -22
    private static let silenceTimeout: TimeInterval = 3.0
    private static let pollInterval: TimeInterval = 0.2

    private let engine = AVAudioEngine()
    /// The tap closure and this object both reach the file through this box, so
    /// teardown can release it deterministically: AVAudioFile only writes the
    /// RIFF header's sizes when the last reference goes away, and stop() reads
    /// the bytes back immediately afterwards.
    private let fileBox = AudioFileBox()
    private var converter: AVAudioConverter?
    private var fileURL: URL?
    private var pollTimer: Timer?
    private var heardSpeech = false
    private var silenceStartedAt: Date?
    // Written by the tap (a real-time audio thread), read by the poll timer on
    // the main actor — hence its own non-isolated, lock-guarded box.
    private nonisolated let meter = AudioLevelMeter()

    private var recognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?

    private(set) var isRecording = false

    /// Called (on the main actor) when the recording auto-stops after ~3s of
    /// silence following speech. The caller should then take `stop()`'s bytes.
    var onSilenceAutoStop: (() -> Void)?

    /// Called (on the main actor) with the recognizer's best guess so far, so the
    /// practitioner can see they're being heard. Display only — see the type doc.
    var onLiveCaption: ((String) -> Void)?

    static func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { granted in cont.resume(returning: granted) }
        }
    }

    static var hasPermission: Bool {
        AVAudioApplication.shared.recordPermission == .granted
    }

    /// Asks for speech-recognition access, used only for the live captions. A
    /// refusal is not an error: recording still works, just without captions.
    static func requestSpeechPermission() async -> Bool {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
    }

    /// Starts capture. Throws if the mic or audio session can't be set up.
    /// `captionLanguage` nil skips the live captions entirely.
    func start(captionLanguage: VoiceLanguage?) throws {
        let session = AVAudioSession.sharedInstance()
        // .measurement keeps iOS's own processing off the signal; .record rather
        // than .playAndRecord since nothing is played back here.
        try session.setCategory(.record, mode: .measurement, options: [])
        try session.setActive(true, options: [])
        // Every throw below this line happens with the session already active;
        // leaving it that way keeps the mic indicator lit and blocks other audio
        // with no UI left to stop it. One exit path covers all of them.
        var started = false
        defer { if !started { finishSession() } }

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice_marker_\(Int(Date().timeIntervalSince1970 * 1000)).wav")

        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0 else {
            throw APIError(message: "Couldn't start the microphone")
        }
        // 16 kHz mono 16-bit PCM — ideal for speech, and a real RIFF/WAV (the
        // .wav extension picks the container) matching the mime type we send.
        guard let targetFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                               sampleRate: 16_000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: inputFormat, to: targetFormat)
        else { throw APIError(message: "Couldn't start the microphone") }
        self.converter = converter

        fileBox.file = try AVAudioFile(forWriting: url, settings: targetFormat.settings,
                                       commonFormat: .pcmFormatInt16, interleaved: true)
        fileURL = url

        startCaptions(language: captionLanguage)
        let request = recognitionRequest

        let box = fileBox
        input.installTap(onBus: 0, bufferSize: 4_096, format: inputFormat) { [weak self] buffer, _ in
            // Real-time audio thread: no main-actor hops, no allocation-heavy work.
            request?.append(buffer)
            self?.noteLevel(of: buffer)
            self?.write(buffer, using: converter, to: box, targetFormat: targetFormat)
        }

        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            stopCaptions()
            fileBox.file = nil
            fileURL = nil
            // Leaving the .record session active would keep the mic indicator
            // lit and block other audio after a failure the user never saw.
            finishSession()
            try? FileManager.default.removeItem(at: url)
            throw APIError(message: "Couldn't start the microphone")
        }

        started = true
        isRecording = true
        heardSpeech = false
        silenceStartedAt = nil
        meter.reset()

        let timer = Timer(timeInterval: Self.pollInterval, repeats: true) { _ in
            Task { @MainActor [weak self] in self?.pollSilence() }
        }
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }

    /// Stops capture and returns the recorded WAV bytes (nil if nothing usable was
    /// captured). Cleans up the temp file and the poll timer.
    func stop() -> Data? {
        teardownTimer()
        guard let url = fileURL else { teardownAudio(); finishSession(); return nil }
        teardownAudio()
        let data = try? Data(contentsOf: url)
        try? FileManager.default.removeItem(at: url)
        fileURL = nil
        finishSession()
        // A recording with no captured frames (tapped and immediately re-tapped)
        // is effectively empty — treat a tiny file as nothing.
        if let data, data.count > 1_024 { return data }
        return nil
    }

    /// Discards whatever the mic is doing right now — never uploads. Idempotent.
    func abort() {
        teardownTimer()
        teardownAudio()
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
        fileURL = nil
        finishSession()
    }

    // MARK: - Audio plumbing

    private nonisolated func write(_ buffer: AVAudioPCMBuffer, using converter: AVAudioConverter,
                                   to box: AudioFileBox, targetFormat: AVAudioFormat) {
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1_024
        guard let out = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }
        var supplied = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            // One input buffer per call: anything more and the converter would
            // block waiting on audio that hasn't been captured yet.
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, out.frameLength > 0 else { return }
        box.write(out)
    }

    /// Peak level of this buffer, kept for the next silence poll. Reading the
    /// float channel data directly is the AVAudioEngine equivalent of
    /// AVAudioRecorder's metering.
    private nonisolated func noteLevel(of buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        var peak: Float = 0
        for frame in 0..<Int(buffer.frameLength) {
            peak = max(peak, abs(channel[frame]))
        }
        meter.note(db: peak > 0 ? 20 * log10(peak) : AudioLevelMeter.floorDB)
    }

    private func teardownAudio() {
        if engine.isRunning || fileBox.file != nil {
            engine.inputNode.removeTap(onBus: 0)
        }
        if engine.isRunning { engine.stop() }
        stopCaptions()
        // Order matters: the tap is gone by here, so dropping the box's
        // reference is the last one, and the WAV's header sizes are written
        // before stop() reads the file back.
        fileBox.file = nil
        converter = nil
        isRecording = false
    }

    // MARK: - Live captions

    private func startCaptions(language: VoiceLanguage?) {
        guard let language,
              SFSpeechRecognizer.authorizationStatus() == .authorized,
              let recognizer = SFSpeechRecognizer(locale: language.captionLocale),
              recognizer.isAvailable
        else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.recognizer = recognizer
        recognitionRequest = request
        recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, _ in
            guard let result else { return }
            let text = result.bestTranscription.formattedString
            guard !text.isEmpty else { return }
            Task { @MainActor [weak self] in
                // Stopped or aborted since this callback was queued — don't
                // clobber "Transcribing…".
                guard let self, self.isRecording else { return }
                self.onLiveCaption?(text)
            }
        }
    }

    private func stopCaptions() {
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        recognitionRequest = nil
        recognitionTask = nil
        recognizer = nil
    }

    // MARK: - Silence auto-stop

    private func pollSilence() {
        guard isRecording else { return }
        let level = meter.takePeak()
        if level > Self.speechThresholdDB {
            heardSpeech = true
            silenceStartedAt = nil
        } else if heardSpeech {
            if let started = silenceStartedAt {
                if Date().timeIntervalSince(started) >= Self.silenceTimeout {
                    onSilenceAutoStop?()
                }
            } else {
                silenceStartedAt = Date()
            }
        }
    }

    private func teardownTimer() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func finishSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}


/// The loudest sample seen since the last poll, written from the audio tap's
/// real-time thread and read from the main actor. Its own type purely so that
/// state crossing threads is visibly separate from the recorder's isolated state.
/// Holds the file being written so the audio tap and the recorder share one
/// reference that teardown can drop deterministically. `nonisolated`: the tap
/// writes through it from the real-time thread.
private nonisolated final class AudioFileBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _file: AVAudioFile?

    var file: AVAudioFile? {
        get { lock.lock(); defer { lock.unlock() }; return _file }
        set { lock.lock(); _file = newValue; lock.unlock() }
    }

    func write(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); defer { lock.unlock() }
        try? _file?.write(from: buffer)
    }
}

/// `nonisolated`: written from the audio tap's real-time thread, which this
/// build would otherwise implicitly put on the main actor.
private nonisolated final class AudioLevelMeter: @unchecked Sendable {
    static let floorDB: Float = -160

    private let lock = NSLock()
    private var peak: Float = AudioLevelMeter.floorDB

    func note(db: Float) {
        lock.lock(); peak = max(peak, db); lock.unlock()
    }

    /// The peak since the previous call, then starts a fresh window.
    func takePeak() -> Float {
        lock.lock(); defer { peak = Self.floorDB; lock.unlock() }
        return peak
    }

    func reset() {
        lock.lock(); peak = Self.floorDB; lock.unlock()
    }
}
