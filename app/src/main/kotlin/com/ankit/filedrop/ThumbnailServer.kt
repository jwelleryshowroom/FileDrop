package com.ankit.filedrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import fi.iki.elonen.NanoHTTPD
import java.io.*

class ThumbnailServer(
    private val context: Context,
    private val transferMap: Map<String, Uri>
) : NanoHTTPD(8081) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/thumbnail") {
            val id = session.parameters["id"]?.firstOrNull()

            if (id != null && transferMap.containsKey(id)) {
                val uri = transferMap[id]!!

                try {
                    var bitmap: Bitmap? = null
                    val mimeType = context.contentResolver.getType(uri) ?: ""

                    // ✅ STEP 1: RESOLVE BITMAP (Safe Multi-format logic)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        println("📸 Thumbnail via loadThumbnail() for $uri")
                        bitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    } else {
                        if (mimeType.startsWith("video/")) {
                            println("🎬 Extracting video frame (fallback) for $uri")
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, uri)
                                bitmap = retriever.getFrameAtTime(0)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                retriever.release()
                            }
                        } else {
                            println("🖼️ Decoding image stream for $uri")
                            val inputStream = context.contentResolver.openInputStream(uri)
                            bitmap = BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                        }
                    }

                    // ✅ STEP 2: SCALE & COMPRESS (Security & Latency Guard)
                    if (bitmap != null) {
                        val stream = ByteArrayOutputStream()
                        
                        // ✅ Aspect-ratio safe scaling (Phase 2 Polish)
                        val maxSize = 512
                        val originalWidth = bitmap.width
                        val originalHeight = bitmap.height
                        val ratio = originalWidth.toFloat() / originalHeight.toFloat()

                        val (targetWidth, targetHeight) = if (ratio > 1) {
                            // Landscape
                            maxSize to (maxSize / ratio).toInt()
                        } else {
                            // Portrait or square
                            (maxSize * ratio).toInt() to maxSize
                        }

                        val resizedBitmap = Bitmap.createScaledBitmap(
                            bitmap,
                            targetWidth.coerceAtLeast(1),
                            targetHeight.coerceAtLeast(1),
                            true
                        )
                        
                        // Increase quality to 55% for sharper previews (optimized for current LAN system)
                        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 55, stream)
                        val bytes = stream.toByteArray()

                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            ByteArrayInputStream(bytes),
                            bytes.size.toLong()
                        )
                    } else {
                        println("⚠️ Thumbnail generation failed for URI: $uri")
                    }
                } catch (e: Exception) {
                    println("❌ Error generating thumbnail: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "Thumbnail Not Found"
        )
    }
}
