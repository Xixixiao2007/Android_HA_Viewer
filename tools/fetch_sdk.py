#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Download the minimal Android toolchain needed to build the APK.

下载构建 Android APK 所需的最小工具集（纯 Python urllib，不依赖 Android Studio）。

目标目录: <本脚本所在目录>/android-sdk/
  - build-tools/30.0.3   (aapt2 / d8 / zipalign / apksigner)
  - platforms/android-23 (android.jar，对应 Android 6.0 = API 23)

支持 Windows / Linux / macOS —— Google 仓库里同一个包在不同平台上文件名不同
（连 sha1 前缀都不一样），所以这里按平台选择。
"""
import os
import sys
import time
import urllib.request
import zipfile

BASE = "https://dl.google.com/android/repository/"
HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.join(HERE, "android-sdk")
CACHE = os.path.join(HERE, "_dl_cache")

# 判定当前平台（对应 Google 仓库的文件名后缀）
if sys.platform.startswith("win"):
    HOST = "windows"
elif sys.platform == "darwin":
    HOST = "macosx"
else:
    HOST = "linux"

# build-tools 30.0.3：只有它在 JDK 8 下就能跑 d8，别随意升级（见 README）
BUILD_TOOLS_ZIP = {
    "windows": "91936d4ee3ccc839f0addd53c9ebf087b1e39251.build-tools_r30.0.3-windows.zip",
    "linux": "build-tools_r30.0.3-linux.zip",
    "macosx": "f6d24b187cc6bd534c6c37604205171784ac5621.build-tools_r30.0.3-macosx.zip",
}
BUILD_TOOLS_SIZE = {"windows": 54_200_000, "linux": 53_200_000, "macosx": 51_700_000}

# (远端文件名, 解压后放置的相对目录, 期望字节数)
PKGS = [
    (BUILD_TOOLS_ZIP[HOST], os.path.join("build-tools", "30.0.3"), BUILD_TOOLS_SIZE[HOST]),
    ("platform-23_r03.zip", os.path.join("platforms", "android-23"), 70_500_000),
]


def human(n):
    return "%.1f MB" % (n / 1048576.0)


def download(url, dest, expect):
    """带断点续传的下载。返回最终文件大小。"""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and abs(os.path.getsize(dest) - expect) < expect * 0.05:
        print("  [skip] already have %s (%s)"
              % (os.path.basename(dest), human(os.path.getsize(dest))))
        return os.path.getsize(dest)

    got = os.path.getsize(dest) if os.path.exists(dest) else 0
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0",
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


def make_executable(root):
    """
    POSIX 下给 ELF 可执行文件补上 +x。

    必须做这一步：Python 的 zipfile.extractall 不会恢复 Unix 权限位，
    解出来的 aapt2 / zipalign 会变成不可执行，构建时报 permission denied。
    """
    if os.name == "nt":
        return 0
    fixed = 0
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            p = os.path.join(dirpath, name)
            try:
                with open(p, "rb") as f:
                    if f.read(4) == b"\x7fELF":
                        os.chmod(p, 0o755)
                        fixed += 1
            except OSError:
                pass
    return fixed


def main():
    print("[*] 平台: %s" % HOST)
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
        os.makedirs(target, exist_ok=True)
        with zipfile.ZipFile(dest_zip) as z:
            z.extractall(target)

        # Google 的 zip 里多套了一层目录（windows 版是 android-11/，
        # platform 包是 android-6.0/），抹平它，让 SDK 布局符合标准。
        entries = os.listdir(target)
        if len(entries) == 1 and os.path.isdir(os.path.join(target, entries[0])):
            inner = os.path.join(target, entries[0])
            for name in os.listdir(inner):
                os.rename(os.path.join(inner, name), os.path.join(target, name))
            os.rmdir(inner)
            print("  [ok] 已抹平多套的一层目录: %s/" % entries[0])

        fixed = make_executable(target)
        if fixed:
            print("  [ok] 已为 %d 个可执行文件补上 +x" % fixed)
        print("  [ok] extracted %d entries" % len(os.listdir(target)))

    print("\nSDK root: %s" % SDK)
    return 0


if __name__ == "__main__":
    sys.exit(main())
