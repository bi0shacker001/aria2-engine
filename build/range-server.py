#!/usr/bin/env python3
"""The on-device tests' file server: HTTP with Range, so a download that loses its connection resumes.

    build/range-server.py <folder> <port>

Serves the files of <folder> with GET and HEAD:
- Every answer carries Accept-Ranges, a strong ETag and Last-Modified.
- A single "Range: bytes=" range (a-b, a- or -n) gets 206 with Content-Range. A range past the end gets 416, as
  does a range the server cannot read; a request with no range gets the whole file.
- /cut/<name> is <name> served once with its full length promised and the connection closed after the first MiB.
  Every later request for it is answered normally. The client must resume with a range, which is what the resume test
  checks.

Python's own http.server answers every request with the whole file, so a download that adb reverse dropped mid-file
(API 28, run 39007351089: "Invalid range header") could not be resumed.
"""
import email.utils
import hashlib
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CUT_AFTER = 1024 * 1024
RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    folder = "."
    cut_served = set()
    lock = threading.Lock()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def do_HEAD(self):
        self.answer(body=False)

    def do_GET(self):
        self.answer(body=True)

    def resolve(self):
        path = self.path.split("?", 1)[0]
        cut = path.startswith("/cut/")
        name = path[len("/cut/"):] if cut else path.lstrip("/")
        if not name or "/" in name or name.startswith("."):
            return None, False
        file = os.path.join(self.folder, name)
        return (file if os.path.isfile(file) else None), cut

    def answer(self, body):
        file, cut = self.resolve()
        if file is None:
            self.send_error(404)
            return
        size = os.path.getsize(file)
        stat = os.stat(file)
        etag = '"%x-%x"' % (stat.st_mtime_ns, size)
        start, end = 0, size - 1
        partial = False
        asked = self.headers.get("Range")
        if asked is not None:
            match = RANGE.match(asked.strip())
            first, last = (match.group(1), match.group(2)) if match else ("", "")
            if not match or (first == "" and last == ""):
                self.unsatisfiable(size)
                return
            if first == "":
                start, end = max(0, size - int(last)), size - 1
            else:
                start = int(first)
                end = min(int(last), size - 1) if last else size - 1
            if start >= size or start > end:
                self.unsatisfiable(size)
                return
            partial = True
        cut_now = False
        if cut and not partial:
            with self.lock:
                if self.path not in self.cut_served:
                    self.cut_served.add(self.path)
                    cut_now = True
        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", email.utils.formatdate(stat.st_mtime, usegmt=True))
        if partial:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        if cut_now:
            self.send_header("Connection", "close")
        self.end_headers()
        if not body:
            return
        left = min(length, CUT_AFTER) if cut_now else length
        with open(file, "rb") as source:
            source.seek(start)
            while left > 0:
                chunk = source.read(min(64 * 1024, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)
        if cut_now:
            self.wfile.flush()
            self.close_connection = True

    def unsatisfiable(self, size):
        self.send_response(416)
        self.send_header("Content-Range", "bytes */%d" % size)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: range-server.py <folder> <port>")
    Handler.folder = os.path.abspath(sys.argv[1])
    for name in sorted(os.listdir(Handler.folder)):
        file = os.path.join(Handler.folder, name)
        if os.path.isfile(file) and not name.endswith(".sha256"):
            with open(file, "rb") as source:
                digest = hashlib.sha256(source.read()).hexdigest()
            with open(file + ".sha256", "w") as out:
                out.write(digest + "\n")
    ThreadingHTTPServer(("0.0.0.0", int(sys.argv[2])), Handler).serve_forever()


if __name__ == "__main__":
    main()
