"""AES-128-ECB 解密（纯 Python）。

状态布局 s[col][row]，字节顺序 block[col*4+row]（FIPS 197 §3.4 的列优先约定）。
已用 FIPS-197 附录 C.1 向量校验，加密/解密互为逆运算。
"""
from gen_sbox import make_sbox

S = make_sbox()
IS = [0] * 256
for _i, _v in enumerate(S):
    IS[_v] = _i
RC = [1, 2, 4, 8, 16, 32, 64, 128, 27, 54]


def _gmul(a, b):
    p = 0
    for _ in range(8):
        if b & 1:
            p ^= a
        a = ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else ((a << 1) & 0xFF)
        b >>= 1
    return p


M2 = [_gmul(a, 2) for a in range(256)]
M3 = [_gmul(a, 3) for a in range(256)]
M9 = [_gmul(a, 9) for a in range(256)]
M11 = [_gmul(a, 11) for a in range(256)]
M13 = [_gmul(a, 13) for a in range(256)]
M14 = [_gmul(a, 14) for a in range(256)]


def expand(key):
    w = [list(key[i * 4:i * 4 + 4]) for i in range(4)]
    for i in range(4, 44):
        t = list(w[i - 1])
        if i % 4 == 0:
            t = [S[t[1]], S[t[2]], S[t[3]], S[t[0]]]
            t[0] ^= RC[i // 4 - 1]
        w.append([w[i - 4][j] ^ t[j] for j in range(4)])
    return w


def decrypt_block(block, w):
    s = [[block[c * 4 + r] for r in range(4)] for c in range(4)]   # s[col][row]

    def ark(rnd):
        for c in range(4):
            wc = w[rnd * 4 + c]
            for r in range(4):
                s[c][r] ^= wc[r]

    ark(10)
    for rnd in range(9, -1, -1):
        # InvShiftRows：s[c][r] = old[(c - r) % 4][r]
        s[:] = [[s[(c - r) % 4][r] for r in range(4)] for c in range(4)]
        # InvSubBytes
        s[:] = [[IS[s[c][r]] for r in range(4)] for c in range(4)]
        # AddRoundKey
        ark(rnd)
        if rnd == 0:
            break
        # InvMixColumns（按列）
        new = [[0] * 4 for _ in range(4)]
        for c in range(4):
            a0, a1, a2, a3 = s[c][0], s[c][1], s[c][2], s[c][3]
            new[c][0] = M14[a0] ^ M11[a1] ^ M13[a2] ^ M9[a3]
            new[c][1] = M9[a0] ^ M14[a1] ^ M11[a2] ^ M13[a3]
            new[c][2] = M13[a0] ^ M9[a1] ^ M14[a2] ^ M11[a3]
            new[c][3] = M11[a0] ^ M13[a1] ^ M9[a2] ^ M14[a3]
        s[:] = new
    return bytes(s[c][r] for c in range(4) for r in range(4))


def ecb_decrypt(data, key):
    if len(data) % 16:
        data = data[:len(data) - len(data) % 16]
    w = expand(key)
    return b"".join(decrypt_block(data[i:i + 16], w) for i in range(0, len(data), 16))


def encrypt_block(block, w):
    """AES-128 单块加密（与 decrypt_block 互为逆运算）。"""
    s = [[block[c * 4 + r] for r in range(4)] for c in range(4)]

    def ark(rnd):
        for c in range(4):
            wc = w[rnd * 4 + c]
            for r in range(4):
                s[c][r] ^= wc[r]

    ark(0)
    for rnd in range(1, 11):
        # SubBytes
        s[:] = [[S[s[c][r]] for r in range(4)] for c in range(4)]
        # ShiftRows：第 r 行左移 r 位 → s[c][r] = old[(c + r) % 4][r]
        s[:] = [[s[(c + r) % 4][r] for r in range(4)] for c in range(4)]
        if rnd != 10:
            # MixColumns
            new = [[0] * 4 for _ in range(4)]
            for c in range(4):
                a0, a1, a2, a3 = s[c][0], s[c][1], s[c][2], s[c][3]
                new[c][0] = M2[a0] ^ M3[a1] ^ a2 ^ a3
                new[c][1] = a0 ^ M2[a1] ^ M3[a2] ^ a3
                new[c][2] = a0 ^ a1 ^ M2[a2] ^ M3[a3]
                new[c][3] = M3[a0] ^ a1 ^ a2 ^ M2[a3]
            s[:] = new
        ark(rnd)
    return bytes(s[c][r] for c in range(4) for r in range(4))


def ecb_encrypt(data, key):
    if len(data) % 16:
        raise ValueError("ECB 数据长度必须是 16 的倍数")
    w = expand(key)
    return b"".join(encrypt_block(data[i:i + 16], w) for i in range(0, len(data), 16))


if __name__ == "__main__":
    k = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    w = expand(k)
    pt = bytes.fromhex("00112233445566778899aabbccddeeff")
    ct = "69c4e0d86a7b0430d8cdb78070b4c55a"
    got_d = decrypt_block(bytes.fromhex(ct), w).hex()
    got_e = encrypt_block(pt, w).hex()
    print("decrypt:", got_d, "OK" if got_d == pt.hex() else "FAIL")
    print("encrypt:", got_e, "OK" if got_e == ct else "FAIL")
    print("roundtrip:", "OK" if ecb_decrypt(ecb_encrypt(pt, k), k) == pt else "FAIL")
