"""由 GF(2^8) 逆元 + 仿射变换直接生成 AES S 盒，避免手抄表出错。"""


def _affine(x):
    b = [(x >> i) & 1 for i in range(8)]
    r = 0
    for i in range(8):
        v = b[i] ^ b[(i + 4) % 8] ^ b[(i + 5) % 8] ^ b[(i + 6) % 8] ^ b[(i + 7) % 8] ^ ((0x63 >> i) & 1)
        r |= v << i
    return r


def _gmul(a, b):
    p = 0
    for _ in range(8):
        if b & 1:
            p ^= a
        hi = a & 0x80
        a = ((a << 1) & 0xFF) ^ (0x1B if hi else 0)
        b >>= 1
    return p


def _inv(a):
    if a == 0:
        return 0
    for x in range(1, 256):
        if _gmul(a, x) == 1:
            return x
    raise ValueError(a)


def make_sbox():
    return [_affine(_inv(x)) for x in range(256)]


if __name__ == "__main__":
    import hashlib
    s = make_sbox()
    print("len:", len(s), "md5:", hashlib.md5(bytes(s)).hexdigest())
    print("row7:", " ".join("%02x" % s[0x70 + c] for c in range(16)))
