#!/usr/bin/env python3
"""Minimal zipalign: rewrites an APK so every stored (uncompressed) entry starts on a
4-byte boundary (.so files on 4 KiB), and keeps resources.arsc uncompressed as
Android 11+ requires. Usage: zipalign.py in.apk out.apk"""
import sys, zipfile

STORE_EXT = ('.png', '.jpg', '.jpeg', '.webp', '.ogg', '.mp3', '.m4a', '.flac', '.so')

def main(src, dst):
    zin = zipfile.ZipFile(src)
    with zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            name = info.filename
            stored = name == 'resources.arsc' or name.lower().endswith(STORE_EXT) or info.compress_type == zipfile.ZIP_STORED and len(data) < 64
            zi = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
            zi.external_attr = info.external_attr
            zi.create_system = 0
            if stored:
                zi.compress_type = zipfile.ZIP_STORED
                align = 4096 if name.endswith('.so') else 4
                offset = zout.fp.tell() + 30 + len(name.encode('utf-8'))
                zi.extra = b'\0' * ((-offset) % align)
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, data, compresslevel=9 if not stored else None)
    # verify
    with open(dst, 'rb') as f:
        raw = f.read()
    for info in zipfile.ZipFile(dst).infolist():
        if info.compress_type == zipfile.ZIP_STORED:
            ho = info.header_offset
            nlen = int.from_bytes(raw[ho + 26:ho + 28], 'little')
            xlen = int.from_bytes(raw[ho + 28:ho + 30], 'little')
            data_off = ho + 30 + nlen + xlen
            assert data_off % 4 == 0, (info.filename, data_off)
    print('aligned', dst)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
