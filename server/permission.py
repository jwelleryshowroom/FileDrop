import asyncio
import os
import json
import base64
import time
import uuid
from typing import Optional, Dict

# Dictionary of futures keyed by request_id to allow concurrent-safe decisions
pending_decisions: Dict[str, asyncio.Future] = {}

def get_file_type_label(filename: str) -> str:
    """Returns a user-friendly label for the file type based on extension."""
    extension = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if extension in {"jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"}:
        return "image"
    if extension == "pdf":
        return "pdf"
    if extension in {"mp4", "mov", "m4v", "mkv", "webm", "avi"}:
        return "video"
    return "other"

async def ask_mac_permission(
    device_name: str, 
    filename: str, 
    file_size: int, 
    file_type: str = "file",
    preview_mode: str = "SINGLE_FILE",
    count: int = 1,
    device_ip: str = None,
    transfer_id: str = None
) -> bool:
    """Signals the Swift app to show a premium preview and waits for the decision (Glass Pipe Relay)."""
    global pending_decisions
    
    # Priority: Use Android's transfer_id for deterministic handshake
    request_id = transfer_id if transfer_id else str(uuid.uuid4())
    
    # Thumbnails removed from handshake to ensure 100% transport stability
    thumbnail_base64 = None

    # Emit signal for Swift app to catch (passing metadata + unique ids)
    signal_data = {
        "id": request_id, 
        "fileName": filename,
        "fileSize": file_size,
        "deviceName": device_name,
        "fileType": file_type,
        "previewMode": preview_mode,
        "count": count,
        "deviceIp": device_ip
    }
    
    # Delimited protocol for reliable multi-chunk streaming
    print("INCOMING_REQUEST_START", flush=True)
    print(json.dumps(signal_data), flush=True)
    print("INCOMING_REQUEST_END", flush=True)
    
    # Initialize and wait for the decision future
    loop = asyncio.get_event_loop()
    future = loop.create_future()
    pending_decisions[request_id] = future
    
    try:
        # 45 second timeout for user response
        accepted = await asyncio.wait_for(future, timeout=45.0)
        return accepted
    except asyncio.TimeoutError:
        print(f"TIMEOUT:{request_id}", flush=True)
        return False
    finally:
        # Cleanup decision state
        if request_id in pending_decisions:
            del pending_decisions[request_id]
