#!/usr/bin/env python3
"""Drives a running Companion server (tests/java/HostServer, or the stick) with pyatv's real client: pair with the PIN
shown, then connect again with the stored credentials and press buttons.

    python3 tests/companion_client_check.py <host> <port> <pin-file>

Needs pyatv (and srptools<2) in whatever Python runs it; it is not in requirements.txt.
"""
import asyncio
import sys
import time
from pathlib import Path

import pyatv
from pyatv.conf import AppleTV, ManualService
from pyatv.const import Protocol


async def main(host, port, pin_file):
    loop = asyncio.get_running_loop()
    conf = AppleTV(host, "check")
    conf.add_service(ManualService("check-id", Protocol.Companion, port, {}))

    pairing = await pyatv.pair(conf, Protocol.Companion, loop)
    await pairing.begin()
    for _ in range(900):
        if Path(pin_file).exists() and Path(pin_file).read_text().strip():
            break
        time.sleep(0.1)
    pin = Path(pin_file).read_text().strip()
    print("pairing with PIN", pin)
    pairing.pin(pin)
    await pairing.finish()
    assert pairing.has_paired, "pairing did not finish"
    await pairing.close()
    print("paired; credentials stored")

    atv = await pyatv.connect(conf, loop)
    rc = atv.remote_control
    await rc.up()
    await rc.select()
    await rc.menu()
    await rc.home()
    await rc.play_pause()
    await asyncio.sleep(0.5)
    atv.close()
    print("buttons sent")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], int(sys.argv[2]), sys.argv[3]))
