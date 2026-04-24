# Task.md Plan: Replicate Android QuickDrop into iOS/iPadOS Without Feature Loss

## Summary
Build the iOS/iPadOS app in `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS` to reach behavioral parity with the Android app in `/Users/ankitkumarsah/Projects/FileDrop/app/src/main/kotlin/com/ankit/filedrop`, while preserving iOS-native constraints and UX. The result must support Android <-> iPhone/iPad and macOS <-> iPhone/iPad local transfers over the existing QuickDrop protocol: mDNS discovery on `_http._tcp` and HTTP on port `8000` with `POST /request-transfer` and `POST /upload`.

This plan is written for Gemini Flash to execute safely. It is intentionally explicit about what to change, where to change it, what not to break, and what assumptions to keep fixed.

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

Use these current iOS files as the starting point:
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Package.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/QuickDropApp.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/ContentView.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/QuickDropDiscovery.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/QuickDropAdvertiser.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/QuickDropServer.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/QuickDropTransfer.swift`
- `/Users/ankitkumarsah/Projects/FileDrop/QuickDrop-iOS/QuickDrop.swiftpm/Sources/TransferStatus.swift`

Ignore the duplicate non-swiftpm tree unless actively consolidating it. The SwiftPM app should be the single active target.

## Required Structural Decision
Standardize the implementation on the SwiftPM iOS app:
- Keep `QuickDrop-iOS/QuickDrop.swiftpm` as the active app target.
- Move or mirror the current source organization under `QuickDrop.swiftpm/Sources/`.
- Do not maintain two parallel iOS implementations.
- If duplicate files exist in `QuickDrop-iOS/QuickDrop/`, either:
  - delete them only after equivalent functionality exists in `QuickDrop.swiftpm/Sources/`, or
  - leave them untouched but stop using them.
- All new implementation work should happen in `QuickDrop.swiftpm/Sources/`.

## Implementation Changes

### 1. App Configuration and Capabilities
Update `QuickDrop.swiftpm/Package.swift` so the app target has all required capabilities and permissions.

Required changes:
- Keep iOS app platform target at iOS 15 or higher.
- Keep local network capability enabled.
- Ensure Bonjour services includes `_http._tcp`.
- Add photo library usage description if saving media to Photos.
- Add photo library add-only usage description if saving received media directly to Photos.
- Keep incoming and outgoing network connection capabilities enabled.
- Do not add unnecessary entitlements unrelated to local transfer.

Expected result:
- First launch correctly prompts for local network access.
- Bonjour discovery and local server publishing are allowed by iOS.

### 2. Consolidate App State Into a Real View Model
Create a dedicated iOS `QuickDropViewModel` in `QuickDrop.swiftpm/Sources/` and move orchestration out of `ContentView`.

The view model must own:
- discovery state
- nearby device list
- selected device
- scan state: idle, scanning, devices found, selected, empty
- upload state
- incoming request state
- transfer progress
- transfer speed and ETA
- selected file metadata
- transfer summary state
- app foreground/background awareness
- startup actions for advertiser and receiver server

Mirror Android behavior from `QuickDropViewModel.kt` where feasible on iOS.

Do not let `ContentView` directly manage network services as primary state owners once the view model exists.

### 3. Discovery and Advertising Parity
Harden `QuickDropDiscovery.swift` and `QuickDropAdvertiser.swift`.

`QuickDropAdvertiser.swift` requirements:
- publish service name as `QuickDrop-<sanitized device name>`
- sanitize similarly to Android: short, alphanumeric-safe, no unstable formatting
- publish on port `8000`
- service type must remain `_http._tcp.`

`QuickDropDiscovery.swift` requirements:
- browse `_http._tcp.` in `local.`
- include only services containing `QuickDrop`
- exclude the local device’s own advertised name
- resolve host/IP robustly
- avoid duplicate devices
- remove lost services
- expose device list as published observable state
- support continuous browsing instead of one-shot discovery

Do not weaken discovery logic to a simple “first service wins” model.

### 4. Replace the Current iOS Receiver Server
The existing `QuickDropServer.swift` based on raw `NWListener` string parsing is insufficient for real multipart uploads.

Replace it with an embedded HTTP server implementation that can safely handle:
- `POST /request-transfer`
- `POST /upload`
- multipart form parsing
- large payload streaming
- request lifecycle management

Preferred implementation:
- use `GCDWebServer` as a Swift package dependency if it can be added cleanly to the SwiftPM app

If `GCDWebServer` cannot be used cleanly under the current app structure, use another embedded HTTP server that:
- supports streaming multipart handling
- works in an iOS app target
- does not require a major project restructure

`POST /request-transfer` behavior:
- parse JSON payload fields:
  - `id`
  - `fileName`
  - `fileSize`
  - `deviceName`
  - `fileType`
  - `previewMode`
  - `count`
- create a pending transfer request model
- surface accept/decline UI through the view model
- wait for user decision before replying
- return JSON `{ "accepted": true|false }` with HTTP 200 on a valid request
- if the request is malformed, return an error response
- if timed out or declined, return accepted false

`POST /upload` behavior:
- accept multipart `files` parts
- preserve original filenames
- support one or many files in the same upload
- save to a temp location first
- move to final destination only after each file is fully received
- update transfer progress during receive if possible
- fail safely on partial or corrupted transfer
- never rely on naive string parsing of HTTP bodies

### 5. Sending Pipeline Parity
Rebuild `QuickDropTransfer.swift` to match Android transfer sequencing and resilience.

Handshake stage:
- send `POST /request-transfer` to `http://<target-ip>:8000/request-transfer`
- content type `application/json`
- include the same JSON shape Android sends
- use a stable transfer id for each request
- do not skip handshake before upload

Upload stage:
- only start upload if handshake returns accepted true
- send multipart `POST /upload` to the same host and port
- include `X-Device-Name`
- include `X-File-Size`
- preserve multipart part name `files`
- support multiple files in one request

Critical implementation rule:
- do not construct giant multipart bodies fully in memory for large files
- use file-backed temporary multipart body generation or stream-based upload
- maintain progress callbacks during upload
- support canceling the active upload task
- map result into success, declined, cancelled, or error

Retry/network behavior:
- implement lightweight retry only for transient connection failures
- do not retry after explicit decline
- do not retry after user cancellation
- if network becomes unavailable, surface waiting/error state clearly rather than silently failing

### 6. Transfer State Model
Expand `TransferStatus.swift` so it is feature-complete enough for Android parity.

Add state for:
- isUploading
- progress
- speed
- ETA
- filename
- size
- queued count if queueing is implemented
- waiting-for-network state if implemented
- incoming request
- transfer result
- transfer summary
- optional thumbnail/file type metadata if the UI uses it

Use one shared observable state object or one view-model-owned state model consistently. Do not split transfer truth across many disconnected singletons.

### 7. File Picking and File Metadata
Improve file selection and metadata extraction.

Required support:
- document picker for arbitrary files
- photos picker for images/videos if desired by UX
- multiple selection
- file size extraction
- original filename preservation
- file type classification into image, video, pdf, generic file

Create a helper similar in purpose to Android `FileHelper.kt`:
- filename extraction
- byte formatting
- file type classification
- optional thumbnail support for local preview if needed

Do not block v1 on rich thumbnails. Thumbnails are optional unless needed for UI parity.

### 8. Receive-Side File Saving Strategy
Implement a deterministic save policy for iOS.

Default save policy:
- save all received files into an app-controlled local folder named `QuickDrop` under the app’s Documents directory
- do not default to Photos for every media file
- if the received item is an image or video and photo library permission is available, optional later enhancement can save to Photos, but v1 must succeed without this

Required behaviors:
- create the `QuickDrop` directory if missing
- avoid filename collisions by auto-renaming
- ensure partially received files do not appear as completed files
- return success only after files are fully persisted

### 9. UI Parity in SwiftUI
Refactor `ContentView.swift` and related views to follow Android product behavior, not just the current prototype.

Required screens/components:
- splash/loading entry state
- nearby device list
- empty/scanning state
- selected device state
- send action entry point
- incoming transfer alert/dialog
- bottom progress card during active transfer
- transfer summary UI after completion/cancel/failure

The UI should remain iOS-native, but the flow must preserve Android behavior:
- auto start advertiser/server on app appear
- allow manual re-scan
- show transfer progress persistently while active
- allow cancel during active send
- clearly show accept/decline for incoming files

Do not over-invest in visual polish before behavior is complete.

### 10. Lifecycle and Background Assumptions
Implement safe lifecycle handling.

Required behavior:
- start advertiser, discovery manager, and server when the app is active
- stop or pause network services cleanly when the app terminates
- maintain sending progress while the app remains alive
- assume receiving requires the app to be foregrounded

Do not promise Android-like background service behavior on iOS.

## Public Interfaces and Types To Add or Standardize
Add or standardize these iOS-side types:
- `QuickDropViewModel`
- `UiState` equivalent for SwiftUI view switching
- `IncomingRequest`
- `TransferResult`
- `TransferSummary`
- `QuickDropDevice`
- file metadata helper model if useful for selected files

Standardize protocol payload handling:
- handshake request JSON fields:
  - `id: String`
  - `fileName: String`
  - `fileSize: Int64`
  - `deviceName: String`
  - `fileType: String`
  - `previewMode: String`
  - `count: Int`
- handshake response JSON:
  - `accepted: Bool`

Do not invent incompatible wire formats.

## Safe Execution Order For Gemini Flash
Implement in this exact order to reduce breakage:

1. Inspect current SwiftPM app target and confirm all iOS source-of-truth files are under `QuickDrop.swiftpm/Sources/`.
2. Update `Package.swift` capabilities and dependencies.
3. Introduce models and `QuickDropViewModel` without deleting old UI yet.
4. Harden advertiser and discovery.
5. Replace `QuickDropServer.swift` with a real embedded HTTP server solution.
6. Rebuild send pipeline in `QuickDropTransfer.swift` with non-memory-heavy multipart uploads.
7. Expand `TransferStatus.swift` and state wiring.
8. Refactor `ContentView.swift` and `ProgressCardView.swift` to use the new view model/state.
9. Implement file saving and collision-safe receive handling.
10. Run interoperability validation against Android and macOS behavior.
11. Only after parity is proven, clean up dead/duplicate iOS code.

## What Gemini Flash Must Not Miss
- The Android app already has more behavior than the current iOS app. Do not stop at “it compiles”.
- The iOS receiver must support real multipart uploads. Raw socket string parsing is not acceptable.
- The send path must not read all selected files into one giant in-memory body for large transfers.
- Multi-file transfer support is required.
- Handshake accept/decline is required.
- Cancellation is required.
- Exact endpoint names and port must stay compatible with Android/macOS.
- Discovery filtering must avoid showing the device itself.
- Receive save path must be deterministic and safe.
- Duplicate iOS code trees must not stay actively maintained in parallel.

## Test Plan

### Protocol Compatibility
- iPhone -> Android single image transfer succeeds.
- iPhone -> Android multi-file transfer succeeds.
- Android -> iPhone single file transfer succeeds.
- Android -> iPhone multi-file transfer succeeds.
- Mac -> iPhone transfer succeeds.
- iPhone -> Mac transfer succeeds.
- Handshake decline on either side prevents upload.
- Handshake malformed request fails safely.

### File Handling
- image file keeps a valid filename and opens correctly
- video file transfers fully and opens correctly
- PDF transfers fully and opens correctly
- generic document transfers fully and opens correctly
- duplicate filename saves with a collision-safe renamed result
- cancelled transfer does not leave a fake completed file

### UI/State
- scan state shows while searching
- nearby device appears once, not duplicated repeatedly
- selecting a device enables sending
- incoming request prompt shows sender name and file info
- progress card updates during send
- cancel updates state to cancelled
- success shows completion summary
- failure shows error state without hanging UI

### Large Transfer and Reliability
- large video transfer does not cause memory blow-up
- network interruption yields a recoverable error or waiting state
- app reopening does not leave stale transfer UI stuck active

## Acceptance Criteria
The work is complete only when all of the following are true:
- The iOS app can discover Android/macOS QuickDrop peers on the local network.
- The iOS app can send files to Android and macOS using the existing QuickDrop protocol.
- The iOS app can receive files from Android and macOS after user acceptance.
- Multi-file transfers work in both directions.
- Large files do not require fully in-memory multipart construction.
- The app exposes progress, speed, ETA, cancellation, and completion state.
- The SwiftPM app is the single maintained iOS implementation.
- No Android/macOS compatibility regressions are introduced.

## Assumptions and Defaults
- Source of truth for product behavior is the Android implementation.
- Source of truth for macOS/iOS interoperability is the existing QuickDrop HTTP + Bonjour protocol.
- iOS receive is foreground-only for v1.
- Default received-file destination is `Documents/QuickDrop` inside the app sandbox.
- The active iOS app target is the SwiftPM app in `QuickDrop-iOS/QuickDrop.swiftpm`.
- `GCDWebServer` is the preferred receive-server dependency if compatible with the SwiftPM app target.
