import Foundation

struct QuickDropDevice: Identifiable, Hashable {
    let id = UUID()
    let deviceName: String
    let ipAddress: String
    var isResolving: Bool = false
    
    func hash(into hasher: inout Hasher) {
        hasher.combine(deviceName)
        hasher.combine(ipAddress)
    }
}

extension String {
    var alphanumeric: String {
        return components(separatedBy: CharacterSet.alphanumerics.inverted).joined()
    }
}
