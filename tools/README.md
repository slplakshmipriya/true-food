# BareLabel debug tools

Ad-hoc scripts for debugging USDA responses. Not part of the build.

## fetch_usda.py

Saves a live USDA FoodData Central search response as a JSON fixture:

```bash
USDA_API_KEY=... python3 tools/fetch_usda.py "bread" /tmp/usda_bread.json
```

Copy the result to `app/src/test/resources/` when a test needs a fresh
fixture. The API key comes from the `USDA_API_KEY` environment variable
only -- never hardcode it.

## Running the regression suite

The regression suite is plain JVM unit tests in `app/src/test/java`
(JUnit 4, no emulator needed). Every debug build runs them automatically
(`assembleDebug` depends on `testDebugUnitTest`); a failing test fails
the build. Run them directly with:

```bash
./gradlew testDebugUnitTest
```

Testability contract: the decision logic under test is extracted into
Android-free code (`util.CategorySearchDecider`,
`UsdaApiClient.selectTopPick` / `majorityFoodCategory`,
`GenericWords.parseLines`). If you change the Java logic, change the
tests in the same commit -- the tests assert exact behavior, not a
Python replica of it.
