import Foundation

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
        guard let data = sender.addresses?.first else { return }
        
        var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        data.withUnsafeBytes { ptr in
            guard let sockaddr = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }
            getnameinfo(sockaddr, socklen_t(data.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
        }
        
        let ipAddress = String(cString: hostname)
        
        DispatchQueue.main.async {
            if !self.devices.contains(where: { $0.deviceName == sender.name }) {
                let device = QuickDropDevice(deviceName: sender.name, ipAddress: ipAddress)
                self.devices.append(device)
                print("✅ Resolved: \(sender.name) at \(ipAddress)")
            }
            self.pendingServices.remove(sender)
        }
    }
    
    func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        print("❌ Failed to resolve \(sender.name): \(errorDict)")
        pendingServices.remove(sender)
    }
}
