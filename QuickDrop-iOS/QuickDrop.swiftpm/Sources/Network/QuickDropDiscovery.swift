import Foundation
import UIKit

class QuickDropDiscovery: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    @Published var devices: [QuickDropDevice] = []
    private var browser = NetServiceBrowser()
    private var pendingServices: Set<NetService> = []
    
    override init() {
        super.init()
        browser.delegate = self
    }
    
    func start() {
        devices.removeAll()
        browser.searchForServices(ofType: "_http._tcp.", inDomain: "local.")
        print("🔍 iOS Discovery scanning...")
    }
    
    func stop() {
        browser.stop()
    }
    
    // MARK: - NetServiceBrowserDelegate
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        if service.name.contains("QuickDrop") && !service.name.contains(UIDevice.current.name.alphanumeric) {
            print("🎯 Found candidate: \(service.name)")
            pendingServices.insert(service)
            service.delegate = self
            service.resolve(withTimeout: 5.0)
        }
    }
    
    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        DispatchQueue.main.async {
            self.devices.removeAll { $0.deviceName == service.name }
        }
    }
    
    // MARK: - NetServiceDelegate
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses else { return }
        
        var ipAddress: String?
        
        for data in addresses {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            data.withUnsafeBytes { ptr in
                guard let sockaddr = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }
                
                // Prioritize IPv4
                if sockaddr.pointee.sa_family == AF_INET {
                    if getnameinfo(sockaddr, socklen_t(data.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                        ipAddress = String(cString: hostname)
                    }
                }
            }
            if ipAddress != nil { break }
        }
        
        // Fallback to first address if no IPv4 found
        if ipAddress == nil, let data = addresses.first {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            data.withUnsafeBytes { ptr in
                guard let sockaddr = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }
                if getnameinfo(sockaddr, socklen_t(data.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                    ipAddress = String(cString: hostname)
                }
            }
        }
        
        guard let resolvedIP = ipAddress else { return }
        
        DispatchQueue.main.async {
            if !self.devices.contains(where: { $0.deviceName == sender.name }) {
                let device = QuickDropDevice(deviceName: sender.name, ipAddress: resolvedIP)
                self.devices.append(device)
                print("✅ Resolved: \(sender.name) at \(resolvedIP)")
            }
            self.pendingServices.remove(sender)
        }
    }
    
    func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        print("❌ Failed to resolve \(sender.name): \(errorDict)")
        pendingServices.remove(sender)
    }
}
