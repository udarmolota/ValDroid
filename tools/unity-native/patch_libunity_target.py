"""Patch the Android libunity.so (6000.0.75f1, il2cpp Release arm64) to accept serialized files built
for another target: in SerializedFile::ReadMetadata,
    0xa2c270  cmp  w1, #0xd        ->  mov w1, #0xd   (the file is treated as an Android file)
    0xa2c27c  b.ne <error>         ->  nop
The original bytes are verified first, so the script refuses any other libunity build.
usage: patch_libunity_target.py IN OUT
"""
import struct, sys
src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, "rb").read())
ph = struct.unpack_from("<Q", d, 0x20)[0]; n = struct.unpack_from("<H", d, 0x38)[0]
def off(va):
    for k in range(n):
        t, f, o, v, p, fs = struct.unpack_from("<IIQQQQ", d, ph + k * 56)
        if t == 1 and v <= va < v + fs and f & 1:
            return o + va - v
    raise SystemExit("address not in an executable segment")
CMP_W1_13 = 0x7100343F   # cmp w1, #0xd
MOV_W1_13 = 0x528001A1   # mov w1, #0xd
NOP = 0xD503201F
a, b = off(0xa2c270), off(0xa2c27c)
cur_a, cur_b = struct.unpack_from("<I", d, a)[0], struct.unpack_from("<I", d, b)[0]
if cur_a != CMP_W1_13 or (cur_b & 0xFF00001F) != 0x54000001:
    raise SystemExit(f"unexpected bytes: {cur_a:#x} {cur_b:#x} - not the analysed libunity")
struct.pack_into("<I", d, a, MOV_W1_13)
struct.pack_into("<I", d, b, NOP)
open(dst, "wb").write(d)
print("patched", dst)
