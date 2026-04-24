import Foundation
import UIKit

class QuickDropAdvertiser: NSObject, NetServiceDelegate {
    private var netService: NetService?
    
    func start(port: Int = 8000) {
        let deviceName = UIDevice.current.name.prefix(20).components(separatedBy: CharacterSet.alphanumerics.inverted).joined()
        let serviceName = "QuickDrop-\(deviceName)"
        
        netService = NetService(domain: "local.", type: "_http._tcp.", name: serviceName, port: Int32(port))
        netService?.delegate = self
        netService?.publish()
        
        print("📡 iOS Advertiser started as: \(serviceName)")
    }
    
    func stop() {
        netService?.stop()
        netService = nil
    }
    
    // MARK: - NetServiceDelegate
    func netServiceDidPublish(_ sender: NetService) {
        print("✅ iOS mDNS Service Published: \(sender.name)")
    }
    
    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        print("❌ iOS mDNS Publication Failed: \(errorDict)")
    }
}
