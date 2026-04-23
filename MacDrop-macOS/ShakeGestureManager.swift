import AppKit
import CoreGraphics

class ShakeGestureManager {
    static let shared = ShakeGestureManager()
    
    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    
    private struct MousePoint {
        let location: NSPoint
        let timestamp: Date
    }
    
    private var points: [MousePoint] = []
    private let maxPoints = 25
    private let shakeThresholdCount = 3 // Slightly lower for better detection
    private let timeWindow: TimeInterval = 1.0 // Wider window for slower drag updates
    private let minAmplitude: CGFloat = 25.0 // Slightly more sensitive
    
    private var lastTriggerTime: Date = .distantPast
    private let cooldownPeriod: TimeInterval = 2.0

    private init() {}

    func startMonitoring() {
        print("🖱 [SHAKE] Starting robust CGEventTap monitoring...")
        
        // 1. Check Accessibility permissions
        let options: [String: Any] = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true]
        let isTrusted = AXIsProcessTrustedWithOptions(options as CFDictionary)
        print("🖱 [SHAKE] Accessibility Trusted: \(isTrusted)")
        
        if !isTrusted {
            print("⚠️ [SHAKE] Accessibility NOT trusted. Please grant access in System Settings > Privacy & Security > Accessibility for QuickDrop.app")
            return
        }
        
        // 2. Create a passive CGEventTap
        // We listen for .leftMouseDragged and .leftMouseUp
        let eventMask = (1 << CGEventType.leftMouseDragged.rawValue) | (1 << CGEventType.leftMouseUp.rawValue)
        
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .listenOnly,
            eventsOfInterest: UInt64(eventMask),
            callback: { (proxy, type, event, refcon) -> Unmanaged<CGEvent>? in
                if let refcon = refcon {
                    let manager = Unmanaged<ShakeGestureManager>.fromOpaque(refcon).takeUnretainedValue()
                    manager.handleCGEvent(type, event)
                }
                return Unmanaged.passRetained(event)
            },
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else {
            print("❌ [SHAKE] Failed to create CGEventTap")
            return
        }
        
        self.eventTap = tap
        self.runLoopSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        
        if let source = self.runLoopSource {
            CFRunLoopAddSource(CFRunLoopGetCurrent(), source, .commonModes)
            CGEvent.tapEnable(tap: tap, enable: true)
            print("✅ [SHAKE] CGEventTap Active and Running")
        }
    }

    private func handleCGEvent(_ type: CGEventType, _ event: CGEvent) {
        if type == .leftMouseUp {
            resetTracking()
            return
        }
        
        if type == .leftMouseDragged {
            let location = event.location
            handlePoint(location)
        }
    }

    private func handlePoint(_ location: CGPoint) {
        let now = Date()
        
        // Cooldown check
        if now.timeIntervalSince(lastTriggerTime) < cooldownPeriod {
            return
        }
        
        // Convert CG point to NSPoint (flipped Y)
        let newPoint = MousePoint(location: NSPoint(x: location.x, y: location.y), timestamp: now)
        points.append(newPoint)
        
        // Keep buffer size managed
        if points.count > maxPoints {
            points.removeFirst()
        }
        
        // Filter points within the time window
        let validPoints = points.filter { now.timeIntervalSince($0.timestamp) <= timeWindow }
        
        if validPoints.count < 6 { return }
        
        if detectShake(in: validPoints) {
            triggerDropZone()
        }
    }

    private func detectShake(in validPoints: [MousePoint]) -> Bool {
        var xDirections: [CGFloat] = []
        var yDirections: [CGFloat] = []
        
        for i in 1..<validPoints.count {
            let dx = validPoints[i].location.x - validPoints[i-1].location.x
            let dy = validPoints[i].location.y - validPoints[i-1].location.y
            
            if abs(dx) > 1 { xDirections.append(dx) }
            if abs(dy) > 1 { yDirections.append(dy) }
        }
        
        let xShake = countZeroCrossings(in: xDirections) >= shakeThresholdCount && calculateAmplitude(in: validPoints.map { $0.location.x }) >= minAmplitude
        let yShake = countZeroCrossings(in: yDirections) >= shakeThresholdCount && calculateAmplitude(in: validPoints.map { $0.location.y }) >= minAmplitude
        
        return xShake || yShake
    }

    private func countZeroCrossings(in deltas: [CGFloat]) -> Int {
        guard deltas.count > 1 else { return 0 }
        var crossings = 0
        for i in 1..<deltas.count {
            if (deltas[i] > 0 && deltas[i-1] < 0) || (deltas[i] < 0 && deltas[i-1] > 0) {
                crossings += 1
            }
        }
        return crossings
    }
    
    private func calculateAmplitude(in values: [CGFloat]) -> CGFloat {
        guard let minVal = values.min(), let maxVal = values.max() else { return 0 }
        return maxVal - minVal
    }

    private func resetTracking() {
        points.removeAll()
    }

    private func triggerDropZone() {
        lastTriggerTime = Date()
        print("🚀 [SHAKE] GESTURE DETECTED! Summoning Drop Zone...")
        
        DispatchQueue.main.async {
            // Get current mouse location in Cocoa coordinates
            let mouseLoc = NSEvent.mouseLocation
            PreviewWindowController.shared.showDropZone(at: mouseLoc) { urls in
                print("📦 [SHAKE] Handoff: \(urls.count) files")
                PreviewWindowController.shared.show(urls: urls, manager: ServerManager.shared)
            }
        }
    }
}
