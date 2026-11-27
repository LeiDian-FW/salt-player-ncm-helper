#!/usr/bin/env python3
"""下载并校验原生 UI（Kotlin 2.4.0 + Compose 编译器）工具链。

版本与参考实现 Salt Extend Online 锁定的一致（Steam Salt 1.19.3 / Kotlin 2.4.0）。
工具链仅用于本机构建，不进入 .spmod。
"""
import hashlib
import os
import sys
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(BASE, ".tools", "kotlin-2.4.0")
M2 = "https://repo.maven.apache.org/maven2"
G = "https://dl.google.com/dl/android/maven2"

ITEMS = [
    ("kotlin-compiler-embeddable-2.4.0.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.0/kotlin-compiler-embeddable-2.4.0.jar",
     "c2b25e8c1c93ec416ba4327f5e31eaec0f0c8847241b9353e294b8db9dce564f"),
    ("kotlin-build-tools-api-2.4.0.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-build-tools-api/2.4.0/kotlin-build-tools-api-2.4.0.jar",
     "66917335497f9ab75a36c34b3d90bad2bc8dc256c34b2413ecd0c9467fcf597d"),
    ("kotlin-stdlib-2.4.0.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar",
     "ccb14ff83fabcb11458b798dbc9824748ccdffeec79c9aba789e6ed1cda86b1c"),
    ("kotlin-script-runtime-2.4.0.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-script-runtime/2.4.0/kotlin-script-runtime-2.4.0.jar",
     "196e8ecbf63daf477e34e67c2f871ee232ae7bf8b377432d876d1bbf9d273de3"),
    ("kotlin-reflect-1.6.10.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar",
     "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203"),
    ("kotlin-daemon-embeddable-2.4.0.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-daemon-embeddable/2.4.0/kotlin-daemon-embeddable-2.4.0.jar",
     "383093af5e078435b60043d19bfa83eb6191be0f4d13a28fb1c8a0f3ab24fe66"),
    ("kotlinx-coroutines-core-jvm-1.8.0.jar",
     M2 + "/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar",
     "9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5"),
    ("kotlin-compose-compiler-plugin-embeddable-2.4.0.jar",
     M2 + "/org/jetbrains/kotlin/kotlin-compose-compiler-plugin-embeddable/2.4.0/kotlin-compose-compiler-plugin-embeddable-2.4.0.jar",
     "95ccd5c4008e454a5cb26f89405613f2ae33f46bf950c8be4d01bbdbdcfbcd08"),
    ("annotations-13.0.jar",
     M2 + "/org/jetbrains/annotations/13.0/annotations-13.0.jar",
     "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478"),
    ("foundation-desktop-1.10.3.jar",
     M2 + "/org/jetbrains/compose/foundation/foundation-desktop/1.10.3/foundation-desktop-1.10.3.jar",
     "3a9b7bfae72f1a92a8123d3290bb7d7f61d92e5cde877af5373c3e5c2caceb2b"),
    ("runtime-desktop-1.10.5.jar",
     G + "/androidx/compose/runtime/runtime-desktop/1.10.5/runtime-desktop-1.10.5.jar",
     "b195860542f612de0512b54a75353e0f6d58815f0c9530a03bc5b0e734474829"),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    os.makedirs(TOOLS, exist_ok=True)
    for name, url, sha in ITEMS:
        dest = os.path.join(TOOLS, name)
        if os.path.isfile(dest) and sha256(dest) == sha:
            print("已存在:", name)
            continue
        print("下载:", name, "...", flush=True)
        tmp = dest + ".part"
        urllib.request.urlretrieve(url, tmp)
        got = sha256(tmp)
        if got != sha:
            os.remove(tmp)
            print("SHA-256 不匹配:", name, got, file=sys.stderr)
            return 1
        os.replace(tmp, dest)
        print("  完成", os.path.getsize(dest), "字节")
    print("工具链就绪:", TOOLS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
