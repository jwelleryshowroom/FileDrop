import Foundation
import GCDWebServer
import UIKit

class QuickDropThumbnailServer {
    static let shared = QuickDropThumbnailServer()
    private let server = GCDWebServer()
    private var registrations: [String: URL] = [:]
    private let queue = DispatchQueue(label: "com.quickdrop.thumbnail.registry")
    
    func register(transferId: String, url: URL) {
        queue.async {
            self.registrations[transferId] = url
        }
    }
    
    func unregister(transferId: String) {
        queue.async {
            self.registrations.removeValue(forKey: transferId)
        }
    }
    
    func start(port: Int = 8081) {
        server.addHandler(forMethod: "GET", path: "/thumbnail", request: GCDWebServerRequest.self, processBlock: { [weak self] request in
            guard let self = self,
                  let transferId = request.query?["id"] else {
                return GCDWebServerErrorResponse(statusCode: 400)
            }
            
            var sourceURL: URL?
            self.queue.sync {
                sourceURL = self.registrations[transferId]
            }
            
            guard let url = sourceURL else {
                return GCDWebServerErrorResponse(statusCode: 404)
            }
            
            guard let thumbnail = QuickDropThumbnailProvider.shared.generateThumbnail(for: url),
                  let data = thumbnail.jpegData(compressionQuality: 0.7) else {
                return GCDWebServerResponse(statusCode: 204)
            }
            
            return GCDWebServerDataResponse(data: data, contentType: "image/jpeg")
        })
        
        do {
            try server.start(options: [
                GCDWebServerOption_Port: port,
                GCDWebServerOption_BindToLocalhost: false
            ])
            print("🖼️ iOS Thumbnail Sidecar ready on port \(port)")
        } catch {
            print("❌ Failed to start Thumbnail Sidecar: \(error)")
        }
    }
    
    func stop() {
        if server.isRunning {
            server.stop()
        }
    }
}
