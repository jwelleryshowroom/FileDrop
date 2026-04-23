import asyncio
import time

async def test():
    proc = await asyncio.create_subprocess_exec(
        "dns-sd", "-B", "_http._tcp",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE
    )
    
    start = time.time()
    lines = []
    print("Reading lines...")
    while time.time() - start < 3:
        try:
            # Read with a short timeout to keep the loop responsive
            line = await asyncio.wait_for(proc.stdout.readline(), timeout=0.1)
            if line:
                decoded = line.decode('utf-8').strip()
                print(f"GOT: {decoded}")
                lines.append(decoded)
        except asyncio.TimeoutError:
            continue
    
    proc.terminate()
    print(f"Total lines: {len(lines)}")

asyncio.run(test())
