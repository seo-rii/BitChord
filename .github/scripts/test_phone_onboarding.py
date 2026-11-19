"""Exercise the first run of the freshly installed dev APK on the English CI emulator."""

import re
import subprocess
import time
from xml.etree import ElementTree

PACKAGE = "com.dev.bitchord"
ACTIVITY = PACKAGE + "/com.music.bitchord.MainActivity"
DUMP = "/data/local/tmp/bitchord-onboarding.xml"
PROXY = "Continue without WireGuard"
CONNECT = "Sign in with microG"
SKIP = "Skip sign in"
WELCOME = (PROXY, CONNECT, SKIP, "What should we call you?")


def adb(*args, timeout=20):
    return subprocess.check_output(["adb", *args], timeout=timeout)


def wait_for(*labels, absent=()):
    # Compose briefly exposes both pages while the transition animates.
    deadline = time.monotonic() + 60
    last_package = None
    known_labels = set()
    while time.monotonic() < deadline:
        try:
            status = adb("shell", "uiautomator", "dump", DUMP)
        except subprocess.TimeoutExpired:
            continue
        if b"dumped to:" in status:
            nodes = {}
            hierarchy = ElementTree.fromstring(adb("exec-out", "cat", DUMP))
            root = hierarchy.find("node")
            last_package = root.get("package") if root is not None else None
            for node in hierarchy.iter("node"):
                if node.get("package") == PACKAGE and node.get("enabled") == "true":
                    for attribute in ("text", "content-desc"):
                        label = node.get(attribute)
                        if label:
                            nodes[label] = node.get("bounds")
            known_labels = nodes.keys() & {*WELCOME, "Library"}
            if all(label in nodes for label in labels) and not any(label in nodes for label in absent):
                return nodes
        time.sleep(0.3)
    raise AssertionError(
        f"Expected BitChord to show {labels} and hide {absent}. "
        f"Active package {last_package}, known controls {sorted(known_labels)}"
    )


def tap(bounds):
    left, top, right, bottom = map(int, re.findall(r"-?\d+", bounds))
    assert right > left and bottom > top, f"Control has empty bounds {bounds}"
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def launch():
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=40)


def main():
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise RuntimeError("Run this test on the CI emulator")
    launch()
    tap(wait_for(PROXY)[PROXY])
    wait_for(CONNECT, SKIP, absent=(PROXY,))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap(wait_for(PROXY, absent=(SKIP,))[PROXY])
    tap(wait_for(CONNECT, SKIP, absent=(PROXY,))[SKIP])
    wait_for("Library", absent=WELCOME)

    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    wait_for("Library", absent=WELCOME)
    print("First-run proxy, optional sign-in, Back and completed cold start passed.")


if __name__ == "__main__":
    main()
