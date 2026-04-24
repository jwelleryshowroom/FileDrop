import Foundation
import GCDWebServer

class QuickDropServer {
    private let server = GCDWebServer()
    
    func start(port: Int = 8000) {
        // 1. Handshake Endpoint
        server.addHandler(forMethod: "POST", path: "/request-transfer", request: GCDWebServerDataRequest.self, processBlock: { request in
            guard let dataRequest = request as? GCDWebServerDataRequest,
                  let json = dataRequest.jsonObject as? [String: Any],
                  let transferId = json["id"] as? String,
                  let fileName = json["fileName"] as? String,
                  let fileSize = json["fileSize"] as? Int64,
                  let deviceName = json["deviceName"] as? String else {
                return GCDWebServerErrorResponse(statusCode: 400)
            }
            
            let count = json["count"] as? Int ?? 1
            let fileType = json["fileType"] as? String ?? "file"
            let previewMode = json["previewMode"] as? String ?? "NONE"
            let senderIp = request.remoteAddressString
            
            let semaphore = DispatchSemaphore(value: 0)
            var accepted = false
            
            DispatchQueue.main.async {
                TransferStatus.shared.incomingRequest = IncomingRequest(
                    transferId: transferId,
                    senderIp: senderIp,
                    fileName: fileName,
                    fileSize: fileSize,
                    deviceName: deviceName,
                    fileType: fileType,
                    previewMode: previewMode,
                    count: count,
                    onDecision: { decision in
                        if decision {
                            TransferStatus.shared.reset()
                            TransferStatus.shared.isUploading = true
                            TransferStatus.shared.transferMode = "receive"
                            TransferStatus.shared.fileName = count > 1 ? "\(count) Files" : QuickDropServer.displayTitle(for: fileType)
                            TransferStatus.shared.fileSize = FileHelper.formatBytes(fileSize)
                            TransferStatus.shared.fileType = fileType
                            TransferStatus.shared.previewMode = previewMode
                            TransferStatus.shared.progress = 0.01
                            TransferStatus.shared.speed = "Waiting..."
                            TransferStatus.shared.eta = "Preparing..."
                        }
                        accepted = decision
                        semaphore.signal()
                    }
                )
            }
            
            // Wait for user decision (max 60 seconds)
            let result = semaphore.wait(timeout: .now() + 60)
            
            if result == .timedOut {
                print("🕒 Handshake timed out")
                accepted = false
            }
            
            // Clear incoming request
            DispatchQueue.main.async {
                TransferStatus.shared.incomingRequest = nil
            }
            
            return GCDWebServerDataResponse(jsonObject: ["accepted": accepted])
        })
        
        // 2. Upload Endpoint
        server.addHandler(forMethod: "POST", path: "/upload", request: GCDWebServerMultiPartFormRequest.self, processBlock: { request in
            guard let multipartRequest = request as? GCDWebServerMultiPartFormRequest else {
                return GCDWebServerErrorResponse(statusCode: 400)
            }
            
            var receivedCount = 0
            var totalSizeReceived: Int64 = 0
            var lastSavedPath: String?
            
            // Clear previous session's saved URLs
            FileHelper.shared.lastReceivedURLs = []
            
            DispatchQueue.main.async {
                TransferStatus.shared.isUploading = true
                TransferStatus.shared.transferMode = "receive"
                TransferStatus.shared.progress = max(TransferStatus.shared.progress, 0.05)
            }
            
            let totalFiles = max(multipartRequest.files.count, 1)
            for (index, filePart) in multipartRequest.files.enumerated() {
                if filePart.controlName == "files" {
                    let tempPath = filePart.temporaryPath
                    let fileName = filePart.fileName
                    
                    if let savedURL = FileHelper.shared.saveFile(from: URL(fileURLWithPath: tempPath), originalName: fileName) {
                        receivedCount += 1
                        lastSavedPath = savedURL.deletingLastPathComponent().path
                        FileHelper.shared.lastReceivedURLs.append(savedURL)
                        if let attrs = try? FileManager.default.attributesOfItem(atPath: savedURL.path),
                           let size = attrs[.size] as? Int64 {
                            totalSizeReceived += size
                        }
                        DispatchQueue.main.async {
                            if totalFiles == 1 {
                                TransferStatus.shared.fileName = QuickDropServer.displayTitle(for: TransferStatus.shared.fileType)
                            } else {
                                TransferStatus.shared.fileName = "\(totalFiles) Files"
                            }
                            TransferStatus.shared.fileSize = FileHelper.formatBytes(totalSizeReceived)
                            TransferStatus.shared.progress = Float(index + 1) / Float(totalFiles)
                            TransferStatus.shared.speed = "Saving..."
                            TransferStatus.shared.eta = receivedCount == totalFiles ? "Finishing..." : "Receiving..."
                        }
                    }
                }
            }
            
            DispatchQueue.main.async {
                TransferStatus.shared.isUploading = false
                if receivedCount > 0 {
                    TransferStatus.shared.lastSummary = TransferSummary(
                        type: "receive",
                        count: receivedCount,
                        totalSize: FileHelper.formatBytes(totalSizeReceived),
                        result: "success",
                        saveLocation: lastSavedPath
                    )
                } else {
                    TransferStatus.shared.result = .error("No files received")
                }
            }
            
            if receivedCount > 0 {
                return GCDWebServerDataResponse(text: "Successfully uploaded \(receivedCount) files")
            } else {
                return GCDWebServerErrorResponse(statusCode: 500)
            }
        })
        
        do {
            try server.start(options: [
                GCDWebServerOption_Port: port,
                GCDWebServerOption_BindToLocalhost: false
            ])
            print("🚀 iOS Receiver Server ready on port \(port)")
        } catch {
            print("❌ Failed to start GCDWebServer: \(error)")
        }
    }
    
    func stop() {
        if server.isRunning {
            server.stop()
        }
    }

    private static func displayTitle(for fileType: String) -> String {
        switch fileType {
        case "image": return "Image"
        case "video": return "Video"
        case "pdf": return "PDF"
        default: return "File"
        }
    }
}
