"""Minimal XAR (.pkg) reader: list entries or extract one entry's raw data.

usage: xar_extract.py PKG list
       xar_extract.py PKG get <path-in-archive> <out-file>
"""
import struct, sys, zlib, bz2, lzma
import xml.etree.ElementTree as ET


def read_toc(f):
    hdr = f.read(28)
    magic, hsize, ver, toc_c, toc_u, ck = struct.unpack(">4sHHQQI", hdr)
    assert magic == b"xar!", magic
    f.seek(hsize)
    toc = zlib.decompress(f.read(toc_c))
    return ET.fromstring(toc), hsize + toc_c


def walk(node, prefix=""):
    for fe in node.findall("file"):
        name = fe.findtext("name")
        path = prefix + name
        yield path, fe
        yield from walk(fe, path + "/")


def main():
    pkg, cmd = sys.argv[1], sys.argv[2]
    with open(pkg, "rb") as f:
        root, heap = read_toc(f)
        entries = list(walk(root.find("toc")))
        if cmd == "list":
            for p, fe in entries:
                d = fe.find("data")
                size = d.findtext("size") if d is not None else "-"
                print(size, p)
            return
        want, out = sys.argv[3], sys.argv[4]
        fe = dict(entries)[want]
        d = fe.find("data")
        off, length = int(d.findtext("offset")), int(d.findtext("length"))
        enc = d.find("encoding").get("style")
        f.seek(heap + off)
        dec = None
        if "gzip" in enc or "zlib" in enc:
            dec = zlib.decompressobj()
        elif "bzip2" in enc:
            dec = bz2.BZ2Decompressor()
        elif "xz" in enc or "lzma" in enc:
            dec = lzma.LZMADecompressor()
        with open(out, "wb") as o:
            left = length
            while left:
                chunk = f.read(min(left, 1 << 22))
                left -= len(chunk)
                o.write(dec.decompress(chunk) if dec else chunk)
        print("wrote", out, "encoding", enc)


if __name__ == "__main__":
    main()
