import SwiftUI

struct ProgressCardView: View {
    @ObservedObject var status = TransferStatus.shared
    var onStop: () -> Void
    
    @State private var shimmerOffset: CGFloat = -1.0
    
    private var isReceiveBuffering: Bool {
        status.transferMode == "receive" && status.progress < 0.5
    }
    
    var body: some View {
        VStack(spacing: 20) {
            HStack(spacing: 16) {
                // File Icon / Thumbnail
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.orange.opacity(0.1))
                        .frame(width: 48, height: 48)
                    
                    if let thumb = status.thumbnailImage {
                        Image(uiImage: thumb)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: 48, height: 48)
                            .cornerRadius(12)
                    } else {
                        Image(systemName: iconForFileType(status.fileType))
                            .font(.system(size: 20))
                            .foregroundColor(.orange)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(status.fileName)
                        .font(.system(size: 16, weight: .bold))
                        .lineLimit(1)
                    Text(status.fileSize)
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                
                Spacer()
                
                Button(action: onStop) {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white)
                        .padding(10)
                        .background(Color.red)
                        .clipShape(Circle())
                }
            }
            
            VStack(spacing: 10) {
                if isReceiveBuffering {
                    // Animated indeterminate progress for receive mode
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.orange.opacity(0.15))
                                .frame(height: 6)
                            
                            RoundedRectangle(cornerRadius: 4)
                                .fill(
                                    LinearGradient(
                                        colors: [Color.orange.opacity(0.3), Color.orange, Color.orange.opacity(0.3)],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .frame(width: geo.size.width * 0.35, height: 6)
                                .offset(x: shimmerOffset * geo.size.width)
                        }
                    }
                    .frame(height: 6)
                    .onAppear {
                        withAnimation(Animation.easeInOut(duration: 1.5).repeatForever(autoreverses: true)) {
                            shimmerOffset = 0.65
                        }
                    }
                } else {
                    ProgressView(value: status.progress)
                        .progressViewStyle(LinearProgressViewStyle(tint: .orange))
                        .scaleEffect(x: 1, y: 1.5, anchor: .center)
                }
                
                HStack {
                    if isReceiveBuffering {
                        Text("Receiving...")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.orange)
                    } else {
                        Text("\(Int(status.progress * 100))%")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.orange)
                    }
                    
                    Spacer()
                    
                    HStack(spacing: 16) {
                        if !isReceiveBuffering {
                            HStack(spacing: 4) {
                                Image(systemName: "bolt.fill")
                                Text(status.speed)
                            }
                            HStack(spacing: 4) {
                                Image(systemName: "clock.fill")
                                Text(status.eta)
                            }
                        } else {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.down.circle.fill")
                                Text(status.fileSize)
                            }
                        }
                    }
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.secondary)
                }
            }
        }
        .padding(20)
        .frame(maxWidth: 500)
        .background(
            RoundedRectangle(cornerRadius: 28)
                .fill(Color(uiColor: .secondarySystemBackground))
                .shadow(color: Color.black.opacity(0.05), radius: 10, x: 0, y: 5)
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 20)
    }
    
    private func iconForFileType(_ type: String) -> String {
        switch type {
        case "image": return "photo.fill"
        case "video": return "video.fill"
        case "pdf": return "doc.richtext.fill"
        default: return "doc.fill"
        }
    }
}
