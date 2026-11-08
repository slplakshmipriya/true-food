#!/usr/bin/env python3
"""Static verification for the BareLabel nutrition feature (Contract section 9).

Usage (from the worktree / repo root):

    python3 tools/verify_nutrition.py

Exit code is 0 when every hard check passes. Feature markers that later
tickets (NUT-01..NUT-10) will introduce are reported as PRESENT / MISSING
but never fail this script yet, so it runs green on the base tree.

Checks:
  (a) Every JSON file under app/src (assets + test resources) parses.
  (b) Every XML file under app/src (layouts, other res XML, manifest)
      is well-formed.
  (c) Java brace balance for files under
      app/src/main/java/com/barelabel/app/nutrition/ (SKIP when the
      directory does not exist yet).
  (d) Contract section 9 markers: UI labels, class names, config asset,
      LLM constant. Reported PRESENT / MISSING, never fatal. Also:
      blend weights must not be rendered in layouts (hard check), no
      nutrition endpoint / persistence strings in share/ or spellcheck/
      (hard check), and -- when the nutrition config asset exists -- a
      light section 1.3 validation of it (hard check when present).
  (e) Chips fixture validation (see expected values below).

----------------------------------------------------------------------
Chips fixture: hand-computed expected values (NUT-00)
Fixture: app/src/test/resources/usda_chips_nutrition.json
13 branded products, single foodCategory "Chips, Pretzels & Snacks".
All nutrient amounts are per 100 g.

Sodium (nutrientId 1093, mg), sorted:
    [140, 180, 320, 350, 380, 410, 450, 480, 540, 580, 620, 680, 920]
    median (7th of 13) = 450 mg
Fiber (nutrientId 1079, g), sorted:
    [1.0, 2.0, 2.5, 2.8, 3.0, 3.0, 3.2, 3.5, 3.8, 4.0, 4.5, 6.0, 7.0]
    median (7th of 13) = 3.2 g

Percentiles use the PRD section 6.5 formula
    rawPercentile = 100 * (peersBelow + 0.5 * peersEqual) / peerCount
with the candidate excluded from its own peer set (peerCount = 12):

  * Candidate-like product fdcId 990001
    ("Potato Chips, Classic Salted", sodium 920 mg, fiber 1.0 g):
      sodium raw percentile = 100.0  (12 of 12 peers below)
      sodium favorable percentile (higher_bad) = 0.0
      fiber  raw percentile = 0.0    (0 of 12 peers below)
      fiber  favorable percentile (higher_good) = 0.0
    -> sodium crosses the section 1.2 flag rule (pct >= 85 and >= 600 mg)
       and fiber crosses the low rule (pct <= 15 and <= 3 g): the mockup
       CONCERNING scenario.

  * Median product fdcId 990007
    ("Potato Chips, Lightly Salted", sodium 450 mg, fiber 4.0 g):
      sodium raw percentile = 50.0   (6 of 12 peers below)
      fiber  raw percentile = 75.0   (9 of 12 peers below)

  * Clean-and-safe-looking product fdcId 990013
    ("Lentil Chips, Sea Salt", sodium 140 mg, fiber 7.0 g):
      sodium raw percentile = 0.0, favorable = 100.0
      fiber  raw percentile = 100.0, favorable = 100.0

The second clean-and-safe-looking product is fdcId 990012
("Baked Potato Crisps, Sea Salt", sodium 180 mg, saturated fat 1.5 g,
fiber 6.0 g). Its carbohydrates are 62.0 g: above the 60 g flag floor,
but its raw percentile is 83.3 (three-way tie at 62 g with fdcId
990003 and 990010; (9 below + 0.5 * 2 equal) / 12), below the 85
threshold, so the relative+floor rule does not fire and the product
is genuinely unflagged under every section 1.2 flag rule, as is
fdcId 990013. (An earlier 72.0 g value was the cohort maximum at
percentile 100.0 and did flag; any value below 62 g would instead
push fdcId 990003 and 990010 over the carbs percentile threshold.)

Deferred to NUT-11: broader server-marker file-type scope, and
stronger fixture validation (plausible-range checks and
machine-checked percentile claims).
----------------------------------------------------------------------
"""

import json
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_SRC = ROOT / "app" / "src"
CHIPS_FIXTURE = APP_SRC / "test" / "resources" / "usda_chips_nutrition.json"
CONFIG_ASSET = APP_SRC / "main" / "assets" / "nutrition_config.json"
NUTRITION_PKG = APP_SRC / "main" / "java" / "com" / "barelabel" / "app" / "nutrition"

# Contract section 1.2 nutrient IDs.
CONFIGURED_NUTRIENT_IDS = [1008, 1003, 1004, 1005, 2000, 1235, 1079, 1093, 1258, 1257, 1253]

# Hand-computed fixture expectations (see docstring).
EXPECTED_MEDIAN_SODIUM = 450
EXPECTED_MEDIAN_FIBER = 3.2

# Mirrors UsdaSpamFilter.NON_FOOD_BRAND_WORDS.
SPAM_BRAND_WORDS = {"publishing", "software", "entertainment", "media", "films"}

# Contract section 9 / section 7 markers. (marker, where it is expected)
MARKERS = [
    ("Your item", "alternates comparison label (Contract 7.2)"),
    ("This alternative", "alternates comparison label (Contract 7.2)"),
    ("Compare nutrients", "alternates comparison toggle (Contract 7.2)"),
    ("No clean and safe alternatives were found", "alternates empty state (Contract 7.2)"),
    ("Nutrition unavailable", "nutrition UNAVAILABLE UI state (Contract 7.1)"),
    ("Overall Score", "verdict card score ring (Contract 7.1)"),
    ("Ingredient Score", "verdict card score strip (Contract 7.1)"),
    ("Nutrition Score", "verdict card score strip (Contract 7.1)"),
    ("nutrition_config.json", "nutrition config asset (Contract 1)"),
    ("NutritionConfig", "config model class (Contract 1)"),
    ("NutritionConfigLoader", "config loader (Contract 1)"),
    ("UsdaNutritionParser", "USDA parser (Contract 3)"),
    ("PeerCohort", "peer cohort builder (Contract 4)"),
    ("NutritionAnalyzer", "analyzer (Contract 4)"),
    ("OverallScoreCalculator", "overall score blend (Contract 4)"),
    ("NutritionCohortService", "cohort orchestration (Contract 5)"),
    ("rankNutritionAware", "nutrition-aware ranking (Contract 6)"),
    ("AlternativeExplainer", "alternative explanations (Contract 6)"),
    ("NutritionPanelController", "nutrition panel UI (Contract 7.1)"),
    ("EXPLAIN_NUTRITION_SYSTEM", "LLM nutrition prompt (Contract 8)"),
    ("scoreStrip", "verdict card score strip layout id (Contract 7.1, NUT-08)"),
    ("verdictBubblesRow", "verdict card bubbles row layout id (Contract 7.1, NUT-08)"),
    ("ingredientDetailPanel", "verdict card ingredient detail panel layout id (Contract 7.1, NUT-08)"),
    ("nutritionDetailPanel", "verdict card nutrition detail panel layout id (Contract 7.1, NUT-08)"),
]

# Strings that would mean blend weights are rendered in a layout (Contract 9).
BLEND_LAYOUT_MARKERS = ["ingredientWeight", "nutritionWeight", "70/30", "blend"]

# Strings that would mean a BareLabel server endpoint persists nutrition
# data (Contract 9 / PRD section 12). Deliberately specific: the bare word
# "nutrition" legitimately appears in spellcheck stopword data.
SERVER_NUTRITION_MARKERS = [
    "/nutrition", "nutrition_profile", "nutrition_score", "nutritionScore",
    "NutritionProfile", "PeerCohort", "peer_percentile",
]

failures = []


def section(title):
    print()
    print("=" * 68)
    print(title)
    print("=" * 68)


def check_json():
    section("(a) JSON validity under app/src")
    files = sorted(APP_SRC.rglob("*.json"))
    if not files:
        failures.append("no JSON files found under app/src")
        print("  FAIL  no JSON files found")
        return
    for path in files:
        rel = path.relative_to(ROOT)
        try:
            with open(path, encoding="utf-8") as fh:
                json.load(fh)
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
    main_layout = APP_SRC / "main" / "res" / "layout" / "activity_main.xml"
    print(f"  activity_main.xml present: {main_layout.exists()}")


def _strip_java_noise(text):
    """Remove // and /* */ comments and string/char literals (brace-safe)."""
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
    section("(c) Java brace balance (nutrition package)")
    if not NUTRITION_PKG.is_dir():
        print(f"  SKIP  {NUTRITION_PKG.relative_to(ROOT)} does not exist yet")
        return
    files = sorted(NUTRITION_PKG.rglob("*.java"))
    if not files:
        print("  SKIP  nutrition package exists but has no .java files yet")
        return
    for path in files:
        rel = path.relative_to(ROOT)
        cleaned = _strip_java_noise(path.read_text(encoding="utf-8", errors="replace"))
        opens, closes = cleaned.count("{"), cleaned.count("}")
        status = "OK  " if opens == closes else "FAIL"
        if opens != closes:
            failures.append(f"brace imbalance: {rel}: {{={opens} }}={closes}")
        print(f"  {status}  {rel}: {{ = {opens}, }} = {closes}")


def _search_corpus():
    """All text under app/src/main + app/src/test (code, layouts, assets)."""
    chunks = []
    for base in (APP_SRC / "main", APP_SRC / "test"):
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.is_file() and path.suffix in (".java", ".xml", ".json", ".txt"):
                try:
                    chunks.append(path.read_text(encoding="utf-8", errors="replace"))
                except OSError:
                    pass
    return "\n".join(chunks)


def check_markers():
    section("(d) Contract section 9 markers (report only; missing != fail)")
    corpus = _search_corpus()
    present = 0
    for marker, where in MARKERS:
        found = marker in corpus
        present += 1 if found else 0
        print(f"  {'PRESENT' if found else 'MISSING'}  {marker!r}  ({where})")
    print(f"  -> {present}/{len(MARKERS)} markers present so far")

    # Hard check: blend weights must never be rendered in layouts.
    layout_hits = []
    for path in sorted((APP_SRC / "main" / "res" / "layout").rglob("*.xml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for marker in BLEND_LAYOUT_MARKERS:
            if marker.lower() in text.lower():
                layout_hits.append(f"{path.relative_to(ROOT)}: {marker!r}")
    if layout_hits:
        failures.append("blend-weight marker rendered in layout: " + "; ".join(layout_hits))
        for hit in layout_hits:
            print(f"  FAIL  blend marker in layout: {hit}")
    else:
        print("  OK    no blend-weight markers in layouts")

    # Hard check: no nutrition endpoint/persistence strings on the servers.
    server_hits = []
    for server_dir in ("share", "spellcheck"):
        base = ROOT / server_dir
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.py")):
            text = path.read_text(encoding="utf-8", errors="replace")
            for marker in SERVER_NUTRITION_MARKERS:
                if marker in text:
                    server_hits.append(f"{path.relative_to(ROOT)}: {marker!r}")
    if server_hits:
        failures.append("nutrition server marker found: " + "; ".join(server_hits))
        for hit in server_hits:
            print(f"  FAIL  nutrition server marker: {hit}")
    else:
        print("  OK    no nutrition endpoint/persistence markers in share/ or spellcheck/")


def check_config_if_present():
    section("(d2) Nutrition config validation (Contract 1.3, when present)")
    if not CONFIG_ASSET.exists():
        print(f"  SKIP  {CONFIG_ASSET.relative_to(ROOT)} not present yet (NUT-01)")
        return
    try:
        cfg = json.loads(CONFIG_ASSET.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        failures.append(f"nutrition_config.json does not parse: {exc}")
        print(f"  FAIL  config does not parse: {exc}")
        return
    problems = []
    blend = cfg.get("blend") or {}
    total = (blend.get("ingredientWeight") or 0) + (blend.get("nutritionWeight") or 0)
    if abs(total - 1.0) > 0.001:
        problems.append(f"blend weights sum to {total}, not 1.0")
    keys = [n.get("key") for n in cfg.get("nutrients", [])]
    if len(keys) != len(set(keys)):
        problems.append("duplicate nutrient keys")
    for nut in cfg.get("nutrients", []):
        if nut.get("direction") not in ("higher_good", "higher_bad"):
            problems.append(f"{nut.get('key')}: unknown direction {nut.get('direction')!r}")
        if not (nut.get("weight") or 0) > 0:
            problems.append(f"{nut.get('key')}: non-positive weight")
        if not (nut.get("plausibleMin", 0) < nut.get("plausibleMax", 0)):
            problems.append(f"{nut.get('key')}: plausibleMin >= plausibleMax")
    for name, profile in (cfg.get("rankingProfiles") or {}).items():
        s = sum(profile.values())
        if abs(s - 1.0) > 0.001:
            problems.append(f"ranking profile {name} sums to {s}, not 1.0")
    if problems:
        for p in problems:
            failures.append(f"config validation: {p}")
            print(f"  FAIL  {p}")
    else:
        print("  OK    config parses and passes the section 1.3 checks above")


def _nutrient_value(food, nutrient_id):
    for entry in food.get("foodNutrients", []):
        if entry.get("nutrientId") == nutrient_id:
            return entry.get("value")
    return None


def check_chips_fixture():
    section("(e) Chips fixture validation (NUT-00)")
    if not CHIPS_FIXTURE.exists():
        failures.append(f"chips fixture missing: {CHIPS_FIXTURE}")
        print(f"  FAIL  fixture missing: {CHIPS_FIXTURE.relative_to(ROOT)}")
        return
    try:
        with open(CHIPS_FIXTURE, encoding="utf-8") as fh:
            doc = json.load(fh)
    except Exception as exc:  # noqa: BLE001 - malformed fixture is a clean FAIL
        failures.append(f"chips fixture does not parse: {exc}")
        print(f"  FAIL  fixture does not parse: {exc}")
        return
    if not isinstance(doc, dict):
        failures.append("chips fixture top level is not a JSON object")
        print("  FAIL  fixture top level is not a JSON object")
        return
    foods = doc.get("foods") or []
    print(f"  products: {len(foods)}")
    if len(foods) < 12:
        failures.append(f"chips fixture has {len(foods)} products, need >= 12")

    categories = {f.get("foodCategory") for f in foods}
    print(f"  categories: {sorted(c for c in categories if c)}")
    if len(categories) != 1 or None in categories:
        failures.append(f"chips fixture must have exactly one foodCategory, got {categories}")

    required_fields = ("fdcId", "description", "brandName", "brandOwner",
                       "foodCategory", "gtinUpc", "servingSize",
                       "servingSizeUnit", "ingredients")
    for food in foods:
        label = f"fdcId {food.get('fdcId')}"
        for field in required_fields:
            if food.get(field) in (None, ""):
                failures.append(f"chips fixture {label}: missing field {field}")
                print(f"  FAIL  {label}: missing field {field}")
        owner_words = {w.strip(".,") for w in (food.get("brandOwner") or "").lower().split()}
        spam = owner_words & SPAM_BRAND_WORDS
        if spam:
            failures.append(f"chips fixture {label}: spam brandOwner {food.get('brandOwner')!r}")
            print(f"  FAIL  {label}: spam brandOwner words {sorted(spam)}")
        reported = {e.get("nutrientId") for e in food.get("foodNutrients", [])}
        covered = reported & set(CONFIGURED_NUTRIENT_IDS)
        if len(covered) < 8:
            failures.append(f"chips fixture {label}: reports {len(covered)}/11 configured nutrients, need >= 8")
            print(f"  FAIL  {label}: only {len(covered)}/11 configured nutrients")
        for entry in food.get("foodNutrients", []):
            for key in ("nutrientId", "nutrientName", "unitName", "value"):
                if key not in entry:
                    failures.append(f"chips fixture {label}: nutrient entry missing {key}")
                    print(f"  FAIL  {label}: nutrient entry missing {key}")
                    break

    sodium = sorted(v for v in (_nutrient_value(f, 1093) for f in foods) if v is not None)
    fiber = sorted(v for v in (_nutrient_value(f, 1079) for f in foods) if v is not None)
    median_sodium = statistics.median(sodium) if sodium else None
    median_fiber = statistics.median(fiber) if fiber else None
    print(f"  sodium values (mg per 100 g), sorted: {sodium}")
    print(f"  median sodium: {median_sodium} mg (hand-computed expected: {EXPECTED_MEDIAN_SODIUM})")
    print(f"  fiber values (g per 100 g), sorted: {fiber}")
    print(f"  median fiber: {median_fiber} g (hand-computed expected: {EXPECTED_MEDIAN_FIBER})")
    if median_sodium != EXPECTED_MEDIAN_SODIUM:
        failures.append(f"median sodium {median_sodium} != hand-computed {EXPECTED_MEDIAN_SODIUM}")
    if median_fiber != EXPECTED_MEDIAN_FIBER:
        failures.append(f"median fiber {median_fiber} != hand-computed {EXPECTED_MEDIAN_FIBER}")
    if not failures:
        print("  OK    fixture shape, coverage, category and medians all match")


def main():
    print(f"verify_nutrition.py — repo root: {ROOT}")
    check_json()
    check_xml()
    check_brace_balance()
    check_markers()
    check_config_if_present()
    check_chips_fixture()
    section("Summary")
    if failures:
        print(f"  RESULT: FAIL ({len(failures)} problem(s))")
        for failure in failures:
            print(f"   - {failure}")
        return 1
    print("  RESULT: PASS (all hard checks green; marker gaps are expected until later tickets land)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
