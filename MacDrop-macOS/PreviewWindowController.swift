import AppKit
import SwiftUI

// ✅ Production Subclass (v2.4.2): NSPanel is designed for utility popups
// ✅ Production Subclass (v2.4.2): NSPanel is designed for utility popups
class QuickDropWindow: NSPanel {
    override var canBecomeKey: Bool { return true }
    override var canBecomeMain: Bool { return true }
    
    // 🔥 CLICK DETECTION (v2.2.2)
    override func mouseDown(with event: NSEvent) {
        print("🖱 [DEBUG] PANEL CLICK RECEIVED")
        super.mouseDown(with: event)
    }
    
    // 🔥 CLOSE HOOK (v2.2.4)
    override func performClose(_ sender: Any?) {
        print("🔴 [DEBUG] performClose CALLED")
        super.performClose(sender)
    }
}

class PreviewWindowController: NSObject, NSWindowDelegate {

    // ✅ Singleton access point
    static var shared: PreviewWindowController = {
        return PreviewWindowController()
    }()

    private var window: NSWindow?
    private var strongWindowRef: NSWindow? // ✅ Retention against deallocation

    private override init() {
        super.init()
        
        // 🔥 LOCAL CLICK MONITOR (v2.2.3) - Does NOT hijack events
        NSEvent.addLocalMonitorForEvents(matching: .leftMouseDown) { event in
            print("🖱 [DEBUG] LOCAL CLICK DETECTED (Event passing through responder chain)")
            return event
        }
    }

    func show(urls: [URL], manager: ServerManager) {
        print("🪟 [DEBUG] Creating preview window on main thread: \(Thread.isMainThread)")
        
        DispatchQueue.main.async { [weak self] in
            self?._showInternal(urls: urls, manager: manager)
        }
    }

    private func _showInternal(urls: [URL], manager: ServerManager) {
        guard NSApp.isRunning else { return }

        // ✅ Step 1: Force immediate cleanup of any existing window lifecycle
        self.close()

        let view = FilePreviewView(
            manager: manager,
            urls: urls,
            onSend: { [weak self] in
                self?.handleSend(urls: urls, manager: manager)
            },
            onCancel: { [weak self] in
                self?.close()
            }
        )

        showWindow(contentView: view, width: 340, height: 380)
    }

    func showIncoming(request: IncomingRequest, manager: ServerManager) {
        print("🪟 [DEBUG] Creating incoming window on main thread: \(Thread.isMainThread)")
        
        DispatchQueue.main.async { [weak self] in
            self?._showIncomingInternal(request: request, manager: manager)
        }
    }

    private func _showIncomingInternal(request: IncomingRequest, manager: ServerManager) {
        guard NSApp.isRunning else { return }

        // ✅ Step 1: Force immediate cleanup
        self.close()

        let view = IncomingTransferView(
            request: request,
            onAccept: { [weak self] in
                manager.respondToRequest(accepted: true)
                self?.close()
            },
            onDecline: { [weak self] in
                manager.respondToRequest(accepted: false)
                self?.close()
            }
        )

        showWindow(contentView: view, width: 340, height: 440)
    }

    func showDropZone(at location: NSPoint? = nil, onDrop: @escaping ([URL]) -> Void) {
        print("🪟 [DEBUG] Creating drop zone window on main thread: \(Thread.isMainThread)")
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // ✅ Step 1: Force immediate cleanup
            self.close()

            let view = DropZoneView { urls in
                self.close()
                onDrop(urls)
            }

            self.showWindow(contentView: view, width: 280, height: 160, at: location)
        }
    }

    private func showWindow<V: View>(contentView: V, width: CGFloat, height: CGFloat, at location: NSPoint? = nil) {
        print("\n================ 🧪 APP STATE BEFORE WINDOW ================")
        print("🧪 NSApp.isActive: \(NSApp.isActive)")
        print("🧪 Activation Policy: \(NSApp.activationPolicy().rawValue)")
        print("🧪 Frontmost App: \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "nil")")
        print("🧪 Key Window: \(String(describing: NSApp.keyWindow))")
        print("🧪 Main Window: \(String(describing: NSApp.mainWindow))")
        print("===========================================================\n")

        // 🔥 FORCE ACTIVATE + VERIFY (v2.2.2)
        NSApp.setActivationPolicy(.regular)
        NSRunningApplication.current.activate(options: [.activateIgnoringOtherApps])

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            print("\n================ 🧪 AFTER ACTIVATION =======================")
            print("🧪 NSApp.isActive: \(NSApp.isActive)")
            print("🧪 Frontmost App: \(NSWorkspace.shared.frontmostApplication?.localizedName ?? "nil")")
            print("===========================================================\n")
        }

        let hosting = NSHostingController(rootView: contentView)

        // ✅ Create panel
        let window = QuickDropWindow(
            contentRect: NSRect(x: 0, y: 0, width: width, height: height),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )

        print("\n================ 🪟 WINDOW CREATED ========================")
        print("🪟 window: \(window)")
        print("🪟 level: \(window.level.rawValue)")
        print("🪟 isFloatingPanel: \(window.isFloatingPanel)")
        print("🪟 canBecomeKey: \(window.canBecomeKey)")
        print("🪟 canBecomeMain: \(window.canBecomeMain)")
        print("🧪 isFloatingPanel (Raw): \(window.isFloatingPanel)") // Added as requested
        print("🧪 styleMask: \(window.styleMask.rawValue)") // Added as requested
        print("===========================================================\n")

        window.contentViewController = hosting
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        
        window.isFloatingPanel = false // 🔥 STABILIZE FOCUS (v2.2.5)
        window.level = .popUpMenu // 🔥 High system priority (v2.2.1)
        window.hidesOnDeactivate = false
        window.isReleasedWhenClosed = false
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary] // ✅ Show over everything
        
        window.delegate = self
        window.hasShadow = true

        self.window = window
        self.strongWindowRef = window 

        if let loc = location {
            // Center the window at the provided location (e.g., mouse cursor)
            let frame = NSRect(x: loc.x - width/2, y: loc.y - height/2, width: width, height: height)
            window.setFrame(frame, display: true)
        } else {
            window.center()
        }
        
        // ✅ Hook Native Close Button (v2.2.4)
        if let closeButton = window.standardWindowButton(.closeButton) {
            closeButton.target = self
            closeButton.action = #selector(handleCloseButton)
        }
        
        // ✅ Show using standard API (v2.2.3)
        window.makeKeyAndOrderFront(nil)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            print("\n================ 🪟 WINDOW STATE AFTER SHOW ===============")
            print("🪟 isVisible: \(window.isVisible)")
            print("🪟 isKeyWindow: \(window.isKeyWindow)")
            print("🪟 isMainWindow: \(window.isMainWindow)")
            print("🪟 NSApp.keyWindow: \(String(describing: NSApp.keyWindow))")
            print("🪟 NSApp.mainWindow: \(String(describing: NSApp.mainWindow))")
            print("🪟 orderedWindows: \(NSApp.orderedWindows.count) windows tracked")
            print("===========================================================\n")
        }
    }

    func close() {
        // 🔥 If a transfer is active, cancel it! (Requirement Fix)
        if ServerManager.shared.isTransferring {
            ServerManager.shared.cancelTransfer()
        }

        // 🔥 Fix: Synchronous UI hidden + nil-ing is safer for the event loop
        if let win = window {
            win.orderOut(nil) // Immediate removal from screen/focus
        }
        window = nil
        strongWindowRef = nil
        
        // 🔥 Revert to accessory mode when UI is gone (v2.2.0)
        NSApp.setActivationPolicy(.accessory)
    }

    @objc private func handleCloseButton() {
        print("🔴 [DEBUG] Close button clicked")
        close()
    }

    private func handleSend(urls: [URL], manager: ServerManager) {
        let paths = urls.map { $0.path }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            manager.sendFiles(atPaths: paths)
        }
    }

    func windowDidBecomeKey(_ notification: Notification) {
        print("🟢 [DEBUG] Window DID become KEY")
    }

    func windowDidResignKey(_ notification: Notification) {
        print("🟡 [DEBUG] Window DID resign KEY")
    }

    func windowWillClose(_ notification: Notification) {
        print("🔴 [DEBUG] Window WILL close")
        // Clean up references
        if window != nil {
            window = nil
            strongWindowRef = nil
        }
    }
}
