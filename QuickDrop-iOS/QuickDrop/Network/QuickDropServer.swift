import Foundation
import Network

class QuickDropServer {
    private var listener: NWListener?
    
    func start(port: Int = 8000) {
        do {
            let params = NWParameters.tcp
            listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: UInt16(port))!)
            
            listener?.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("🚀 iOS Receiver Server ready on port \(port)")
                case .failed(let error):
                    print("❌ iOS Receiver Server failed: \(error)")
                default:
                    break
                }
            }
            
            listener?.newConnectionHandler = { connection in
                self.handleConnection(connection)
            }
            
            listener?.start(queue: .main)
        } catch {
            print("❌ Failed to start listener: \(error)")
        }
    }
    
    func stop() {
        listener?.cancel()
        listener = nil
    }
    
    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: .main)
        receiveRequest(on: connection)
    }
    
    private func receiveRequest(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { data, _, isComplete, error in
            if let data = data, !data.isEmpty {
                let request = String(data: data, encoding: .utf8) ?? ""
                if request.contains("POST /request-transfer") {
                    self.handleHandshake(connection, request: request)
                } else if request.contains("POST /upload") {
                    // Multipart handling would go here - complex for native NWConnection
                    // Suggest using GCDWebServer for full multipart support
                    print("📥 Received /upload request")
                }
            }
        }
    }
    
    private func handleHandshake(_ connection: NWConnection, request: String) {
        // Simple JSON extraction logic (in production use a real HTTP parser)
        guard let jsonStart = request.range(of: "{")?.lowerBound else { return }
        let jsonStr = String(request[jsonStart...])
        
        guard let jsonData = jsonStr.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              let fileName = json["fileName"] as? String,
              let fileSize = json["fileSize"] as? Int64,
              let deviceName = json["deviceName"] as? String else {
            return
        }
        
        DispatchQueue.main.async {
            TransferStatus.shared.incomingRequest = IncomingRequest(
                fileName: fileName,
                fileSize: fileSize,
                deviceName: deviceName,
                onDecision: { accepted in
                    let response = accepted ? "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" : "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                    connection.send(content: response.data(using: .utf8), completion: .contentProcessed({ _ in
                        connection.cancel()
                    }))
                    TransferStatus.shared.incomingRequest = nil
                }
            )
        }
    }
}
