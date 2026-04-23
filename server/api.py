import time
import asyncio
import traceback
from typing import List, Optional
from contextlib import asynccontextmanager
from fastapi import FastAPI, File, UploadFile, Request, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel
import os
import subprocess
import tempfile
from .config import IDLE_TIMEOUT
from . import permission
from .permission import ask_mac_permission
from .transfer import TransferRequest, save_upload_file, save_multiple_files
from .discovery import start_mdns_broadcast, stop_mdns_broadcast
from .thumbnail_server import start_thumbnail_server

# Global state for inactivity monitoring
last_activity_time = time.time()
active_transfers = 0

async def monitor_inactivity():
    """Background task to shut down the server if idle for too long."""
    global last_activity_time, active_transfers
    while True:
        await asyncio.sleep(10)
        idle_time = time.time() - last_activity_time
        if active_transfers == 0 and idle_time > IDLE_TIMEOUT:
            print(f"🛑 Idle timeout reached ({int(idle_time)}s) and no active transfers. Monitoring stopped.")
            # asyncio.get_event_loop().stop()  # Removed as per requirement
            break

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manages the startup and shutdown of auxiliary services (mDNS, inactivity monitor)."""
    # Startup
    await start_mdns_broadcast()
    start_thumbnail_server() # ✅ Phase 2: Orchestrate Sidecar
    inactivity_task = asyncio.create_task(monitor_inactivity())
    
    yield
    
    # Shutdown
    inactivity_task.cancel()
    await stop_mdns_broadcast()

app = FastAPI(lifespan=lifespan)

@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Global middleware to log all incoming HTTP requests for better visibility."""
    print(f"🌐 [HTTP] {request.method} {request.url.path}")
    response = await call_next(request)
    return response

@app.get("/")
def health():
    return {"status": "ok", "service": "QuickDrop"}

@app.post("/decide-transfer")
async def decide_transfer(accepted: bool, id: Optional[str] = None):
    """Endpoint called by the Swift app to resolve a pending request (ID-based, strict)."""
    if id and id in permission.pending_decisions:
        future = permission.pending_decisions[id]
        if not future.done():
            future.set_result(accepted)
            return {"status": "success"}
    
    # Strict logging for missing/invalid request IDs
    if not id:
        print("❌ Missing request ID in decision API", flush=True)
    else:
        print(f"❌ Handshake ID mismatch or expired: {id}", flush=True)
        
    return {"status": "error", "message": f"No pending decision found for ID: {id}"}

@app.post("/request-transfer")
async def request_transfer(request: TransferRequest, info: Request):
    """Endpoint for handshaking and requesting user permission to transfer files."""
    print("🔥🔥🔥 /request-transfer HIT 🔥🔥🔥")
    print(f"[DEBUG] From: {request.deviceName}, File: {request.fileName}, Size: {request.fileSize}")
    
    # Validation Guard
    if not request.fileName or not request.deviceName:
        print("❌ Invalid request payload: Missing fileName or deviceName")
        return {"accepted": False, "error": "Invalid payload"}

    global last_activity_time
    last_activity_time = time.time()
    
    try:
        print(f"⏳ Waiting for user decision via Mac popup (Source IP: {info.client.host})...", flush=True)
        # Pass the sender's IP and transfer ID to macOS for async thumbnail fetching
        accepted = await ask_mac_permission(
            device_name=request.deviceName, 
            filename=request.fileName, 
            file_size=request.fileSize, 
            file_type=request.fileType,
            preview_mode=request.previewMode,
            count=request.count,
            device_ip=info.client.host,
            transfer_id=request.id
        )
        
        print(f"📥 Decision received: {accepted}")
        
        if accepted:
            print(f"✅ User accepted transfer of '{request.fileName}'")
        else:
            print(f"❌ User declined transfer of '{request.fileName}' (or timeout)")
            
        return {"accepted": bool(accepted)}
    except Exception as e:
        print(f"❌ Error in /request-transfer: {e}")
        traceback.print_exc()
        return {"status": "error", "message": str(e), "accepted": False}

@app.post("/upload")
async def upload_file(request: Request):
    """Endpoint for receiving one or more files dynamically."""
    upload_list = []
    
    try:
        form = await request.form()
        for key, value in form.multi_items():
            # Starlette UploadFile objects expose a filename attribute
            if hasattr(value, "filename") and value.filename:
                upload_list.append(value)
    except Exception as e:
        print(f"⚠️ Form parsing error (potentially empty): {e}")

    if not upload_list:
        print("❌ No files detected in the request.")
        return {"status": "error", "message": "No files uploaded."}

    print(f"📥 [DEBUG] /upload starting for {len(upload_list)} total files")
    try:
        # Extract expected size for validation
        expected_size = request.headers.get("X-File-Size")
        if expected_size:
            expected_size = int(expected_size)
            print(f"🔍 Expected total batch size: {expected_size} bytes")

        # Handle multiple files using the new helper
        result = await save_multiple_files(upload_list, expected_size=expected_size)
        
        if result["status"] == "success":
            print(f"🎉 Successfully saved {len(result['files'])} files.")
        elif result["status"] == "partial_success":
            print(f"⚠️ Partial success: {len(result['success'])} saved, {len(result['failed'])} failed.")
            
        return result
    except Exception as e:
        print(f"❌ Error in /upload: {e}")
        traceback.print_exc()
        return {"status": "error", "message": str(e)}
