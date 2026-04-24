import SwiftUI

struct IncomingRequestCard: View {
    let request: IncomingRequest
    let thumbnail: UIImage?
    
    var body: some View {
        VStack(spacing: 24) {
            // Header
            VStack(spacing: 8) {
                Text(request.deviceName)
                    .font(.headline)
                    .foregroundColor(.secondary)
                Text(displayTitle)
                    .font(.title3.weight(.bold))
            }
            
            // Thumbnail / Icon
            ZStack {
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color.orange.opacity(0.1))
                    .frame(width: 200, height: 200)
                
                if let thumb = thumbnail {
                    Image(uiImage: thumb)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 200, height: 200)
                        .cornerRadius(24)
                } else {
                    Image(systemName: iconForFileType(request.fileType))
                        .font(.system(size: 80))
                        .foregroundColor(.orange)
                }
            }
            .shadow(color: .black.opacity(0.1), radius: 10, x: 0, y: 5)
            
            // Details
            VStack(spacing: 4) {
                Text(request.count > 1 ? "\(request.count) items" : request.fileType.capitalized)
                    .font(.headline)
                Text(FileHelper.formatBytes(request.fileSize))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            // Actions
            HStack(spacing: 16) {
                Button(action: { request.onDecision(false) }) {
                    Text("Decline")
                        .font(.body.weight(.bold))
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.red.opacity(0.1))
                        .cornerRadius(16)
                }
                
                Button(action: { request.onDecision(true) }) {
                    Text("Accept")
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

    private var displayTitle: String {
        if request.count > 1 {
            return "\(request.count) Files"
        }

        switch request.fileType {
        case "image": return "Incoming Image"
        case "video": return "Incoming Video"
        case "pdf": return "Incoming PDF"
        default: return "Incoming File"
        }
    }
}
