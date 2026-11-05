import SwiftUI
#if canImport(GoogleSignIn)
import GoogleSignIn
#endif

/// Sign-in / create-account screen. Port of Android's LoginActivity. On success it
/// calls `onAuthenticated`, which the root view uses to route to the role's home screen.
struct LoginView: View {
    let onAuthenticated: () -> Void

    private enum Mode: String, CaseIterable { case signIn = "Sign in", createAccount = "Create account" }
    private enum Role: String, CaseIterable { case patient = "Patient", practitioner = "Practitioner" }

    @State private var serverHost = AppConfig.serverHost
    @State private var showServerSettings = AppConfig.serverHost.isEmpty
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var name = ""
    @State private var mode: Mode = .signIn
    // Patient defaults selected — patients are the far more common signup.
    @State private var role: Role = .patient
    @State private var errorText = ""
    @State private var isSubmitting = false
    @State private var pending: PendingRegistration?
    @State private var showQrScanner = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DisclosureGroup("Server settings", isExpanded: $showServerSettings) {
                        HStack {
                            TextField("http://192.168.1.20:8000", text: $serverHost)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keyboardType(.URL)

                            Button {
                                showQrScanner = true
                            } label: {
                                Image(systemName: "qrcode.viewfinder")
                                    .imageScale(.large)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Section {
                    Picker("Mode", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    if mode == .createAccount {
                        Picker("I am a", selection: $role) {
                            ForEach(Role.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        TextField("Name", text: $name)
                            .textContentType(.name)
                    }

                    TextField("Email", text: $email)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)

                    SecureField("Password", text: $password)
                        .textContentType(mode == .createAccount ? .newPassword : .password)

                    if mode == .createAccount {
                        SecureField("Confirm password", text: $confirmPassword)
                            .textContentType(.newPassword)
                    }
                }

                if !errorText.isEmpty {
                    Section { Text(errorText).foregroundStyle(.red) }
                }

                Section {
                    Button(mode.rawValue) { submit() }
                        .disabled(isSubmitting)
                        .frame(maxWidth: .infinity)

                    if mode == .signIn {
                        #if canImport(GoogleSignIn)
                        Button {
                            Task { await signInWithGoogle() }
                        } label: {
                            Label(isSubmitting ? "Signing in…" : "Continue with Google", systemImage: "g.circle")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(isSubmitting)
                        #endif
                    }
                }
            }
            .navigationTitle("TCM Sessions")
            .navigationDestination(item: $pending) { reg in
                VerifyEmailView(
                    email: reg.email,
                    codeSentTo: reg.codeSentTo,
                    password: password,
                    name: name,
                    role: role.rawValue.lowercased(),
                    onAuthenticated: onAuthenticated
                )
            }
            .sheet(isPresented: $showQrScanner) {
                QrScanView { scanned in
                    let trimmed = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
                    serverHost = trimmed
                    AppConfig.setServerHost(trimmed)
                }
            }
        }
    }


    private func submit() {
        let host = serverHost.trimmingCharacters(in: .whitespaces)
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        if host.isEmpty || trimmedEmail.isEmpty || password.isEmpty {
            errorText = "Enter a server address, email, and password"
            return
        }
        if mode == .createAccount {
            if name.trimmingCharacters(in: .whitespaces).isEmpty {
                errorText = "Enter your name"; return
            }
            if password != confirmPassword {
                errorText = "Passwords don't match"; return
            }
        }
        AppConfig.setServerHost(host)
        errorText = ""
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            do {
                if mode == .createAccount {
                    // register() emails a 6-digit code; the account only becomes
                    // real once VerifyEmailView confirms it.
                    let reg = try await APIClient.register(
                        email: trimmedEmail, password: password,
                        name: name.trimmingCharacters(in: .whitespaces),
                        role: role.rawValue.lowercased()
                    )
                    pending = reg
                } else {
                    let result = try await APIClient.login(email: trimmedEmail, password: password)
                    AppConfig.saveSession(result)
                    onAuthenticated()
                }
            } catch {
                errorText = error.localizedDescription
            }
        }
    }

    #if canImport(GoogleSignIn)
    private func signInWithGoogle() async {
        let host = serverHost.trimmingCharacters(in: .whitespaces)
        if host.isEmpty {
            errorText = "Enter a server address first"
            return
        }
        AppConfig.setServerHost(host)
        errorText = ""
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let root = scene.keyWindow?.rootViewController else {
                throw APIError(message: "No window to present sign-in from")
            }
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: root)
            guard let idToken = result.user.idToken?.tokenString else {
                throw APIError(message: "Google returned no ID token")
            }
            let loginResult = try await APIClient.googleLogin(idToken: idToken)
            AppConfig.saveSession(loginResult)
            onAuthenticated()
        } catch is CancellationError {
            // User cancelled account picker — not an error worth surfacing
        } catch {
            let msg = error.localizedDescription
            if msg.contains("role is required") {
                errorText = "No account found for that Google email. Create an account first, then use \"Continue with Google\" to link it."
            } else {
                errorText = msg
            }
        }
    }
    #endif
}

extension PendingRegistration: Identifiable, Hashable {
    var id: String { email }
    static func == (lhs: PendingRegistration, rhs: PendingRegistration) -> Bool { lhs.email == rhs.email }
    func hash(into hasher: inout Hasher) { hasher.combine(email) }
}

