import SwiftUI
import Combine

class AppDelegate: NSObject, NSApplicationDelegate {
    var statusItem: NSStatusItem!
    let serverManager = ServerManager()
    var previewWindow: NSWindow?
    private var cancellables = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Create the status item in the menu bar
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        
        if let button = statusItem.button {
            button.title = "🔴 QuickDrop" // Initial status
        }
        
        // Reactive UI Binding (Requirement)
        serverManager.$serverStatus
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.updateStatusUI()
            }
            .store(in: &cancellables)
            
        serverManager.$pendingRequest
            .receive(on: RunLoop.main)
            .sink { [weak self] request in
                if let request = request {
                    self?.showIncomingRequestPanel(for: request)
                }
            }
            .store(in: &cancellables)
        
        constructMenu()
    }

    func constructMenu() {
        let menu = NSMenu()

        // Status Item (Informational - Requirement)
        let statusText = "Status: \(serverManager.serverStatus)"
        menu.addItem(NSMenuItem(title: statusText, action: nil, keyEquivalent: ""))
        
        menu.addItem(NSMenuItem.separator())

        // Start Server Item
        let startItem = NSMenuItem(title: "Start Server", action: #selector(startServerAction), keyEquivalent: "")
        startItem.target = self
        menu.addItem(startItem)

        // Stop Server Item
        let stopItem = NSMenuItem(title: "Stop Server", action: #selector(stopServerAction), keyEquivalent: "")
        stopItem.target = self
        menu.addItem(stopItem)

        menu.addItem(NSMenuItem.separator())

        // Send Files Item
        let sendItem = NSMenuItem(title: "Send Files to Device...", action: #selector(sendFilesAction), keyEquivalent: "s")
        sendItem.target = self
        menu.addItem(sendItem)

        menu.addItem(NSMenuItem.separator())

        // Quit Item
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))

        statusItem.menu = menu
    }

    @objc func startServerAction() {
        print("🟢 Start Server Clicked")
        
        // Modal feedback (Requirement)
        let alert = NSAlert()
        alert.messageText = "Starting Server..."
        alert.informativeText = "Check logs at /tmp/quickdrop.log"
        alert.alertStyle = .informational
        alert.addButton(withTitle: "OK")
        alert.runModal()
        
        print("Triggering startServer()")
        serverManager.startServer()
    }

    @objc func stopServerAction() {
        print("🔴 Stop Server Clicked")
        serverManager.stopServer()
    }

    @objc func sendFilesAction() {
        print("📤 Send Files Clicked")
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.message = "Choose one or more files to send"
        panel.prompt = "Select"
        
        if panel.runModal() == .OK {
            let urls = panel.urls
            if !urls.isEmpty {
                showPreviewPanel(for: urls)
            }
        }
    }

    func showPreviewPanel(for urls: [URL]) {
        let previewView = FilePreviewView(
            manager: serverManager,
            urls: urls,
            onSend: { [weak self] in
                print("🚀 Confirmed Send for \(urls.count) files")
                let paths = urls.map { $0.path }
                self?.serverManager.sendFiles(atPaths: paths)
                self?.previewWindow?.close()
                self?.previewWindow = nil
            },
            onCancel: { [weak self] in
                print("🚫 Cancelled Send")
                self?.previewWindow?.close()
                self?.previewWindow = nil
            }
        )

        let hostingController = NSHostingController(rootView: previewView)
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 340, height: 450),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered, defer: false)
        
        window.center()
        window.contentViewController = hostingController
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.isReleasedWhenClosed = false
        window.level = .floating // Keep on top
        
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        
        self.previewWindow = window
    }

    func showIncomingRequestPanel(for request: IncomingRequest) {
        let incomingView = IncomingTransferView(
            request: request,
            onAccept: { [weak self] in
                self?.serverManager.respondToRequest(accepted: true)
                self?.previewWindow?.close()
                self?.previewWindow = nil
            },
            onDecline: { [weak self] in
                self?.serverManager.respondToRequest(accepted: false)
                self?.previewWindow?.close()
                self?.previewWindow = nil
            }
        )

        let hostingController = NSHostingController(rootView: incomingView)
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 340, height: 500),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered, defer: false)
        
        window.center()
        window.contentViewController = hostingController
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.isReleasedWhenClosed = false
        window.hasShadow = true
        window.backgroundColor = .clear // Allow VisualEffectView to show
        window.level = .floating // Stay on top
        
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        
        self.previewWindow = window
    }

    func updateStatusUI() {
        DispatchQueue.main.async {
            // Update Menu Bar Title with color indicators (Requirement)
            if let button = self.statusItem.button {
                let status = self.serverManager.serverStatus
                
                if status == "Running" {
                    button.title = "🟢 QuickDrop"
                } else if status == "Starting..." {
                    button.title = "🟡 QuickDrop"
                } else if status == "Stopping..." {
                    button.title = "🟠 QuickDrop"
                } else {
                    button.title = "🔴 QuickDrop"
                }
            }
            
            // Reconstruct menu to update the "Status" text item
            self.constructMenu()
        }
    }
}

// --- Main Entry Point ---
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory) // Hidden from Dock, Menu bar only
app.run()