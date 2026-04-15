# QuickDrop 🚀

**Drop files. Instantly.**

QuickDrop is a high-speed, local network file transfer ecosystem for macOS and Android.

## Getting Started

### 🖥️ macOS Server (Backend)

The macOS app acts as a wrapper for the Python backend. To run the backend manually for development:

1. **Install Dependencies**:
   ```bash
   pip3 install -r requirements.txt
   ```

2. **Run the Server**:
   ```bash
   python3 main.py
   ```

3. **Development Mode**:
   ```bash
   python3 main.py --dev
   ```

### 📱 Android Client

1. Open the project in Android Studio.
2. Build and run on your device.
3. Tap "Scan" to find your Mac and start dropping files!

## 📂 Storage
Received files are automatically saved to:
- **macOS**: `~/Downloads/QuickDrop`
- **Android**: `Downloads/QuickDrop`

## 🛠️ Requirements
- Python 3.9+
- Android API 26+ (Oreo)
- Wi-Fi network with mDNS support (ZeroConf)
