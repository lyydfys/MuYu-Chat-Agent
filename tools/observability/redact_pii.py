#!/usr/bin/env python3
"""
redact_pii.py

Observability & telemetry log sanitizer.
Redacts PII, authentication tokens, credentials, and sensitive filesystem paths
from diagnostic logs, traces, and incident dumps.
"""

import re
import sys

REDACTION_PATTERNS = [
    # Bearer tokens
    (re.compile(r'(?i)bearer\s+[A-Za-z0-9_\-\.]{8,}'), 'Bearer [REDACTED_TOKEN]'),
    # Generic API keys
    (re.compile(r'(?i)(api[_\-]?key|auth[_\-]?token|secret)\s*[:=]\s*["\']?[A-Za-z0-9_\-]{8,}["\']?'), r'\1=[REDACTED_SECRET]'),
    # User internal file paths
    (re.compile(r'(?:/data/user/\d+/[^\s,"]+)|(?:/storage/emulated/\d+/[^\s,"]+)|(?:[A-Za-z]:\\Users\\[^\s,"]+)'), '<redacted-path>'),
    # Passwords
    (re.compile(r'(?i)password\s*[:=]\s*["\']?[^"\',\s]+["\']?'), 'password=[REDACTED_PASSWORD]'),
]

def redact_text(text: str) -> str:
    sanitized = text
    for pattern, replacement in REDACTION_PATTERNS:
        sanitized = pattern.sub(replacement, sanitized)
    return sanitized

def main():
    if len(sys.argv) > 1:
        input_file = sys.argv[1]
        with open(input_file, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
        print(redact_text(content))
    else:
        sample = 'Authorization: Bearer my_secret_token_12345, path: /data/user/0/com.muyuchat/files/db.sqlite'
        print("Sample test redaction:")
        print("Original:", sample)
        print("Sanitized:", redact_text(sample))

if __name__ == "__main__":
    main()
