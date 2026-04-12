import os
import socket
import asyncio
import sys
import time
import httpx
from contextlib import asynccontextmanager
from fastapi import FastAPI, File, UploadFile, Request, HTTPException
from pydantic import BaseModel
import uvicorn
from zeroconf import ServiceInfo, ServiceBrowser, ServiceStateChange
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
def get_file_type_label(filename: str) -> str:
    extension = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if extension in {"jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"}:
        return "Image"
    if extension == "pdf":
        return "PDF"
    if extension in {"mp4", "mov", "m4v", "mkv", "webm", "avi"}:
        return "Video"
    return "File"

def get_file_type_article(file_type: str) -> str:
    return "an" if file_type == "Image" else "a"

async def ask_mac_permission(device_name: str, filename: str) -> bool:
    """Triggers a native macOS dialog, handles timeouts, and prevents script injection."""
    safe_filename = filename.replace('"', '\\"')
    safe_device = device_name.replace('"', '\\"')
    file_type = get_file_type_label(filename)
    article = get_file_type_article(file_type)
    script = f'''
    display dialog "{safe_device} wants to send {article} {file_type}.\\n\\n{safe_filename}" ¬
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
last_activity_time = time.time()

async def monitor_inactivity():
    """Background task to shut down if idle for > 120 seconds."""
    global last_activity_time
    while True:
        await asyncio.sleep(10)
        idle_time = time.time() - last_activity_time
        if idle_time > 120:
            print(f"🛑 Idle timeout reached ({int(idle_time)}s). Shutting down server...")
            asyncio.get_event_loop().stop()
            break

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
    aio_zc = AsyncZeroconf()
    await aio_zc.async_register_service(service_info)
    inactivity_task = asyncio.create_task(monitor_inactivity())
    yield 
    inactivity_task.cancel()
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

@app.post("/request-transfer")
async def request_transfer(request: TransferRequest):
    global last_activity_time
    last_activity_time = time.time()
    print(f"🔔 Incoming request from {request.deviceName} for '{request.fileName}' ({request.fileSize} bytes)")
    accepted = await ask_mac_permission(request.deviceName, request.fileName)
    if accepted:
        print(f"✅ User accepted transfer of '{request.fileName}'")
        return {"accepted": True}
    else:
        print(f"❌ User declined transfer of '{request.fileName}' (or timeout)")
        return {"accepted": False}

@app.post("/upload")
async def upload_file(request: Request, file: UploadFile = File(...)):
    global last_activity_time
    last_activity_time = time.time()
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

# --- SENDER LOGIC (PHASE 5.4) ---

class DiscoveryListener:
    def __init__(self):
        self.found_devices = []

    def on_service_state_change(self, zeroconf, service_type, state_change, name):
        if state_change is ServiceStateChange.Added:
            info = zeroconf.get_service_info(service_type, name)
            if info:
                # Filter for Android/MacDrop devices and ignore ourselves
                hostname = socket.gethostname().split('.')[0]
                if name.startswith("MacDrop-") and hostname not in name:
                    ip = socket.inet_ntoa(info.addresses[0])
                    device = {"name": name.split('.')[0], "ip": ip, "port": info.port}
                    if device not in self.found_devices:
                        self.found_devices.append(device)
                        print(f"📱 Discovered: {device['name']} at {ip}:{info.port}")

async def discover_devices():
    print("🔍 Scanning for nearby devices...")
    zc = AsyncZeroconf()
    listener = DiscoveryListener()
    browser = ServiceBrowser(zc.zeroconf, "_http._tcp.local.", handlers=[listener.on_service_state_change])

    await asyncio.sleep(5)
    await zc.async_close()
    return listener.found_devices

def parse_device_target(device_ip, default_port=8000):
    if ":" in device_ip:
        host, port_text = device_ip.rsplit(":", 1)
        if host and port_text.isdigit():
            return host, int(port_text)
    return device_ip, default_port

async def send_to_device(file_path, device_ip, port=8000, target_name=None):
    device_ip, port = parse_device_target(device_ip, port)
    target_url = f"http://{device_ip}:{port}"
    file_name = os.path.basename(file_path)
    file_size = os.path.getsize(file_path)
    my_name = socket.gethostname().split('.')[0]
    display_name = target_name or device_ip

    print(f"🤝 Handshaking with {display_name}...")
    async with httpx.AsyncClient() as client:
        try:
            resp = await client.post(
                f"{target_url}/request-transfer",
                json={"fileName": file_name, "fileSize": file_size, "deviceName": my_name},
                timeout=60.0
            )
            resp.raise_for_status()
            data = resp.json()
            if not data.get("accepted", False):
                print("🚫 Transfer declined by Android.")
                return

            print(f"🚀 Transfer accepted! Sending '{file_name}'...")
            with open(file_path, "rb") as f:
                upload_resp = await client.post(
                    f"{target_url}/upload",
                    files={"file": (file_name, f, "application/octet-stream")},
                    headers={"filename": file_name},
                    timeout=None
                )

            if upload_resp.status_code == 200:
                print(f"✅ Successfully sent '{file_name}' to {display_name}!")
            else:
                print(f"❌ Upload failed with status {upload_resp.status_code}")

        except Exception as e:
            print(f"❌ Error during transfer: {e}")

async def send_file(file_path, device_ip=None):
    if not os.path.exists(file_path):
        print(f"❌ File not found: {file_path}")
        return

    if device_ip:
        await send_to_device(file_path, device_ip)
        return

    found_devices = await discover_devices()

    if not found_devices:
        print("❌ No MacDrop devices found.")
        return

    print("\n--- AVAILABLE DEVICES ---")
    for i, dev in enumerate(found_devices):
        print(f"{i + 1}. {dev['name']} ({dev['ip']})")

    android_devices = [dev for dev in found_devices if dev["name"].startswith("MacDrop-Android-")]
    if len(android_devices) == 1:
        target = android_devices[0]
        await send_to_device(file_path, target["ip"], target["port"], target["name"])
        return

    if len(found_devices) != 1:
        print("\nMultiple devices found. Re-run with: python3 server.py send <file_path> <device_ip>")
        return

    target = found_devices[0]
    await send_to_device(file_path, target["ip"], target["port"], target["name"])

def run_server():
    print("🚀 MacDrop Server Starting...")
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=True)

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "send":
        if len(sys.argv) < 3:
            print("Usage: python3 server.py send <file_path> [device_ip]")
        else:
            device_ip = sys.argv[3] if len(sys.argv) > 3 else None
            asyncio.run(send_file(sys.argv[2], device_ip))
    else:
        run_server()
