import Foundation
import UIKit
import AVFoundation
import PDFKit

class QuickDropThumbnailProvider {
    static let shared = QuickDropThumbnailProvider()
    
    func generateMetadata(for urls: [URL]) -> TransferPreviewMetadata {
        let transferId = UUID().uuidString
        let count = urls.count
        var totalSize: Int64 = 0
        for url in urls {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? Int64 {
                totalSize += size
            }
        }
        
        let heroURL = selectHeroURL(from: urls)
        let fileType = classifyFileType(url: heroURL)
        let previewMode = determinePreviewMode(for: urls, heroType: fileType)
        let protocolFileName = buildProtocolFileName(for: urls)
        let displayTitle = buildDisplayTitle(fileType: fileType, count: count)
        
        var metadata = TransferPreviewMetadata(
            transferId: transferId,
            fileType: fileType,
            previewMode: previewMode,
            count: count,
            heroURL: heroURL,
            allURLs: urls,
            protocolFileName: protocolFileName,
            displayTitle: displayTitle,
            totalSize: totalSize,
            localPreviewImage: nil
        )
        
        metadata.localPreviewImage = generateThumbnail(for: heroURL)
        return metadata
    }
    
    private func selectHeroURL(from urls: [URL]) -> URL {
        for url in urls {
            let type = classifyFileType(url: url)
            if type == "image" || type == "video" {
                return url
            }
        }
        return urls[0]
    }

    private func determinePreviewMode(for urls: [URL], heroType: String) -> String {
        if urls.count == 1 {
            switch heroType {
            case "image": return "SINGLE_IMAGE"
            case "video": return "SINGLE_VIDEO"
            default: return "SINGLE_FILE"
            }
        }

        let uniqueTypes = Set(urls.map { classifyFileType(url: $0) })
        return uniqueTypes.count == 1 ? "MULTIPLE_SAME" : "MULTIPLE_MIXED"
    }

    private func buildProtocolFileName(for urls: [URL]) -> String {
        guard let first = urls.first else { return "file" }
        if urls.count == 1 {
            return first.lastPathComponent
        }
        return "\(first.lastPathComponent) + \(urls.count - 1)"
    }

    private func buildDisplayTitle(fileType: String, count: Int) -> String {
        if count > 1 {
            return "\(count) Files"
        }

        switch fileType {
        case "image": return "Image"
        case "video": return "Video"
        case "pdf": return "PDF"
        default: return "File"
        }
    }
    
    private func classifyFileType(url: URL) -> String {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg", "png", "heic", "webp": return "image"
        case "mp4", "mov", "m4v", "avi": return "video"
        case "pdf": return "pdf"
        default: return "other"
        }
    }
    
    func generateThumbnail(for url: URL) -> UIImage? {
        let type = classifyFileType(url: url)
        let size = CGSize(width: 300, height: 300)
        
        switch type {
        case "image":
            return scaleImage(at: url, to: size)
        case "video":
            return generateVideoThumbnail(at: url)
        case "pdf":
            return generatePdfThumbnail(at: url)
        default:
            return nil
        }
    }
    
    private func scaleImage(at url: URL, to size: CGSize) -> UIImage? {
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        
        let aspectWidth = size.width / image.size.width
        let aspectHeight = size.height / image.size.height
        let aspectRatio = min(aspectWidth, aspectHeight)
        
        let newSize = CGSize(width: image.size.width * aspectRatio, height: image.size.height * aspectRatio)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
    
    private func generateVideoThumbnail(at url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        
        let time = CMTime(seconds: 1, preferredTimescale: 60)
        guard let cgImage = try? generator.copyCGImage(at: time, actualTime: nil) else { return nil }
        
        return UIImage(cgImage: cgImage)
    }
    
    private func generatePdfThumbnail(at url: URL) -> UIImage? {
        guard let document = PDFDocument(url: url),
              let page = document.page(at: 0) else { return nil }
        
        return page.thumbnail(of: CGSize(width: 300, height: 300), for: .mediaBox)
    }
}
