import SwiftUI
import PhotosUI

struct ContentView: View {
    @StateObject private var discovery = QuickDropDiscovery()
    @StateObject private var status = TransferStatus.shared
    @State private var advertiser = QuickDropAdvertiser()
    @State private var server = QuickDropServer()
    
    @State private var selectedDevice: QuickDropDevice?
    @State private var showFilePicker = false
    @State private var showPhotosPicker = false
    @State private var selectedItems: [PhotosPickerItem] = []
    
    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                VStack(alignment: .leading, spacing: 8) {
                    Text("QuickDrop v1.0")
                        .font(.largeTitle)
                        .fontWeight(.black)
                        .foregroundColor(.accentColor)
                    Text("Drop files. Instantly.")
                        .font(.headline)
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal)
                .padding(.top, 40)
                
                Text("Nearby Devices")
                    .font(.caption)
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)
                
                // Devices List
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(discovery.devices) { device in
                            DeviceCard(device: device, isSelected: selectedDevice?.id == device.id)
                                .onTapGesture {
                                    selectedDevice = device
                                }
                        }
                        
                        if discovery.devices.isEmpty {
                            VStack(spacing: 12) {
                                ProgressView()
                                Text("Searching for devices...")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.top, 40)
                        }
                    }
                    .padding()
                }
                
                Spacer()
                
                // Actions
                if let device = selectedDevice {
                    VStack(spacing: 12) {
                        Button(action: { showFilePicker = true }) {
                            Text("Send Files")
                                .fontWeight(.bold)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .cornerRadius(16)
                        }
                        
                        Button(action: { selectedDevice = nil }) {
                            Text("Change Device")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding()
                }
            }
            
            // Overlays
            if status.isUploading {
                VStack {
                    Spacer()
                    ProgressCardView(onStop: {
                        status.isUploading = false
                        // Add cancellation logic
                    })
                }
                .transition(.move(edge: .bottom))
            }
        }
        .onAppear {
            discovery.start()
            advertiser.start()
            server.start()
        }
        .onDisappear {
            discovery.stop()
            advertiser.stop()
            server.stop()
        }
        .alert(item: $status.incomingRequest) { request in
            Alert(
                title: Text("Incoming File Request"),
                message: Text("\(request.deviceName) wants to send: \(request.fileName)"),
                primaryButton: .default(Text("Accept")) {
                    request.onDecision(true)
                },
                secondaryButton: .destructive(Text("Decline")) {
                    request.onDecision(false)
                }
            )
        }
        .sheet(isPresented: $showFilePicker) {
            DocumentPicker { urls in
                if let device = selectedDevice {
                    QuickDropTransfer.shared.sendFiles(urls: urls, targetIp: device.ipAddress)
                }
            }
        }
    }
}

struct DeviceCard: View {
    let device: QuickDropDevice
    let isSelected: Bool
    
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "laptopcomputer")
                .font(.system(size: 40))
                .foregroundColor(isSelected ? .accentColor : .secondary)
            
            VStack(spacing: 4) {
                Text(device.deviceName)
                    .fontWeight(.bold)
                    .multilineTextAlignment(.center)
                Text(device.ipAddress)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(isSelected ? Color.accentColor.opacity(0.1) : Color.secondary.opacity(0.05))
        .cornerRadius(24)
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(isSelected ? Color.accentColor : Color.clear, lineWidth: 2)
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
