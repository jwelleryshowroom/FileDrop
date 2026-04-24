import Foundation
import UIKit

class QuickDropAdvertiser: NSObject, NetServiceDelegate {
    private var netService: NetService?
    
    func start(port: Int = 8000) {
        let rawName = UIDevice.current.name
        let sanitized = rawName.components(separatedBy: CharacterSet.alphanumerics.inverted).joined()
        let deviceName = String(sanitized.prefix(20))
        let serviceName = "QuickDrop-\(deviceName)"
        
        let service = NetService(domain: "local.", type: "_http._tcp.", name: serviceName, port: Int32(port))
        service.delegate = self
        
        // Include peer-to-peer for better discoverability on local network
        service.includesPeerToPeer = true
        
        // Schedule on main RunLoop to ensure mDNS events are processed
        service.schedule(in: .main, forMode: .common)
        
        // Set TXT record with platform info for cross-platform identification
        let txtData: [String: Data] = [
            "platform": "ios".data(using: .utf8)!,
            "version": "1.1.0".data(using: .utf8)!
        ]
        service.setTXTRecord(NetService.data(fromTXTRecord: txtData))
        
        service.publish()
        netService = service
        
        print("📡 iOS Advertiser started as: \(serviceName) on port \(port)")
    }
    
    func stop() {
        netService?.stop()
        netService = nil
    }
    
    // MARK: - NetServiceDelegate
    func netServiceDidPublish(_ sender: NetService) {
        print("✅ iOS mDNS Service Published: \(sender.name) on port \(sender.port)")
    }
    
    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        print("❌ iOS mDNS Publication Failed: \(errorDict)")
    }
}
