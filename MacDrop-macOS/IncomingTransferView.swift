import SwiftUI
import UniformTypeIdentifiers

// --- DESIGN TOKENS ---
enum UIConstants {
    static let cornerRadius: CGFloat = 20
    static let cardSize: CGFloat = 232 // Outer card
    static let previewSize: CGFloat = 200 // Inner image
    static let hoverScale: CGFloat = 1.12
    static let baseScale: CGFloat = 1.05
    static let animationDuration: Double = 0.2
    static let springResponse: Double = 0.4
    static let springDamping: Double = 0.7
}

struct IncomingRequest: Codable {
    let id: String
    let deviceIp: String?
    let fileName: String
    let fileSize: Int64
    let deviceName: String
    let fileType: String
    let previewMode: String
    let count: Int
    let thumbnail: String?
}

struct IncomingTransferView: View {
    let request: IncomingRequest
    let onAccept: () -> Void
    let onDecline: () -> Void
    
    // --- STATE ---
    @State private var isAnimating = false
    @State private var isHovering = false
    @State private var isAcceptHover = false
    @State private var isDeclineHover = false
    @State private var cachedImage: NSImage? = nil

    private var shouldShowFileName: Bool {
        // Show fileName if it's a PDF/Document (Hero is just an icon) OR if thumbnail failed
        return request.fileType == "pdf" || request.fileType == "other" || cachedImage == nil
    }

    private var previewGradient: LinearGradient {
        LinearGradient(
            colors: [Color.black.opacity(0.35), .clear],
            startPoint: .bottom,
            endPoint: .center
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            // --- HEADER ---
            Text("Receive this file?")
                .font(.system(size: 20, weight: .black))
                .padding(.top, 24)
                .accessibilityLabel("Incoming file request from \(request.deviceName)")
            
            Spacer().frame(height: 24)
            
            // --- PREVIEW CARD (HERO) ---
            ZStack {
                // --- VISUAL STACK (Phase 3: Apple-level smooth) ---
                if request.count > 1 {
                    ForEach(1...min(request.count - 1, 2), id: \.self) { index in
                        RoundedRectangle(cornerRadius: 24)
                            .fill(Color.white.opacity(0.04))
                            .frame(width: UIConstants.cardSize, height: UIConstants.cardSize)
                            .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.05), lineWidth: 1))
                            .offset(x: CGFloat(index) * 8, y: CGFloat(index) * -8)
                            .zIndex(Double(-index))
                    }
                }

                RoundedRectangle(cornerRadius: 24)
                    .fill(Color.white.opacity(0.08))
                    .frame(width: UIConstants.cardSize, height: UIConstants.cardSize)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.1), lineWidth: 1)
                    )
                    .shadow(
                        color: Color.black.opacity(isHovering ? 0.4 : 0.2),
                        radius: isHovering ? 20 : 12,
                        x: 0,
                        y: isHovering ? 10 : 6
                    )
                
                Group {
                    if let img = cachedImage {
                        renderPreview(image: img)
                    } else {
                        fallbackView
                    }
                }
                .frame(width: UIConstants.previewSize, height: UIConstants.previewSize)
            }
            .hoverScale(isHovering: $isHovering)
            
            Spacer().frame(height: 20)
            
            // --- METADATA (SMART VISIBILITY) ---
            VStack(spacing: 6) {
                Text(formatBytes(request.fileSize))
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.secondary) // Hardened contrast
                
                if shouldShowFileName {
                    Text(request.fileName)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.primary) // Hardened visibility
                        .lineLimit(1)
                        .truncationMode(.middle)
                } else {
                    // Context label when filename is hidden (Step 4)
                    Text(request.fileType.capitalized)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 32)
            
            Spacer().frame(height: 32)
            
            // --- HORIZONTAL DECISION GROUP ---
            HStack(spacing: 16) {
                declineButton
                acceptButton
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 32)
        }
        .frame(width: 340)
        .background(VisualEffectView(material: .hudWindow, blendingMode: .withinWindow).ignoresSafeArea())
        .onAppear {
            fetchThumbnail()
        }
    }
    
    private func fetchThumbnail() {
        guard let deviceIp = request.deviceIp else { return }
        let urlString = "http://\(deviceIp):8081/thumbnail?id=\(request.id)"
        guard let url = URL(string: urlString) else { return }
        
        print("📡 [DEBUG] Fetching async thumbnail from: \(urlString)")
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 3
        let session = URLSession(configuration: config)
        
        session.dataTask(with: url) { data, _, _ in
            if let data = data, let img = NSImage(data: data) {
                DispatchQueue.main.async {
                    withAnimation(.easeIn(duration: 0.3)) {
                        self.cachedImage = img
                    }
                    print("✅ [DEBUG] Async thumbnail loaded (\(img.size.width)x\(img.size.height))")
                }
            }
        }.resume()
    }
    
    // --- DECISION BUTTONS ---
    
    private var declineButton: some View {
        Button(action: onDecline) {
            Text("Decline")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(isDeclineHover ? .white : .red) // Dynamic contrast
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(
                            isDeclineHover
                            ? Color.red.opacity(0.85)     // 🔴 strong on hover
                            : Color.white.opacity(0.08)  // ⚪ glass base
                        )
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.red.opacity(0.5), lineWidth: 1)
                )
                .shadow(color: Color.red.opacity(0.1), radius: 6, y: 2) // subtle depth
                .scaleEffect(isDeclineHover ? 1.04 : 1.0)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isDeclineHover = hovering
            if hovering { NSCursor.pointingHand.set() } else { NSCursor.arrow.set() }
        }
        .animation(.easeInOut(duration: 0.15), value: isDeclineHover)
        .accessibilityLabel("Decline file transfer")
    }
    
    private var acceptButton: some View {
        Button(action: onAccept) {
            Text("Accept")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(isAcceptHover ? Color.accentColor.opacity(0.9) : Color.accentColor)
                )
                .shadow(color: Color.accentColor.opacity(0.4), radius: 10, y: 4)
                .scaleEffect(isAcceptHover ? 1.03 : 1.0)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isAcceptHover = hovering
            if hovering { NSCursor.pointingHand.set() } else { NSCursor.arrow.set() }
        }
        .animation(.easeInOut(duration: 0.15), value: isAcceptHover)
        .accessibilityLabel("Accept file transfer")
    }

    @ViewBuilder
    private func renderPreview(image: NSImage) -> some View {
        ZStack {
            Image(nsImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: UIConstants.previewSize, height: UIConstants.previewSize)
                .clipShape(RoundedRectangle(cornerRadius: UIConstants.cornerRadius))
                .clipped() 
                .overlay(
                    previewGradient
                        .clipShape(RoundedRectangle(cornerRadius: UIConstants.cornerRadius))
                )
            
            // 🎬 Step 3.5: Video Play Overlay
            if request.fileType.lowercased() == "video" {
                Circle()
                    .fill(.black.opacity(0.4))
                    .frame(width: 50, height: 50)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 24))
                            .foregroundColor(.white)
                    )
                    .shadow(radius: 6)
            }
            
            // 📦 Step 3.6: Multi-file Badge (+X)
            if request.count > 1 {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Text("+\(request.count - 1)")
                            .font(.system(size: 14, weight: .black, design: .monospaced))
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Capsule().fill(Color.black.opacity(0.8)))
                            .padding(12)
                    }
                }
            }
        }
    }
    
    private var fallbackView: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 40)
                .fill(Color.white.opacity(0.05))
                .frame(width: 160, height: 160)
                .blur(radius: 5)
            
            if let icon = getSystemIcon() {
                Image(nsImage: icon)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 100, height: 100)
                    .shadow(color: Color.black.opacity(0.2), radius: 10, x: 0, y: 5)
            } else {
                Image(systemName: fallbackIcon)
                    .font(.system(size: 70))
                    .foregroundColor(fallbackColor)
            }
        }
        .transition(.scale.combined(with: .opacity))
        .scaleEffect(isAnimating ? 1.0 : 0.8)
        .opacity(isAnimating ? 1.0 : 0.0)
        .onAppear {
            withAnimation(.spring(response: UIConstants.springResponse, dampingFraction: UIConstants.springDamping)) {
                isAnimating = true
            }
        }
    }
    
    private func getSystemIcon() -> NSImage? {
        let ext = (request.fileName as NSString).pathExtension
        // Modern API for resolving system icons based on content type
        if let utType = UTType(filenameExtension: ext) {
            return NSWorkspace.shared.icon(for: utType)
        }
        return nil
    }
    
    private var fallbackIcon: String {
        switch request.fileType {
        case "image": return "photo"
        case "video": return "play.circle.fill"
        case "pdf": return "doc.richtext"
        default: return "doc.fill"
        }
    }
    
    private var fallbackColor: Color {
        switch request.fileType {
        case "image": return .blue
        case "video": return .purple
        case "pdf": return .pink
        default: return .gray
        }
    }
    
    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

// --- REUSABLE MODIFIERS ---
struct HoverScaleEffect: ViewModifier {
    @Binding var isHovering: Bool

    func body(content: Content) -> some View {
        content
            .scaleEffect(isHovering ? UIConstants.hoverScale : UIConstants.baseScale)
            .animation(.easeInOut(duration: UIConstants.animationDuration), value: isHovering)
            .onHover { hovering in
                isHovering = hovering
                if hovering {
                    NSCursor.pointingHand.set()
                } else {
                    NSCursor.arrow.set()
                }
            }
    }
}

extension View {
    func hoverScale(isHovering: Binding<Bool>) -> some View {
        self.modifier(HoverScaleEffect(isHovering: isHovering))
    }
}

struct IncomingTransferView_Previews: PreviewProvider {
    static var previews: some View {
        IncomingTransferView(
            request: IncomingRequest(
                id: "preview_id",
                deviceIp: "192.168.1.5",
                fileName: "Vacation.jpg",
                fileSize: 1024 * 1024 * 5,
                deviceName: "Pixel 7 Pro",
                fileType: "image",
                previewMode: "SINGLE_IMAGE",
                count: 1,
                thumbnail: nil
            ),
            onAccept: {},
            onDecline: {}
        )
        .preferredColorScheme(.dark)
    }
}
