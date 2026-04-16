import SwiftUI
import Combine

class AppDelegate: NSObject, NSApplicationDelegate {
    var statusItem: NSStatusItem!
    var statusMenuItem: NSMenuItem?
    var appMenu: NSMenu?
    let serverManager = ServerManager()
    private var cancellables = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Create the status item in the menu bar
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        
        if let button = statusItem.button {
            button.title = "🔴 QuickDrop"
        }
        
        constructMenu()
        statusItem.menu = appMenu // ✅ Step 2: Native Menu Assignment (Stable)
        
        updateStatusUI()
        
        // --- Setup Pipeline Listeners ---
        serverManager.$serverStatus
            .sink { [weak self] _ in self?.updateStatusUI() }
            .store(in: &cancellables)
            
        serverManager.$pendingRequest
            .receive(on: RunLoop.main)
            .sink { [weak self] request in
                guard let self = self else { return }
                
                // ✅ Prevent duplicate/unsafe trigger storm
                guard let request = request else { return }
                
                DispatchQueue.main.async {
                    self.showIncomingRequestPanel(for: request)
                }
            }
            .store(in: &cancellables)
    }

    func constructMenu() {
        let menu = NSMenu()

        // Status Item (Informational - Requirement)
        let statusItem = NSMenuItem(title: "Status: \(serverManager.serverStatus)", action: nil, keyEquivalent: "")
        self.statusMenuItem = statusItem
        menu.addItem(statusItem)
        
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

        let dropItem = NSMenuItem(title: "Drop Files...", action: #selector(openDropZone), keyEquivalent: "d")
        dropItem.target = self
        menu.addItem(dropItem)

        menu.addItem(NSMenuItem.separator())
        
        // Quit Item
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))

        self.appMenu = menu
    }

    @objc func startServerAction() {
        print("🟢 Start Server Clicked")
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
        PreviewWindowController.shared.show(urls: urls, manager: serverManager)
    }

    @objc func openDropZone() {
        print("📦 Drop Zone Triggered")
        PreviewWindowController.shared.showDropZone { [weak self] urls in
            guard let self = self else { return }
            print("📦 Drop Zone Handoff: \(urls.count) files")
            PreviewWindowController.shared.show(urls: urls, manager: self.serverManager)
        }
    }

    func showIncomingRequestPanel(for request: IncomingRequest) {
        PreviewWindowController.shared.showIncoming(request: request, manager: serverManager)
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
            
            // Update status text item if it exists
            self.statusMenuItem?.title = "Status: \(self.serverManager.serverStatus)"
        }
    }
}

// --- Main Entry Point ---
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory) // Pure menu-bar app (Native AirDrop style)
app.run()