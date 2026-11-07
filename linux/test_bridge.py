#!/usr/bin/env python3
"""End-to-end daemon test using a fake phone (no Android device needed).

Requires a running phone-speakerd. Verifies protocol, device lifetime and audio
content in both directions, and measures one-way latency through the daemon.
Audio is routed only between the virtual devices, so nothing plays out loud.
"""
import array
import socket
import subprocess
import sys
import threading
import time

PORT = 47100
RATE = 48000
FORMAT_ARGS = ["--raw", "--rate", str(RATE), "--format", "s16"]


def devices():
    return subprocess.run(["wpctl", "status"], capture_output=True, text=True).stdout


def connect(flags):
    sock = socket.create_connection(("127.0.0.1", PORT), timeout=3)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(b"PSPK" + bytes([1, flags]))
    assert sock.recv(4) == b"PSOK", "no ack"
    return sock


def wait_for(text, present=True, timeout=4.0):
    end = time.time() + timeout
    while time.time() < end:
        if (text in devices()) == present:
            return True
        time.sleep(0.05)
    return False


def beep(samples=480, level=20000):
    return array.array("h", [level] * samples).tobytes()


def peak(data):
    a = array.array("h")
    a.frombytes(data[: len(data) // 2 * 2])
    return max((abs(x) for x in a), default=0)


def test_bad_header():
    sock = socket.create_connection(("127.0.0.1", PORT), timeout=3)
    sock.sendall(b"NOPE\x01\x03")
    sock.settimeout(3)
    assert sock.recv(4) == b"", "bad header should be rejected"
    assert "Phone Speaker" not in devices()
    print("ok  bad header rejected")


def test_lifetime():
    assert "Phone Speaker" not in devices() and "Phone Mic" not in devices(), "already present"
    sock = connect(3)
    assert wait_for("Phone Speaker") and wait_for("Phone Mic"), "devices did not appear"
    sock.close()
    assert wait_for("Phone Speaker", False) and wait_for("Phone Mic", False), "devices stayed"
    sock = connect(1)
    assert wait_for("Phone Speaker") and not wait_for("Phone Mic", timeout=0.5), "mic flag ignored"
    sock.close()
    assert wait_for("Phone Speaker", False)
    print("ok  devices follow the session and flags")


def test_speaker():
    sock = connect(1)
    assert wait_for("Phone Speaker")
    sock.settimeout(5)
    player = subprocess.Popen(
        ["pw-play", *FORMAT_ARGS, "--channels", "1", "--target", "phone_speaker", "-"],
        stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    sent = beep(48000)
    player.stdin.write(sent)
    player.stdin.close()
    got, loud_at, start = bytearray(), None, time.time()
    while time.time() - start < 3:
        try:
            chunk = sock.recv(8192)
        except socket.timeout:
            break
        if not chunk:
            break
        got += chunk
        if loud_at is None and peak(chunk) > 3000:
            loud_at = time.time() - start
        if len(got) > 192000:
            break
    player.terminate()
    sock.close()
    assert loud_at is not None, f"no speaker audio ({len(got)} bytes, peak {peak(bytes(got))})"
    print(f"ok  speaker audio arrives ({len(got)} bytes, peak {peak(bytes(got))})")
    wait_for("Phone Speaker", False)


def test_mic():
    sock = connect(2)
    assert wait_for("Phone Mic")
    rec = subprocess.Popen(
        ["pw-record", *FORMAT_ARGS, "--channels", "1", "--latency", "20ms",
         "--target", "phone_mic", "-"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    got, stop = bytearray(), threading.Event()

    def reader():
        while not stop.is_set():
            chunk = rec.stdout.read1(4096)
            if not chunk:
                return
            got.extend(chunk)

    threading.Thread(target=reader, daemon=True).start()
    time.sleep(0.5)
    for _ in range(100):  # 1 s of 10 ms packets, paced like a phone
        sock.sendall(beep(480))
        time.sleep(0.01)
    time.sleep(0.3)
    stop.set()
    rec.terminate()
    sock.close()
    assert peak(bytes(got)) > 3000, f"no mic audio ({len(got)} bytes, peak {peak(bytes(got))})"
    print(f"ok  mic audio arrives ({len(got)} bytes, peak {peak(bytes(got))})")
    wait_for("Phone Mic", False)


def test_mic_flood_does_not_block():
    """Sending mic audio with no recorder must not stall the speaker path."""
    sock = connect(3)
    assert wait_for("Phone Mic")
    sock.settimeout(0.2)
    start = time.time()
    for _ in range(200):
        sock.sendall(beep(480))
    took = time.time() - start
    sock.close()
    assert took < 2, f"daemon stopped draining mic audio ({took:.2f}s)"
    print(f"ok  unread mic audio is dropped, not queued ({took*1000:.0f} ms for 2 s of audio)")
    wait_for("Phone Mic", False)


def test_latency():
    """One-way time from a burst entering "Phone Speaker" to it leaving the daemon."""
    sock = connect(1)
    assert wait_for("Phone Speaker")
    sock.setblocking(False)
    player = subprocess.Popen(
        ["pw-play", *FORMAT_ARGS, "--channels", "1", "--latency", "10ms",
         "--target", "phone_speaker", "-"],
        stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    results = []
    silence, loud = beep(480, 0), beep(480, 20000)

    def drain(until, watch):
        hit = None
        while time.time() < until:
            try:
                chunk = sock.recv(8192)
                if watch and hit is None and peak(chunk) > 3000:
                    hit = time.time()
            except BlockingIOError:
                pass
            time.sleep(0.0005)
        return hit

    for _ in range(50):  # settle
        player.stdin.write(silence)
        player.stdin.flush()
        drain(time.time() + 0.01, False)
    for _ in range(10):
        start = time.time()
        player.stdin.write(loud * 5)
        player.stdin.flush()
        hit = drain(start + 0.4, True)
        if hit:
            results.append((hit - start) * 1000)
        for _ in range(30):
            player.stdin.write(silence)
            player.stdin.flush()
            drain(time.time() + 0.01, False)
    player.terminate()
    sock.close()
    assert results, "no bursts detected"
    results.sort()
    print(f"ok  latency PC side, including test pw-play: median {results[len(results)//2]:.0f} ms, "
          f"worst {results[-1]:.0f} ms over {len(results)} bursts")


if __name__ == "__main__":
    tests = [test_bad_header, test_lifetime, test_speaker, test_mic,
             test_mic_flood_does_not_block, test_latency]
    failed = 0
    for test in tests:
        try:
            test()
        except AssertionError as err:
            failed += 1
            print(f"FAIL {test.__name__}: {err}")
    sys.exit(1 if failed else 0)
