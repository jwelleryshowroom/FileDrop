import os
import socket
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, File, UploadFile, Request, HTTPException
from pydantic import BaseModel
import uvicorn
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

# --- HELPER: GET TRUE LOCAL IP ---
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

# --- HELPER: NATIVE MAC POPUP ---
async def ask_mac_permission(device_name: str, filename: str) -> bool:
    """Triggers a native macOS dialog, handles timeouts, and prevents script injection."""
    
    # 1. Escape quotes to prevent AppleScript injection/crashes
    safe_filename = filename.replace('"', '\\"')
    safe_device = device_name.replace('"', '\\"')

    # 2. Native timeout: 'giving up after 45' closes the popup after 45 seconds
    script = f'''
    display dialog "{safe_device} wants to send '{safe_filename}'. Accept?" ¬
    buttons {{"Decline", "Accept"}} default button "Accept" with title "MacDrop Request" giving up after 45
    '''
    
    try:
        process = await asyncio.create_subprocess_exec(
            'osascript', '-e', script,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        stdout, stderr = await process.communicate()
        output = stdout.decode('utf-8')
        
        # 3. Handle the timeout or the button click
        if "gave up:true" in output:
            print("⏳ Popup timed out (user didn't respond). Auto-declining.")
            return False
            
        return "button returned:Accept" in output
        
    except Exception as e:
        print(f"❌ Popup execution error: {e}")
        return False

# Global references for cleanup
aio_zc = None
service_info = None

# --- LIFESPAN: MANAGE mDNS BROADCAST ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    global aio_zc, service_info
    
    local_ip = get_local_ip()
    hostname = socket.gethostname().split('.')[0] 
    service_name = f"MacDrop-{hostname}._http._tcp.local."
    
    print(f"📢 Broadcasting mDNS as: {service_name}")
    
    service_info = ServiceInfo(
        "_http._tcp.local.",
        service_name,
        addresses=[socket.inet_aton(local_ip)],
        port=8000,
        server=f"{hostname}.local.",
    )
    
    # Use AsyncZeroconf to prevent blocking the FastAPI event loop!
    aio_zc = AsyncZeroconf()
    await aio_zc.async_register_service(service_info)
    
    yield # Server is actively running here
    
    # Cleanup on Ctrl+C
    print("\n🛑 Shutting down mDNS broadcast cleanly...")
    await aio_zc.async_unregister_service(service_info)
    await aio_zc.async_close()

app = FastAPI(lifespan=lifespan)
SAVE_DIR = os.path.expanduser("~/Downloads/MacDrop")
os.makedirs(SAVE_DIR, exist_ok=True)

class TransferRequest(BaseModel):
    fileName: str
    fileSize: int
    deviceName: str

# --- THE HANDSHAKE ENDPOINT ---
@app.post("/request-transfer")
async def request_transfer(request: TransferRequest):
    print(f"🔔 Incoming request from {request.deviceName} for '{request.fileName}' ({request.fileSize} bytes)")
    
    # Pause and ask for human consent
    accepted = await ask_mac_permission(request.deviceName, request.fileName)
    
    if accepted:
        print(f"✅ User accepted transfer of '{request.fileName}'")
        return {"accepted": True}
    else:
        print(f"❌ User declined transfer of '{request.fileName}' (or timeout)")
        return {"accepted": False}

# --- THE UPLOAD ENDPOINT ---
@app.post("/upload")
async def upload_file(request: Request, file: UploadFile = File(...)):
    print(f"📥 Receiving file: '{file.filename}'...")
    
    file_path = os.path.join(SAVE_DIR, file.filename)
    try:
        with open(file_path, "wb") as buffer:
            while content := await file.read(1024 * 1024):  
                buffer.write(content)
        
        print(f"🎉 Saved successfully to: {file_path}")
        return {"status": "success", "path": file_path}
    except Exception as e:
        print(f"❌ Error saving file: {e}")
        raise HTTPException(status_code=500, detail="Server error while saving.")

if __name__ == "__main__":
    print("🚀 MacDrop Server Starting...")
    uvicorn.run(app, host="0.0.0.0", port=8000)
