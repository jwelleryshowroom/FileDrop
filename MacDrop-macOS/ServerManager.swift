import Foundation
import Combine

class ServerManager: ObservableObject {
    @Published var isServerRunning = false
    @Published var serverStatus = "Server Stopped"
    
    private var serverProcess: Process?
    
    // ✅ REAL Python Path found in Step 1
    private let pythonPath = "/Library/Developer/CommandLineTools/usr/bin/python3"
    private let scriptPath = "/Users/ankitkumarsah/Projects/FileDrop/server.py"
    private let workingDirectory = "/Users/ankitkumarsah/Projects/FileDrop"
    private let deviceIP = ProcessInfo.processInfo.environment["MACDROP_ANDROID_IP"]

    func startServer() {
        guard !isServerRunning else { return }
        
        // 🧹 Step A: Free port 8000 before starting to avoid "Address already in use"
        let cleanup = Process()
        cleanup.executableURL = URL(fileURLWithPath: "/bin/bash")
        cleanup.arguments = ["-c", "kill -9 $(lsof -ti :8000) 2>/dev/null"]
        try? cleanup.run()
        cleanup.waitUntilExit()
        
        let process = Process()
        process.executableURL = URL(fileURLWithPath: pythonPath)
        process.arguments = ["\(scriptPath)"]
        process.currentDirectoryURL = URL(fileURLWithPath: workingDirectory)
        
        // ✅ Pass full environment to inherit Python paths/modules
        process.environment = ProcessInfo.processInfo.environment
        
        // 🐞 Capture Python errors for easier debugging
        let pipe = Pipe()
        process.standardError = pipe
        
        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if let output = String(data: data, encoding: .utf8), !output.isEmpty {
                print("PYTHON ERROR:", output)
            }
        }
        
        do {
            try process.run()
            self.serverProcess = process
            
            DispatchQueue.main.async {
                self.isServerRunning = true
                self.serverStatus = "Server Running"
            }
            
            process.terminationHandler = { _ in
                DispatchQueue.main.async {
                    self.isServerRunning = false
                    self.serverStatus = "Server Stopped"
                }
            }
        } catch {
            print("❌ Failed to start server: \(error)")
        }
    }

    func stopServer() {
        serverProcess?.terminate()
        serverProcess = nil
        
        // 🧹 Ensure port cleanup on stop
        let cleanup = Process()
        cleanup.executableURL = URL(fileURLWithPath: "/bin/bash")
        cleanup.arguments = ["-c", "kill -9 $(lsof -ti :8000) 2>/dev/null"]
        try? cleanup.run()
        
        DispatchQueue.main.async {
            self.isServerRunning = false
            self.serverStatus = "Server Stopped"
        }
    }

    func sendFile(atPath path: String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: pythonPath)
        if let deviceIP, !deviceIP.isEmpty {
            process.arguments = ["\(scriptPath)", "send", path, deviceIP]
        } else {
            process.arguments = ["\(scriptPath)", "send", path]
        }
        process.currentDirectoryURL = URL(fileURLWithPath: workingDirectory)
        process.environment = ProcessInfo.processInfo.environment
        
        do {
            try process.run()
            print("✅ File send triggered for: \(path)")
        } catch {
            print("❌ Failed to trigger send: \(error)")
        }
    }
}
