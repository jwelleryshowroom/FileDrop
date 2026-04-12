import SwiftUI
import AppKit

struct MacDropApp: App {
    @StateObject private var serverManager = ServerManager()

    var body: some Scene {
        MenuBarExtra("MacDrop", systemImage: "paperplane.fill") {
            
            Text("Status: \(serverManager.serverStatus)")
            
            Divider()
            
            if serverManager.isServerRunning {
                Button("Stop Server") {
                    serverManager.stopServer()
                }
            } else {
                Button("Start Server") {
                    serverManager.startServer()
                }
            }
            
            Divider()
            
            Button("Send File to Phone...") {
                selectAndSendFile()
            }
            
            Divider()
            
            Button("Quit") {
                if serverManager.isServerRunning {
                    serverManager.stopServer()
                }
                NSApplication.shared.terminate(nil)
            }
        }
    }

    private func selectAndSendFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        
        if panel.runModal() == .OK {
            if let url = panel.url {
                serverManager.sendFile(atPath: url.path)
            }
        }
    }
}

// 🚨 Manual trigger so the terminal compiler knows where to start
MacDropApp.main()