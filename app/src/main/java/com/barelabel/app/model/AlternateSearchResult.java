package com.barelabel.app.model;

import com.barelabel.app.nutrition.NutritionAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of an alternates search: the clean candidates plus the flagged
 * categories seen across scanned (dirty) candidates, so the UI can explain
 * why a category search came up empty.
 * <p>
 * {@code filterBlockCounts} maps each filter category to the number of
 * scanned candidates it is blocking (a candidate blocked by several filters
 * counts toward each of them). Sorted descending, this ranks filters by how
 * restrictive they are, which powers the smart "relax a filter" suggestions
 * in the empty state.
 */
public class AlternateSearchResult {
    public final List<ProductResult> alternates = new ArrayList<>();
    public final Set<String> flaggedCategories = new LinkedHashSet<>();
    public final Map<String, Integer> filterBlockCounts = new LinkedHashMap<>();
    /**
     * Every product the finder scanned, deduped, carrying its parsed
     * nutrition profile (Contract §5 step 5) — the pool the final peer
     * cohort is rebuilt from. Populated by the finder (NUT-06); empty
     * until then and whenever the finder did not run.
     */
    public final List<ProductResult> peerPool = new ArrayList<>();
    /**
     * The primary's nutrition analysis refreshed against the final
     * cohort (Contract §5 step 5). Null when the finder did not run —
     * callers then use the primary's own {@code nutritionAnalysis}
     * from the step-3 analysis.
     */
    public NutritionAnalysis primaryAnalysis;
}
