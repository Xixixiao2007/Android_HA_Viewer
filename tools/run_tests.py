#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
桌面端单元测试：验证 HaClient 的时间解析与 Prefs 的筛选逻辑。

用桌面 JDK 8 编译真实的 app 源码（HaClient/Prefs/Change），
配合 tztest/stub 下的极简 android.* / org.json 桩。

用法: python dsh/tools/run_tests.py
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
T = os.path.join(HERE, "tztest")
OUT = os.path.join(T, "out")
_root = os.path.dirname(HERE)
_proj = None
for _c in (os.path.join(_root, "app"), os.path.join(_root, "apk"),
           os.path.join(HERE, "app"), os.path.join(HERE, "apk")):
    if os.path.isfile(os.path.join(_c, "AndroidManifest.xml")):
        _proj = _c
        break
if _proj is None:
    _proj = os.path.join(_root, "app")
APPSRC = os.path.join(_proj, "src", "com", "haf1", "entitylist")
JDK = r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08"


def collect(root):
    out = []
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return out


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)

    sources = []
    sources += collect(os.path.join(T, "stub"))
    sources += collect(os.path.join(T, "src"))
    for name in ("HaClient.java", "Prefs.java", "Change.java", "WsFrame.java"):
        sources.append(os.path.join(APPSRC, name))

    argfile = os.path.join(T, "javac.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("\n".join('"%s"' % p.replace("\\", "/") for p in sources))

    javac = os.path.join(JDK, "bin", "javac.exe")
    print("[test] 编译 %d 个源文件" % len(sources))
    p = subprocess.run([javac, "-J-Duser.language=en", "-J-Duser.country=US",
                        "-encoding", "UTF-8", "-d", OUT, "@" + argfile],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True, encoding="utf-8", errors="replace")
    if p.stdout.strip():
        print(p.stdout)
    if p.returncode != 0:
        print("[test] 编译失败")
        return 1

    java = os.path.join(JDK, "bin", "java.exe")
    print("[test] 运行 TzTest")
    print()
    p = subprocess.run([java, "-cp", OUT, "com.haf1.entitylist.TzTest"],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True, encoding="utf-8", errors="replace")
    sys.stdout.write(p.stdout)
    return p.returncode


if __name__ == "__main__":
    sys.exit(main())
