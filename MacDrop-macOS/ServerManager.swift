import Foundation
import Combine
import AppKit
import Network

class ServerManager: ObservableObject {
    static let shared = ServerManager()
    @Published var isServerRunning = false
    @Published var serverStatus = "Stopped"
    
    // Progress Tracking
    @Published var isTransferring = false
    @Published var transferResult: String? = nil
    @Published var transferProgress: Double = 0.0
    @Published var transferSpeed: String = ""
    @Published var transferEta: String = ""
    @Published var queuedCount = 0
    @Published var isNetworkAvailable = true
    @Published var isWaitingForNetwork = false
    @Published var retryCount = 0
    @Published var pendingRequest: IncomingRequest? = nil
    
    private var sendQueue: [[String]] = []
    private let networkMonitor = NWPathMonitor()
    private var healthCheckTimer: Timer?
    
    // --- Handshake Buffering (Transport Layer Hardening) ---
    private var incomingBuffer = ""
    private var isBuffering = false
    
    init() {
        startHealthMonitor()
        
        networkMonitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isNetworkAvailable = path.status == .satisfied
                if path.status != .satisfied {
                    self?.serverStatus = "Network Offline"
                    print("📡 Network status: Offline")
                } else {
                    if self?.isServerRunning == true {
                        self?.serverStatus = "Running"
                    }
                    print("📡 Network status: Online")
                }
            }
        }
        networkMonitor.start(queue: DispatchQueue.global(qos: .background))
    }
    
    private var serverProcess: Process?
    
    // Updated Paths for the modular Python backend
    private let pythonPath = "/usr/bin/python3"
    private let workingDirectory = "/Users/ankitkumarsah/Projects/FileDrop"
    private let mainScript = "/Users/ankitkumarsah/Projects/FileDrop/main.py"

    func startServer() {
        guard !isServerRunning else { return }
        
        let projectURL = URL(fileURLWithPath: workingDirectory)
        let logPath = "/tmp/quickdrop.log"
        
        // 🚀 Launching Python with Debug Logs
        print("🚀 Launching Python with:")
        print("Working Dir: \(workingDirectory)")
        print("Script: \(mainScript)")
        print("📄 Logs: \(logPath)")

        // 🧹 Cleanup: Ensure port 8000 is free before starting
        let cleanup = Process()
        cleanup.executableURL = URL(fileURLWithPath: "/bin/bash")
        cleanup.arguments = ["-c", "kill -9 $(lsof -ti :8000) 2>/dev/null"]
        try? cleanup.run()
        cleanup.waitUntilExit()
        
        // 📝 Setup File Logging
        FileManager.default.createFile(atPath: logPath, contents: nil)
        let logFile = FileHandle(forWritingAtPath: logPath)
        
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = [mainScript]
        
        process.currentDirectoryURL = projectURL
        var env = ProcessInfo.processInfo.environment
        env["PYTHONUNBUFFERED"] = "1"
        process.environment = env
        
        // Capture output and errors for logging AND file writing
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        
            let logHandle = pipe.fileHandleForReading
            
            do {
                try process.run()
                self.serverProcess = process // Global reference
                
                // --- CONTINUOUS BACKGROUND READ LOOP ---
                DispatchQueue.global(qos: .background).async {
                    while true {
                        let data = logHandle.availableData
                        if data.isEmpty { break }
                        
                        // Write to log file
                        try? logFile?.write(contentsOf: data)
                        
                        if let output = String(data: data, encoding: .utf8) {
                            let lines = output.components(separatedBy: .newlines)
                            for line in lines {
                                // --- DELIMITED PROTOCOL PARSING ---
                                if line.contains("INCOMING_REQUEST_START") {
                                    // 🔥 Force reset any corrupted/incomplete buffer
                                    if self.isBuffering {
                                        print("⚠️ Buffer reset due to unexpected START")
                                    }
                                    
                                    self.incomingBuffer = ""
                                    self.isBuffering = true
                                    continue
                                }
                                
                                if line.contains("INCOMING_REQUEST_END") {
                                    self.isBuffering = false
                                    
                                    let finalJSON = self.incomingBuffer.trimmingCharacters(in: .whitespacesAndNewlines)
                                    
                                    // 🚫 Guard against empty or corrupted buffer
                                    guard finalJSON.hasPrefix("{"), finalJSON.hasSuffix("}") else {
                                        print("❌ Invalid JSON boundaries in buffer. Skipping parse.")
                                        self.incomingBuffer = ""
                                        continue
                                    }

                                    if let jsonData = finalJSON.data(using: .utf8) {
                                        do {
                                            let request = try JSONDecoder().decode(IncomingRequest.self, from: jsonData)
                                            DispatchQueue.main.async {
                                                // Force UI refresh every time to avoid state lock
                                                self.pendingRequest = nil
                                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                                                    self.pendingRequest = request
                                                    print("📣 UI TRIGGERED (SAFE)")
                                                }
                                            }
                                            print("✅ Parsed handshake (safe mode)")
                                        } catch {
                                            print("❌ JSON decoding failed: \(error)")
                                            print("🔍 BUFFER CONTENT:", finalJSON)
                                        }
                                    }
                                    self.incomingBuffer = ""
                                    continue
                                }
                                
                                if self.isBuffering && !line.trimmingCharacters(in: .whitespaces).isEmpty {
                                    self.incomingBuffer += line + "\n"
                                }
                            }
                            print("PYTHON:", output.trimmingCharacters(in: .whitespacesAndNewlines))
                        }
                    }
                }
                
                DispatchQueue.main.async {
                    self.serverStatus = "Starting..."
                }
                print("🚀 Process launched, verifying...")
                
                // Immediate check after 1.2s
                DispatchQueue.global().asyncAfter(deadline: .now() + 1.2) {
                    self.checkServerHealth()
                }
                
                process.terminationHandler = { _ in
                    try? logFile?.close()
                    DispatchQueue.main.async {
                        if self.serverStatus == "Starting..." {
                            print("❌ Server failed to start")
                            self.serverStatus = "Server Failed"
                        } else if self.serverStatus != "Stopping..." {
                            self.serverStatus = "Stopped"
                        }
                        self.isServerRunning = false
                        self.serverProcess = nil
                    }
                }
            } catch {
                print("❌ Failed to start server: \(error)")
            }
    }

    func stopServer() {
        DispatchQueue.main.async {
            self.serverStatus = "Stopping..."
            self.isServerRunning = false
        }

        // Kill tracked process (direct Python handle)
        serverProcess?.terminate()
        serverProcess = nil

        // 🔥 Force kill Python on port 8000 (Safety Cleanup)
        let cleanup = Process()
        cleanup.executableURL = URL(fileURLWithPath: "/bin/bash")
        cleanup.arguments = ["-c", "kill -9 $(lsof -ti :8000) 2>/dev/null"]
        try? cleanup.run()
        cleanup.waitUntilExit()

        print("🛑 Force killed Python server")

        // Recheck status
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) {
            self.checkServerHealth()
        }
    }
    
    private func startHealthMonitor() {
        healthCheckTimer?.invalidate()
        healthCheckTimer = Timer.scheduledTimer(withTimeInterval: 4.0, repeats: true) { [weak self] _ in
            self?.checkServerHealth()
        }
    }
    
    private func checkServerHealth() {
        let check = Process()
        check.executableURL = URL(fileURLWithPath: "/bin/bash")
        check.arguments = ["-c", "lsof -i :8000"]
        
        let pipe = Pipe()
        check.standardOutput = pipe
        
        do {
            try check.run()
            check.waitUntilExit()
            
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let output = String(data: data, encoding: .utf8) ?? ""
            let isListening = output.contains("LISTEN")
            
            DispatchQueue.main.async {
                if isListening {
                    self.isServerRunning = true
                    self.serverStatus = "Running"
                } else {
                    if self.serverStatus != "Starting..." {
                        self.isServerRunning = false
                        self.serverStatus = "Stopped"
                        self.serverProcess = nil
                    }
                }
            }
        } catch {
            print("❌ Health check failed: \(error)")
        }
    }

    func sendFiles(atPaths paths: [String]) {
        guard !paths.isEmpty else { return }
        
        sendQueue.append(paths)
        updateQueuedCount()
        
        if !isTransferring {
            processNextInQueue()
        }
    }

    private func updateQueuedCount() {
        DispatchQueue.main.async {
            self.queuedCount = self.sendQueue.count
        }
    }

    private func processNextInQueue() {
        guard !sendQueue.isEmpty else { 
            DispatchQueue.main.async {
                self.isTransferring = false 
            }
            return 
        }
        
        let paths = sendQueue.removeFirst()
        updateQueuedCount()
        
        print("📤 Processing Queue: Sending \(paths.count) file(s)...")
        
        let process = Process()
        process.executableURL = URL(fileURLWithPath: pythonPath)
        
        var arguments = [mainScript, "send"]
        arguments.append(contentsOf: paths)
        
        process.arguments = arguments
        process.currentDirectoryURL = URL(fileURLWithPath: workingDirectory)
        var env = ProcessInfo.processInfo.environment
        env["PYTHONUNBUFFERED"] = "1"
        process.environment = env
        
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        
        let sendHandle = pipe.fileHandleForReading
        
        do {
            print("🚀 Executing Send: \(pythonPath) \(arguments.joined(separator: " "))")
            DispatchQueue.main.async {
                self.transferResult = nil
                self.isTransferring = true
                self.transferProgress = 0
                self.retryCount = 0
            }
            try process.run()
            
            // --- CONTINUOUS BACKGROUND READ LOOP (SEND) ---
            DispatchQueue.global(qos: .background).async {
                while true {
                    let data = sendHandle.availableData
                    if data.isEmpty { break }
                    
                    if let output = String(data: data, encoding: .utf8) {
                        let lines = output.components(separatedBy: .newlines)
                        for line in lines {
                            if line.starts(with: "PROGRESS:") {
                                let value = line.replacingOccurrences(of: "PROGRESS:", with: "")
                                DispatchQueue.main.async {
                                    self.transferProgress = Double(value.trimmingCharacters(in: .whitespaces)) ?? 0
                                }
                            } else if line.starts(with: "SPEED:") {
                                let value = line.replacingOccurrences(of: "SPEED:", with: "")
                                DispatchQueue.main.async {
                                    self.transferSpeed = value.trimmingCharacters(in: .whitespaces)
                                }
                            } else if line.starts(with: "ETA:") {
                                let value = line.replacingOccurrences(of: "ETA:", with: "")
                                DispatchQueue.main.async {
                                    self.transferEta = value.trimmingCharacters(in: .whitespaces)
                                }
                            } else if line.contains("RETRYING: Waiting for Network") {
                                DispatchQueue.main.async {
                                    self.isWaitingForNetwork = true
                                    self.retryCount += 1
                                }
                            } else if line.contains("🤝 Handshaking") || line.contains("🚀 Transfer accepted") {
                                // Clear waiting state on new attempt/success
                                DispatchQueue.main.async {
                                    self.isWaitingForNetwork = false
                                }
                            } else if line.contains("Successfully sent") {
                                DispatchQueue.main.async {
                                    self.transferResult = "success"
                                    self.isTransferring = false
                                }
                            } else if line.contains("Transfer declined") {
                                DispatchQueue.main.async {
                                    self.transferResult = "declined"
                                    self.isTransferring = false
                                }
                            } else if line.contains("Error during transfer") ||
                                        line.contains("Max retries reached") {
                                DispatchQueue.main.async {
                                    print("❌ Transfer failed detected from Python logs")
                                    self.transferResult = "failed"
                                    self.isTransferring = false
                                    self.isWaitingForNetwork = false
                                }
                            }
                        }
                        print("PYTHON SEND:", output.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                }
            }
            
            process.terminationHandler = { p in
                if p.terminationStatus == 0 {
                    print("✅ Send batch completed successfully.")
                } else {
                    print("❌ Send batch failed with status \(p.terminationStatus).")
                }
                
                // Advance to next in queue
                self.processNextInQueue()
            }
        } catch {
            print("❌ Failed to trigger send: \(error)")
            // Advance even on error
            self.processNextInQueue()
        }
    }

    func respondToRequest(accepted: Bool) {
        guard let pending = pendingRequest else { return }
        guard let url = URL(string: "http://localhost:8000/decide-transfer?accepted=\(accepted)&id=\(pending.id)") else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        
        URLSession.shared.dataTask(with: request) { _, _, _ in
            DispatchQueue.main.async {
                self.pendingRequest = nil
            }
        }.resume()
    }
}
