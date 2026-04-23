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
from .thumbnail_server import thumbnail_registry

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
                print(f"PROGRESS:{percentage:.4f}", flush=True)
                print(f"SPEED:{speed / (1024*1024):.2f} MB/s", flush=True)
                print(f"ETA:{int(eta)}s", flush=True)

    def close(self):
        self.f.close()

    def __getattr__(self, name):
        return getattr(self.f, name)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

import uuid

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

async def cleanup_thumbnail(transfer_id: str):
    """v1.7.1 Proactive Early Cleanup: Evict mapping after handshake window (30s)."""
    await asyncio.sleep(30)
    thumbnail_registry.pop(transfer_id, None)
    # print(f"🧹 [DEBUG] [Memory] Proactively evicted transfer ID: {transfer_id}")

async def send_to_device(file_paths: List[str], device_ip: str, port: int = 8000, target_name: str = None) -> None:
    """Performs the handshake and file upload to a target device (supports multiple files)."""
    device_ip, port = parse_device_target(device_ip, port)
    target_url = f"http://{device_ip}:{port}"
    my_name = HOSTNAME
    display_name = target_name or device_ip

    total_size = sum(os.path.getsize(f) for f in file_paths)
    
    # ⚡ PHASE 2 Polish: Register Thumbnail dynamically without blocking the thread
    transfer_id = str(uuid.uuid4())
    first_file_path = os.path.abspath(file_paths[0])
    
    # ⚡ [v1.7.2] IPC Bridge Registration
    # Previously, direct dictionary writes were lost due to process isolation.
    # We now notify the persistent Daemon (8081) via loopback POST.
    try:
        async with httpx.AsyncClient(timeout=1.0) as client:
            await client.post(
                "http://127.0.0.1:8081/internal/register",
                json={"id": transfer_id, "path": first_file_path, "expiry": time.time() + 600}
            )
        # print(f"✅ [DEBUG] [IPC] Registered mapping with daemon: {transfer_id}")
    except Exception as e:
        print(f"⚠️ [DEBUG] [IPC] Could not notify daemon: {e}")

    await asyncio.sleep(0.01) # Surgical buffer for memory visibility safety
    
    if len(file_paths) == 1:
        handshake_name = os.path.basename(file_paths[0])
    else:
        handshake_name = f"{os.path.basename(file_paths[0])} and {len(file_paths) - 1} more files"

    print(f"🤝 Handshaking with {display_name} for {len(file_paths)} files...", flush=True)
    
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
                        "count": len(file_paths),
                        "id": transfer_id
                    }
                    resp = await client.post(
                        f"{target_url}/request-transfer",
                        json=payload,
                        timeout=60.0
                    )
                    resp.raise_for_status()
                    data = resp.json()
                    
                    if not data.get("accepted", False):
                        print(f"🚫 Transfer declined by {display_name}.", flush=True)
                        return

                    print(f"🚀 Transfer accepted! Sending {len(file_paths)} files...", flush=True)
                    files = []
                    try:
                        for i, path in enumerate(file_paths):
                            field_name = f"file_{i}"
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
                            print(f"❌ Upload failed with status {upload_resp.status_code}: {upload_resp.text}")
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

    # Remove Stabilization Delay: Native DNS-SD is instant.
    print("⚡ Instant discovery initiated...", flush=True)

    found_devices, last_device = await discover_devices()

    if not found_devices:
        if last_device:
            print("⚠️ No new devices found. Using last known device as fallback.", flush=True)
            found_devices = [last_device]
        else:
            print("❌ No QuickDrop devices found.", flush=True)
            return

    print("\n--- AVAILABLE DEVICES ---")
    for i, dev in enumerate(found_devices):
        print(f"{i + 1}. {dev['name']} ({dev['ip']}:{dev['port']})")

    # Auto-target discovered QuickDrop devices if exactly one exists
    quickdrop_devices = [dev for dev in found_devices if "QuickDrop" in dev["name"]]
    if len(quickdrop_devices) == 1:
        target = quickdrop_devices[0]
        await send_to_device(valid_paths, target["ip"], target["port"], target["name"])
        return

    if len(found_devices) != 1:
        print("\nMultiple devices found. Specify one using --to <IP>")
        return

    target = found_devices[0]
    await send_to_device(valid_paths, target["ip"], target["port"], target["name"])
