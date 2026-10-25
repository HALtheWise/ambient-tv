#!/usr/bin/env python3
"""Tiny ADB-over-TCP helper for the TV.

  tools/tv.py 'getprop ro.build.version.sdk'          run a shell command
  tools/tv.py --keys DPAD_RIGHT DPAD_DOWN               press remote keys
  tools/tv.py --screenshot shots/01.png [KEY ...]       press keys, then capture + pull PNG
  tools/tv.py --push local.apk /data/local/tmp/x.apk    push a file (md5-verified)
  tools/tv.py --pull /sdcard/x out                      pull a file
  tools/tv.py --sleep                                   standby (via the device owner)
  tools/tv.py --wake                                    Wake-on-LAN, then wait for adb

Host, port and the MAC for --wake come from TV_HOST / TV_PORT / TV_MAC, in the environment or in
tools/tv.env (KEY=value lines, not committed; see tools/tv.env.example). In standby the TV drops
off the network entirely, but a magic packet wakes it fully. The ADB key is ~/.android/adbkey,
made on first use if missing; the TV asks once to allow it.
"""
import base64, hashlib, os, socket, sys, time
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

def settings():
    env = {}
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tv.env")
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip()
    env.update({k: v for k, v in os.environ.items() if k.startswith("TV_")})
    return env


ENV = settings()
HOST = ENV.get("TV_HOST")
PORT = int(ENV.get("TV_PORT", "5555"))
MAC = ENV.get("TV_MAC")
KEY = os.path.expanduser("~/.android/adbkey")


def connect():
    if not HOST:
        sys.exit("Set TV_HOST (the TV's IP address) in tools/tv.env or the environment.")
    if not os.path.exists(KEY):
        from adb_shell.auth.keygen import keygen
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
        print("Made a new ADB key; accept the prompt on the TV.", file=sys.stderr)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=30)
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=120)
    return dev


def sh(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout)


def keys(dev, names):
    for k in names:
        k = k if k.isdigit() or k.startswith("KEYCODE_") else "KEYCODE_" + k
        sh(dev, f"input keyevent {k}")
        time.sleep(0.6)


def pull(dev, remote, local):
    os.makedirs(os.path.dirname(local) or ".", exist_ok=True)
    dev.pull(remote, local, transport_timeout_s=60, read_timeout_s=60)


def push(dev, local, remote):
    # The adb sync protocol stalls on this TV for files over ~100 KB, so send base64 chunks
    # through the shell instead; the md5 check below catches any corruption.
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    b64 = base64.b64encode(data).decode()
    sh(dev, f"rm -f {remote}.b64")
    for i in range(0, len(b64), 60000):
        sh(dev, f"echo -n '{b64[i:i+60000]}' >> {remote}.b64")
    sh(dev, f"base64 -d {remote}.b64 > {remote} && rm {remote}.b64")
    got = sh(dev, f"md5sum {remote}").split()[0]
    if got != want:
        sys.exit(f"md5 mismatch: {got} != {want}")
    print(f"pushed {local} -> {remote} ({len(data)} bytes, md5 ok)")


def wake():
    packet = b"\xff" * 6 + bytes.fromhex(MAC.replace(":", "")) * 16
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    subnet = HOST.rsplit(".", 1)[0] + ".255"
    deadline = time.time() + 60
    while time.time() < deadline:
        for dst in ("255.255.255.255", subnet, HOST):
            s.sendto(packet, (dst, 9))
        try:
            socket.create_connection((HOST, PORT), timeout=3).close()
            print("awake")
            return 0
        except OSError:
            time.sleep(2)
    print("no answer after 60 s", file=sys.stderr)
    return 1


def main(argv):
    if argv[:1] == ["--wake"]:
        return wake()
    dev = connect()
    if not argv:
        print(sh(dev, "getprop ro.product.model"))
    elif argv[0] == "--screenshot":
        out, ks = argv[1], argv[2:]
        keys(dev, ks)
        time.sleep(1.0)
        sh(dev, "screencap -p /sdcard/_shot.png")
        pull(dev, "/sdcard/_shot.png", out)
        if os.path.getsize(out) == 0:
            print("empty screenshot (protected content: Live TV/HDMI/DRM app); "
                  "check `dumpsys activity activities | grep topResumedActivity`", file=sys.stderr)
            return 1
        print(out)
    elif argv[0] == "--keys":
        keys(dev, argv[1:])
    elif argv[0] == "--push":
        push(dev, argv[1], argv[2])
    elif argv[0] == "--pull":
        pull(dev, argv[1], argv[2])
    elif argv[0] == "--sleep":
        print(sh(dev, "am broadcast -a bot.tvowner.CMD -n bot.tvowner/.Cmd --es op sleep").strip().splitlines()[-1])
    else:
        print(sh(dev, " ".join(argv)), end="")
    dev.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
