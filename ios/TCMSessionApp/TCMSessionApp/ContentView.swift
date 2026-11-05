import SwiftUI

/// Role-routed root: not-logged-in → LoginView; logged-in practitioner → patient
/// roster; logged-in patient → their own read-only self-view. Mirrors Android's
/// LoginActivity.goToRoleHome / onCreate routing.
struct RootView: View {
    @State private var loggedIn = AppConfig.isLoggedIn
    @State private var isPatient = AppConfig.isPatient

    var body: some View {
        if !loggedIn {
            LoginView(onAuthenticated: refresh)
        } else if isPatient {
            NavigationStack {
                PatientDetailView(
                    patientId: AppConfig.userId,
                    initialName: AppConfig.userName,
                    initialEmail: nil,
                    initialHeadband: nil,
                    initialLastSession: nil,
                    isSelfView: true,
                    onSignOut: refresh
                )
                .tcmDestinations()
            }
        } else {
            NavigationStack {
                PatientListView(onSignOut: refresh)
                    .tcmDestinations()
            }
        }
    }

    private func refresh() {
        loggedIn = AppConfig.isLoggedIn
        isPatient = AppConfig.isPatient
    }
}

#Preview {
    RootView()
        .environmentObject(MuseSessionService())
}
