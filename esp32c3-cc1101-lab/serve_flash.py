#!/usr/bin/env python3
"""Serve flashsite/ on 0.0.0.0 behind HTTP basic auth (the images contain the OTA secret).
usage: serve_flash.py [port]   (TLS if ~/.keeloq-tls/{cert,key}.pem exist)   user 'flash', password from ~/.keeloq-flash-pass"""
import base64, functools, http.server, os, ssl, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flashsite")
pw = open(os.path.expanduser("~/.keeloq-flash-pass")).read().strip()
tok = "Basic " + base64.b64encode(f"flash:{pw}".encode()).decode()
class H(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != tok:
            self.send_response(401); self.send_header("WWW-Authenticate", 'Basic realm="flash"'); self.end_headers(); return
        super().do_GET()
    def end_headers(self):
        self.send_header("Cache-Control", "no-store"); super().end_headers()
srv = http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 8000),
    functools.partial(H, directory=root))
tls = os.path.expanduser("~/.keeloq-tls")
if os.path.exists(tls + "/cert.pem"):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(tls + "/cert.pem", tls + "/key.pem")
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
