package com.ankit.filedrop

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking

class MacDropReceiverServer(
    private val context: Context,
    private val onRequest: suspend (fileName: String, fileSize: Long, deviceName: String) -> Boolean,
    private val onUploadComplete: (fileName: String, fileSize: Long) -> Unit,
    private val onUploadFailed: (fileName: String) -> Unit
) : NanoHTTPD(8000) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/request-transfer" -> handleRequestTransfer(session)
            "/upload" -> handleUpload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleRequestTransfer(session: IHTTPSession): Response {
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val jsonStr = map["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No data")
            val json = JSONObject(jsonStr)
            val fileName = json.getString("fileName")
            val fileSize = json.getLong("fileSize")
            val deviceName = json.optString("deviceName", "Unknown Device")

            val accepted = runBlocking {
                onRequest(fileName, fileSize, deviceName)
            }

            val responseJson = JSONObject().put("accepted", accepted)
            newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
        } catch (e: Exception) {
            Log.e("MacDropServer", "Error handling request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only POST allowed")
        }

        var fileName = "received_file"
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)

            val tempFilePath = files["file"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file uploaded")
            val tempFile = File(tempFilePath)
            fileName = sanitizeFileName(
                session.parameters["file"]?.firstOrNull()
                    ?: session.headers["filename"]
                    ?: session.headers["x-filename"]
                    ?: "received_file"
            )

            saveToDownloads(fileName, tempFile)
            onUploadComplete(fileName, tempFile.length())

            Log.d("MacDropServer", "Saved received file: $fileName")

            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "File saved")
        } catch (e: Exception) {
            onUploadFailed(fileName)
            Log.e("MacDropServer", "Error handling upload", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun saveToDownloads(fileName: String, tempFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MacDrop")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw IllegalStateException("Unable to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to open MediaStore output stream")
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MacDrop"
            )
            downloadsDir.mkdirs()
            tempFile.inputStream().use { input ->
                FileOutputStream(File(downloadsDir, fileName)).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank {
            "received_file"
        }
    }
}
