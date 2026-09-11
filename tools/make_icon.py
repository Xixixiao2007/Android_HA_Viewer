#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成应用图标（纯标准库，不依赖 PIL）。

设计：蓝色圆角方块 + 三行「圆点 + 横条」的列表图形。
用 4 倍超采样再降采样得到抗锯齿边缘。

输出到 apk/res/mipmap-*/ic_launcher.png
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
def _project_dir():
    root = os.path.dirname(HERE)
    for cand in (os.path.join(root, "app"), os.path.join(root, "apk"),
                 os.path.join(HERE, "app"), os.path.join(HERE, "apk")):
        if os.path.isfile(os.path.join(cand, "AndroidManifest.xml")):
            return cand
    return os.path.join(root, "app")


RES = os.path.join(_project_dir(), "res")

BG = (0x19, 0x76, 0xD2)      # 蓝
FG = (0xFF, 0xFF, 0xFF)      # 白

SS = 4                       # 超采样倍数
DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144)]


def rounded_rect_alpha(x, y, w, h, r):
    """点 (x,y) 在圆角矩形内的覆盖率（0 或 1，用于超采样）。"""
    if x < 0 or y < 0 or x >= w or y >= h:
        return 0.0
    # 四个角的圆心
    for cx, cy in ((r, r), (w - r, r), (r, h - r), (w - r, h - r)):
        inside_x = (cx == r and x < r) or (cx == w - r and x > w - r)
        inside_y = (cy == r and y < r) or (cy == h - r and y > h - r)
        if inside_x and inside_y:
            dx, dy = x - cx, y - cy
            return 1.0 if dx * dx + dy * dy <= r * r else 0.0
    return 1.0


def in_bar(x, y, w, h):
    rows_y = (0.30, 0.50, 0.70)
    bar_h = 0.085
    for ry in rows_y:
        cy = ry * h
        # 圆点
        dx, dy = x - 0.275 * w, y - cy
        if dx * dx + dy * dy <= (0.052 * w) ** 2:
            return True
        # 横条
        if 0.38 * w <= x <= 0.755 * w and abs(y - cy) <= bar_h * h * 0.5:
            return True
    return False


def render(size):
    """返回 size*size*4 的 RGBA 字节串。"""
    w = h = size
    big = size * SS
    # 累积缓冲区（超采样空间 -> 目标像素）
    acc = [[[0.0, 0.0, 0.0, 0.0] for _ in range(w)] for _ in range(h)]
    r = 0.22 * big
    for by in range(big):
        ty = by // SS
        for bx in range(big):
            tx = bx // SS
            a = rounded_rect_alpha(bx + 0.5, by + 0.5, big, big, r)
            if a <= 0.0:
                continue
            if in_bar(bx + 0.5, by + 0.5, big, big):
                cr, cg, cb = FG
            else:
                cr, cg, cb = BG
            cell = acc[ty][tx]
            cell[0] += cr
            cell[1] += cg
            cell[2] += cb
            cell[3] += 255.0 * a
    n = float(SS * SS)
    out = bytearray()
    for y in range(h):
        for x in range(w):
            cr, cg, cb, ca = acc[y][x]
            if ca <= 0:
                out += bytes((0, 0, 0, 0))
                continue
            # 颜色按实际覆盖数归一（PNG 是非预乘 alpha，存原始颜色）
            covered = ca / 255.0
            out.append(int(min(255, cr / covered + 0.5)))
            out.append(int(min(255, cg / covered + 0.5)))
            out.append(int(min(255, cb / covered + 0.5)))
            out.append(int(min(255, ca / n + 0.5)))
    return bytes(out)


def write_png(path, size, rgba):
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)
        raw.extend(rgba[y * stride:(y + 1) * stride])

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    for dpi, size in DENSITIES:
        d = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        rgba = render(size)
        p = os.path.join(d, "ic_launcher.png")
        write_png(p, size, rgba)
        print("[icon] %-8s %3dx%-3d  %6d bytes  %s"
              % (dpi, size, size, os.path.getsize(p), p))


if __name__ == "__main__":
    main()
