import SwiftUI

struct ProgressCardView: View {
    @ObservedObject var status = TransferStatus.shared
    var onStop: () -> Void
    
    var body: some View {
        VStack(spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(status.fileName)
                        .font(.headline)
                        .lineLimit(1)
                    Text(status.fileSize)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Spacer()
                Button(action: onStop) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
            }
            
            ProgressView(value: status.progress)
                .progressViewStyle(LinearProgressViewStyle(tint: .accentColor))
            
            HStack {
                Label(status.speed, systemImage: "laptopcomputer.and.iphone")
                    .font(.caption)
                Spacer()
                Text(status.eta)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.secondary.opacity(0.1))
                    .cornerRadius(8)
            }
        }
        .padding()
        .background(Color(UIColor.secondarySystemBackground))
        .cornerRadius(24)
        .shadow(radius: 10)
        .padding()
    }
}
