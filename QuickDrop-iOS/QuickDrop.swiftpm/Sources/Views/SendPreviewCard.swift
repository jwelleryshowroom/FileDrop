import SwiftUI

struct SendPreviewCard: View {
    let metadata: TransferPreviewMetadata
    let onSend: () -> Void
    let onCancel: () -> Void
    
    var body: some View {
        VStack(spacing: 24) {
            // Header
            VStack(spacing: 8) {
                Text("Send Preview")
                    .font(.headline)
                    .foregroundColor(.secondary)
                Text(metadata.displayTitle)
                    .font(.title3.weight(.bold))
                    .lineLimit(1)
            }
            
            // Thumbnail / Icon
            ZStack {
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color.orange.opacity(0.1))
                    .frame(width: 200, height: 200)
                
                if let thumb = metadata.localPreviewImage {
                    Image(uiImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 200, height: 200)
                        .cornerRadius(24)
                } else {
                    Image(systemName: iconForFileType(metadata.fileType))
                        .font(.system(size: 80))
                        .foregroundColor(.orange)
                }
            }
            .shadow(color: .black.opacity(0.1), radius: 10, x: 0, y: 5)
            
            // Details
            VStack(spacing: 4) {
                Text(metadata.count > 1 ? "\(metadata.count) files" : metadata.fileType.capitalized)
                    .font(.headline)
                Text(FileHelper.formatBytes(metadata.totalSize))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            // Actions
            HStack(spacing: 16) {
                Button(action: onCancel) {
                    Text("Cancel")
                        .font(.body.weight(.bold))
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.secondary.opacity(0.1))
                        .cornerRadius(16)
                }
                
                Button(action: onSend) {
                    HStack {
                        Image(systemName: "paperplane.fill")
                        Text("Send")
                    }
                    .font(.body.weight(.bold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.orange)
                    .cornerRadius(16)
                }
            }
        }
        .padding(32)
        .frame(maxWidth: 400)
        .background(Color(uiColor: .systemBackground))
        .cornerRadius(40)
        .shadow(color: .black.opacity(0.2), radius: 30, x: 0, y: 15)
        .padding(24)
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
