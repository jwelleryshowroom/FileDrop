import SwiftUI
import PhotosUI
import UIKit

struct ContentView: View {
    @StateObject private var vm = QuickDropViewModel()
    @StateObject private var status = TransferStatus.shared
    
    @State private var showFilePicker = false
    @State private var sendPreviewMetadata: TransferPreviewMetadata? = nil
    
    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            
            VStack(alignment: .leading, spacing: 0) {
                // Header
                VStack(alignment: .leading, spacing: 8) {
                    Text("QuickDrop")
                        .font(.system(size: 34, weight: .black, design: .rounded))
                        .foregroundColor(.orange)
                    Text("Nearby Share for iOS")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 24)
                .padding(.top, 24)
                .padding(.bottom, 32)
                
                // Main Content Area
                VStack {
                    switch vm.uiState {
                    case .idle:
                        IdleView(onScan: vm.startManualScan)
                    case .scanning:
                        ScanningView()
                    case .devicesFound(let devices):
                        DeviceListView(devices: devices, onSelect: vm.selectDevice)
                    case .deviceSelected(let device):
                        DeviceSelectedView(device: device, onSend: { showFilePicker = true }, onCancel: vm.resetToIdle)
                    case .noDevicesFound:
                        NoDevicesView(onRetry: vm.startManualScan)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            
            // Overlays
            if status.isUploading {
                VStack {
                    Spacer()
                    ProgressCardView(onStop: {
                        vm.cancelTransfer()
                    })
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .zIndex(10)
            }
            
            // Incoming Request Overlay
            if let request = status.incomingRequest {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .zIndex(15)
                
                IncomingRequestCard(request: request, thumbnail: vm.remoteThumbnail)
                    .transition(.scale.combined(with: .opacity))
                    .zIndex(20)
            }
            
            // Send Preview Overlay
            if let preview = sendPreviewMetadata {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .zIndex(25)
                
                SendPreviewCard(
                    metadata: preview,
                    onSend: {
                        vm.sendFiles(urls: preview.allURLs)
                        sendPreviewMetadata = nil
                    },
                    onCancel: {
                        sendPreviewMetadata = nil
                    }
                )
                .transition(.scale.combined(with: .opacity))
                .zIndex(30)
            }
            // Transfer Complete Overlay
            if let summary = status.lastSummary {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .zIndex(35)
                
                TransferCompleteCard(
                    summary: summary,
                    onDismiss: {
                        status.clearSummary()
                    }
                )
                .transition(.scale.combined(with: .opacity))
                .zIndex(40)
            }
        }
        .onAppear {
            vm.startServices()
        }
        .onDisappear {
            vm.stopServices()
        }
        .sheet(isPresented: $showFilePicker) {
            DocumentPicker { urls in
                if !urls.isEmpty {
                    sendPreviewMetadata = QuickDropThumbnailProvider.shared.generateMetadata(for: urls)
                }
            }
        }
        .animation(.spring(), value: vm.uiState)
        .animation(.spring(), value: status.incomingRequest != nil)
        .animation(.spring(), value: sendPreviewMetadata != nil)
        .animation(.spring(), value: status.lastSummary != nil)
    }
}

// MARK: - Subviews

struct IdleView: View {
    let onScan: () -> Void
    
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 80))
                .foregroundColor(.orange)
            
            VStack(spacing: 8) {
                Text("Ready to Send")
                    .font(.title2.weight(.bold))
                Text("Tap the button below to find nearby QuickDrop devices.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }
            
            Button(action: onScan) {
                Text("Scan for Devices")
                    .font(.body.weight(.bold))
                    .padding(.horizontal, 32)
                    .padding(.vertical, 16)
                    .background(Color.orange)
                    .foregroundColor(.white)
                    .cornerRadius(30)
                    .shadow(color: .orange.opacity(0.3), radius: 10, x: 0, y: 5)
            }
        }
    }
}

struct ScanningView: View {
    @State private var isAnimating = false
    
    var body: some View {
        VStack(spacing: 32) {
            ZStack {
                Circle()
                    .stroke(Color.orange.opacity(0.2), lineWidth: 2)
                    .frame(width: 200, height: 200)
                    .scaleEffect(isAnimating ? 1.5 : 1.0)
                    .opacity(isAnimating ? 0 : 1)
                
                Circle()
                    .stroke(Color.orange.opacity(0.4), lineWidth: 2)
                    .frame(width: 150, height: 150)
                    .scaleEffect(isAnimating ? 1.5 : 1.0)
                    .opacity(isAnimating ? 0 : 1)
                
                Circle()
                    .fill(Color.orange)
                    .frame(width: 100, height: 100)
                    .overlay(
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 40, weight: .bold))
                            .foregroundColor(.white)
                    )
            }
            .onAppear {
                withAnimation(Animation.easeOut(duration: 2).repeatForever(autoreverses: false)) {
                    isAnimating = true
                }
            }
            
            Text("Searching for peers...")
                .font(.headline)
                .foregroundColor(.secondary)
        }
    }
}

struct DeviceListView: View {
    let devices: [QuickDropDevice]
    let onSelect: (QuickDropDevice) -> Void
    
    let columns = [GridItem(.flexible()), GridItem(.flexible())]
    
    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 20) {
                ForEach(devices) { device in
                    DeviceCard(device: device)
                        .onTapGesture {
                            onSelect(device)
                        }
                }
            }
            .padding(24)
        }
    }
}

struct DeviceSelectedView: View {
    let device: QuickDropDevice
    let onSend: () -> Void
    let onCancel: () -> Void
    
    var body: some View {
        VStack(spacing: 40) {
            VStack(spacing: 16) {
                Image(systemName: "laptopcomputer")
                    .font(.system(size: 100))
                    .foregroundColor(.orange)
                
                VStack(spacing: 4) {
                    Text(device.deviceName)
                        .font(.title.weight(.black))
                    Text("Ready to receive files")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
            
            VStack(spacing: 16) {
                Button(action: onSend) {
                    HStack {
                        Image(systemName: "paperplane.fill")
                        Text("Send Files")
                    }
                    .font(.body.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.orange)
                    .foregroundColor(.white)
                    .cornerRadius(16)
                }
                
                Button(action: onCancel) {
                    Text("Cancel")
                        .font(.body.weight(.medium))
                        .foregroundColor(.secondary)
                }
            }
            .frame(maxWidth: 400)
            .padding(.horizontal, 40)
        }
    }
}

struct NoDevicesView: View {
    let onRetry: () -> Void
    
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 60))
                .foregroundColor(.secondary)
            
            VStack(spacing: 8) {
                Text("No Devices Found")
                    .font(.headline)
                Text("Make sure the other device has QuickDrop open and is on the same Wi-Fi.")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }
            
            Button(action: onRetry) {
                Text("Try Again")
                    .font(.body.weight(.bold))
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(Color.orange.opacity(0.1))
                    .foregroundColor(.orange)
                    .cornerRadius(12)
            }
        }
    }
}

struct DeviceCard: View {
    let device: QuickDropDevice
    
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "display")
                .font(.system(size: 44))
                .foregroundColor(.orange)
            
            Text(device.deviceName)
                .font(.system(size: 14, weight: .bold))
                .lineLimit(1)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .padding(.horizontal, 12)
        .background(Color.orange.opacity(0.05))
        .cornerRadius(20)
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.orange.opacity(0.1), lineWidth: 1)
        )
    }
}

struct DocumentPicker: UIViewControllerRepresentable {
    var onPick: ([URL]) -> Void
    
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data, .item])
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIDocumentPickerDelegate {
        var parent: DocumentPicker
        
        init(_ parent: DocumentPicker) {
            self.parent = parent
        }
        
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            parent.onPick(urls)
        }
    }
}
