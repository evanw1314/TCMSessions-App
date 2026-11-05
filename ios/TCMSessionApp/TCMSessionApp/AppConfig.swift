import Foundation
import Security

/// Server address + session token. The token lives in the Keychain; everything
/// else in UserDefaults. Port of Android's AppConfig.kt (which seals the token
/// with the Android Keystore instead).
///
/// The practitioner's phone is a separate device from whatever runs the FastAPI
/// server, so the server host is a runtime-editable field on the login screen
/// (same as Android) rather than baked into the build.
/// `nonisolated` so APIClient (also nonisolated, so it can build request bodies
/// off the main thread) can read the token and host. UserDefaults and the
/// Keychain are both thread-safe.
nonisolated enum AppConfig {
    private static let keyServerHost = "server_host"
    // Where builds before the Keychain kept the token, in UserDefaults; moved
    // across the first time it is read.
    private static let keyLegacyToken = "access_token"
    private static let keyUserId = "user_id"
    private static let keyUserName = "user_name"
    private static let keyClinicName = "clinic_name"
    // "practitioner" or "patient" (mirrors LoginResult.role) — decides which
    // screen a returning, already-logged-in session lands on and which endpoints
    // APIClient may call.
    private static let keyRole = "role"

    private static var defaults: UserDefaults { .standard }

    // No baked-in default host: the practitioner types their clinic server's
    // address (e.g. http://192.168.1.20:8000) once.
    static var serverHost: String {
        defaults.string(forKey: keyServerHost) ?? ""
    }

    static func setServerHost(_ host: String) {
        let trimmed = host.trimmingCharacters(in: .whitespaces)
        defaults.set(trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed, forKey: keyServerHost)
    }

    static var token: String? {
        if let legacy = defaults.string(forKey: keyLegacyToken) {
            TokenKeychain.save(legacy)
            defaults.removeObject(forKey: keyLegacyToken)
        }
        // Keychain items outlive an uninstall but UserDefaults don't, so a
        // reinstalled app can find a token with no user beside it: signed out.
        if role.isEmpty {
            TokenKeychain.delete()
            return nil
        }
        return TokenKeychain.read()
    }

    // -1 when unset, not UserDefaults' 0: a journal stamped with practitioner 0
    // would be offered for recovery to whoever happened to be user 0, and the
    // "unknown practitioner" case would be indistinguishable from a real id.
    static var userId: Int {
        defaults.object(forKey: keyUserId) as? Int ?? -1
    }

    static var userName: String { defaults.string(forKey: keyUserName) ?? "" }

    // Blank when the account has no clinic (shouldn't happen for a practitioner
    // login, but this app doesn't hard-fail a display nicety).
    static var clinicName: String { defaults.string(forKey: keyClinicName) ?? "" }

    static var role: String { defaults.string(forKey: keyRole) ?? "" }

    static var isPatient: Bool { role == "patient" }

    static var isLoggedIn: Bool { token != nil }

    static func saveSession(_ result: LoginResult) {
        // Role first: a token read in between would otherwise see a token
        // with no role and delete it as an uninstall leftover.
        defaults.set(result.role, forKey: keyRole)
        defaults.set(result.userId, forKey: keyUserId)
        defaults.set(result.userName, forKey: keyUserName)
        defaults.set(result.clinicName ?? "", forKey: keyClinicName)
        defaults.removeObject(forKey: keyLegacyToken)
        TokenKeychain.save(result.token)
    }

    // Clears the cached login only — NOT the saved server address, since the
    // login screen pre-fills that field and it's editable there; this exists so
    // switching networks (a new server address) doesn't require wiping app data
    // to reach a fresh login screen. Mirrors Android's clearSession().
    static func clearSession() {
        TokenKeychain.delete()
        [keyLegacyToken, keyUserId, keyUserName, keyClinicName, keyRole].forEach {
            defaults.removeObject(forKey: $0)
        }
    }
}

/// One generic-password Keychain item holding the session token.
/// AfterFirstUnlockThisDeviceOnly: readable while the phone is locked (a
/// recording keeps uploading in the background), and never copied into a
/// backup or onto another device.
private nonisolated enum TokenKeychain {
    private static let service = "TCMSessionApp.session"
    private static let account = "access_token"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func read() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func save(_ token: String) {
        delete()
        var q = query
        q[kSecValueData as String] = Data(token.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        assert(status == errSecSuccess, "Keychain save failed: \(status)")
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
