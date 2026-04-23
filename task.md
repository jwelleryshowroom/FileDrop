# Fix Plan: Global Shake-to-Drop Gesture

## Root Cause Analysis
The logs clearly show `🖱 [SHAKE] Accessibility Trusted: false`. 
When this is `false`, macOS completely blocks `NSEvent.addGlobalMonitorForEvents`, meaning the app receives zero mouse events.

There are two distinct issues preventing the shake gesture from working:

### 1. The Execution Context (Permission Issue)
You ran the binary directly from the terminal (`./QuickDrop`). In macOS, when you execute a raw binary from a terminal, the Accessibility permission request is attributed to the **Terminal App** (or VSCode/Cursor), not your QuickDrop app. If the terminal lacks permission, it silently fails.
**Fix**: The app must be launched as a bundle (`open QuickDrop.app`) so macOS registers it correctly in the Privacy & Security settings.

### 2. The Drag Session Blackhole (System Limitation)
Even with Accessibility permissions granted, `NSEvent.addGlobalMonitorForEvents` is notoriously unreliable during a system-wide file drag. When you start dragging a file in Finder, macOS enters a secure "Drag Loop" that often swallows high-level `NSEvent` broadcasts to prevent interference.
**Fix**: We need to drop down a level and use a **`CGEventTap`** (Core Graphics Event Tap). This API sits closer to the hardware and allows us to passively monitor mouse movements *even when Finder has locked the system into a drag loop*.

---

## Detailed Implementation Plan (For Gemini)

### Step 1: Upgrade to `CGEventTap`
We will rewrite `ShakeGestureManager.swift` to replace `NSEvent` monitors with a passive `CGEventTap`.
*   **Tap Location**: `CGEventTapLocation.cgSessionEventTap`
*   **Tap Options**: `CGEventTapOptions.listenOnly` (passive monitoring, no event modification)
*   **Events to Mask**: `CGEventType.leftMouseDragged` and `CGEventType.leftMouseUp`
*   **RunLoop**: We must add the `CFMachPort` run loop source to the main `RunLoop` so the callback fires asynchronously.

### Step 2: Handle Accessibility Properly
`CGEventTap` strictly requires Accessibility permissions. 
*   We will enhance the permission check. If `AXIsProcessTrusted()` is false, we will proactively alert the user or log a very clear message instructing them to grant access to `QuickDrop.app` in System Settings.

### Step 3: Refine the Shake Math
When the system is bogged down rendering translucent file drag previews, mouse event frequency can drop.
*   We will increase the `timeWindow` slightly (e.g., to 1.2 seconds).
*   We will lower the `shakeThresholdCount` to 3 direction reversals.
*   We will reduce the `minAmplitude` to 20 pixels to make it easier to trigger.

### Step 4: Execution Workflow
*   Delete the current terminal process.
*   Implement the `CGEventTap` in `ShakeGestureManager.swift`.
*   Rebuild the app and bundle it.
*   Provide instructions to launch via `open QuickDrop.app` so the user gets the correct permission prompt.

---
**Next Step**: If you approve this plan, I will rewrite `ShakeGestureManager.swift` to use the robust `CGEventTap` approach and instruct you on how to grant the correct permissions!
