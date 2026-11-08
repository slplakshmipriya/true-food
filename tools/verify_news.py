#!/usr/bin/env python3
"""Static verification for the BareLabel Search News Alerts feature.

Usage (from the worktree / repo root):

    python3 tools/verify_news.py

Mirrors tools/verify_nutrition.py: sectioned checks, clear PASS/FAIL
output, exit 0 only when every hard check passes.

Checks:
  (a) Every JSON file under app/src parses (strict: duplicate keys
      rejected, mirroring verify_nutrition.py's _reject_duplicate_keys).
  (b) Every XML file under app/src is well-formed.
  (c) The NA-00 fixtures exist and match the Contracts section 9
      expectations exactly, against the fixture reference "today"
      of 2026-11-08 (see the expectation tables below):
        - fda_strawberry.json: 5 records, pinned event_ids /
          classifications / statuses / report_dates, and the
          kept/dropped outcome of the section 3.2 matcher + section 4
          FDA filter (Ongoing OR report_date within 90 days) for the
          query "strawberry".
        - rss_strawberry.xml: 5 items, pinned titles / pubDates, and
          the section 3.3 matcher (food token + hazard keyword),
          30-day window and 6-token-prefix dedupe outcomes (newest
          kept) for the query "strawberry".
  (d) Java brace balance for files under
      app/src/main/java/com/barelabel/app/news/ (SKIP gracefully
      when the directory does not exist yet).
  (e) Contract feature markers as HARD failures: this is what makes
      the script RED on the NA-00 tree and GREEN only when
      NA-01..NA-06 land. Each marker is reported PRESENT / MISSING
      and any missing marker fails the run.
  (f) Scope guards (hard fail if violated, evaluated when the news
      package exists): no "PremiumGates" reference in the news
      package; no "http" URL strings in the news package other than
      the two contract endpoints and the FDA recalls page host
      www.fda.gov.

On the NA-00 tree the expected state is: (a), (b) and (c) PASS,
(d) and (f) SKIP, (e) FAIL (feature markers missing) and overall
exit 1 -- red for the right reason.
"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import date, datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_SRC = ROOT / "app" / "src"
FDA_FIXTURE = APP_SRC / "test" / "resources" / "fda_strawberry.json"
RSS_FIXTURE = APP_SRC / "test" / "resources" / "rss_strawberry.xml"
NEWS_PKG = APP_SRC / "main" / "java" / "com" / "barelabel" / "app" / "news"

# Fixture reference "today" (Contracts section 9, NA-00).
REFERENCE_DATE = date(2026, 11, 8)
QUERY = "strawberry"

# ----------------------------------------------------------------------
# FDA fixture expectations (Contracts sections 3.2, 4 and 9).
# kept == the section 3.2 whole-token match for QUERY succeeds AND
# (status == Ongoing OR report_date within 90 days before
# REFERENCE_DATE).
# ----------------------------------------------------------------------
EXPECTED_FDA = [
    {
        "label": "R1",
        "event_id": "98241",
        "recall_number": "F-0451-2026",
        "classification": "Class I",
        "status": "Ongoing",
        "report_date": "20261102",
        "recalling_firm": "Fresh Breeze Farms",
        "kept": True,
        "why": "Ongoing Class I strawberry recall (hepatitis A)",
    },
    {
        "label": "R2",
        "event_id": "98187",
        "recall_number": "F-0438-2026",
        "classification": "Class II",
        "status": "Completed",
        "report_date": "20261015",
        "recalling_firm": "Sunny Valley Produce LLC",
        "kept": True,
        "why": "Completed but report_date within 90 days of 2026-11-08",
    },
    {
        "label": "R3",
        "event_id": "95420",
        "recall_number": "F-1987-2025",
        "classification": "Class II",
        "status": "Terminated",
        "report_date": "20250601",
        "recalling_firm": "Gulf Coast Growers Inc.",
        "kept": False,
        "why": "Terminated and older than 90 days",
    },
    {
        "label": "R4",
        "event_id": "96833",
        "recall_number": "F-1675-2026",
        "classification": "Class III",
        "status": "Completed",
        "report_date": "20260711",
        "recalling_firm": "Pacific Berry Co.",
        "kept": False,
        "why": "Completed, 120 days before 2026-11-08 (outside 90 days)",
    },
    {
        "label": "R5",
        "event_id": "98205",
        "recall_number": "F-0449-2026",
        "classification": "Class I",
        "status": "Ongoing",
        "report_date": "20261101",
        "recalling_firm": "Green Valley Salads Inc.",
        "kept": False,
        "why": "Different food (romaine lettuce); no strawberry token",
    },
]

# ----------------------------------------------------------------------
# RSS fixture expectations (Contracts sections 3.3, 4 and 9).
# kept == final survivor after: food-token + hazard-keyword match,
# pubDate within 30 days before REFERENCE_DATE, and dedupe on the
# first 6 normalized title tokens keeping the newest.
# ----------------------------------------------------------------------
EXPECTED_RSS = [
    {
        "label": "I1",
        "title": "FDA investigating hepatitis A outbreak linked to fresh strawberries",
        "pub_date": date(2026, 11, 6),
        "source": "Food Safety News",
        "kept": False,
        "why": "Matches, but deduped: I2 is the same story and newer",
    },
    {
        "label": "I2",
        "title": "FDA investigating hepatitis A outbreak linked to strawberries, officials say",
        "pub_date": date(2026, 11, 7),
        "source": "NBC News",
        "kept": True,
        "why": "Duplicate of I1's story, newest kept by dedupe",
    },
    {
        "label": "I3",
        "title": "Strawberry prices rise as California harvest begins",
        "pub_date": date(2026, 11, 5),
        "source": None,
        "kept": False,
        "why": "Same food, no hazard keyword",
    },
    {
        "label": "I4",
        "title": "Salmonella outbreak linked to cucumbers sickens dozens",
        "pub_date": date(2026, 11, 6),
        "source": None,
        "kept": False,
        "why": "Hazard keyword but different food (cucumbers)",
    },
    {
        "label": "I5",
        "title": "Strawberry recall expands to additional states",
        "pub_date": date(2026, 9, 24),
        "source": None,
        "kept": False,
        "why": "Food token + hazard keyword but 45 days old (30-day window)",
    },
]

# Contracts section 3.3 hazard keywords.
HAZARD_KEYWORDS = [
    "recall", "outbreak", "contamination", "contaminated",
    "salmonella", "listeria", "e coli", "hepatitis", "fda warns",
    "do not eat", "illness", "hospitalized",
]

# Contracts section 2 endpoints (also the only egress, section 8).
FDA_ENDPOINT = "api.fda.gov/food/enforcement.json"
RSS_ENDPOINT = "news.google.com/rss/search"
FDA_RECALLS_HOST = "www.fda.gov"

# NA-02 review ruling B (2026-11-08), discharged in NA-07: the four
# standard Xerces/SAX parser-feature URIs used by RssFeedParser to
# harden the XML parser (XXE protections) are whitelisted in the
# section (f) URL scan as EXACT strings only. They are parser
# configuration identifiers, never network egress. Any other
# non-contract URL must still FAIL section (f).
PARSER_FEATURE_URIS = frozenset([
    "http://apache.org/xml/features/disallow-doctype-decl",
    "http://xml.org/sax/features/external-general-entities",
    "http://xml.org/sax/features/external-parameter-entities",
    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
])

# Section (e) markers: (marker, where it is expected, corpus kind).
# corpus kinds: "news_pkg" = news package only; "java" = anywhere
# under app/src/main/java; "main" = anywhere under app/src/main
# (java or res).
MARKERS = [
    ("class SafetyAlert", "SafetyAlert model in the news package (Contracts section 1)", "news_pkg"),
    (FDA_ENDPOINT, "openFDA endpoint (Contracts section 2.1)", "java"),
    (RSS_ENDPOINT, "Google News RSS endpoint (Contracts section 2.2)", "java"),
    ("Safety reports", "verdict card section heading (Contracts section 6.4)", "main"),
    ("safety report", "search count line copy (Contracts section 6.2)", "main"),
    ("FDA RECALL", "FDA item tag (Contracts section 6.4)", "main"),
    ("NEWS REPORT", "news item tag (Contracts section 6.4)", "main"),
    ("\u26a0 recall", "search row badge (Contracts section 6.3)", "main"),
]

failures = []


def section(title):
    print()
    print("=" * 68)
    print(title)
    print("=" * 68)


def _reject_duplicate_keys(pairs):
    """object_pairs_hook: reject duplicate keys in any object; a
    plain json.loads would silently keep the last value (mirrors
    verify_nutrition.py)."""
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError(f"duplicate key {key!r}")
        obj[key] = value
    return obj


def _strict_json_load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh, object_pairs_hook=_reject_duplicate_keys)


def check_json():
    section("(a) JSON validity under app/src (strict, duplicate keys rejected)")
    files = sorted(APP_SRC.rglob("*.json"))
    if not files:
        failures.append("no JSON files found under app/src")
        print("  FAIL  no JSON files found")
        return
    for path in files:
        rel = path.relative_to(ROOT)
        try:
            _strict_json_load(path)
            print(f"  OK    {rel}")
        except Exception as exc:  # noqa: BLE001 - report any parse failure
            failures.append(f"invalid JSON: {rel}: {exc}")
            print(f"  FAIL  {rel}: {exc}")
    print(f"  -> {len(files)} JSON file(s) checked")


def check_xml():
    section("(b) XML well-formedness under app/src")
    files = sorted(APP_SRC.rglob("*.xml"))
    if not files:
        failures.append("no XML files found under app/src")
        print("  FAIL  no XML files found")
        return
    bad = 0
    for path in files:
        rel = path.relative_to(ROOT)
        try:
            ET.parse(path)
        except Exception as exc:  # noqa: BLE001
            bad += 1
            failures.append(f"malformed XML: {rel}: {exc}")
            print(f"  FAIL  {rel}: {exc}")
    print(f"  -> {len(files)} XML file(s) checked, {bad} malformed")


# --- Normalization / matching helpers (Contracts section 3.1) ---

def _normalize_tokens(text):
    lowered = (text or "").lower()
    spaced = re.sub(r"[^a-z0-9]+", " ", lowered)
    return spaced.split()


def _fold(token):
    if token.endswith("ies") and len(token) > 3:
        return token[:-3] + "y"
    if token.endswith("s") and not token.endswith("ss") and len(token) > 1:
        return token[:-1]
    return token


def _token_set(text):
    tokens = _normalize_tokens(text)
    out = set(tokens)
    out.update(_fold(t) for t in tokens)
    return out


def _has_query_token(text, query=QUERY):
    query_tokens = _token_set(query)
    return bool(query_tokens & _token_set(text))


def _has_hazard_keyword(title):
    tokens = _normalize_tokens(title)
    folded = [_fold(t) for t in tokens]
    for keyword in HAZARD_KEYWORDS:
        kw_tokens = [_fold(t) for t in _normalize_tokens(keyword)]
        if len(kw_tokens) == 1:
            if kw_tokens[0] in folded:
                return True
        else:
            for i in range(len(folded) - len(kw_tokens) + 1):
                if folded[i:i + len(kw_tokens)] == kw_tokens:
                    return True
    return False


def _parse_yyyymmdd(value):
    return datetime.strptime(value, "%Y%m%d").date()


def _fda_kept(record):
    """Contracts sections 3.2 + 4 for QUERY at REFERENCE_DATE."""
    haystack = ((record.get("product_description") or "") + " "
                + (record.get("reason_for_recall") or ""))
    if not _has_query_token(haystack):
        return False
    if record.get("status") == "Ongoing":
        return True
    try:
        report_date = _parse_yyyymmdd(record.get("report_date") or "")
    except ValueError:
        return False
    return 0 <= (REFERENCE_DATE - report_date).days <= 90


def check_fda_fixture():
    print("  -- fda_strawberry.json --")
    if not FDA_FIXTURE.exists():
        failures.append(f"FDA fixture missing: {FDA_FIXTURE}")
        print(f"  FAIL  fixture missing: {FDA_FIXTURE.relative_to(ROOT)}")
        return
    try:
        doc = _strict_json_load(FDA_FIXTURE)
    except Exception as exc:  # noqa: BLE001
        failures.append(f"FDA fixture does not parse: {exc}")
        print(f"  FAIL  fixture does not parse: {exc}")
        return
    if not isinstance(doc, dict) or not isinstance(doc.get("results"), list):
        failures.append("FDA fixture top level lacks a results array")
        print("  FAIL  fixture top level lacks a results array (meta + results expected)")
        return
    if not isinstance(doc.get("meta"), dict):
        failures.append("FDA fixture lacks a meta object")
        print("  FAIL  fixture lacks a meta object")
    results = doc["results"]
    print(f"  records: {len(results)} (expected 5)")
    if len(results) != len(EXPECTED_FDA):
        failures.append(
            f"FDA fixture has {len(results)} records, expected {len(EXPECTED_FDA)}")
        print(f"  FAIL  expected {len(EXPECTED_FDA)} records, got {len(results)}")
    event_ids = [r.get("event_id") for r in results]
    recall_numbers = [r.get("recall_number") for r in results]
    if len(set(event_ids)) != len(event_ids):
        failures.append("FDA fixture event_ids are not distinct")
        print("  FAIL  event_ids are not distinct")
    if len(set(recall_numbers)) != len(recall_numbers):
        failures.append("FDA fixture recall_numbers are not distinct")
        print("  FAIL  recall_numbers are not distinct")
    required_fields = ("status", "classification", "event_id",
                       "recall_number", "recalling_firm",
                       "product_description", "reason_for_recall",
                       "report_date")
    by_event_id = {r.get("event_id"): r for r in results}
    for expected in EXPECTED_FDA:
        label = expected["label"]
        record = by_event_id.get(expected["event_id"])
        if record is None:
            failures.append(
                f"FDA {label}: no record with event_id {expected['event_id']}")
            print(f"  FAIL  {label}: no record with event_id {expected['event_id']}")
            continue
        for field in required_fields:
            if record.get(field) in (None, ""):
                failures.append(f"FDA {label}: missing field {field}")
                print(f"  FAIL  {label}: missing field {field}")
        for field in ("recall_number", "classification", "status",
                      "report_date", "recalling_firm"):
            if record.get(field) != expected[field]:
                failures.append(
                    f"FDA {label}: {field} {record.get(field)!r} "
                    f"!= pinned {expected[field]!r}")
                print(f"  FAIL  {label}: {field} {record.get(field)!r} "
                      f"!= pinned {expected[field]!r}")
        kept = _fda_kept(record)
        if kept != expected["kept"]:
            failures.append(
                f"FDA {label}: computed kept={kept}, "
                f"expected {expected['kept']} ({expected['why']})")
            print(f"  FAIL  {label}: computed kept={kept}, "
                  f"expected {expected['kept']} ({expected['why']})")
        else:
            outcome = "KEPT" if kept else "DROPPED"
            print(f"  OK    {label} event_id={expected['event_id']} "
                  f"{record.get('classification')} / {record.get('status')} / "
                  f"{record.get('report_date')} -> {outcome} ({expected['why']})")
    # R1 / R5 content pins from the NA-00 specification.
    r1 = by_event_id.get(EXPECTED_FDA[0]["event_id"]) or {}
    if "Fresh Breeze Farms Organic Strawberries" not in (r1.get("product_description") or ""):
        failures.append("FDA R1: product_description does not mention "
                        "'Fresh Breeze Farms Organic Strawberries'")
        print("  FAIL  R1: product_description pin not met")
    if "hepatitis a" not in (r1.get("reason_for_recall") or "").lower():
        failures.append("FDA R1: reason_for_recall does not mention hepatitis A")
        print("  FAIL  R1: reason_for_recall does not mention hepatitis A")
    r5 = by_event_id.get(EXPECTED_FDA[4]["event_id"]) or {}
    r5_text = ((r5.get("product_description") or "") + " "
               + (r5.get("reason_for_recall") or ""))
    if _has_query_token(r5_text):
        failures.append("FDA R5: record unexpectedly contains a strawberry token")
        print("  FAIL  R5: record unexpectedly contains a strawberry token")
    if "e. coli" not in (r5.get("reason_for_recall") or "").lower() \
            and "e coli" not in (r5.get("reason_for_recall") or "").lower():
        failures.append("FDA R5: reason_for_recall does not mention E. coli")
        print("  FAIL  R5: reason_for_recall does not mention E. coli")


def check_rss_fixture():
    print("  -- rss_strawberry.xml --")
    if not RSS_FIXTURE.exists():
        failures.append(f"RSS fixture missing: {RSS_FIXTURE}")
        print(f"  FAIL  fixture missing: {RSS_FIXTURE.relative_to(ROOT)}")
        return
    try:
        tree = ET.parse(RSS_FIXTURE)
    except Exception as exc:  # noqa: BLE001
        failures.append(f"RSS fixture does not parse: {exc}")
        print(f"  FAIL  fixture does not parse: {exc}")
        return
    root_el = tree.getroot()
    if root_el.tag != "rss":
        failures.append(f"RSS fixture root is {root_el.tag!r}, expected 'rss'")
        print(f"  FAIL  root element is {root_el.tag!r}, expected 'rss'")
        return
    channel = root_el.find("channel")
    if channel is None:
        failures.append("RSS fixture has no channel element")
        print("  FAIL  no channel element")
        return
    items = channel.findall("item")
    print(f"  items: {len(items)} (expected 5)")
    if len(items) != len(EXPECTED_RSS):
        failures.append(
            f"RSS fixture has {len(items)} items, expected {len(EXPECTED_RSS)}")
        print(f"  FAIL  expected {len(EXPECTED_RSS)} items, got {len(items)}")

    parsed = []
    for idx, item in enumerate(items):
        title = (item.findtext("title") or "").strip()
        link = (item.findtext("link") or "").strip()
        pub_raw = (item.findtext("pubDate") or "").strip()
        source_el = item.find("source")
        source = (source_el.text or "").strip() if source_el is not None else ""
        try:
            pub_dt = parsedate_to_datetime(pub_raw)
            if pub_dt.tzinfo is None:
                pub_dt = pub_dt.replace(tzinfo=timezone.utc)
            pub_date = pub_dt.date()
        except Exception:  # noqa: BLE001
            pub_date = None
        parsed.append({"title": title, "link": link, "pubDate": pub_date,
                       "pub_raw": pub_raw, "source": source})
        if idx < len(EXPECTED_RSS):
            expected = EXPECTED_RSS[idx]
            label = expected["label"]
            if title != expected["title"]:
                failures.append(
                    f"RSS {label}: title {title!r} != pinned {expected['title']!r}")
                print(f"  FAIL  {label}: title {title!r} != pinned title")
            if pub_date != expected["pub_date"]:
                failures.append(
                    f"RSS {label}: pubDate {pub_raw!r} != pinned "
                    f"{expected['pub_date'].isoformat()}")
                print(f"  FAIL  {label}: pubDate {pub_raw!r} != pinned "
                      f"{expected['pub_date'].isoformat()}")
            if not link:
                failures.append(f"RSS {label}: missing link")
                print(f"  FAIL  {label}: missing link")
            if source_el is None or not source:
                failures.append(f"RSS {label}: missing source element")
                print(f"  FAIL  {label}: missing source element")
            if expected["source"] is not None and source != expected["source"]:
                failures.append(
                    f"RSS {label}: source {source!r} != pinned {expected['source']!r}")
                print(f"  FAIL  {label}: source {source!r} "
                      f"!= pinned {expected['source']!r}")

    # Dedupe precondition: I1 and I2 share the first 6 normalized tokens.
    if len(parsed) >= 2:
        first6_i1 = _normalize_tokens(parsed[0]["title"])[:6]
        first6_i2 = _normalize_tokens(parsed[1]["title"])[:6]
        if first6_i1 != first6_i2:
            failures.append(
                f"RSS dedupe pair: first 6 normalized tokens differ: "
                f"{first6_i1} vs {first6_i2}")
            print(f"  FAIL  dedupe pair first-6 tokens differ: "
                  f"{first6_i1} vs {first6_i2}")
        else:
            print(f"  OK    I1/I2 share first 6 normalized tokens: {first6_i1}")

    # Section 3.3 + 4 pipeline: match, 30-day window, dedupe newest.
    matched = []
    for idx, entry in enumerate(parsed):
        label = EXPECTED_RSS[idx]["label"] if idx < len(EXPECTED_RSS) else f"#{idx}"
        entry = dict(entry, label=label)
        if entry["pubDate"] is None:
            entry["pipeline_kept"] = False
            entry["drop_reason"] = "unparseable pubDate"
            matched.append(entry)
            continue
        age = (REFERENCE_DATE - entry["pubDate"]).days
        if not _has_query_token(entry["title"]):
            entry["pipeline_kept"] = False
            entry["drop_reason"] = "no food token"
        elif not _has_hazard_keyword(entry["title"]):
            entry["pipeline_kept"] = False
            entry["drop_reason"] = "no hazard keyword"
        elif not (0 <= age <= 30):
            entry["pipeline_kept"] = False
            entry["drop_reason"] = f"outside 30-day window ({age} days old)"
        else:
            entry["pipeline_kept"] = True
            entry["drop_reason"] = ""
        matched.append(entry)
    # Dedupe: same first-6-token prefix -> keep newest only.
    groups = {}
    for entry in matched:
        if not entry["pipeline_kept"]:
            continue
        key = tuple(_normalize_tokens(entry["title"])[:6])
        groups.setdefault(key, []).append(entry)
    for group in groups.values():
        if len(group) <= 1:
            continue
        newest = max(group, key=lambda e: e["pubDate"])
        for entry in group:
            if entry is not newest:
                entry["pipeline_kept"] = False
                entry["drop_reason"] = "duplicate story; newer copy kept"

    for idx, entry in enumerate(matched):
        if idx >= len(EXPECTED_RSS):
            break
        expected = EXPECTED_RSS[idx]
        kept = bool(entry["pipeline_kept"])
        if kept != expected["kept"]:
            failures.append(
                f"RSS {expected['label']}: computed kept={kept}, "
                f"expected {expected['kept']} ({expected['why']})")
            print(f"  FAIL  {expected['label']}: computed kept={kept}, "
                  f"expected {expected['kept']} ({expected['why']})")
        else:
            outcome = "KEPT" if kept else "DROPPED"
            detail = expected["why"]
            if entry["drop_reason"]:
                detail = f"{detail} [{entry['drop_reason']}]"
            print(f"  OK    {expected['label']} {entry['pubDate']} -> "
                  f"{outcome} ({detail})")
    survivors = [e["label"] for e in matched if e["pipeline_kept"]]
    print(f"  -> RSS survivors after match + window + dedupe: {survivors} "
          f"(expected ['I2'])")


def check_fixtures():
    section("(c) NA-00 fixture expectations (Contracts section 9, ref 2026-11-08)")
    check_fda_fixture()
    check_rss_fixture()


def _strip_java_noise(text):
    """Remove // and /* */ comments and string/char literals (brace-safe).
    Mirrors verify_nutrition.py."""
    out = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line_comment"; i += 2; continue
            if c == "/" and nxt == "*":
                state = "block_comment"; i += 2; continue
            if c == '"':
                state = "string"; i += 1; continue
            if c == "'":
                state = "char"; i += 1; continue
            out.append(c); i += 1; continue
        if state == "line_comment":
            if c == "\n":
                state = "code"; out.append(c)
            i += 1; continue
        if state == "block_comment":
            if c == "*" and nxt == "/":
                state = "code"; i += 2; continue
            i += 1; continue
        if state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if c == "\\":
                i += 2; continue
            if c == quote:
                state = "code"
            i += 1; continue
    return "".join(out)


def check_brace_balance():
    section("(d) Java brace balance (news package)")
    if not NEWS_PKG.is_dir():
        print(f"  SKIP  {NEWS_PKG.relative_to(ROOT)} does not exist yet")
        return
    files = sorted(NEWS_PKG.rglob("*.java"))
    if not files:
        print("  SKIP  news package exists but has no .java files yet")
        return
    for path in files:
        rel = path.relative_to(ROOT)
        cleaned = _strip_java_noise(path.read_text(encoding="utf-8", errors="replace"))
        opens, closes = cleaned.count("{"), cleaned.count("}")
        status = "OK  " if opens == closes else "FAIL"
        if opens != closes:
            failures.append(f"brace imbalance: {rel}: {{={opens} }}={closes}")
        print(f"  {status}  {rel}: {{ = {opens}, }} = {closes}")


def _corpus(base, suffixes=None):
    chunks = []
    if not base.is_dir():
        return ""
    for path in sorted(base.rglob("*")):
        if not path.is_file():
            continue
        if suffixes is not None and path.suffix not in suffixes:
            continue
        try:
            chunks.append(path.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            pass
    return "\n".join(chunks)


def check_markers():
    section("(e) Contract feature markers (HARD: missing = fail)")
    corpora = {
        "news_pkg": _corpus(NEWS_PKG),
        "java": _corpus(APP_SRC / "main" / "java"),
        "main": _corpus(APP_SRC / "main"),
    }
    present = 0
    for marker, where, kind in MARKERS:
        found = marker in corpora[kind]
        present += 1 if found else 0
        if not found:
            failures.append(f"missing contract marker: {marker!r} ({where})")
        print(f"  {'PRESENT' if found else 'MISSING'}  {marker!r}  ({where})")
    print(f"  -> {present}/{len(MARKERS)} markers present")


def check_scope_guards():
    section("(f) Scope guards (news package, hard when package exists)")
    if not NEWS_PKG.is_dir():
        print(f"  SKIP  {NEWS_PKG.relative_to(ROOT)} does not exist yet")
        return
    files = sorted(NEWS_PKG.rglob("*"))
    texts = {}
    for path in files:
        if path.is_file():
            try:
                texts[path] = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                pass
    # Guard 1: no premium gating in the news feature (Contracts CP-1 /
    # PRD: alerts are free for all).
    premium_hits = [str(p.relative_to(ROOT)) for p, t in texts.items()
                    if "PremiumGates" in t]
    if premium_hits:
        failures.append("PremiumGates reference in news package: "
                        + "; ".join(premium_hits))
        for hit in premium_hits:
            print(f"  FAIL  PremiumGates reference in {hit}")
    else:
        print("  OK    no PremiumGates reference in the news package")
    # Guard 2: no http URLs beyond the two contract endpoints and the
    # FDA recalls page host (Contracts sections 2, 6.4 and 8), plus
    # the exact-string parser-feature whitelist below.
    url_pattern = re.compile(r"https?://[^\s\"'<>\)\]]+")
    bad_urls = []
    for path, text in texts.items():
        for url in url_pattern.findall(text):
            cleaned = url.rstrip(".,;")
            # NA-02 review ruling B (2026-11-08): exact-string
            # whitelist of the 4 standard parser-feature URIs only
            # (see PARSER_FEATURE_URIS); no pattern/broad exception.
            if cleaned in PARSER_FEATURE_URIS:
                continue
            if cleaned.startswith("https://" + FDA_ENDPOINT) \
                    or cleaned.startswith("https://" + RSS_ENDPOINT) \
                    or cleaned.startswith("https://" + FDA_RECALLS_HOST + "/") \
                    or cleaned == "https://" + FDA_RECALLS_HOST:
                continue
            bad_urls.append(f"{path.relative_to(ROOT)}: {cleaned}")
    if bad_urls:
        failures.append("non-contract URL in news package: " + "; ".join(bad_urls))
        for hit in bad_urls:
            print(f"  FAIL  non-contract URL: {hit}")
    else:
        print("  OK    no http URLs in the news package beyond the "
              "contract endpoints / www.fda.gov")


def main():
    print(f"verify_news.py - repo root: {ROOT}")
    check_json()
    check_xml()
    check_fixtures()
    check_brace_balance()
    check_markers()
    check_scope_guards()
    section("Summary")
    if failures:
        print(f"  RESULT: FAIL ({len(failures)} problem(s))")
        for failure in failures:
            print(f"   - {failure}")
        print("  SUMMARY: verify_news.py FAIL - see sections above "
              "((e) marker failures are expected on the NA-00 tree "
              "until NA-01..NA-06 land)")
        return 1
    print("  RESULT: PASS (all hard checks green)")
    print("  SUMMARY: verify_news.py PASS - all hard checks green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
