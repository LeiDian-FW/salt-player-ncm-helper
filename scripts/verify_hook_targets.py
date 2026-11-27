#!/usr/bin/env python3
"""验证原生界面 Hook 候选注入点是否仍与当前 Salt 宿主匹配。

用法:
    python scripts/verify_hook_targets.py [ffmpeg-x64.dll 路径]

检查项（对每个候选注入点）:
  1. 目标类存在，且包含目标方法名
  2. 调用点类（inClass）确实调用了「目标类#目标方法」（解析 class 常量池）
  3. 参数类型与插件注册时使用的列表一致（通过描述符粗校验）

宿主升级后如果本脚本失败，说明需要更新注入点，而不是插件逻辑坏了。
"""
import os
import struct
import sys
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_HOST = r"D:\SteamLibrary\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"

# 与 NeteaseNativeUi.kt 中 hookTargets 保持一致
CANDIDATES = [
    {
        "name": "候选1 标题栏",
        "owner": "androidx/compose/ui/\u0b61",
        "method": "\u037f",
        "caller": "androidx/compose/ui/\u0537",
        "params": 4,
    },
    {
        "name": "候选2 导航内容根",
        "owner": "com/xuncorp/voxzen/ui/navigation/AppNavigationKt",
        "method": "Content",
        "caller": "com/xuncorp/voxzen/ui/navigation/AppNavigationKt",
        "params": 0,
    },
    {
        "name": "候选3 侧栏项",
        "owner": "androidx/compose/ui/\u0b1a",
        "method": "\u037f",
        "caller": "androidx/compose/ui/\u0b1a$\u052e",
        "params": 1,
    },
]


def load_class(host, internal_name):
    with zipfile.ZipFile(host) as z:
        return z.read(internal_name + ".class")


def parse_constant_pool(data):
    """返回 (utf8 表, class 名表, name_and_type 表, 方法引用列表, 方法表)。"""
    count = struct.unpack(">H", data[8:10])[0]
    off = 10
    utf8 = {}
    classes = {}
    nats = {}
    refs = []
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            length = struct.unpack(">H", data[off:off + 2])[0]
            off += 2
            utf8[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag in (7, 8, 16, 19, 20):
            classes[i] = struct.unpack(">H", data[off:off + 2])[0]
            off += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            a = struct.unpack(">H", data[off:off + 2])[0]
            b = struct.unpack(">H", data[off + 2:off + 4])[0]
            off += 4
            if tag == 12:
                nats[i] = (a, b)
            else:
                refs.append((tag, a, b))
        elif tag == 15:
            off += 3
        elif tag in (3, 4):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        else:
            raise ValueError("未知常量池 tag: %d" % tag)
        i += 1
    return utf8, classes, nats, refs


def method_references(data):
    utf8, classes, nats, refs = parse_constant_pool(data)
    out = []
    for tag, class_idx, nat_idx in refs:
        if tag not in (10, 11):  # Methodref / InterfaceMethodref
            continue
        class_name = utf8.get(classes.get(class_idx, -1), "?")
        nat = nats.get(nat_idx)
        if not nat:
            continue
        out.append((class_name, utf8.get(nat[0], "?"), utf8.get(nat[1], "?")))
    return out


def real_param_count(descriptor):
    """从方法描述符统计真实参数个数（不含末尾 Compose 的 Composer/int）。"""
    try:
        args = descriptor[1:descriptor.index(")")]
    except ValueError:
        return -1
    # Compose 函数签名 = 真实参数 + Composer + int(变更掩码) [+ int(默认值掩码)]
    marker = "Landroidx/compose/runtime/Composer;"
    idx = args.find(marker)
    if idx >= 0:
        args = args[:idx]
    count = 0
    i = 0
    while i < len(args):
        ch = args[i]
        if ch == "[":
            i += 1
            continue
        if ch == "L":
            i = args.index(";", i) + 1
            count += 1
        else:
            i += 1
            count += 1
    # Compose 函数末尾固定是 Composer + int（这里参数里不含）
    return count


def declared_methods(data):
    """返回 (方法名, 描述符) 列表；失败时返回 None。"""
    count = struct.unpack(">H", data[8:10])[0]
    off = 10
    utf8 = {}
    i = 1
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:
            length = struct.unpack(">H", data[off:off + 2])[0]
            off += 2
            utf8[i] = data[off:off + length].decode("utf-8", "replace")
            off += length
        elif tag in (7, 8, 16, 19, 20):
            off += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            off += 4
        elif tag == 15:
            off += 3
        elif tag in (3, 4):
            off += 4
        elif tag in (5, 6):
            off += 8
            i += 1
        i += 1
    pos = off
    pos += 6
    interfaces = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2 + interfaces * 2

    def skip(pos, count):
        for _ in range(count):
            pos += 6
            attrs = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            for _ in range(attrs):
                pos += 2
                length = struct.unpack(">I", data[pos:pos + 4])[0]
                pos += 4 + length
        return pos

    try:
        fields = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        pos = skip(pos, fields)
        methods = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        out = []
        for _ in range(methods):
            _acc, name_idx, desc_idx = struct.unpack(">HHH", data[pos:pos + 6])
            pos += 6
            out.append((utf8.get(name_idx, "?"), utf8.get(desc_idx, "?")))
            attrs = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            for _ in range(attrs):
                pos += 2
                length = struct.unpack(">I", data[pos:pos + 4])[0]
                pos += 4 + length
        return out
    except struct.error:
        return None


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("SALT_HOST_PACKAGE", DEFAULT_HOST)
    if not os.path.isfile(host):
        print("找不到宿主归档:", host)
        return 2
    try:
        with zipfile.ZipFile(host) as z:
            names = set(z.namelist())
    except Exception as exc:
        print("无法读取宿主归档:", exc)
        return 2
    ok = 0
    for cand in CANDIDATES:
        owner = cand["owner"]
        caller = cand["caller"]
        print("[%s] %s#%s" % (cand["name"], owner, cand["method"]))
        if owner + ".class" not in names:
            print("   ✗ 目标类不存在")
            continue
        owner_data = load_class(host, owner)
        methods = declared_methods(owner_data)
        if methods is None:
            print("   ! 无法解析目标类方法表，跳过描述符检查")
        else:
            hits = [d for n, d in methods if n == cand["method"]]
            if not hits:
                print("   ✗ 目标类中找不到方法", cand["method"])
                continue
            print("   ✓ 目标方法存在，描述符:", hits[:4])
        if caller + ".class" not in names:
            print("   ✗ 调用点类不存在:", caller)
            continue
        refs = method_references(load_class(host, caller))
        calls = [(m, d) for c, m, d in refs if c == owner and m == cand["method"]]
        if not calls:
            print("   ✗ 调用点未调用目标方法（注入点可能已变化）")
            continue
        print("   ✓ 调用点调用目标方法 %d 次，描述符: %s"
              % (len(calls), [d for _, d in calls][:3]))
        match = any(real_param_count(d) == cand["params"] for _, d in calls)
        if not match:
            print("   ✗ 调用描述符的真实参数个数与插件注册的 params 不一致（期望 %d）" % cand["params"])
            continue
        print("   ✓ 调用描述符参数个数匹配（%d）" % cand["params"])
        ok += 1
    print("\n通过候选: %d/%d" % (ok, len(CANDIDATES)))
    return 0 if ok == len(CANDIDATES) else 1


if __name__ == "__main__":
    sys.exit(main())
