import AppKit

let apps = NSWorkspace.shared.runningApplications.filter { $0.bundleIdentifier == "com.ankit.filedrop" }
guard let app = apps.first else {
    print("❌ QuickDrop not running")
    exit(1)
}

print("✅ QuickDrop PID: \(app.processIdentifier)")

// We can't easily inspect other process windows from here without accessibility, 
// but we can check if the app is active and has focus.
print("App is active: \(app.isActive)")
