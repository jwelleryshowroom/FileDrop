import Foundation
import UIKit

class QuickDropTransfer: NSObject, URLSessionTaskDelegate {
    static let shared = QuickDropTransfer()
    private var uploadSession: URLSession?
    private var startTime: Date?
    private var handshakeTask: URLSessionDataTask?
    private var currentTask: URLSessionTask?
    private var currentUploadBodyURL: URL?
    private var currentFileCount: Int = 0
    private var isUserCancelled: Bool = false
    private var securityScopedURLs: [URL] = []
    
    override init() {
        super.init()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 600
        uploadSession = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }
    
    func cancelCurrentTransfer() {
        print("🛑 User initiated transfer cancellation")
        isUserCancelled = true
        handshakeTask?.cancel()
        handshakeTask = nil
        currentTask?.cancel()
        currentTask = nil
        cleanupUploadBody()
        releaseSecurityScopedURLs()
        
        DispatchQueue.main.async {
            TransferStatus.shared.isUploading = false
            TransferStatus.shared.result = .cancelled
        }
    }
    
    func sendFiles(urls: [URL], targetIp: String) {
        guard !urls.isEmpty else { return }
        
        // Acquire security-scoped access for document picker URLs
        securityScopedURLs = urls
        for url in urls {
            _ = url.startAccessingSecurityScopedResource()
        }
        
        // Generate metadata and local thumbnail
        let metadata = QuickDropThumbnailProvider.shared.generateMetadata(for: urls)
        self.currentFileCount = metadata.count
        self.isUserCancelled = false
        
        // Register hero file for thumbnail sidecar
        if let heroURL = metadata.heroURL {
            QuickDropThumbnailServer.shared.register(transferId: metadata.transferId, url: heroURL)
        }
        
        DispatchQueue.main.async {
            TransferStatus.shared.reset()
            TransferStatus.shared.transferMode = "send"
            TransferStatus.shared.fileName = metadata.displayTitle
            TransferStatus.shared.fileSize = FileHelper.formatBytes(metadata.totalSize)
            TransferStatus.shared.fileType = metadata.fileType
            TransferStatus.shared.previewMode = metadata.previewMode
            TransferStatus.shared.thumbnailImage = metadata.localPreviewImage
        }
        
        print("📦 iOS Transfer metadata: type=\(metadata.fileType) mode=\(metadata.previewMode) size=\(metadata.totalSize) count=\(metadata.count)")
        
        // 1. Handshake (use ephemeral session — no delegate conflicts)
        let handshakeUrl = URL(string: "http://\(targetIp):8000/request-transfer")!
        var request = URLRequest(url: handshakeUrl)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 30
        
        let body: [String: Any] = [
            "id": metadata.transferId,
            "fileName": metadata.protocolFileName,
            "fileSize": NSNumber(value: metadata.totalSize),
            "deviceName": UIDevice.current.name,
            "count": metadata.count,
            "fileType": metadata.fileType,
            "previewMode": metadata.previewMode
        ]
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        print("🤝 iOS Handshaking with \(targetIp)... body=\(String(data: request.httpBody ?? Data(), encoding: .utf8) ?? "nil")")
        
        let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            self.handshakeTask = nil

            if self.isUserCancelled {
                print("ℹ️ Handshake completed after user cancellation; ignoring result")
                QuickDropThumbnailServer.shared.unregister(transferId: metadata.transferId)
                self.releaseSecurityScopedURLs()
                return
            }

            if let error = error {
                print("❌ Handshake network error: \(error.localizedDescription)")
                QuickDropThumbnailServer.shared.unregister(transferId: metadata.transferId)
                self.releaseSecurityScopedURLs()
                DispatchQueue.main.async {
                    TransferStatus.shared.result = .error("Connection failed: \(error.localizedDescription)")
                }
                return
            }

            if let httpResponse = response as? HTTPURLResponse {
                print("📡 Handshake HTTP status: \(httpResponse.statusCode)")
            }

            if let data = data,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let accepted = json["accepted"] as? Bool, accepted {
                print("🚀 Handshake accepted! Starting upload...")
                self.startUpload(urls: urls, targetIp: targetIp, totalSize: metadata.totalSize, transferId: metadata.transferId)
            } else {
                print("❌ Handshake declined or failed. Response data: \(String(data: data ?? Data(), encoding: .utf8) ?? "nil")")
                QuickDropThumbnailServer.shared.unregister(transferId: metadata.transferId)
                self.releaseSecurityScopedURLs()
                DispatchQueue.main.async {
                    TransferStatus.shared.result = .declined
                }
            }
        }
        handshakeTask = task
        task.resume()
    }
    
    private func startUpload(urls: [URL], targetIp: String, totalSize: Int64, transferId: String) {
        DispatchQueue.main.async {
            TransferStatus.shared.isUploading = true
        }
        
        let uploadUrl = URL(string: "http://\(targetIp):8000/upload")!
        var request = URLRequest(url: uploadUrl)
        request.httpMethod = "POST"
        request.timeoutInterval = 600
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.addValue(UIDevice.current.name, forHTTPHeaderField: "X-Device-Name")
        request.addValue(String(totalSize), forHTTPHeaderField: "X-File-Size")
        request.addValue(transferId, forHTTPHeaderField: "X-Transfer-Id")
        
        // Create temporary file for multipart body to avoid memory pressure
        let tempFileURL = FileManager.default.temporaryDirectory.appendingPathComponent("upload-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: tempFileURL.path, contents: nil)
        currentUploadBodyURL = tempFileURL
        
        do {
            let handle = try FileHandle(forWritingTo: tempFileURL)
            
            for url in urls {
                let header = "--\(boundary)\r\nContent-Disposition: form-data; name=\"files\"; filename=\"\(url.lastPathComponent)\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                handle.write(header.data(using: .utf8)!)
                
                if let fileHandle = try? FileHandle(forReadingFrom: url) {
                    while true {
                        let data = fileHandle.readData(ofLength: 1024 * 1024) // 1MB chunks
                        if data.isEmpty { break }
                        handle.write(data)
                    }
                    fileHandle.closeFile()
                } else {
                    print("⚠️ Could not read file: \(url.lastPathComponent)")
                }
                handle.write("\r\n".data(using: .utf8)!)
            }
            handle.write("--\(boundary)--\r\n".data(using: .utf8)!)
            handle.closeFile()
            
            // Verify the body file has real content
            let bodyAttrs = try FileManager.default.attributesOfItem(atPath: tempFileURL.path)
            let bodySize = bodyAttrs[.size] as? Int64 ?? 0
            print("📤 Upload body size: \(bodySize) bytes")
            
            if bodySize < 100 {
                print("❌ Upload body is too small — file reads likely failed (security scoping issue?)")
                QuickDropThumbnailServer.shared.unregister(transferId: transferId)
                cleanupUploadBody()
                releaseSecurityScopedURLs()
                DispatchQueue.main.async {
                    TransferStatus.shared.isUploading = false
                    TransferStatus.shared.result = .error("Could not read selected files")
                }
                return
            }
            
            self.startTime = Date()
            let task = uploadSession?.uploadTask(with: request, fromFile: tempFileURL)
            self.currentTask = task
            task?.taskDescription = transferId
            task?.resume()
        } catch {
            print("❌ Failed to create upload body: \(error)")
            QuickDropThumbnailServer.shared.unregister(transferId: transferId)
            cleanupUploadBody()
            releaseSecurityScopedURLs()
        }
    }
    
    // MARK: - URLSessionTaskDelegate
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        let progress = Float(totalBytesSent) / Float(totalBytesExpectedToSend)
        let now = Date()
        if let start = startTime {
            let elapsed = now.timeIntervalSince(start)
            if elapsed > 0 {
                let bytesPerSecond = Double(totalBytesSent) / elapsed
                let speed = FileHelper.formatBytes(Int64(bytesPerSecond)) + "/s"
                let remainingBytes = totalBytesExpectedToSend - totalBytesSent
                let etaSeconds = Double(remainingBytes) / bytesPerSecond
                let eta = formatEta(etaSeconds)
                
                DispatchQueue.main.async {
                    TransferStatus.shared.progress = progress
                    TransferStatus.shared.speed = speed
                    TransferStatus.shared.eta = eta
                }
            }
        }
    }
    
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let transferId = task.taskDescription ?? ""
        QuickDropThumbnailServer.shared.unregister(transferId: transferId)
        releaseSecurityScopedURLs()
        
        DispatchQueue.main.async {
            TransferStatus.shared.isUploading = false
            self.currentTask = nil
            self.cleanupUploadBody()
            
            if self.isUserCancelled {
                print("ℹ️ Upload completed with user cancellation")
                TransferStatus.shared.result = .cancelled
                return
            }
            
            if let error = error {
                let nsError = error as NSError
                if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                    TransferStatus.shared.result = .cancelled
                } else {
                    print("❌ Upload failed: \(error.localizedDescription)")
                    TransferStatus.shared.result = .error(error.localizedDescription)
                }
            } else {
                print("✅ Upload complete!")
                TransferStatus.shared.result = .success
                TransferStatus.shared.lastSummary = TransferSummary(
                    type: "send",
                    count: self.currentFileCount,
                    totalSize: TransferStatus.shared.fileSize,
                    result: "success",
                    saveLocation: nil
                )
            }
        }
    }

    private func cleanupUploadBody() {
        guard let url = currentUploadBodyURL else { return }
        currentUploadBodyURL = nil
        try? FileManager.default.removeItem(at: url)
    }
    
    private func releaseSecurityScopedURLs() {
        for url in securityScopedURLs {
            url.stopAccessingSecurityScopedResource()
        }
        securityScopedURLs = []
    }
    
    private func formatEta(_ seconds: Double) -> String {
        if seconds < 1 { return "0s" }
        if seconds > 3600 { return "> 1h" }
        let s = Int(seconds) % 60
        let m = Int(seconds) / 60
        if m > 0 { return "\(m)m \(s)s" }
        return "\(s)s"
    }
}
