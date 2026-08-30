#!/usr/bin/env python3
"""Extract one complete EVM code value from official Console output framing."""

from __future__ import annotations

import json
import re
import sys

MAX_OUTPUT = 12 * 1024 * 1024
PROMPT = re.compile(r"^\[[A-Za-z0-9_-]{1,128}\]: /[A-Za-z0-9_./-]*> ?")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def extract_runtime(text: str) -> str:
    """Remove only ANSI color, CRLF and anchored prompts; reject errors and ambiguity."""
    if len(text) > MAX_OUTPUT:
        raise ValueError("output bounds")
    text = ANSI.sub("", text).replace("\r\n", "\n")
    if re.search(r"\b(?:error|exception|failed|revert(?:ed)?)\b", text, re.IGNORECASE):
        raise ValueError("error output")
    candidates: list[str] = []
    for line in text.split("\n"):
        value = PROMPT.sub("", line).strip()
        if value.startswith("{") or value.startswith('"'):
            try:
                document = json.loads(value, object_pairs_hook=_unique_pairs)
            except (ValueError, TypeError) as failure:
                raise ValueError("malformed code response") from failure
            if isinstance(document, dict):
                if set(document) == {"code"}:
                    value = document["code"]
                elif (
                    set(document) <= {"jsonrpc", "id", "result"}
                    and "result" in document
                ):
                    value = document["result"]
                else:
                    raise ValueError("unknown code response")
            else:
                value = document
            if not isinstance(value, str) or not value.startswith("0x"):
                raise ValueError("invalid code response")
        if isinstance(value, str) and value.startswith("0x"):
            if not re.fullmatch(r"0x(?:[0-9a-fA-F]{2})+", value):
                raise ValueError("malformed code")
            candidates.append(value.lower())
        elif isinstance(value, str) and "0x" in value:
            # An echoed read-only command carries an address, not runtime bytes.
            if not re.fullmatch(r"getCode 0x[0-9a-fA-F]{40}", value):
                raise ValueError("unrecognized code framing")
    if len(candidates) != 1:
        raise ValueError("ambiguous or empty code")
    return candidates[0]


def _unique_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """Reject repeated keys rather than letting JSON overwrite conflicting code."""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


if __name__ == "__main__":
    try:
        print(extract_runtime(sys.stdin.read(MAX_OUTPUT + 1)))
    except ValueError:
        raise SystemExit(1) from None
