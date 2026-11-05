import Combine
import SwiftUI

/// Second half of account creation: after POST /auth/register returns "pending,
/// check your email". Carries email/password/name/role so "Resend code" can just
/// re-call register() (the server's resend path). Port of VerifyEmailActivity.
struct VerifyEmailView: View {
    let email: String
    // A practitioner's code may go to the team inbox instead (the server's
    // practitioner_verification_inbox); the server says where.
    let codeSentTo: String
    let password: String
    let name: String
    let role: String
    let onAuthenticated: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var errorText = ""
    @State private var isSubmitting = false
    @State private var resendCooldown = 30

    private let cooldownTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section {
                Text(subtitle).foregroundStyle(.secondary)
            }
            Section {
                TextField("6-digit code", text: $code)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
            }
            if !errorText.isEmpty {
                Section { Text(errorText).foregroundStyle(.red) }
            }
            Section {
                Button("Verify") { verify() }
                    .disabled(isSubmitting)
                    .frame(maxWidth: .infinity)
                Button(resendCooldown > 0 ? "Resend code (\(resendCooldown)s)" : "Resend code") { resend() }
                    .disabled(resendCooldown > 0 || isSubmitting)
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Verify email")
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(cooldownTimer) { _ in if resendCooldown > 0 { resendCooldown -= 1 } }
    }

    private var subtitle: String {
        codeSentTo.caseInsensitiveCompare(email) != .orderedSame
            ? "We sent a code to \(codeSentTo) for verification"
            : "We sent a code to \(email)"
    }

    private func verify() {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard trimmed.count == 6 else { errorText = "Enter the 6-digit code"; return }
        errorText = ""
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            do {
                let result = try await APIClient.verify(email: email, code: trimmed)
                AppConfig.saveSession(result)
                onAuthenticated()
            } catch {
                errorText = error.localizedDescription
            }
        }
    }

    private func resend() {
        errorText = ""
        Task {
            do {
                _ = try await APIClient.register(email: email, password: password, name: name, role: role)
                resendCooldown = 30
            } catch {
                errorText = error.localizedDescription
            }
        }
    }
}
