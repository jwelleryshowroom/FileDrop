import sys
import asyncio
import argparse
import uvicorn
from server.transfer import send_file

def run_server(dev=False):
    """Starts the FastAPI server using uvicorn."""
    print(f"🚀 QuickDrop Server Starting (dev_mode={dev})...")
    # Using reload=True spawns child processes which can be problematic when controlled by NSTask/Process
    uvicorn.run("server.api:app", host="0.0.0.0", port=8000, reload=dev)

def main():
    """Main entry point for the CLI."""
    parser = argparse.ArgumentParser(description="QuickDrop - Local Network File Transfer")
    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    # 'send' command
    send_parser = subparsers.add_parser("send", help="Send files to another device")
    send_parser.add_argument("files", nargs="+", help="One or more files to send")
    send_parser.add_argument("--to", help="Target device IP (e.g. 192.168.1.5)")

    # Global options
    parser.add_argument("--dev", action="store_true", help="Run server in development mode (with auto-reload)")

    # Parse arguments
    args = parser.parse_args()

    if args.command == "send":
        # Run the async send_file logic
        try:
            asyncio.run(send_file(args.files, args.to))
        except KeyboardInterrupt:
            print("\n👋 Sending cancelled.")
    elif args.command is None:
        # Default to server mode
        run_server(dev=args.dev)
    else:
        parser.print_help()

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n👋 Exiting gracefully...")
        sys.exit(0)
