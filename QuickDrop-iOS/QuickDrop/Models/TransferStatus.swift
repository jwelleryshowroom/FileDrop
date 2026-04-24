import Foundation
import Combine

class TransferStatus: ObservableObject {
    static let shared = TransferStatus()
    
    @Published var isUploading = false
    @Published var progress: Float = 0.0
    @Published var speed: String = ""
    @Published var eta: String = ""
    @Published var fileName: String = ""
    @Published var fileSize: String = ""
    
    // For incoming requests
    @Published var incomingRequest: IncomingRequest?
    
    func reset() {
        isUploading = false
        progress = 0.0
        speed = ""
        eta = ""
        fileName = ""
        fileSize = ""
    }
}

struct IncomingRequest: Identifiable {
    let id = UUID()
    let fileName: String
    let fileSize: Int64
    let deviceName: String
    let onDecision: (Bool) -> Void
}
