#!/usr/bin/env python3
"""生成测试用的合成 NCM / FLAC / JPEG（不依赖任何外部文件与第三方库）。

NCM 只是一层容器：音频部分可以是任意字节，测试关心的是容器解析、
密钥推导、流式解密、封面内嵌与归档逻辑。因此这里构造一个结构合法的
"假 FLAC"（含 STREAMINFO 与 VORBIS_COMMENT 块），再封装成 NCM。

用法： python test/tools/build_test_ncm.py <输出目录>
产物： sample.flac / sample.ncm / cover.jpg / sample.lrc
"""
import base64
import json
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aes

CORE_KEY = b"hzHRAmso5kInbaxW"
META_KEY = bytes([0x23, 0x31, 0x34, 0x6c, 0x6a, 0x6b, 0x5f, 0x21,
                  0x5c, 0x5d, 0x26, 0x30, 0x55, 0x3c, 0x27, 0x28])
RC4_KEY = (b"24098232679316E7fT49x7dof9OKCgg9cdvhEuezy3iZCL1"
           b"nFvBFd1T4uSktAJKmwZXsijPbijliionVUXXg9plTbXEclAE9Lb")

TITLE = "测试歌曲"
ARTIST = "测试歌手/合作歌手"
ALBUM = "测试专辑"


# ---------------- 合成 FLAC ----------------
def _vorbis_comment(pairs):
    vendor = b"ncm-helper-test"
    out = bytearray()
    out += struct.pack("<I", len(vendor)) + vendor
    out += struct.pack("<I", len(pairs))
    for k, v in pairs:
        item = ("%s=%s" % (k, v)).encode("utf-8")
        out += struct.pack("<I", len(item)) + item
    return bytes(out)


def make_flac(audio_size=1_500_000):
    """返回 (flac_bytes, audio_offset)。"""
    streaminfo = bytearray(34)
    streaminfo[0:2] = struct.pack(">H", 4096)          # min block size
    streaminfo[2:4] = struct.pack(">H", 4096)          # max block size
    packed = (44100 << 44) | ((2 - 1) << 41) | ((16 - 1) << 36) | (audio_size // 8)
    streaminfo[10:18] = packed.to_bytes(8, "big")

    blocks = [
        (0, bytes(streaminfo)),
        (4, _vorbis_comment([("TITLE", TITLE), ("ARTIST", ARTIST), ("ALBUM", ALBUM)])),
    ]
    out = bytearray(b"fLaC")
    for i, (btype, body) in enumerate(blocks):
        out.append((0x80 if i == len(blocks) - 1 else 0) | btype)
        out += len(body).to_bytes(3, "big")
        out += body
    offset = len(out)

    # 音频帧区：FLAC 帧同步 + 伪数据（容器层测试不需要能解码）
    audio = bytearray(b"\xff\xf8\x69\x18\x00")
    for i in range(max(0, audio_size - len(audio))):
        audio.append((i * 37 + 11) & 0xFF)
    out += audio
    return bytes(out), offset


def make_cover():
    """最小可解析 JPEG 头（SOI + SOF0），供封面尺寸解析使用。"""
    return (b"\xff\xd8" + b"\xff\xc0" + struct.pack(">H", 17) + b"\x08"
            + struct.pack(">H", 64) + struct.pack(">H", 64) + b"\x03"
            + b"\x01\x11\x00\x02\x11\x01\x03\x11\x01" + b"\xff\xd9")


# ---------------- NCM 封装 ----------------
def _pkcs7(data):
    pad = 16 - len(data) % 16
    return data + bytes([pad]) * pad


def _keybox_mask(key):
    box = list(range(256))
    last = 0
    for i in range(256):
        k = (box[i] + last + key[i % len(key)]) & 0xFF
        box[i], box[k] = box[k], box[i]
        last = k
    mask = bytearray(256)
    for idx in range(256):
        j = (idx + 1) & 0xFF
        mask[idx] = box[(box[j] + box[(box[j] + j) & 0xFF]) & 0xFF]
    return bytes(mask)


def _rc4_xor(data, key):
    mask = _keybox_mask(key)
    return bytes(b ^ mask[i & 0xFF] for i, b in enumerate(data))


def build_ncm(flac, cover, out_path):
    key_plain = b"neteasecloudmusic" + RC4_KEY
    key_plain += b"\x0c" * (128 - len(key_plain))
    key_block = bytes(b ^ 0x64 for b in aes.ecb_encrypt(key_plain, CORE_KEY))

    meta = {
        "musicId": "1", "musicName": TITLE,
        "artist": [[a, "1"] for a in ARTIST.split("/") if a],
        "albumId": "1", "album": ALBUM, "albumPicDocId": "", "albumPic": "",
        "bitrate": 999000, "mp3DocId": "", "duration": 1000, "mvId": 0,
        "alias": [], "transNames": [], "format": "flac", "fee": 0,
        "volumeDelta": 0.0, "privilege": {"flag": 0},
    }
    plain = _pkcs7(b"music:" + json.dumps(meta, ensure_ascii=False).encode("utf-8"))
    blob = b"163 key(Don't modify):" + base64.b64encode(aes.ecb_encrypt(plain, META_KEY))
    blob = bytes(b ^ 0x63 for b in blob)

    out = bytearray()
    out += b"CTENFDAM" + b"\x01\x70" + struct.pack("<I", len(key_block)) + key_block
    out += struct.pack("<I", len(blob)) + blob
    out += struct.pack("<I", zlib.crc32(flac) & 0xFFFFFFFF)
    out += b"\x01\x00\x3a\x03\x00"
    out += struct.pack("<I", len(cover)) + cover
    out += _rc4_xor(flac, RC4_KEY)
    with open(out_path, "wb") as f:
        f.write(out)
    return len(out)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "test/samples"
    os.makedirs(out_dir, exist_ok=True)
    flac, _ = make_flac()
    cover = make_cover()
    flac_path = os.path.join(out_dir, "sample.flac")
    ncm_path = os.path.join(out_dir, "sample.ncm")
    cover_path = os.path.join(out_dir, "cover.jpg")
    with open(flac_path, "wb") as f:
        f.write(flac)
    with open(cover_path, "wb") as f:
        f.write(cover)
    size = build_ncm(flac, cover, ncm_path)
    print("生成: %s (%d 字节 FLAC)" % (flac_path, len(flac)))
    print("生成: %s (%d 字节 NCM)" % (ncm_path, size))
    print("生成: %s (%d 字节 JPEG)" % (cover_path, len(cover)))


if __name__ == "__main__":
    main()
