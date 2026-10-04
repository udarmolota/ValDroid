"""Rewrite the target-platform field in Unity SerializedFile headers (e.g. 24 StandaloneLinux64 -> 13 Android).

The Android player's SerializedFile::ReadMetadata refuses any file whose header target is not 13
("created for another build target"). The check compares only that int, so this changes only it.
Format v22+: 48-byte header, then unityVersion (C string), then int32 targetPlatform in the file's
endianness (byte 16 of the header: 0 = little endian).

usage: retarget_sf.py NEW_TARGET FILE [FILE...]   (files are patched in place)
"""
import struct
import sys

new_target = int(sys.argv[1])
for path in sys.argv[2:]:
    with open(path, "r+b") as f:
        head = f.read(4096)
        version = struct.unpack_from(">I", head, 8)[0]
        if version < 22:
            print(f"{path}: format v{version} not handled, skipped")
            continue
        little = head[16] == 0
        off = 48
        off = head.index(b"\0", off) + 1
        fmt = "<i" if little else ">i"
        old = struct.unpack_from(fmt, head, off)[0]
        f.seek(off)
        f.write(struct.pack(fmt, new_target))
        print(f"{path}: target {old} -> {new_target} (v{version}, offset {off})")
