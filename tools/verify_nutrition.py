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
      (hard check, all file types), and -- when the nutrition config
      asset exists -- the full section 1.3 validation of the real
      asset in section (d2) (hard check when present).
  (e) Chips fixture validation (see expected values below), including
      plausible-range checks on every configured fixture value.
  (f) NUT-09 alternates UI markers (Contract 7.2, hard).
  (g) Machine-checked percentile claims for the pinned candidates
      below (mirror of the PeerCohort plausible-range + MAD fence).

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
over the peer distribution AFTER the Contract section 4 pipeline —
candidate excluded, plausible-range filter, then the median/MAD
fence (median +/- 3.5 x 1.4826 x MAD) that PeerCohort applies:

  * Candidate-like product fdcId 990001
    ("Potato Chips, Classic Salted", sodium 920 mg, fiber 1.0 g):
      sodium raw percentile = 100.0  (12 of 12 peers below)
      sodium favorable percentile (higher_bad) = 0.0
      fiber  raw percentile = 0.0    (0 peers below)
      fiber  favorable percentile (higher_good) = 0.0
    -> sodium crosses the section 1.2 flag rule (pct >= 85 and >= 600 mg)
       and fiber crosses the low rule (pct <= 15 and <= 3 g): the mockup
       CONCERNING scenario.

  * Median product fdcId 990007
    ("Potato Chips, Lightly Salted", sodium 450 mg, fiber 4.0 g):
      sodium raw percentile = 50.0   (6 of 12 peers below)
      fiber  raw percentile = 81.82  (the MAD fence drops the 7.0 g
             peer first, leaving 11 peers, 9 below: 100 * 9 / 11.
             A pre-fence figure of 75.0 circulated in an earlier
             revision of this docstring; 81.82 is what the production
             pipeline computes, pinned by PeerCohortTest.)

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

NUT-11 additions: every percentile claim above is machine-checked
by section (g) against a mirror of the PeerCohort pipeline; fixture
values are checked against the config's plausible ranges in
section (e); the section (d) server-marker scan covers every file
type under share/ and spellcheck/; and section (d2) runs the full
section 1.3 validation against the real config asset.
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
    ("nutritionOutstandingContainer", "nutrition panel outstanding container layout id (Contract 7.1, NUT-08)"),
    ("toggleRemainingNutrients", "nutrition panel remaining-nutrients toggle layout id (Contract 7.1, NUT-08)"),
    ("nutritionRemainingContainer", "nutrition panel remaining container layout id (Contract 7.1, NUT-08)"),
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

    # Hard check: no nutrition endpoint/persistence strings on the
    # servers. NUT-11: scan every file type, not just *.py — an
    # endpoint string is a contract violation wherever it lands
    # (JS/HTML/JSON/config), and marker strings are plain ASCII.
    server_hits = []
    for server_dir in ("share", "spellcheck"):
        base = ROOT / server_dir
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if not path.is_file():
                continue
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


def _reject_duplicate_keys(pairs):
    """object_pairs_hook: §1.3 rejects duplicate keys in any object;
    a plain json.loads would silently keep the last value."""
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError(f"duplicate key {key!r}")
        obj[key] = value
    return obj


def check_config_if_present():
    section("(d2) Nutrition config validation (Contract 1.3, when present)")
    if not CONFIG_ASSET.exists():
        print(f"  SKIP  {CONFIG_ASSET.relative_to(ROOT)} not present yet (NUT-01)")
        return
    # Full section 1.3 validation of the REAL asset (NUT-11; this is
    # the "test reads the real asset" check deferred from NUT-01).
    # Mirrors NutritionConfigLoader's fail-closed rule list.
    try:
        cfg = json.loads(CONFIG_ASSET.read_text(encoding="utf-8"),
                         object_pairs_hook=_reject_duplicate_keys)
    except Exception as exc:  # noqa: BLE001
        failures.append(f"nutrition_config.json fails the 1.3 load: {exc}")
        print(f"  FAIL  config fails the 1.3 load: {exc}")
        return
    problems = []
    blend = cfg.get("blend") or {}
    total = (blend.get("ingredientWeight") or 0) + (blend.get("nutritionWeight") or 0)
    if abs(total - 1.0) > 0.001:
        problems.append(f"blend weights sum to {total}, not 1.0")
    keys = [n.get("key") for n in cfg.get("nutrients", [])]
    if len(keys) != len(set(keys)):
        problems.append("duplicate nutrient keys")
    seen_usda_ids = {}
    for nut in cfg.get("nutrients", []):
        key = nut.get("key")
        if nut.get("direction") not in ("higher_good", "higher_bad"):
            problems.append(f"{key}: unknown direction {nut.get('direction')!r}")
        if nut.get("unit") not in ("g", "mg", "kcal"):
            problems.append(f"{key}: unknown unit {nut.get('unit')!r}")
        if not (nut.get("weight") or 0) > 0:
            problems.append(f"{key}: non-positive weight")
        plausible_min = nut.get("plausibleMin")
        plausible_max = nut.get("plausibleMax")
        if plausible_min is None or plausible_max is None \
                or not plausible_min < plausible_max:
            problems.append(f"{key}: plausibleMin >= plausibleMax")
        else:
            good_at, bad_at = nut.get("goodAt"), nut.get("badAt")
            if good_at is not None and good_at == bad_at:
                problems.append(f"{key}: anchors equal ({good_at})")
            for anchor_name, anchor in (("goodAt", good_at), ("badAt", bad_at)):
                if anchor is not None \
                        and not (plausible_min <= anchor <= plausible_max):
                    problems.append(
                        f"{key}: {anchor_name} {anchor} outside plausible "
                        f"range [{plausible_min}, {plausible_max}]")
        for usda_id in nut.get("usdaIds") or []:
            if usda_id in seen_usda_ids:
                problems.append(
                    f"duplicate USDA ID {usda_id} "
                    f"({seen_usda_ids[usda_id]} and {key})")
            seen_usda_ids[usda_id] = key
    for name, profile in (cfg.get("rankingProfiles") or {}).items():
        s = sum(profile.values())
        if abs(s - 1.0) > 0.001:
            problems.append(f"ranking profile {name} sums to {s}, not 1.0")
    peers = cfg.get("peers") or {}
    if (peers.get("minPeers") or 0) < 1:
        problems.append(f"minPeers < 1: {peers.get('minPeers')}")
    scoring = cfg.get("scoring") or {}
    min_coverage = scoring.get("minCoverage")
    if min_coverage is None or not (0 < min_coverage <= 1):
        problems.append(f"minCoverage outside (0, 1]: {min_coverage}")
    if problems:
        for p in problems:
            failures.append(f"config validation: {p}")
            print(f"  FAIL  {p}")
    else:
        print("  OK    real config asset passes the full section 1.3 validation")


def _nutrient_value(food, nutrient_id):
    for entry in food.get("foodNutrients", []):
        if entry.get("nutrientId") == nutrient_id:
            return entry.get("value")
    return None


def check_nut09_alternates_ui():
    """(f) NUT-09 alternates UI markers (Contract 7.2) — HARD checks.

    Unlike the section 9 markers above (report-only until NUT-11), these
    are the acceptance markers for the NUT-09 ticket itself, so a miss
    fails the run:
      * AlternatesCardController carries the exact strings "Your item",
        "This alternative", "Compare nutrients" and the empty-state
        sentence "No clean and safe alternatives were found for this
        item." (plus the category variant stem), wires candidate context
        through setCandidateContext, and ranks via rankNutritionAware.
      * MainActivity calls setCandidateContext before setPool.
      * No arrow-string comparison remnants in the controller: no
        string literal contains a unicode arrow or an ASCII " -> "
        comparison arrow (Java lambdas use -> unspaced and outside
        strings, and javadoc prose arrows are not UI strings, so
        neither is flagged).
      * Brace balance for AlternatesCardController.java (section (c)
        only covers the nutrition package).
    """
    section("(f) NUT-09 alternates UI markers (Contract 7.2, hard)")
    controller = (APP_SRC / "main" / "java" / "com" / "barelabel" / "app"
                  / "ui" / "AlternatesCardController.java")
    main_activity = (APP_SRC / "main" / "java" / "com" / "barelabel"
                     / "app" / "MainActivity.java")
    if not controller.exists():
        failures.append("NUT-09: AlternatesCardController.java missing")
        print("  FAIL  AlternatesCardController.java missing")
        return
    text = controller.read_text(encoding="utf-8", errors="replace")
    required = [
        "Your item",
        "This alternative",
        "Compare nutrients",
        "No clean and safe alternatives were found for this item.",
        "No clean and safe alternatives were found in ",
        "setCandidateContext",
        "rankNutritionAware",
    ]
    for marker in required:
        if marker in text:
            print(f"  OK    controller contains {marker!r}")
        else:
            failures.append(f"NUT-09: controller missing {marker!r}")
            print(f"  FAIL  controller missing {marker!r}")

    import re
    literals = re.findall(r'"(?:[^"\\]|\\.)*"', text)
    arrows = ["\u2192", "\u279c", "\u2794", "\u27f6", " -> ", "-> ", " ->"]
    arrow_hits = [lit for lit in literals
                  if any(a in lit for a in arrows)]
    if arrow_hits:
        failures.append(
            "NUT-09: arrow-string comparison in controller: "
            + "; ".join(arrow_hits[:3]))
        print(f"  FAIL  arrow-string comparison(s) in controller: {arrow_hits[:3]}")
    else:
        print("  OK    no arrow-string comparison remnants in controller")

    cleaned = _strip_java_noise(text)
    opens, closes = cleaned.count("{"), cleaned.count("}")
    if opens == closes:
        print(f"  OK    controller brace balance: {{ = {opens}, }} = {closes}")
    else:
        failures.append(
            f"NUT-09: controller brace imbalance: {{={opens} }}={closes}")
        print(f"  FAIL  controller brace imbalance: {{ = {opens}, }} = {closes}")

    if main_activity.exists():
        main_text = main_activity.read_text(encoding="utf-8", errors="replace")
        ctx_idx = main_text.find("alternatesController.setCandidateContext(")
        pool_idx = main_text.find("alternatesController.setPool(")
        if ctx_idx >= 0 and pool_idx >= 0 and ctx_idx < pool_idx:
            print("  OK    MainActivity calls setCandidateContext before setPool")
        else:
            failures.append(
                "NUT-09: MainActivity setCandidateContext call missing "
                "or not before setPool")
            print("  FAIL  MainActivity setCandidateContext call missing "
                  "or not before setPool")


def _config_plausible_ranges():
    """usdaId -> (plausibleMin, plausibleMax, key) from the real asset,
    or None when the asset is missing/unreadable."""
    if not CONFIG_ASSET.exists():
        return None
    try:
        cfg = json.loads(CONFIG_ASSET.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 - caller reports the skip
        return None
    ranges = {}
    for nut in cfg.get("nutrients", []):
        for usda_id in nut.get("usdaIds") or []:
            ranges[usda_id] = (nut.get("plausibleMin"),
                               nut.get("plausibleMax"), nut.get("key"))
    return ranges


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

    # Plausible-range validation (NUT-11): every value the fixture
    # reports for a configured nutrient must sit inside that
    # nutrient's section 1.2 plausible range — the same range
    # PeerCohort enforces before fencing, so the fixture can never
    # pin behavior on a value production would discard.
    ranges = _config_plausible_ranges()
    if ranges is None:
        print("  SKIP  plausible-range check (config asset unavailable)")
    else:
        out_of_range = 0
        for food in foods:
            label = f"fdcId {food.get('fdcId')}"
            for entry in food.get("foodNutrients", []):
                nutrient_id = entry.get("nutrientId")
                if nutrient_id not in ranges:
                    continue
                value = entry.get("value", entry.get("amount"))
                low, high, key = ranges[nutrient_id]
                if value is None or not (low <= value <= high):
                    out_of_range += 1
                    failures.append(
                        f"chips fixture {label}: {key} value {value} "
                        f"outside plausible range [{low}, {high}]")
                    print(f"  FAIL  {label}: {key} value {value} "
                          f"outside plausible range [{low}, {high}]")
        if not out_of_range:
            print("  OK    all configured fixture values inside their "
                  "plausible ranges")

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


# Pinned percentile claims from the module docstring: (fdcId,
# nutrientId, expected raw percentile after the section 4 pipeline).
EXPECTED_PERCENTILES = [
    (990001, 1093, 100.0),
    (990001, 1079, 0.0),
    (990007, 1093, 50.0),
    (990007, 1079, 81.82),
    (990013, 1093, 0.0),
    (990013, 1079, 100.0),
    (990012, 1005, 83.33),
]


def _mad_fence(values):
    """Mirror of PeerCohort.applyMadFence: drop values beyond
    median +/- 3.5 * 1.4826 * MAD; MAD == 0 disables the fence."""
    if len(values) < 2:
        return values
    ordered = sorted(values)
    median = statistics.median(ordered)
    mad = statistics.median(sorted(abs(v - median) for v in ordered))
    if mad == 0:
        return values
    half_width = 3.5 * 1.4826 * mad
    return [v for v in values if median - half_width <= v <= median + half_width]


def check_fixture_percentiles():
    section("(g) Chips fixture percentile claims (machine-checked, NUT-11)")
    if not CHIPS_FIXTURE.exists():
        print("  SKIP  fixture missing (section (e) already failed)")
        return
    ranges = _config_plausible_ranges()
    if ranges is None:
        print("  SKIP  config asset unavailable; cannot apply plausible ranges")
        return
    try:
        with open(CHIPS_FIXTURE, encoding="utf-8") as fh:
            doc = json.load(fh)
    except Exception as exc:  # noqa: BLE001
        print(f"  SKIP  fixture does not parse: {exc}")
        return
    foods = doc.get("foods") or []
    for fdc_id, nutrient_id, expected in EXPECTED_PERCENTILES:
        candidate_value = None
        peers = []
        for food in foods:
            value = _nutrient_value(food, nutrient_id)
            if food.get("fdcId") == fdc_id:
                candidate_value = value
            elif value is not None:
                peers.append(value)
        key = ranges.get(nutrient_id, (None, None, str(nutrient_id)))[2]
        if candidate_value is None:
            failures.append(
                f"percentile claim: fdcId {fdc_id} does not report {key}")
            print(f"  FAIL  fdcId {fdc_id} does not report {key}")
            continue
        low, high = ranges[nutrient_id][0], ranges[nutrient_id][1]
        peers = _mad_fence([v for v in peers if low <= v <= high])
        below = sum(1 for v in peers if v < candidate_value)
        equal = sum(1 for v in peers if v == candidate_value)
        actual = 100.0 * (below + 0.5 * equal) / len(peers) if peers \
            else float("nan")
        if abs(actual - expected) <= 0.01:
            print(f"  OK    fdcId {fdc_id} {key}: {actual:.2f} "
                  f"(expected {expected}, {len(peers)} fenced peers)")
        else:
            failures.append(
                f"percentile claim: fdcId {fdc_id} {key} computed "
                f"{actual:.2f}, docstring pins {expected}")
            print(f"  FAIL  fdcId {fdc_id} {key}: computed {actual:.2f}, "
                  f"docstring pins {expected}")


def main():
    print(f"verify_nutrition.py — repo root: {ROOT}")
    check_json()
    check_xml()
    check_brace_balance()
    check_markers()
    check_config_if_present()
    check_nut09_alternates_ui()
    check_chips_fixture()
    check_fixture_percentiles()
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
