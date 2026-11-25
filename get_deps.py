#!/usr/bin/env python3
"""下载构建所需的编译期依赖（不随仓库提交二进制）。

用法： python get_deps.py
产物： libs/api.jar、libs/pf4j.jar、libs/kotlin-stdlib.jar

注意：这些依赖只在编译期使用（compileOnly），不会打进插件包——
运行时由 Salt Player 宿主提供。
"""
import hashlib
import os
import sys
try:  # 某些 Windows 环境默认 cp1252，无法输出中文
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
LIBS = os.path.join(BASE, "libs")

DEPS = [
    {
        "file": "api.jar",
        "url": "https://jitpack.io/com/github/Moriafly/spw-workshop-api/"
               "spw-workshop-api/0.1.0-dev22/spw-workshop-api-0.1.0-dev22.jar",
        "sha256": "00adc73b4802f1e23e55897ea9890200fdc161424f13fa8e0b9dfeb1a21722a8",
        "desc": "SPW Workshop API 0.1.0-dev22",
    },
    {
        "file": "pf4j.jar",
        "url": "https://repo1.maven.org/maven2/org/pf4j/pf4j/3.12.0/pf4j-3.12.0.jar",
        "sha256": "e4af5f0e9a9b11ec187c084ed819cedede7f27076ac5cf6aa2333eceadb57215",
        "desc": "PF4J 3.12.0",
    },
    {
        "file": "kotlin-stdlib.jar",
        "url": "https://repo1.maven.org/maven2/org/jetbrains/kotlin/"
               "kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.jar",
        "sha256": "2226de463d309d4a5500a481320b3dea515a6981dcae1def531fbc158884e25f",
        "desc": "Kotlin stdlib 2.4.20",
    },
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    os.makedirs(LIBS, exist_ok=True)
    for dep in DEPS:
        dest = os.path.join(LIBS, dep["file"])
        if os.path.isfile(dest) and sha256(dest) == dep["sha256"]:
            print("已存在且校验通过: %s (%s)" % (dep["file"], dep["desc"]))
            continue
        print("下载 %s -> %s" % (dep["desc"], dep["file"]))
        tmp = dest + ".part"
        try:
            urllib.request.urlretrieve(dep["url"], tmp)
        except Exception as e:
            print("下载失败: %s\n  URL: %s" % (e, dep["url"]))
            sys.exit(1)
        got = sha256(tmp)
        if got != dep["sha256"]:
            os.remove(tmp)
            print("校验失败: %s\n  期望 %s\n  实际 %s" % (dep["file"], dep["sha256"], got))
            sys.exit(1)
        os.replace(tmp, dest)
        print("  完成，SHA-256 校验通过")
    print("\n依赖就绪，可以运行 build.py")


if __name__ == "__main__":
    main()
