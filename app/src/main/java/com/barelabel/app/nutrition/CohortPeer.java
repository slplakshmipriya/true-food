package com.barelabel.app.nutrition;

import com.barelabel.app.model.NutritionProfile;

/**
 * One candidate peer for a {@link PeerCohort} (Contract §4): the parsed
 * profile plus the two facts the cohort builder cannot derive from the
 * profile alone — the caller's precomputed dedupe key and the product's
 * USDA food category.
 *
 * <p>{@code dedupeKey} is the GTIN/UPC when the caller has one, else a
 * normalized brand+description+serving key; normalization is the caller's
 * job, the cohort compares keys as supplied. The cohort additionally
 * dedupes by {@code profile.fdcId} before consulting this key. A null or
 * empty key simply never dedupes.
 *
 * <p>Immutable.
 */
public final class CohortPeer {

    public final NutritionProfile profile;
    /** Precomputed GTIN-or-name dedupe key; may be null/empty. */
    public final String dedupeKey;
    /** USDA food category as supplied by the caller; may be null. */
    public final String category;

    public CohortPeer(NutritionProfile profile, String dedupeKey,
                      String category) {
        this.profile = profile;
        this.dedupeKey = dedupeKey;
        this.category = category;
    }
}
