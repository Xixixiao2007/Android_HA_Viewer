#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
下载构建 Android APK 所需的最小工具集（不走 schannel，纯 Python urllib）。
目标目录: <workspace>/dsh/tools/android-sdk/
  - build-tools/30.0.3   (aapt2 / aapt / d8 / zipalign / apksigner)
  - platforms/android-23 (android.jar, 对应 Android 6.0 = API 23)
"""
import os
import sys
import time
import urllib.request

BASE = "https://dl.google.com/android/repository/"
HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.join(HERE, "android-sdk")

# (远端文件名, 解压后放置的相对目录, 期望字节数)
PKGS = [
    ("91936d4ee3ccc839f0addd53c9ebf087b1e39251.build-tools_r30.0.3-windows.zip",
     os.path.join("build-tools", "30.0.3"), 54_200_000),
    ("platform-23_r03.zip",
     os.path.join("platforms", "android-23"), 70_500_000),
]

CACHE = os.path.join(HERE, "_dl_cache")


def human(n):
    return "%.1f MB" % (n / 1048576.0)


def download(url, dest, expect):
    """带断点续传的下载。返回最终文件大小。"""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and abs(os.path.getsize(dest) - expect) < expect * 0.05:
        print("  [skip] already have %s (%s)" % (os.path.basename(dest), human(os.path.getsize(dest))))
        return os.path.getsize(dest)

    got = os.path.getsize(dest) if os.path.exists(dest) else 0
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Range": "bytes=%d-" % got,
    })
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=60) as r:
        mode = "ab" if got and r.status == 206 else "wb"
        if mode == "wb":
            got = 0
        total = expect
        cl = r.headers.get("Content-Length")
        if cl:
            total = got + int(cl)
        with open(dest, mode) as f:
            last = 0.0
            while True:
                chunk = r.read(262144)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
                now = time.time()
                if now - last > 2.0:
                    last = now
                    pct = 100.0 * got / total if total else 0
                    speed = got / max(now - t0, 0.001)
                    sys.stdout.write("\r    %5.1f%%  %s / %s   %s/s   "
                                     % (pct, human(got), human(total), human(speed)))
                    sys.stdout.flush()
    sys.stdout.write("\r    done: %s%s\n" % (human(got), " " * 40))
    return got


def main():
    os.makedirs(CACHE, exist_ok=True)
    for remote, rel, expect in PKGS:
        dest_zip = os.path.join(CACHE, remote)
        target = os.path.join(SDK, rel)
        print("[*] %s -> %s" % (remote, target))
        if os.path.isdir(target):
            print("  [skip] already extracted")
            continue
        try:
            download(BASE + remote, dest_zip, expect)
        except Exception as e:
            print("  [!] download failed: %s: %s" % (type(e).__name__, e))
            return 1
        import zipfile
        os.makedirs(target, exist_ok=True)
        with zipfile.ZipFile(dest_zip) as z:
            z.extractall(target)
        print("  [ok] extracted %d entries" % len(os.listdir(target)))
    print("\nSDK root: %s" % SDK)
    return 0


if __name__ == "__main__":
    sys.exit(main())
