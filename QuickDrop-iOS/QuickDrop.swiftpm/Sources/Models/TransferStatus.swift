import Foundation
import Combine
import UIKit

enum TransferResult: Equatable {
    case success
    case error(String)
    case declined
    case cancelled
}

struct TransferSummary: Equatable {
    let type: String // "send" or "receive"
    let count: Int
    let totalSize: String
    let result: String // "success", "cancelled", etc.
    let saveLocation: String?
}

struct TransferPreviewMetadata {
    let transferId: String
    let fileType: String
    let previewMode: String
    let count: Int
    let heroURL: URL?
    let allURLs: [URL]
    let protocolFileName: String
    let displayTitle: String
    let totalSize: Int64
    var localPreviewImage: UIImage?
}

class TransferStatus: ObservableObject {
    static let shared = TransferStatus()
    
    @Published var isUploading = false
    @Published var progress: Float = 0.0
    @Published var speed: String = ""
    @Published var eta: String = ""
    @Published var fileName: String = ""
    @Published var fileSize: String = ""
    @Published var fileType: String = "file"
    @Published var previewMode: String = "NONE"
    @Published var thumbnailImage: UIImage? = nil
    
    @Published var queuedCount: Int = 0
    @Published var isWaitingForNetwork: Bool = false
    @Published var result: TransferResult? = nil
    @Published var lastSummary: TransferSummary? = nil
    @Published var transferMode: String = "send"
    
    // For incoming requests
    @Published var incomingRequest: IncomingRequest?
    
    func reset() {
        isUploading = false
        progress = 0.0
        speed = ""
        eta = ""
        fileName = ""
        fileSize = ""
        fileType = "file"
        previewMode = "NONE"
        thumbnailImage = nil
        queuedCount = 0
        isWaitingForNetwork = false
        result = nil
    }

    func clearSummary() {
        lastSummary = nil
    }
}

struct IncomingRequest: Identifiable {
    let id = UUID()
    let transferId: String
    let senderIp: String
    let fileName: String
    let fileSize: Int64
    let deviceName: String
    let fileType: String
    let previewMode: String
    let count: Int
    let onDecision: (Bool) -> Void
}
