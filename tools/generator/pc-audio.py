#!/usr/bin/env python3
"""Feeds the computer's own sound output to tools/generator/visualizer.html.

Reads the default output's monitor (what the speakers/headphones play) with parec, filters it
by frequency and serves Server-Sent Events on http://127.0.0.1:8767/peaks: every `frame` ms one
line "pos,neg,low,high": the largest positive and negative sample (0..1) of the last `win` ms,
and the peak level below and above `split` Hz in that window (for "treble up, bass down").

Query parameters (all optional):
    frame, win      ms per line / ms measured (40, 6)
    lo, hi          keep only lo..hi Hz (20, 20000)
    gb, gm, gt      gain of bass (<250 Hz), mids (250 Hz..4 kHz), treble (>4 kHz) (1, 1, 1)
    split           bass/treble crossover for the low/high levels (800)

http://127.0.0.1:8767/spectrum?frame=40&bands=25&fmin=40&fmax=16000 instead sends, every
`frame` ms, one line of `bands` levels (0..1, peak amplitude of each log-spaced band from fmin
to fmax), from a 2048-sample (43 ms) windowed FFT of the filtered sound.

Only those numbers leave the script; no sound is stored. Listens on 127.0.0.1 only.

    python3 tools/generator/pc-audio.py            # default output
    python3 tools/generator/pc-audio.py <source>   # a specific monitor (pactl list short sources)
"""
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import numpy as np
from scipy import signal

RATE = 48000
PORT = 8767
SOURCE = sys.argv[1] if len(sys.argv) > 1 else "@DEFAULT_MONITOR@"
BASS_MID, MID_TREBLE = 250.0, 4000.0

ring = np.zeros(RATE, dtype=np.float32)  # the last second of sound, unfiltered
# Each line filters the last BLOCK samples afresh, so every page keeps its own filter settings;
# 170 ms lets the filters settle before the measured window at the end.
BLOCK = 8192
pos = 0
lock = threading.Lock()


class Filters:
    """Three-band split with gains, then a lo..hi band limit."""

    def __init__(self, lo=20.0, hi=20000.0, gb=1.0, gm=1.0, gt=1.0):
        self.key = (lo, hi, gb, gm, gt)
        self.gains = (gb, gm, gt)
        nyq = RATE / 2
        self.bands = [
            signal.butter(4, BASS_MID, "lowpass", fs=RATE, output="sos"),
            signal.butter(4, [BASS_MID, MID_TREBLE], "bandpass", fs=RATE, output="sos"),
            signal.butter(4, MID_TREBLE, "highpass", fs=RATE, output="sos"),
        ]
        lo = max(10.0, min(lo, nyq * 0.95))
        hi = max(lo * 1.05, min(hi, nyq * 0.98))
        if lo > 20 and hi < 20000:
            self.limit = signal.butter(4, [lo, hi], "bandpass", fs=RATE, output="sos")
        elif lo > 20:
            self.limit = signal.butter(4, lo, "highpass", fs=RATE, output="sos")
        elif hi < 20000:
            self.limit = signal.butter(4, hi, "lowpass", fs=RATE, output="sos")
        else:
            self.limit = None

    def run(self, x):
        if self.key == (20.0, 20000.0, 1.0, 1.0, 1.0):
            return x  # everything at unity: the sound as it is
        y = np.zeros_like(x)
        for sos, g in zip(self.bands, self.gains):
            if g:
                y += g * signal.sosfilt(sos, x)
        if self.limit is not None:
            y = signal.sosfilt(self.limit, y)
        return y


def capture():
    global pos
    p = subprocess.Popen(
        ["parec", "-d", SOURCE, "--format=s16le", "--rate=%d" % RATE, "--channels=1",
         "--latency-msec=10", "--raw"],
        stdout=subprocess.PIPE)
    chunk = 480 * 2  # 10 ms
    while True:
        data = p.stdout.read(chunk)
        if not data:
            print("parec ended", file=sys.stderr)
            return
        y = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
        with lock:
            n = len(y)
            end = pos + n
            if end <= RATE:
                ring[pos:end] = y
            else:
                k = RATE - pos
                ring[pos:] = y[:k]
                ring[:n - k] = y[k:]
            pos = end % RATE


def splitters(split):
    split = max(40.0, min(split, RATE / 2 * 0.9))
    return (signal.butter(4, split, "lowpass", fs=RATE, output="sos"),
            signal.butter(4, split, "highpass", fs=RATE, output="sos"))


def peaks(win_ms, filters, split_sos):
    n = max(8, min(BLOCK, int(RATE * win_ms / 1000)))
    with lock:
        block = ring[(np.arange(pos - BLOCK, pos)) % RATE]
    y = filters.run(block)
    v = y[-n:]
    # Bass and treble are measured over at least 10 ms, enough for a bass period to show.
    m = max(n, RATE // 100)
    low = np.abs(signal.sosfilt(split_sos[0], y)[-m:]).max()
    high = np.abs(signal.sosfilt(split_sos[1], y)[-m:]).max()
    c = lambda x: float(max(0.0, min(1.0, x)))
    return c(v.max()), c(-v.min()), c(low), c(high)


FFT_N = 2048
HANN = np.hanning(FFT_N).astype(np.float32)
FREQS = np.fft.rfftfreq(FFT_N, 1 / RATE)


def band_edges(bands, fmin, fmax):
    return fmin * (fmax / fmin) ** (np.arange(bands + 1) / bands)


def spectrum(filters, edges):
    with lock:
        block = ring[(np.arange(pos - BLOCK, pos)) % RATE]
    y = filters.run(block)[-FFT_N:]
    # Peak amplitude of a sine in a bin: 2|X| / sum(window).
    mag = np.abs(np.fft.rfft(y * HANN)) * 2 / HANN.sum()
    out = []
    for a, b in zip(edges[:-1], edges[1:]):
        sel = mag[(FREQS >= a) & (FREQS < b)]
        if sel.size == 0:  # band narrower than a bin: the nearest bin
            sel = mag[[int(np.argmin(np.abs(FREQS - (a * b) ** 0.5)))]]
        out.append(float(min(1.0, sel.max())))
    return out


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        if u.path not in ("/peaks", "/spectrum"):
            self.send_response(404)
            self.end_headers()
            return
        q = parse_qs(u.query)

        def num(k, d, lo, hi):
            try:
                return max(lo, min(hi, float(q.get(k, [d])[0])))
            except ValueError:
                return d

        frame = num("frame", 40, 10, 1000)
        win = num("win", 6, 0.2, 1000)
        filters = Filters(num("lo", 20, 10, 20000), num("hi", 20000, 20, 24000),
                          num("gb", 1, 0, 10), num("gm", 1, 0, 10), num("gt", 1, 0, 10))
        split_sos = splitters(num("split", 800, 40, 20000))
        edges = band_edges(int(num("bands", 25, 1, 64)), num("fmin", 40, 10, 20000),
                           num("fmax", 16000, 100, 24000))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        nxt = time.monotonic()
        try:
            while True:
                if u.path == "/spectrum":
                    line = ",".join("%.4f" % v for v in spectrum(filters, edges))
                    self.wfile.write(b"data: " + line.encode() + b"\n\n")
                else:
                    p, n, lo, hi = peaks(win, filters, split_sos)
                    self.wfile.write(b"data: %.4f,%.4f,%.4f,%.4f\n\n" % (p, n, lo, hi))
                self.wfile.flush()
                nxt += frame / 1000
                time.sleep(max(0, nxt - time.monotonic()))
        except (BrokenPipeError, ConnectionResetError):
            return


if __name__ == "__main__":
    # Take the port first: if a bridge is already running, say so instead of starting a second
    # parec that would only die with "Broken pipe".
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError as e:
        if e.errno == 98:
            print("pc-audio is already running on http://127.0.0.1:%d/peaks" % PORT)
            sys.exit(0)
        raise
    threading.Thread(target=capture, daemon=True).start()
    print("computer sound from %s on http://127.0.0.1:%d/peaks" % (SOURCE, PORT))
    server.serve_forever()
