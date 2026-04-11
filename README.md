# FileDrop (Phase 1)

## Mac receiver

1. Install deps:
   ```bash
   pip3 install -r requirements.txt
   ```

2. Run the server:
   ```bash
   python3 server.py
   ```

3. Find your Mac LAN IP:
   ```bash
   ifconfig | grep "inet "
   ```

Uploads land in `~/Downloads/MacDrop`.

To override the save location (handy for testing):
```bash
MACDROP_SAVE_DIR=/tmp/MacDrop python3 server.py
```
