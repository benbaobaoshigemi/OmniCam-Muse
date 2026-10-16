"""Builds module/payload/audio_policy_volumes.xml (bind-mounted over /odm/etc/audio/).

Change against stock PMA110: STREAM_SYSTEM_ENFORCED (the forced shutter sound stream that
ShutterSound plays on in silent/vibrate mode) uses the system volume curve on the speaker, as the
file's own JP-version branch does. The CN branch caps it at -24 dB, which is barely audible; the
system curve peaks at -5 dB, the stock shutter loudness in normal mode. Everything else is stock.
"""
from __future__ import annotations

from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
STOCK = PROJECT / "inputs/stock_audio/audio_policy_volumes.xml"
OUTPUT = PROJECT / "module/payload/audio_policy_volumes.xml"

CN_SPEAKER = """    <volume stream="AUDIO_STREAM_ENFORCED_AUDIBLE" deviceCategory="DEVICE_CATEGORY_SPEAKER">
        <point>1,-3500</point>
        <point>33,-3100</point>
        <point>66,-2700</point>
        <point>100,-2400</point>
    </volume>
"""
SYSTEM_SPEAKER = """    <volume stream="AUDIO_STREAM_ENFORCED_AUDIBLE" deviceCategory="DEVICE_CATEGORY_SPEAKER"
                                                   ref="DEFAULT_SYSTEM_VOLUME_CURVE"/>
"""


def main() -> None:
    text = STOCK.read_text(encoding="utf-8")
    if text.count(CN_SPEAKER) != 1:
        raise RuntimeError("Stock enforced speaker curve changed; review the patch")
    OUTPUT.write_text(text.replace(CN_SPEAKER, SYSTEM_SPEAKER), encoding="utf-8", newline="\n")
    print("Audio volumes: enforced speaker curve -> DEFAULT_SYSTEM_VOLUME_CURVE")


if __name__ == "__main__":
    main()
