import os
import socket

# --- NETWORK CONFIGURATION ---
PORT = 8000
SERVICE_TYPE = "_http._tcp.local."
HOSTNAME = socket.gethostname().split('.')[0]
DEVICE_NAME = f"QuickDrop-{HOSTNAME}"

# --- DIRECTORY CONFIGURATION ---
SAVE_DIR = os.path.expanduser("~/Downloads/QuickDrop")

# Ensure the save directory exists
os.makedirs(SAVE_DIR, exist_ok=True)

# --- SERVER SETTINGS ---
IDLE_TIMEOUT = 300  # Seconds of inactivity before shutdown
