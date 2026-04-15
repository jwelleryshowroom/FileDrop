import asyncio
import os
import tempfile
import time
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel
import uvicorn

# Dynamic Thumbnail State (Phase 2 MacDrop)
thumbnail_registry = {}   # id -> (path, expiry)
thumbnail_cache = {}      # id -> bytes
in_progress = {}          # id -> Future (v1.7.1 Deduplication Lock)

# Lifecycle Trackers (v1.6.0 Refinement)
_server_task = None
_cleanup_task = None

class ThumbnailRegistration(BaseModel):
    id: str
    path: str
    expiry: float

app = FastAPI()

async def _native_ql_thumbnail(file_path: str) -> bytes:
    """Invokes native macOS QuickLook (qlmanage) to dynamically generate deep-file previews (Video/PDF/Doc)."""
    with tempfile.TemporaryDirectory() as tmp_dir:
        # qlmanage -t (thumbnail) -s (size) <file> -o <output_dir>
        cmd = ["qlmanage", "-t", "-s", "400", file_path, "-o", tmp_dir]
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
        )
        try:
            # 800ms hard timeout to ensure Android UI never hangs
            await asyncio.wait_for(proc.communicate(), timeout=0.8)
            
            if proc.returncode == 0:
                # qlmanage outputs as <filename>.png
                out_name = os.path.basename(file_path) + ".png"
                out_path = os.path.join(tmp_dir, out_name)
                
                if os.path.exists(out_path):
                    with open(out_path, "rb") as f:
                        return f.read()
            return None 
        except asyncio.TimeoutError:
            proc.kill()
            return None

@app.post("/internal/register")
async def internal_register(reg: ThumbnailRegistration):
    """v1.7.2 IPC Bridge: Allows local sender processes to register IDs in daemon memory."""
    thumbnail_registry[reg.id] = (reg.path, reg.expiry)
    print(f"📥 [8081] INTERNAL REGISTRATION → id={reg.id} ({os.path.basename(reg.path)})")
    
    # [v1.7.1] Proactive Early Cleanup: Schedule eviction after 30s handshake window
    asyncio.create_task(_early_cleanup(reg.id))
    return {"status": "ok"}

async def _early_cleanup(transfer_id: str):
    """Proactively evict mapping after 30s to keep daemon memory lean."""
    await asyncio.sleep(30)
    if thumbnail_registry.pop(transfer_id, None):
        print(f"🧹 [8081] PROACTIVE CLEANUP → id={transfer_id}")

@app.get("/thumbnail")
async def get_thumbnail(id: str, request: Request):
    """Dynamic Pull-Based Thumbnail Endpoint with v1.7.1 Deduplication."""
    client_ip = request.client.host
    print(f"🖼️ [8081] REQUEST RECEIVED → id={id} from {client_ip}")
    if id in thumbnail_cache:
        return Response(content=thumbnail_cache[id], media_type="image/jpeg")

    # [v1.7.1] Deduplication Lock: If another fetch is already rendering this ID, wait for it.
    if id in in_progress:
        print(f"⏳ [8081] CONCURRENT FETCH DETECTED → id={id}, waiting for lock...")
        await in_progress[id]
        if id in thumbnail_cache:
            print(f"⚡ [8081] CACHE HIT (after lock) → id={id}")
            return Response(content=thumbnail_cache[id], media_type="image/jpeg")
        # If the other one failed, we continue down to try again or return status codes below.

    if id not in thumbnail_registry:
        print(f"❌ [8081] ID NOT FOUND → id={id}")
        raise HTTPException(status_code=404, detail="Thumbnail ID not found")

    file_path, expiry = thumbnail_registry[id]
    
    if time.time() > expiry:
        print(f"⏳ [8081] ID EXPIRED → id={id}")
        # Return 410 Gone for expired IDs as per v1.7.0 hardening
        raise HTTPException(status_code=410, detail="Thumbnail expired")

    if not os.path.exists(file_path):
        print(f"📂 [8081] FILE MISSING → id={id} ({os.path.basename(file_path)})")
        raise HTTPException(status_code=404, detail="Resource file missing")

    try:
        # [v1.7.1] Burst Protection (Circuit Breaker)
        if len(thumbnail_cache) > 100:
            print("🧱 [DEBUG] [8081] Cache capacity reached (100). Evicting all as circuit breaker.")
            thumbnail_cache.clear()

        # [v1.7.1] Acquire Generation Lock
        future = asyncio.Future()
        in_progress[id] = future

        try:
            print(f"🛠️ [8081] GENERATING THUMBNAIL → id={id} ({os.path.basename(file_path)})")
            img_bytes = await _native_ql_thumbnail(file_path)
            
            if img_bytes:
                print(f"✅ [8081] GENERATED SUCCESS → id={id}")
                thumbnail_cache[id] = img_bytes
                return Response(content=img_bytes, media_type="image/png")
            else:
                print(f"📄 [8081] NO PREVIEW (204) → id={id}")
                return Response(status_code=204)
        finally:
            # Always release lock
            in_progress.pop(id, None)
            future.set_result(True)
    except Exception as e:
        print(f"⚠️ [DEBUG] [8081] Generator error: {e}")
        return Response(status_code=500)

async def cleanup_task():
    """Background task to cleanup expired thumbnails every 60 seconds."""
    while True:
        await asyncio.sleep(60)
        now = time.time()
        expired = [k for k, v in thumbnail_registry.items() if now > v[1]]
        if expired:
            print(f"🧹 [DEBUG] [8081] Cleaning up {len(expired)} expired thumbnails")
            for k in expired:
                thumbnail_registry.pop(k, None)
                thumbnail_cache.pop(k, None)

def start_thumbnail_server():
    """Starts the thumbnail sidecar server on port 8081 with lifecycle protection."""
    global _server_task, _cleanup_task
    
    if _server_task is not None:
        return # Already running
        
    config = uvicorn.Config(app, host="0.0.0.0", port=8081, log_level="warning")
    server = uvicorn.Server(config)
    
    # Run server and cleanup task in the existing event loop
    _server_task = asyncio.create_task(server.serve())
    _cleanup_task = asyncio.create_task(cleanup_task())
    print("🚀 [8081] Thumbnail Sidecar STARTED (v1.6.0 Optimized)")
