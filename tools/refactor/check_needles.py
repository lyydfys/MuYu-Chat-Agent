import re
from pathlib import Path

import glob

bridge = Path("core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp").read_text(encoding="utf-8")

for test_file in glob.glob("core/sd-native/src/test/cpp/*.cpp") + glob.glob("core/native/src/test/cpp/*.cpp"):
    test = Path(test_file).read_text(encoding="utf-8")
    if "bridge" not in test:
        continue
    print(f"Checking {test_file}...")
    needles = re.findall(r'require_contains\(\s*bridge\s*,\s*"([^"]+)"\)', test)
    for n in needles:
        if n not in bridge:
            print(f"  MISSING in {test_file}: {repr(n)}")
    
    before_checks = re.findall(r'require_before\(\s*bridge\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\)', test)
    for f, s in before_checks:
        pos_f = bridge.find(f)
        pos_s = bridge.find(s)
        if pos_f == -1 or pos_s == -1 or pos_f >= pos_s:
            print(f"  BEFORE FAILED in {test_file}: {repr(f)} ({pos_f}) vs {repr(s)} ({pos_s})")

