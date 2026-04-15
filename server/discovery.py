import socket
import asyncio
from zeroconf import ServiceInfo, ServiceBrowser, ServiceStateChange
from zeroconf.asyncio import AsyncZeroconf
from .config import PORT, SERVICE_TYPE, HOSTNAME, DEVICE_NAME

def get_local_ip() -> str:
    """Returns the primary local IP address of the machine."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

class DiscoveryListener:
    """Zeroconf listener that detects other MacDrop instances on the network."""
    def __init__(self):
        self.found_devices = []

    def on_service_state_change(self, zeroconf, service_type, state_change, name):
        if state_change is ServiceStateChange.Added:
            info = zeroconf.get_service_info(service_type, name)
            if info:
                # Filter for QuickDrop devices and ignore ourselves
                my_hostname = HOSTNAME
                if name.startswith("QuickDrop-") and my_hostname not in name:
                    ip = socket.inet_ntoa(info.addresses[0])
                    device = {"name": name.split('.')[0], "ip": ip, "port": info.port}
                    if device not in self.found_devices:
                        self.found_devices.append(device)
                        print(f"📱 Discovered: {device['name']} at {ip}:{info.port}")

async def discover_devices(scan_duration: int = 5) -> list[dict]:
    """Scans the network for visible MacDrop devices."""
    print("🔍 Scanning for nearby devices...")
    zc = AsyncZeroconf()
    listener = DiscoveryListener()
    browser = ServiceBrowser(zc.zeroconf, SERVICE_TYPE, handlers=[listener.on_service_state_change])

    await asyncio.sleep(scan_duration)
    await zc.async_close()
    return listener.found_devices

# Shared Zeroconf session for the server
_aio_zc = None
_service_info = None

async def start_mdns_broadcast():
    """Starts broadcasting this device's presence via mDNS."""
    global _aio_zc, _service_info
    local_ip = get_local_ip()
    service_name = f"{DEVICE_NAME}.{SERVICE_TYPE}"
    
    print(f"📢 Broadcasting mDNS as: {service_name}")
    _service_info = ServiceInfo(
        SERVICE_TYPE,
        service_name,
        addresses=[socket.inet_aton(local_ip)],
        port=PORT,
        server=f"{HOSTNAME}.local.",
    )
    _aio_zc = AsyncZeroconf()
    await _aio_zc.async_register_service(_service_info)

async def stop_mdns_broadcast():
    """Stops the mDNS broadcast cleanly."""
    global _aio_zc, _service_info
    if _aio_zc and _service_info:
        print("\n🛑 Shutting down mDNS broadcast cleanly...")
        await _aio_zc.async_unregister_service(_service_info)
        await _aio_zc.async_close()
        _aio_zc = None
        _service_info = None
