# Task Plan: Smart Incoming Notification

## Understanding Background Limitations
When an Android app is **completely closed** (swiped away from recent apps), the operating system forcibly kills all its background processes. Because QuickDrop relies entirely on your local Wi-Fi (LAN) and not a central cloud server, it cannot receive network requests when the process is dead. 
To keep the app discoverable when completely closed, we would have to use a persistent "Foreground Service" which creates an un-dismissible "QuickDrop is Running" sticky notification 24/7. To preserve the existing architecture and avoid battery drain, we won't do this.
However, we **can** make the notification "smart" so it perfectly triggers when the app is just **backgrounded** (e.g., you pressed Home or opened another app) and stays completely silent when you are actively looking at the QuickDrop UI.

## Phase 1: Track Foreground State
### 1.1 `QuickDropViewModel.kt`
*   Add a boolean variable: `var isAppInForeground = false`

### 1.2 `MainActivity.kt`
*   Override `onStart()` to set `viewModel.isAppInForeground = true`.
*   Override `onStop()` to set `viewModel.isAppInForeground = false`.

## Phase 2: Make Notification Smart
### 2.1 `QuickDropViewModel.kt`
*   Inside the `onRequest` callback, wrap the notification trigger with `if (!isAppInForeground)`. This guarantees the heads-up push notification will NEVER pop up while you are actively looking at the QuickDrop app.

## Phase 3: Execution
*   Apply the logic, ensure it compiles, and rebuild `QuickDrop.apk`.
