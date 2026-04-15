import os
import mimetypes
import httpx
import socket
import asyncio
import time
import base64
import subprocess
import tempfile
from typing import List, Tuple, Optional
from fastapi import UploadFile, HTTPException
from pydantic import BaseModel
from .config import SAVE_DIR, HOSTNAME
from .discovery import discover_devices
from .permission import get_file_type_label

class TransferRequest(BaseModel):
    """Data model for an incoming transfer request."""
    fileName: str
    fileSize: int
    deviceName: str
    fileType: Optional[str] = "file"
    previewMode: Optional[str] = "SINGLE_FILE"
    count: Optional[int] = 1
    id: Optional[str] = None

class ProgressWrapper:
    """Wrapper around a file-like object to track read progress and report it to stdout."""
    def __init__(self, f, total_size, file_name):
        self.f = f
        self.total_size = total_size
        self.file_name = file_name
        self.bytes_read = 0
        self.start_time = time.time()
        self.last_reported_time = 0

    def read(self, size=-1):
        chunk = self.f.read(size)
        if chunk:
            self.bytes_read += len(chunk)
            self._report_progress()
        return chunk

    def _report_progress(self):
        now = time.time()
        # Report at most every 0.1 seconds to avoid flooding stdout
        if now - self.last_reported_time > 0.3 or self.bytes_read == self.total_size:
            self.last_reported_time = now
            elapsed = now - self.start_time
            if elapsed > 0:
                speed = self.bytes_read / elapsed # bytes/sec
                percentage = self.bytes_read / self.total_size if self.total_size > 0 else 1.0
                eta = (self.total_size - self.bytes_read) / speed if speed > 0 else 0
                
                # Format for easy parsing by Swift/Kotlin
                print(f"PROGRESS:{percentage:.4f}")
                print(f"SPEED:{speed / (1024*1024):.2f} MB/s")
                print(f"ETA:{int(eta)}s")

    def close(self):
        self.f.close()

    def __getattr__(self, name):
        return getattr(self.f, name)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

async def generate_thumbnail(file_path: str) -> str:
    """Generates a small Base64-encoded thumbnail for images using macOS native 'sips'."""
    print(f"📸 [DEBUG] Processing file: {file_path}")
    mime_type, _ = mimetypes.guess_type(file_path)
    print(f"📸 [DEBUG] MIME detected: {mime_type}")
    
    ext = os.path.splitext(file_path)[1].lower()
    
    # 🖼️ Hard image check with extension fallback
    if not mime_type or not mime_type.startswith("image/"):
        if ext not in {'.jpg', '.jpeg', '.png', '.webp', '.heic', '.bmp'}:
            print(f"❌ [DEBUG] Not an image extension ({ext}), skipping thumbnail")
            return None
        else:
            print(f"⚠️ [DEBUG] MIME failed, but extension {ext} is supported. Proceeding...")

    try:
        # 🧪 Use native macOS 'sips' (no extra dependencies like Pillow required)
        with tempfile.NamedTemporaryFile(suffix=".jpg") as tmp:
            # -Z 300 ensures max dimension is 300px while maintaining aspect ratio
            cmd = ["sips", "-Z", "300", "-s", "format", "jpeg", file_path, "--out", tmp.name]
            result = subprocess.run(cmd, capture_output=True, check=False)
            
            if result.returncode == 0:
                with open(tmp.name, "rb") as f:
                    encoded = base64.b64encode(f.read()).decode("utf-8")
                    print(f"✅ [DEBUG] Generated thumbnail for: {os.path.basename(file_path)} (Size: {len(encoded)} bytes)")
                    return encoded
            else:
                stderr = result.stderr.decode('utf-8')
                print(f"⚠️ [DEBUG] 'sips' failed for {file_path}: {stderr}")
                return None
    except Exception as e:
        print(f"❌ [DEBUG] Thumbnail generation error: {e}")
        return None
    return None

async def save_upload_file(file: UploadFile) -> str:
    """Saves an uploaded file to the local storage directory in chunks with safety checks."""
    # Debug: Log raw filename
    raw_filename = file.filename or "unnamed_file"
    print(f"DEBUG: Raw filename received: {repr(raw_filename)}")

    # Sanitize filename to prevent directory traversal
    safe_filename = os.path.basename(raw_filename)
    
    # Ensure SAVE_DIR exists
    os.makedirs(SAVE_DIR, exist_ok=True)
    
    final_path = os.path.join(SAVE_DIR, safe_filename)
    part_path = final_path + ".part"
    
    print(f"📥 Saving partial file: {repr(safe_filename)}.part")
    
    try:
        # Handle existing files (overwrite .part)
        if os.path.exists(part_path):
            os.remove(part_path)

        # Check for empty file by reading the first chunk
        first_chunk = await file.read(1024 * 1024)
        if not first_chunk:
            print(f"ℹ️ Received empty file: {repr(safe_filename)}")
            # Create empty file
            with open(final_path, "wb") as buffer:
                pass
            return final_path

        # If not empty, write to .part file
        with open(part_path, "wb") as buffer:
            buffer.write(first_chunk)
            while content := await file.read(1024 * 1024):  # 1MB chunks
                buffer.write(content)
        
        # Finalize the file
        if os.path.exists(final_path):
            os.remove(final_path)
        os.rename(part_path, final_path)
        print(f"✅ Upload completed → renaming file to {repr(safe_filename)}")
        return final_path
    
    except Exception as e:
        print(f"❌ Error saving file {repr(safe_filename)}: {e}")
        if os.path.exists(part_path):
            try:
                os.remove(part_path)
            except:
                pass
        raise HTTPException(status_code=500, detail=f"Server error while saving: {str(e)}")

async def save_multiple_files(files: List[UploadFile], expected_size: int = None) -> dict:
    """Helper to save multiple files and return a summary of success/failure with integrity check."""
    success = []
    failed = []
    actual_total_size = 0
    
    for file in files:
        try:
            path = await save_upload_file(file)
            size = os.path.getsize(path)
            actual_total_size += size
            success.append(os.path.basename(path))
        except Exception as e:
            failed.append({"file": file.filename, "error": str(e)})
            
    # Verification logic
    size_verified = False
    if expected_size is not None:
        print(f"🧐 Integrity Check - Expected: {expected_size}, Actual: {actual_total_size}")
        if actual_total_size == expected_size:
            size_verified = True
        else:
            print(f"❌ Size mismatch detected! Corruption or incomplete upload.")
            # If size mismatch, we mark the whole thing as failed even if files were saved
            return {
                "status": "error", 
                "message": f"Integrity check failed: Received {actual_total_size} bytes but expected {expected_size}",
                "size_verified": False,
                "failed": failed + [{"file": "Batch Verification", "error": "Total size mismatch"}]
            }

    if not failed:
        return {"status": "success", "files": success, "size_verified": size_verified}
    elif not success:
        return {"status": "error", "message": "All files failed to upload", "failed": failed, "size_verified": size_verified}
    else:
        return {"status": "partial_success", "success": success, "failed": failed, "size_verified": size_verified}

def parse_device_target(device_ip: str, default_port: int = 8000) -> Tuple[str, int]:
    """Parses a device target string into an (IP, Port) tuple."""
    if ":" in device_ip:
        host, port_text = device_ip.rsplit(":", 1)
        if host and port_text.isdigit():
            return host, int(port_text)
    return device_ip, default_port

async def send_to_device(file_paths: List[str], device_ip: str, port: int = 8000, target_name: str = None) -> None:
    """Performs the handshake and file upload to a target device (supports multiple files)."""
    device_ip, port = parse_device_target(device_ip, port)
    target_url = f"http://{device_ip}:{port}"
    my_name = HOSTNAME
    display_name = target_name or device_ip

    # Prepare handshake info
    total_size = sum(os.path.getsize(f) for f in file_paths)
    
    # 🔥 ALWAYS generate hero thumbnail for the first file in batch
    thumbnail_data = await generate_thumbnail(file_paths[0])
    print(f"🧪 [DEBUG] HERO Thumbnail generated? {thumbnail_data is not None}")
    
    if len(file_paths) == 1:
        handshake_name = os.path.basename(file_paths[0])
    else:
        handshake_name = f"{os.path.basename(file_paths[0])} and {len(file_paths) - 1} more files"

    print(f"🤝 Handshaking with {display_name} for {len(file_paths)} files...")
    
    retry_delays = [2, 5, 10]
    attempt = 0
    max_attempts = len(retry_delays) + 1

    while attempt < max_attempts:
        file_handles = []
        try:
            async with httpx.AsyncClient() as client:
                try:
                    # Fix multi-file type detection (base it on the first file, not the handshake name)
                    first_file = os.path.basename(file_paths[0])
                    
                    # Phase 1: Request permission
                    payload = {
                        "fileName": handshake_name, 
                        "fileSize": total_size, 
                        "deviceName": my_name,
                        "fileType": get_file_type_label(first_file),
                        "previewMode": "SINGLE_FILE" if len(file_paths) == 1 else "MULTIPLE",
                        "count": len(file_paths)
                    }
                    resp = await client.post(
                        f"{target_url}/request-transfer",
                        json=payload,
                        timeout=60.0
                    )
                    resp.raise_for_status()
                    data = resp.json()
                    
                    if not data.get("accepted", False):
                        print(f"🚫 Transfer declined by {display_name}.")
                        return

                    # Phase 2: Upload files
                    print(f"🚀 Transfer accepted! Sending {len(file_paths)} files...")
                    
                    field_name = "file"
                    files = []
                    
                    try:
                        for path in file_paths:
                            f = open(path, "rb")
                            size = os.path.getsize(path)
                            wrapped_f = ProgressWrapper(f, size, os.path.basename(path))
                            file_handles.append(wrapped_f)
                            mime_type, _ = mimetypes.guess_type(path)
                            mime_type = mime_type or "application/octet-stream"
                            files.append((field_name, (os.path.basename(path), wrapped_f, mime_type)))

                        async with httpx.AsyncClient(timeout=None) as upload_client:
                            upload_resp = await upload_client.post(f"{target_url}/upload", files=files)

                        if upload_resp.status_code == 200:
                            print(f"✅ Successfully sent {len(file_paths)} files to {display_name}!")
                            return 
                        else:
                            print(f"❌ Upload failed with status {upload_resp.status_code}")
                            return 
                    finally:
                        # Handles are closed in outer finally
                        pass

                except (httpx.ConnectError, httpx.ConnectTimeout, httpx.NetworkError) as e:
                    attempt += 1
                    if attempt < max_attempts:
                        delay = retry_delays[attempt - 1]
                        print(f"⚠️ Network error: {e}. RETRYING: Waiting for Network... (Retrying in {delay}s)")
                        await asyncio.sleep(delay)
                    else:
                        print(f"❌ Max retries reached. Error: {e}")
                        raise
                except Exception as e:
                    print(f"❌ Non-retryable error during transfer: {e}")
                    raise

                finally:
                    # Properly close all file handles after request
                    for f in file_handles:
                        try:
                            f.close()
                        except:
                            pass
        except Exception as e:
            print(f"❌ Error during transfer: {e}")
            break

async def send_file(file_paths: List[str], device_ip: str = None) -> None:
    """Main entry point for sending files, including discovery if IP is missing."""
    # Validate paths
    valid_paths = []
    for path in file_paths:
        if os.path.exists(path):
            valid_paths.append(path)
        else:
            print(f"❌ File not found: {path}")
    
    if not valid_paths:
        return

    if device_ip:
        await send_to_device(valid_paths, device_ip)
        return

    found_devices = await discover_devices()

    if not found_devices:
        print("❌ No QuickDrop devices found.")
        return

    print("\n--- AVAILABLE DEVICES ---")
    for i, dev in enumerate(found_devices):
        print(f"{i + 1}. {dev['name']} ({dev['ip']}:{dev['port']})")

    # Auto-target Android if only one exists (legacy logic)
    android_devices = [dev for dev in found_devices if "Android" in dev["name"]]
    if len(android_devices) == 1:
        target = android_devices[0]
        await send_to_device(valid_paths, target["ip"], target["port"], target["name"])
        return

    if len(found_devices) != 1:
        print("\nMultiple devices found. Specify one using --to <IP>")
        return

    target = found_devices[0]
    await send_to_device(valid_paths, target["ip"], target["port"], target["name"])
