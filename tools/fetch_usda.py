#!/usr/bin/env python3
"""Fetch a live USDA FoodData Central search response and save it as a JSON fixture.

Usage:
    USDA_API_KEY=... python3 tools/fetch_usda.py "bread" /tmp/usda_bread.json

Refreshes fixtures like app/src/test/resources/usda_bread_2026-09-26.json
(the JVM test suite reads those from the classpath). The API key is taken
from the USDA_API_KEY environment variable only -- never hardcode it.
"""
import json
import os
import sys
import urllib.parse
import urllib.request


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: fetch_usda.py <query> <out.json>", file=sys.stderr)
        sys.exit(2)
    query, out_path = sys.argv[1], sys.argv[2]
    api_key = os.environ.get("USDA_API_KEY")
    if not api_key:
        print("error: USDA_API_KEY environment variable is not set", file=sys.stderr)
        sys.exit(1)

    params = urllib.parse.urlencode({
        "api_key": api_key,
        "query": query,
        "pageSize": 50,
        "dataType": ["Branded"],
    })
    req = urllib.request.Request(
        "https://api.nal.usda.gov/fdc/v1/foods/search?" + params,
        headers={"User-Agent": "BareLabel/debug-tools"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(body, f, ensure_ascii=False, indent=2)
    print(f"saved {len(body.get('foods', []))} foods -> {out_path}")


if __name__ == "__main__":
    main()
