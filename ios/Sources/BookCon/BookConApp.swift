//
//  BookConApp.swift
//  BookCon
//
//  App entry point. Hosts the library screen in a navigation stack and
//  applies app-wide appearance + orientation policy.
//

import SwiftUI
import UIKit

@main
struct BookConApp: App {
    /// Installs orientation support that cannot be expressed in pure SwiftUI
    /// (iPhone excludes upside-down portrait; iPad allows everything).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                // nil = follow the system light/dark setting.
                .preferredColorScheme(nil)
                // Indigo accent for controls across the whole app.
                .tint(.indigo)
        }
    }
}

/// Orientation policy: every orientation except upside-down portrait on
/// iPhone; unrestricted on iPad.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
}

/// Root of the UI: the library screen inside a navigation stack, wired to the
/// shared library store.
struct RootView: View {
    var body: some View {
        NavigationStack {
            LibraryScreen()
        }
        .environmentObject(LibraryStore.shared)
    }
}
