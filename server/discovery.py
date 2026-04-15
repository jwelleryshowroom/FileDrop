import socket
import asyncio
import subprocess
import time
from typing import List, Dict, Optional, Tuple, Callable
from zeroconf import ServiceInfo
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

async def _run_dns_sd_streaming(args: List[str], duration: float, stop_condition: Callable[[str], bool] = None) -> str:
    """Helper to run dns-sd commands and stream lines for a duration, with early exit."""
    try:
        proc = await asyncio.create_subprocess_exec(
            "dns-sd", *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        lines = []
        start_time = time.time()
        while time.time() - start_time < duration:
            try:
                # Use a small timeout to keep the loop responsive to the duration check
                line = await asyncio.wait_for(proc.stdout.readline(), timeout=0.1)
                if line:
                    decoded = line.decode('utf-8', errors='ignore').strip()
                    lines.append(decoded)
                    if stop_condition and stop_condition(decoded):
                        break
            except asyncio.TimeoutError:
                continue
        
        try:
            proc.terminate()
            await proc.wait()
        except:
            proc.kill()
            
        return "\n".join(lines)
    except Exception as e:
        print(f"⚠️ dns-sd command failed: {e}")
        return ""

async def discover_devices(scan_duration: int = 5) -> Tuple[List[Dict], Optional[Dict]]:
    """Scans the network using native macOS dns-sd for 100% reliability."""
    print("🔍 Scanning via native macOS dns-sd...")
    
    # Step 1: Browse for instances (Break early if we find a QuickDrop device that is NOT the Mac)
    def stop_browse(line):
        return "Add" in line and "QuickDrop" in line and HOSTNAME not in line

    browse_out = await _run_dns_sd_streaming(["-B", "_http._tcp"], duration=1.5, stop_condition=stop_browse)
    
    instance_names = []
    for line in browse_out.splitlines():
        if "Add" in line and "_http._tcp." in line and "QuickDrop" in line:
            parts = line.split()
            try:
                idx = parts.index("_http._tcp.")
                name = " ".join(parts[idx+1:])
                if name not in instance_names:
                    instance_names.append(name)
            except ValueError:
                continue

    found_devices = []
    last_device = None

    for name in instance_names:
        # Skip self conservatively
        if name.startswith(f"QuickDrop-{HOSTNAME}"):
            print(f"🚫 Skipping self: {name}")
            continue

        print(f"📡 Resolving native: {name}...")
        
        # Step 2: Extract port and target host
        resolve_out = await _run_dns_sd_streaming(
            ["-L", name, "_http._tcp"], 
            duration=1.0, 
            stop_condition=lambda l: "can be reached at" in l
        )
        target_host = None
        port = PORT
        
        for line in resolve_out.splitlines():
            if "can be reached at" in line:
                parts = line.split("can be reached at")[-1].strip().split(":")
                if len(parts) >= 2:
                    try:
                        port_str = parts[-1].split()[0]
                        port = int(port_str)
                        target_host = parts[-2].strip()
                        break
                    except:
                        continue

        if not target_host:
            print(f"⏳ Resolve failed for {name}, trying next...")
            continue

        # Step 4: Resolve hostname to IPv4 address
        ip_out = await _run_dns_sd_streaming(
            ["-G", "v4", target_host], 
            duration=1.0,
            stop_condition=lambda l: "Add" in l and target_host in l and ("." in l.split()[-1] or "." in l.split()[-2])
        )
        ip = None
        for line in ip_out.splitlines():
            if target_host in line and "Add" in line:
                parts = line.split()
                if len(parts) >= 6:
                    potential_ip = parts[-1] if "." in parts[-1] else parts[-2]
                    if "." in potential_ip and ":" not in potential_ip:
                        ip = potential_ip
                        break

        if ip:
            device = {
                "name": name,
                "ip": ip,
                "port": port
            }
            if not any(d["ip"] == ip for d in found_devices):
                print(f"✅ NATIVELY DISCOVERED: {device}")
                found_devices.append(device)
                last_device = device

    return found_devices, last_device

# Shared Zeroconf session for the server (Advertisement only)
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
