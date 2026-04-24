import Foundation
import Combine
import SwiftUI

enum UiState: Equatable {
    case idle
    case scanning
    case devicesFound([QuickDropDevice])
    case deviceSelected(QuickDropDevice)
    case noDevicesFound
}

class QuickDropViewModel: ObservableObject {
    @Published var uiState: UiState = .idle
    @Published var selectedDevice: QuickDropDevice?
    
    private var isStarted = false
    private let discovery = QuickDropDiscovery()
    private let advertiser = QuickDropAdvertiser()
    private let server = QuickDropServer()
    private let thumbnailServer = QuickDropThumbnailServer.shared
    private var cancellables = Set<AnyCancellable>()
    
    // For incoming requests
    @Published var remoteThumbnail: UIImage? = nil
    
    init() {
        // Observe discovery devices
        discovery.$devices
            .receive(on: DispatchQueue.main)
            .sink { [weak self] devices in
                guard let self = self else { return }
                
                // Update UI state based on discovery
                switch self.uiState {
                case .scanning, .idle, .noDevicesFound:
                    if !devices.isEmpty {
                        self.uiState = .devicesFound(devices.sorted { $0.deviceName < $1.deviceName })
                    }
                case .devicesFound:
                    if devices.isEmpty {
                        self.uiState = .noDevicesFound
                    } else {
                        self.uiState = .devicesFound(devices.sorted { $0.deviceName < $1.deviceName })
                    }
                case .deviceSelected:
                    // Keep device selected even if list updates
                    break
                }
            }
            .store(in: &cancellables)
        
        // Observe incoming requests to fetch thumbnails
        TransferStatus.shared.$incomingRequest
            .receive(on: DispatchQueue.main)
            .sink { [weak self] request in
                guard let self = self, let request = request else {
                    self?.remoteThumbnail = nil
                    return
                }
                self.fetchRemoteThumbnail(for: request)
            }
            .store(in: &cancellables)
    }
    
    private func fetchRemoteThumbnail(for request: IncomingRequest) {
        let urlString = "http://\(request.senderIp):8081/thumbnail?id=\(request.transferId)"
        guard let url = URL(string: urlString) else { return }
        
        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let data = data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                self?.remoteThumbnail = image
                TransferStatus.shared.thumbnailImage = image
            }
        }.resume()
    }
    
    func startServices() {
        guard !isStarted else { return }
        isStarted = true
        print("🚀 iOS Starting QuickDrop services...")
        advertiser.start()
        server.start()
        thumbnailServer.start()
    }
    
    func stopServices() {
        isStarted = false
        print("🛑 iOS Stopping QuickDrop services...")
        advertiser.stop()
        server.stop()
        thumbnailServer.stop()
        discovery.stop()
    }
    
    func startManualScan() {
        print("🔍 iOS Starting manual scan...")
        uiState = .scanning
        selectedDevice = nil
        discovery.start()
        
        // Timeout after 8 seconds (matching Android)
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self = self else { return }
            if case .scanning = self.uiState {
                print("🕒 iOS Scan timeout reached")
                self.discovery.stop()
                self.uiState = .noDevicesFound
            }
        }
    }
    
    func selectDevice(_ device: QuickDropDevice) {
        selectedDevice = device
        uiState = .deviceSelected(device)
    }
    
    func sendFiles(urls: [URL]) {
        guard let device = selectedDevice else { return }
        QuickDropTransfer.shared.sendFiles(urls: urls, targetIp: device.ipAddress)
    }
    
    func cancelTransfer() {
        QuickDropTransfer.shared.cancelCurrentTransfer()
    }
    
    func resetToIdle() {
        discovery.stop()
        selectedDevice = nil
        uiState = .idle
    }
}
