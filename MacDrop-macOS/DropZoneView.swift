import SwiftUI
import UniformTypeIdentifiers

struct DropZoneView: View {
    var onFilesDropped: ([URL]) -> Void
    
    @State private var isTargeted = false
    
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray.and.arrow.down.fill")
                .font(.system(size: 36))
                .foregroundColor(isTargeted ? .accentColor : .secondary)
            
            Text(isTargeted ? "Release to Send" : "Drop Files Here")
                .font(.headline)
        }
        .frame(width: 260, height: 140)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isTargeted ? Color.accentColor : Color.clear, lineWidth: 2)
        )
        .onDrop(of: [UTType.fileURL.identifier], isTargeted: $isTargeted) { providers in
            handleDrop(providers)
        }
    }
    
    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        var urls: [URL] = []
        let group = DispatchGroup()
        
        for provider in providers {
            group.enter()
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { data, _ in
                defer { group.leave() }
                
                guard let data = data as? Data,
                      let url = URL(dataRepresentation: data, relativeTo: nil) else { return }
                
                // 🚫 Skip directories (IMPORTANT)
                var isDir: ObjCBool = false
                if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                    print("⚠️ Skipping directory: \(url.lastPathComponent)")
                    return
                }
                
                urls.append(url)
            }
        }
        
        group.notify(queue: .main) {
            if urls.isEmpty {
                print("❌ No valid files dropped")
                return
            }
            onFilesDropped(urls)
        }
        
        return true
    }
}
