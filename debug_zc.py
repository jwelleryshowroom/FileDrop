import time
from zeroconf import Zeroconf, ServiceBrowser

class MyListener:
    def remove_service(self, zeroconf, type, name):
        print("Service %s removed" % (name,))

    def add_service(self, zeroconf, type, name):
        print("Service %s added" % (name,))

zeroconf = Zeroconf()
listener = MyListener()
browser = ServiceBrowser(zeroconf, "_http._tcp.local.", listener)
try:
    time.sleep(5)
finally:
    zeroconf.close()
