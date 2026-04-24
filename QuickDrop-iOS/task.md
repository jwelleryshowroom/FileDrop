# Task.md Plan: Replicate Android QuickDrop into iOS/iPadOS Without Feature Loss

## Summary
Build the iOS/iPadOS app in `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS` to reach behavioral parity with the Android app in `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop`, while preserving iOS-native constraints and UX. The result must support Android <-> iPhone/iPad and macOS <-> iPhone/iPad local transfers over the existing QuickDrop protocol: mDNS discovery on `_http._tcp` and HTTP on port `8000` with `POST /request-transfer` and `POST /upload`.

## Non-Negotiable Rules
- Treat Android as the source of truth for transfer behavior, protocol shape, and user-visible states.
- Do not redesign the protocol unless the existing Android/macOS implementation cannot interoperate with iOS.
- Do not replace the existing app with a totally different architecture or move code outside `QuickDrop-iOS/`.
- Do not break Android/macOS interoperability while improving the iOS implementation.
- Do not remove or ignore existing iOS files unless replacing them with a clearer single-source-of-truth structure in the same folder.
- Prefer incremental replacement over full rewrites.
- Assume receive-on-background is not supported for v1 on iPhone/iPad; the app must be foregrounded to receive.
- Sending large files must not require loading the entire multipart payload into RAM.
- The final iOS app must preserve these behaviors:
  - Bonjour discovery of nearby QuickDrop devices
  - manual scan and continuous visible device list
  - incoming transfer accept/decline handshake
  - multipart upload send flow
  - receive/save files locally
  - transfer progress, speed, ETA, and cancellation
  - multi-file transfer support
  - Android <-> iOS transfer compatibility
  - Mac <-> iOS transfer compatibility

## Canonical Source Files To Mirror
Use these Android files as the product reference:
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/QuickDropViewModel.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/QuickDropScreen.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/QuickDropTransfer.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/QuickDropReceiverServer.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/QuickDropDiscovery.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/QuickDropServiceAdvertiser.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/TransferStatus.kt`
- `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop/FileHelper.kt`

## Required Structural Decision
Standardize the implementation on the SwiftPM iOS app:
- Keep `QuickDrop-iOS/QuickDrop.swiftpm` as the active app target.
- Move or mirror the current source organization under `QuickDrop.swiftpm/Sources/`.
- All new implementation work should happen in `QuickDrop.swiftpm/Sources/`.

## Implementation Tasks

### 1. App Configuration and Capabilities
Update `QuickDrop.swiftpm/Package.swift` so the app target has all required capabilities and permissions.
- Ensure iOS platform target is 15.0+.
- Add `GCDWebServer` or equivalent as a dependency for robust HTTP/Multipart handling.
- Ensure `NSBonjourServices` includes `_http._tcp`.
- Add required usage descriptions for Local Network, Photos (add-only/full), and Files.

### 2. Consolidate App State Into a Real View Model
Create `QuickDropViewModel.swift` in `QuickDrop.swiftpm/Sources/`.
- Own discovery state, nearby device list, and transfer states.
- Manage lifecycle of `QuickDropAdvertiser` and `QuickDropServer`.
- Implement scanning logic (idle, scanning, found, selected).

### 3. Discovery and Advertising Parity
Harden `QuickDropDiscovery.swift` and `QuickDropAdvertiser.swift`.
- `Advertiser`: Sanitize device name for service name `QuickDrop-<Name>`.
- `Discovery`: Robustly resolve IP addresses and handle service removal/addition without duplicates.

### 4. Replace the Current iOS Receiver Server
The existing `QuickDropServer.swift` based on raw `NWListener` is insufficient.
- Use `GCDWebServer` to implement `POST /request-transfer` and `POST /upload`.
- `request-transfer`: Parse JSON, surface UI decision, return `{ "accepted": bool }`.
- `upload`: Handle streaming multipart uploads of one or more files.
- Update `TransferStatus` during reception.

### 5. Sending Pipeline Parity
Rebuild `QuickDropTransfer.swift`.
- Implement Handshake: `POST /request-transfer` with standard JSON payload (id, fileName, fileSize, deviceName, fileType, count).
- Implement Upload: Streaming multipart `POST /upload` with headers `X-Device-Name` and `X-File-Size`.
- Support cancellation and progress callbacks.

### 6. Transfer State Model
Expand `TransferStatus.swift`.
- Track isUploading, progress, speed, ETA, fileName, fileSize, and incoming requests.
- Use a single source of truth for the UI.

### 7. File Picking and Metadata
- Use `fileImporter` or `PHPickerViewController` for selection.
- Extract accurate metadata (name, size, type).
- Map file extensions to type: image, video, pdf, other.

### 8. Receive-Side File Saving Strategy
- Save files to `Documents/QuickDrop/` directory.
- Implement collision-safe naming (auto-rename).
- Ensure atomic saves (don't show partial files as complete).

### 9. UI Parity in SwiftUI
Refactor `ContentView.swift`.
- Implement Splash/Loading state.
- Implement Grid/List of nearby devices with scanning animation.
- Implement Incoming Request modal with file info.
- Implement Bottom Progress Card for active transfers.
- Implement Completion Summary.

### 10. Lifecycle Handling
- Start/stop services on app foreground/background if appropriate.
- Ensure clean teardown on termination.

## Safe Execution Order
1.  **Prep**: Inspect `QuickDrop.swiftpm/Sources/` and update `Package.swift`.
2.  **State**: Introduce `QuickDropViewModel.swift`.
3.  **Core Network**: Harden `QuickDropAdvertiser` and `QuickDropDiscovery`.
4.  **Receiver**: Implement real HTTP server using `GCDWebServer` in `QuickDropServer.swift`.
5.  **Sender**: Implement streaming multipart upload in `QuickDropTransfer.swift`.
6.  **Wiring**: Connect `TransferStatus` to the new server and sender.
7.  **UI**: Refactor `ContentView.swift` to use the new ViewModel.
8.  **File Ops**: Implement robust save/collision logic.
9.  **Validation**: Test against Android and Mac versions.

## Acceptance Criteria
- iOS app discovers Android/macOS peers.
- iOS app sends/receives files (single and multiple) to/from Android/macOS.
- Large files don't crash the app (streaming, not in-memory).
- UI shows progress, speed, ETA, and allows cancellation.
- Standardized on `QuickDrop.swiftpm`.
