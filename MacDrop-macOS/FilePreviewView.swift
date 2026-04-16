import SwiftUI
import QuickLookThumbnailing

struct FilePreviewView: View {
    @ObservedObject var manager: ServerManager
    let urls: [URL]
    let onSend: () -> Void
    let onCancel: () -> Void
    
    @State private var thumbnails: [URL: NSImage] = [:]
    @State private var hasCompleted = false
    @State private var hasStarted = false
    
    @State private var elapsedTime: Double = 0
    @State private var showTimeoutRing = false
    @State private var timeoutProgress: Double = 1.0
    @State private var timer: Timer? = nil
    
    private var isMultiple: Bool { urls.count > 1 }
    
    private var totalSize: Int64 {
        urls.reduce(0) { total, url in
            let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
            return total + (attrs?[.size] as? Int64 ?? 0)
        }
    }
    
    private var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: totalSize, countStyle: .file)
    }
    
    private var fileTypeLabel: String {
        guard let url = urls.first else { return "Files" }
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "heic", "gif": return "Image"
        case "mp4", "mov", "mkv", "avi": return "Video"
        case "pdf": return "PDF"
        default: return "Document"
        }
    }
    
    private var fallbackIcon: String {
        guard let url = urls.first else { return "doc.fill" }
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "heic", "gif": return "photo"
        case "mp4", "mov", "mkv", "avi": return "video.fill"
        case "pdf": return "doc.richtext.fill"
        default: return "doc.fill"
        }
    }
    
    var body: some View {
        ZStack {
            VisualEffectView(material: .hudWindow, blendingMode: .behindWindow)
                .ignoresSafeArea()
            
            VStack(spacing: 24) {
                if hasCompleted {
                    // --- Completion State ---
                    VStack(spacing: 16) {
                        Image(systemName: manager.transferResult == "success" ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .font(.system(size: 60))
                            .foregroundColor(manager.transferResult == "success" ? .green : .red)
                        
                        Text(manager.transferResult == "success" ? "Transfer Complete" : "Transfer Declined")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Button("Done") {
                            onCancel()
                        }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .padding(.top, 10)
                    }
                    .transition(.scale.combined(with: .opacity))
                } else if manager.isTransferring || hasStarted {
                    // --- Progress State ---
                    VStack(spacing: 20) {
                        // Remove static "Sending..." header to allow dynamic text to take over
                        
                        previewAreaContent
                        
                        VStack(spacing: 8) {
                            Text({
                                if let result = manager.transferResult {
                                    switch result {
                                    case "success":
                                        return "Transfer Complete"
                                    case "declined":
                                        return "Transfer Declined"
                                    case "failed":
                                        return "Device not reachable"
                                    case "timeout":
                                        return "No response from device"
                                    default:
                                        return "Processing..."
                                    }
                                } else if manager.isWaitingForNetwork {
                                    return "Waiting for network..."
                                } else if manager.transferProgress == 0 {
                                    return "Connecting to device..."
                                } else {
                                    return "Waiting for user..."
                                }
                            }())
                            .font(.headline)
                            .foregroundColor(manager.isWaitingForNetwork ? .orange : .primary)
                            
                            if showTimeoutRing &&
                               manager.transferResult == nil &&
                               !manager.isWaitingForNetwork {
                                
                                timeoutRing
                                    .padding(.top, 8)
                            }
                            
                            ProgressView(value: manager.transferProgress, total: 1.0)
                                .progressViewStyle(.linear)
                                .tint(manager.isWaitingForNetwork ? .orange : .accentColor)
                            
                            HStack {
                                Label(manager.isWaitingForNetwork ? "Paused" : manager.transferSpeed, systemImage: manager.isWaitingForNetwork ? "wifi.exclamationmark" : "bolt.fill")
                                
                                if manager.queuedCount > 0 {
                                    Spacer()
                                    Text("+\(manager.queuedCount) queued")
                                        .font(.caption)
                                        .fontWeight(.bold)
                                        .foregroundColor(.orange)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Color.orange.opacity(0.1))
                                        .cornerRadius(6)
                                }
                                
                                Spacer()
                                Label(manager.transferEta.isEmpty ? "--" : manager.transferEta, systemImage: "clock.fill")
                            }
                            .font(.caption)
                            .foregroundColor(.secondary)
                        }
                    }
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                } else {
                    // --- Preview State ---
                    VStack(spacing: 20) {
                        Text("Send this file?")
                            .font(.title3)
                            .fontWeight(.bold)
                            .multilineTextAlignment(.center)
                        
                        previewAreaContent
                        
                        VStack(spacing: 4) {
                            Text(isMultiple ? "\(urls.count) files selected" : fileTypeLabel)
                                .font(.headline)
                            
                            Text("Total size: \(formattedSize)")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        
                        HStack(spacing: 16) {
                            Button(action: onCancel) {
                                Text("Cancel").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.large)
                            
                            Button(action: {
                                hasStarted = true
                                startTimer()   // ✅ START TIMER HERE
                                onSend()
                            }) {
                                Text("Send").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                        }
                    }
                    .transition(.opacity)
                }
            }
            .padding(24)
        }
        .frame(width: 340)
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .animation(.spring(), value: manager.isTransferring)
        .animation(.spring(), value: hasCompleted)
        .onAppear {
            generateThumbnails()
        }
        .onDisappear {
            timer?.invalidate()
        }
        .onChange(of: manager.transferResult) { _, result in
            guard let result = result else { return }
            
            timer?.invalidate()   // ✅ STOP TIMER
            
            withAnimation {
                if result == "success" {
                    hasCompleted = true
                } else if result == "declined" {
                    hasCompleted = true // Show the "declined" screen (handled by Text condition)
                }
            }
            
            // Auto-close after 2.5 seconds
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                onCancel()
            }
        }
        .onChange(of: manager.isTransferring) { _, isRunning in
            if !isRunning {
                timer?.invalidate()
            }
        }
    }
    
    private var previewArea: some View {
        previewAreaContent
    }

    private var timeoutRing: some View {
        ZStack {
            // Background ring (subtle glass track)
            Circle()
                .stroke(Color.white.opacity(0.08), lineWidth: 6)
            
            // Active draining ring
            Circle()
                .trim(from: 0, to: timeoutProgress)
                .stroke(
                    LinearGradient(
                        colors: [
                            Color.orange.opacity(0.9),
                            Color.orange.opacity(0.6)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    style: StrokeStyle(
                        lineWidth: 6,
                        lineCap: .round
                    )
                )
                .rotationEffect(.degrees(-90))
                .animation(.linear(duration: 1), value: timeoutProgress)
        }
        .frame(width: 36, height: 36)
    }

    private var previewAreaContent: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(NSColor.controlBackgroundColor))
                .frame(height: 180)
                .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
            
            if let firstUrl = urls.first {
                if let thumb = thumbnails[firstUrl] {
                    Image(nsImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .cornerRadius(12)
                        .padding(10)
                } else {
                    // Loading or Fallback
                    VStack(spacing: 12) {
                        if !urls.isEmpty {
                            ProgressView()
                                .scaleEffect(0.8)
                        }
                        
                        let icon = NSWorkspace.shared.icon(forFile: firstUrl.path)
                        Image(nsImage: icon)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 80, height: 80)
                            .opacity(0.4)
                    }
                }
                
                if fileTypeLabel == "Video" && thumbnails[firstUrl] != nil {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 44))
                        .foregroundColor(.white.opacity(0.8))
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
    
    private func generateThumbnails() {
        for url in urls {
            generateThumbnail(for: url)
        }
    }
    
    private func generateThumbnail(for url: URL) {
        let size = CGSize(width: 300, height: 300)
        let scale = NSScreen.main?.backingScaleFactor ?? 2.0
        let request = QLThumbnailGenerator.Request(fileAt: url, size: size, scale: scale, representationTypes: .all)
        
        QLThumbnailGenerator.shared.generateBestRepresentation(for: request) { (representation, error) in
            if let nsImage = representation?.nsImage {
                DispatchQueue.main.async {
                    self.thumbnails[url] = nsImage
                }
            }
        }
    }

    private func startTimer() {
        elapsedTime = 0
        showTimeoutRing = false
        timeoutProgress = 1.0
        
        timer?.invalidate()
        
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            elapsedTime += 1
            
            // After 60s → show ring
            if elapsedTime >= 60 {
                showTimeoutRing = true
            }
            
            // Drain from 60s → 120s
            if elapsedTime >= 60 && elapsedTime <= 120 {
                let progress = 1.0 - ((elapsedTime - 60) / 60.0)
                timeoutProgress = max(progress, 0)
            }
            
            // At 120s → force timeout UI
            if elapsedTime >= 120 {
                timer?.invalidate()
                
                DispatchQueue.main.async {
                    // Only override if still no result and backend isn't actively reporting network issues
                    if self.manager.transferResult == nil &&
                       self.manager.isTransferring &&
                       !self.manager.isWaitingForNetwork {
                        
                        self.manager.transferResult = "timeout"
                        self.manager.isTransferring = false
                    }
                }
            }
        }
    }
}

