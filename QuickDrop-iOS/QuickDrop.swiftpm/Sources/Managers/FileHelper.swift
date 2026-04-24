import Foundation
import Photos
import UIKit

class FileHelper {
    static let shared = FileHelper()
    
    /// URLs of files saved in the most recent receive session
    var lastReceivedURLs: [URL] = []
    
    var downloadsDirectory: URL {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0]
    }
    
    func getUniqueURL(for fileName: String) -> URL {
        let baseURL = downloadsDirectory.appendingPathComponent(fileName)
        if !FileManager.default.fileExists(atPath: baseURL.path) {
            return baseURL
        }
        
        let name = baseURL.deletingPathExtension().lastPathComponent
        let ext = baseURL.pathExtension
        var counter = 1
        
        while true {
            let newName = "\(name) (\(counter))\(ext.isEmpty ? "" : ".\(ext)")"
            let newURL = downloadsDirectory.appendingPathComponent(newName)
            if !FileManager.default.fileExists(atPath: newURL.path) {
                return newURL
            }
            counter += 1
        }
    }
    
    func saveFile(from tempURL: URL, originalName: String) -> URL? {
        let destinationURL = getUniqueURL(for: originalName)
        do {
            if FileManager.default.fileExists(atPath: destinationURL.path) {
                try? FileManager.default.removeItem(at: destinationURL)
            }
            try FileManager.default.moveItem(at: tempURL, to: destinationURL)
            print("✅ File saved to: \(destinationURL.path)")
            
            // Auto-export media (images/videos) to Photos Library
            if isMediaFile(destinationURL) {
                exportToPhotos(url: destinationURL)
            }
            
            return destinationURL
        } catch {
            print("❌ Failed to save file: \(error)")
            return nil
        }
    }
    
    private func isMediaFile(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        return isImageFile(ext) || isVideoFile(ext)
    }
    
    private func isImageFile(_ ext: String) -> Bool {
        return ["jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp", "tiff"].contains(ext)
    }
    
    private func isVideoFile(_ ext: String) -> Bool {
        return ["mp4", "mov", "m4v", "avi", "mkv"].contains(ext)
    }
    
    func exportToPhotos(url: URL) {
        let ext = url.pathExtension.lowercased()
        let isVideo = isVideoFile(ext)
        
        PHPhotoLibrary.requestAuthorization { status in
            guard status == .authorized || status == .limited else {
                print("❌ Photos permission denied")
                return
            }
            
            PHPhotoLibrary.shared().performChanges({
                if isVideo {
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                } else {
                    PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url)
                }
            }) { success, error in
                if success {
                    print("🖼️ Exported to Photos: \(url.lastPathComponent)")
                } else if let error = error {
                    print("❌ Failed to export to Photos: \(error.localizedDescription)")
                }
            }
        }
    }
    
    /// Present a share sheet for the saved files
    static func presentShareSheet(for urls: [URL]) {
        guard !urls.isEmpty else { return }
        
        DispatchQueue.main.async {
            let activityVC = UIActivityViewController(activityItems: urls, applicationActivities: nil)
            
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let rootVC = windowScene.windows.first?.rootViewController {
                var topVC = rootVC
                while let presented = topVC.presentedViewController {
                    topVC = presented
                }
                
                // iPad requires popover configuration
                if let popover = activityVC.popoverPresentationController {
                    popover.sourceView = topVC.view
                    popover.sourceRect = CGRect(x: topVC.view.bounds.midX, y: topVC.view.bounds.midY, width: 0, height: 0)
                    popover.permittedArrowDirections = []
                }
                
                topVC.present(activityVC, animated: true)
            }
        }
    }
    
    static func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
