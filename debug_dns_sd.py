import subprocess
import time

def native_discovery():
    print("Scanning via dns-sd...")
    try:
        result = subprocess.run(
            ["dns-sd", "-B", "_http._tcp"],
            capture_output=True,
            timeout=2.0
        )
        output = result.stdout
    except subprocess.TimeoutExpired as e:
        output = e.stdout

    if isinstance(output, bytes):
        output = output.decode("utf-8", errors="ignore")

    devices = []
    if output:
        lines = output.strip().split("\n")
        for line in lines:
            if "Add" in line and "_http._tcp." in line and "QuickDrop" in line:
                parts = line.split()
                try:
                    idx = parts.index("_http._tcp.")
                    instance_name = " ".join(parts[idx+1:])
                    if instance_name not in devices:
                        devices.append(instance_name)
                except ValueError:
                    pass

    print("Found direct devices:", devices)
    
    resolved = []
    for dev in devices:
        try:
            r_res = subprocess.run(
                ["dns-sd", "-L", dev, "_http._tcp"],
                capture_output=True,
                timeout=1.5
            )
            r_out = r_res.stdout
        except subprocess.TimeoutExpired as e:
            r_out = e.stdout
            
        if isinstance(r_out, bytes):
            r_out = r_out.decode("utf-8", errors="ignore")
            
        if r_out:
            for line in r_out.split('\n'):
                if "can be reached at" in line:
                    parts = line.split("can be reached at")[-1].strip().split(":")
                    if len(parts) >= 2:
                        port = parts[-1].split(" ")[0]
                        host = parts[-2].strip()
                        # get IP
                        try:
                            h_res = subprocess.run(
                                ["dns-sd", "-G", "v4", host],
                                capture_output=True,
                                timeout=1.0
                            )
                            h_out = h_res.stdout
                        except subprocess.TimeoutExpired as e:
                            h_out = e.stdout
                        
                        if isinstance(h_out, bytes):
                            h_out = h_out.decode("utf-8", errors="ignore")
                            
                        ip = None
                        if h_out:
                            for hline in h_out.split('\n'):
                                if host in hline and "Add" in hline:
                                    hparts = hline.split()
                                    if len(hparts) >= 6:
                                        ip = hparts[-2]
                                        break
                        if ip:
                            resolved.append({"name": dev, "ip": ip, "port": int(port)})

    print("Resolved:", resolved)

native_discovery()
