import AppKit
import SwiftUI

// ✅ Production Subclass (v2.4.2): NSPanel is designed for utility popups
class QuickDropWindow: NSPanel {
    override var canBecomeKey: Bool { return true }
    override var canBecomeMain: Bool { return true }
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

    func showDropZone(onDrop: @escaping ([URL]) -> Void) {
        print("🪟 [DEBUG] Creating drop zone window on main thread: \(Thread.isMainThread)")
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // ✅ Step 1: Force immediate cleanup
            self.close()

            let view = DropZoneView { urls in
                self.close()
                onDrop(urls)
            }

            self.showWindow(contentView: view, width: 280, height: 160)
        }
    }

    private func showWindow<V: View>(contentView: V, width: CGFloat, height: CGFloat) {
        let hosting = NSHostingController(rootView: contentView)

        // ✅ Step 1: Transition to NSPanel for floating stability (v2.4.2)
        let window = QuickDropWindow(
            contentRect: NSRect(x: 0, y: 0, width: width, height: height),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )

        window.contentViewController = hosting
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.isOpaque = false
        window.backgroundColor = .clear
        
        // ✅ Step 2: Configure NSPanel specific behaviors
        window.isFloatingPanel = true
        window.level = .floating
        window.becomesKeyOnlyIfNeeded = false // We WANT it to become key for interaction
        window.isReleasedWhenClosed = false
        
        window.delegate = self
        window.hasShadow = true

        self.window = window
        self.strongWindowRef = window 

        NSApp.activate(ignoringOtherApps: true)
        window.center()
        window.makeKeyAndOrderFront(nil)
        
        print("🪟 [DEBUG] Panel Created - Is Key: \(window.isKeyWindow), Is Main: \(window.isMainWindow)")
    }

    func close() {
        // 🔥 Fix: Synchronous UI hidden + nil-ing is safer for the event loop
        if let win = window {
            win.orderOut(nil) // Immediate removal from screen/focus
        }
        window = nil
        strongWindowRef = nil
    }

    private func handleSend(urls: [URL], manager: ServerManager) {
        let paths = urls.map { $0.path }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            manager.sendFiles(atPaths: paths)
        }
    }

    func windowWillClose(_ notification: Notification) {
        // This is still called if user closes via red button, 
        // we clean up references just in case.
        if window != nil {
            window = nil
            strongWindowRef = nil
        }
    }
}
