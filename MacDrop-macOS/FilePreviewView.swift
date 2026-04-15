import SwiftUI
import QuickLookThumbnailing

struct FilePreviewView: View {
    @ObservedObject var manager: ServerManager
    let urls: [URL]
    let onSend: () -> Void
    let onCancel: () -> Void
    
    @State private var thumbnail: NSImage? = nil
    @State private var hasCompleted = false
    
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
        VStack(spacing: 24) {
            if hasCompleted {
                // --- Completion State ---
                VStack(spacing: 16) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.green)
                        .padding(.top, 20)
                    
                    Text("Transfer Complete")
                        .font(.title2)
                        .fontWeight(.bold)
                    
                    Button("Done", action: onCancel)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .padding(.top, 10)
                }
                .transition(.scale.combined(with: .opacity))
            } else if manager.isTransferring {
                // --- Progress State ---
                VStack(spacing: 20) {
                    Text("Sending...")
                        .font(.headline)
                        .fontWeight(.bold)
                    
                    previewArea
                    
                    VStack(spacing: 8) {
                        Text(manager.isWaitingForNetwork ? "Waiting for Network..." : "Sending...")
                            .font(.headline)
                            .foregroundColor(manager.isWaitingForNetwork ? .orange : .primary)
                        
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
                    
                    previewArea
                    
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
                        
                        Button(action: onSend) {
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
        .frame(width: 340)
        .background(VisualEffectView(material: .headerView, blendingMode: .withinWindow).ignoresSafeArea())
        .animation(.spring(), value: manager.isTransferring)
        .animation(.spring(), value: hasCompleted)
        .onAppear {
            generateThumbnail()
        }
        .onChange(of: manager.isTransferring) { wasTransferring, isTransferring in
            // When transfer flips from true to false, it's done
            if wasTransferring && !isTransferring {
                withAnimation {
                    hasCompleted = true
                }
                // Auto-close after 2 seconds
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                    onCancel()
                }
            }
        }
    }
    
    private var previewArea: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(NSColor.controlBackgroundColor))
                .frame(height: 180)
                .shadow(color: Color.black.opacity(0.05), radius: 5, x: 0, y: 2)
            
            if let thumb = thumbnail {
                Image(nsImage: thumb)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .cornerRadius(12)
                    .padding(10)
            } else {
                // System Icon Fallback
                if let firstUrl = urls.first {
                    let icon = NSWorkspace.shared.icon(forFile: firstUrl.path)
                    Image(nsImage: icon)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 80, height: 80)
                        .opacity(0.8)
                } else {
                    Image(systemName: fallbackIcon)
                        .font(.system(size: 64))
                        .foregroundColor(.secondary.opacity(0.6))
                }
            }
            
            if fileTypeLabel == "Video" && thumbnail != nil {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.white.opacity(0.8))
            }
        }
        .frame(maxWidth: .infinity)
    }
    
    private func generateThumbnail() {
        guard let url = urls.first else { return }
        
        let size = CGSize(width: 300, height: 300)
        let scale = NSScreen.main?.backingScaleFactor ?? 1.0
        let request = QLThumbnailGenerator.Request(fileAt: url, size: size, scale: scale, representationTypes: .thumbnail)
        
        QLThumbnailGenerator.shared.generateRepresentations(for: request) { (representation, type, error) in
            DispatchQueue.main.async {
                if let nsImage = representation?.nsImage {
                    self.thumbnail = nsImage
                }
            }
        }
    }
}

