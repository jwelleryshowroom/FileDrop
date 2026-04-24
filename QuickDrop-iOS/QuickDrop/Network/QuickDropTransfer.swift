import Foundation

class QuickDropTransfer: NSObject, URLSessionTaskDelegate {
    static let shared = QuickDropTransfer()
    private var session: URLSession?
    private var startTime: Date?
    
    override init() {
        super.init()
        let config = URLSessionConfiguration.default
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }
    
    func sendFiles(urls: [URL], targetIp: String) {
        guard !urls.isEmpty else { return }
        
        let fileName = urls.count == 1 ? urls[0].lastPathComponent : "\(urls[0].lastPathComponent) + \(urls.count - 1)"
        var totalSize: Int64 = 0
        for url in urls {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? Int64 {
                totalSize += size
            }
        }
        
        // 1. Handshake
        let handshakeUrl = URL(string: "http://\(targetIp):8000/request-transfer")!
        var request = URLRequest(url: handshakeUrl)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: Any] = [
            "fileName": fileName,
            "fileSize": totalSize,
            "deviceName": UIDevice.current.name,
            "count": urls.count
        ]
        
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        print("🤝 iOS Handshaking with \(targetIp)...")
        
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                print("🚀 Handshake accepted! Starting upload...")
                self.startUpload(urls: urls, targetIp: targetIp, totalSize: totalSize)
            } else {
                print("❌ Handshake declined or failed")
            }
        }.resume()
    }
    
    private func startUpload(urls: [URL], targetIp: String, totalSize: Int64) {
        DispatchQueue.main.async {
            TransferStatus.shared.reset()
            TransferStatus.shared.isUploading = true
            TransferStatus.shared.fileName = urls.count == 1 ? urls[0].lastPathComponent : "\(urls[0].lastPathComponent) + \(urls.count - 1)"
            TransferStatus.shared.fileSize = self.formatBytes(totalSize)
        }
        
        let uploadUrl = URL(string: "http://\(targetIp):8000/upload")!
        var request = URLRequest(url: uploadUrl)
        request.httpMethod = "POST"
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.addValue(UIDevice.current.name, forHTTPHeaderField: "X-Device-Name")
        request.addValue(String(totalSize), forHTTPHeaderField: "X-File-Size")
        
        var body = Data()
        for url in urls {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"files\"; filename=\"\(url.lastPathComponent)\"\r\n".data(using: .utf8)!)
            body.append("Content-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
            if let fileData = try? Data(contentsOf: url) {
                body.append(fileData)
            }
            body.append("\r\n".data(using: .utf8)!)
        }
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        
        self.startTime = Date()
        let task = session?.uploadTask(with: request, from: body)
        task?.resume()
    }
    
    // MARK: - URLSessionTaskDelegate
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        let progress = Float(totalBytesSent) / Float(totalBytesExpectedToSend)
        let now = Date()
        if let start = startTime {
            let elapsed = now.timeIntervalSince(start)
            if elapsed > 0 {
                let bytesPerSecond = Double(totalBytesSent) / elapsed
                let speed = formatBytes(Int64(bytesPerSecond)) + "/s"
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
        DispatchQueue.main.async {
            TransferStatus.shared.isUploading = false
            if let error = error {
                print("❌ Upload failed: \(error.localizedDescription)")
            } else {
                print("✅ Upload complete!")
            }
        }
    }
    
    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
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
