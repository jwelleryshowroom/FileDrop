import SwiftUI

struct TransferCompleteCard: View {
    let summary: TransferSummary
    let onDismiss: () -> Void
    
    var body: some View {
        VStack(spacing: 24) {
            // Icon
            ZStack {
                Circle()
                    .fill(summary.result == "success" ? Color.green.opacity(0.15) : Color.red.opacity(0.15))
                    .frame(width: 80, height: 80)
                
                Image(systemName: summary.result == "success" ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(summary.result == "success" ? .green : .red)
            }
            
            // Title
            Text(summary.result == "success" ? "Transfer Complete" : "Transfer Failed")
                .font(.title3.weight(.bold))
            
            // Details
            VStack(spacing: 8) {
                HStack {
                    Image(systemName: "doc.on.doc.fill")
                        .foregroundColor(.orange)
                    Text("\(summary.count) file\(summary.count == 1 ? "" : "s")")
                    Spacer()
                    Text(summary.totalSize)
                        .foregroundColor(.secondary)
                }
                .font(.subheadline)
                
                if summary.type == "receive" && summary.result == "success" {
                    Divider()
                    HStack {
                        Image(systemName: "photo.on.rectangle.angled")
                            .foregroundColor(.orange)
                        Text("Photos & videos saved to Photos")
                            .font(.subheadline)
                        Spacer()
                    }
                }
            }
            .padding(16)
            .background(Color(uiColor: .tertiarySystemBackground))
            .cornerRadius(16)
            
            // Buttons
            VStack(spacing: 12) {
                if summary.type == "receive" && summary.result == "success" {
                    Button(action: {
                        if let url = URL(string: "shareddocuments://") {
                            UIApplication.shared.open(url)
                        }
                    }) {
                        HStack {
                            Image(systemName: "folder.fill")
                            Text("View in Files")
                        }
                        .font(.body.weight(.bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.orange)
                        .cornerRadius(16)
                    }
                    
                    HStack(spacing: 12) {
                        Button(action: {
                            FileHelper.presentShareSheet(for: FileHelper.shared.lastReceivedURLs)
                        }) {
                            HStack {
                                Image(systemName: "square.and.arrow.up")
                                Text("Share")
                            }
                            .font(.body.weight(.bold))
                            .foregroundColor(.orange)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.orange.opacity(0.1))
                            .cornerRadius(16)
                        }
                        
                        Button(action: onDismiss) {
                            Text("Done")
                                .font(.body.weight(.bold))
                                .foregroundColor(.secondary)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.secondary.opacity(0.1))
                                .cornerRadius(16)
                        }
                    }
                } else {
                    Button(action: onDismiss) {
                        Text("Done")
                            .font(.body.weight(.bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.orange)
                            .cornerRadius(16)
                    }
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
}
