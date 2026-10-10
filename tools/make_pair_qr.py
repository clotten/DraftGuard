#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 Android 无线调试的配对二维码（供手机扫码配对）。

原理
----
手机扫到的二维码内容形如：
    WIFI:T:ADB;S:adb-<主机名>-<随机串>;P:<随机密码>;;
手机解析后会：
  1) 通过 mDNS 在局域网上找 _adb-tls-pairing._tcp 服务，
     用 S: 里的名字匹配（所以服务名必须和本机 adb 广播的一致）；
  2) 用 P: 里的密码与那个服务做 TLS 配对。

因此这个脚本的工作方式是：
  · 启动 `adb pair`（不带码），从它输出里读出**真实的服务名与密码**；
  · 按上面格式拼成字符串，渲染成二维码图片。

用法
----
    python make_pair_qr.py                 # 输出 pair-qr.png 并打印明文
    python make_pair_qr.py -o qr.png

注意：如果本机 mDNS 不工作（`adb mdns services` 列表为空），
手机扫码后找不到服务，配对会失败 —— 这是环境限制，不是二维码的问题。
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
def _find_adb():
    """adb 路径自动探测（不要写死本机路径）"""
    import os
    import shutil
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            cand = os.path.join(root, "platform-tools", "adb.exe")
            if os.path.exists(cand):
                return cand
    found = shutil.which("adb")
    if found:
        return found
    for cand in (r"%LOCALAPPDATA%\Android\Sdk", "C:\\Android\\Sdk", "D:\\Android\\Sdk"):
        p = os.path.join(os.path.expandvars(cand), "platform-tools", "adb.exe")
        if os.path.exists(p):
            return p
    return "adb"


DEFAULT_ADB = _find_adb()


def find_adb(explicit: str | None) -> str:
    if explicit and Path(explicit).exists():
        return explicit
    for p in (DEFAULT_ADB, "/usr/bin/adb", "/usr/local/bin/adb"):
        if Path(p).exists():
            return p
    return "adb"


def read_adb_pair_output(adb: str, seconds: float = 3.0):
    """启动 `adb pair`，把它的提示读出来（里面带真实的服务名与密码）。"""
    proc = subprocess.Popen(
        [adb, "pair"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    time.sleep(seconds)
    try:
        proc.kill()
    except Exception:
        pass
    try:
        out, _ = proc.communicate(timeout=5)
    except Exception:
        out = ""
    return out or "", proc


def extract(out: str):
    """从 adb pair 的输出里抽出服务名与密码。"""
    service = None
    password = None
    m = re.search(r"service(?: name)?\s*[:=]\s*(\S+)", out, re.I)
    if m:
        service = m.group(1)
    m = re.search(r"password\s*[:=]\s*(\S+)", out, re.I)
    if m:
        password = m.group(1)
    if not service:
        m = re.search(r"(adb-[\w\-\.]+)", out)
        if m:
            service = m.group(1)
    return service, password


def main() -> int:
    ap = argparse.ArgumentParser(description="生成无线调试配对二维码")
    ap.add_argument("-o", "--out", default=str(HERE / "pair-qr.png"))
    ap.add_argument("--adb", default=None)
    ap.add_argument("--service", default=None, help="手动指定服务名")
    ap.add_argument("--password", default=None, help="手动指定密码")
    args = ap.parse_args()

    adb = find_adb(args.adb)
    service, password = args.service, args.password
    raw = ""

    if not service or not password:
        print(f"启动 {adb} pair 以获取真实的服务名与密码…")
        raw, _ = read_adb_pair_output(adb)
        print("--- adb pair 输出 ---")
        print(raw.strip() or "(无输出)")
        print("---------------------")
        auto_service, auto_password = extract(raw)
        service = service or auto_service
        password = password or auto_password

    if not service or not password:
        print("\n[!] 没能从 adb 输出里解析出服务名/密码。")
        print("    adb 36 的 `adb pair` 在非交互终端下可能不打印这两项。")
        print("    那就只能用「手机显示配对码 + 电脑输入」的方式，或改用数据线。")
        print("\n    如果你能在手机上看到二维码模式给出的信息，也可以用：")
        print("      python make_pair_qr.py --service <服务名> --password <密码>")
        return 2

    payload = f"WIFI:T:ADB;S:{service};P:{password};;"
    print("\n二维码内容（明文）：")
    print("  " + payload)

    import qrcode  # 延迟导入：没有库时给出更清楚的报错
    img = qrcode.make(payload)
    img = img.resize((720, 720))
    img.save(args.out)
    print(f"\n二维码已保存：{args.out}")
    print("用手机的「无线调试 → 使用二维码配对设备」扫描它。")

    # 提醒 mDNS 前提
    try:
        chk = subprocess.run([adb, "mdns", "services"], capture_output=True, text=True, timeout=10)
        body = (chk.stdout or "").strip()
        lines = [l for l in body.splitlines() if l.strip() and "discovered" not in l]
        if not lines:
            print("\n[!] 警告：本机 adb 当前没有广播任何 mDNS 服务（列表为空）。")
            print("    手机扫码后很可能找不到本机，配对会失败。")
            print("    这种情况建议改用数据线：见 docs/REMOTE-DEBUG.md")
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
