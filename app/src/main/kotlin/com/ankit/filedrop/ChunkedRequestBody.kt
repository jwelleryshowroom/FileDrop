package com.ankit.filedrop

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream

class ChunkedRequestBody(
    private val contentType: MediaType?,
    private val inputStream: InputStream
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun writeTo(sink: BufferedSink) {
        inputStream.use { input ->
            val buffer = ByteArray(4096)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
            }
        }
    }
}
