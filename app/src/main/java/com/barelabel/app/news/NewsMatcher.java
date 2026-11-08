package com.barelabel.app.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NEWS matcher (Contracts sections 3.3 and 4).
 *
 * A raw item matches when its normalized headline tokens contain a
 * WHOLE query token (raw or plural-folded form, section 3.1) AND at
 * least one hazard keyword from the pinned section 3.3 list.
 * Multi-token keywords ("e coli", "fda warns", "do not eat") match as
 * consecutive token sequences. Duplicates - normalized titles sharing
 * the first 6 tokens - collapse to the newest copy. Survivors are
 * kept within the 30-day window, ordered pubDate DESC, capped at 3.
 *
 * Window rule: age = reference - pubDate must be 0..30 days. A
 * future-dated item (pubDate after the reference, e.g. outlet clock
 * skew) is treated as age 0 and kept. Each survivor's metaLine is
 * built by {@link NewsMetaFormatter}; the source label is always
 * "News report". Never throws on null/empty input.
 */
public final class NewsMatcher {

    /** Contracts section 4: NEWS window and cap. */
    static final long WINDOW_MS = 30L * 24L * 60L * 60L * 1000L;
    static final int MAX_STORIES = 3;

    /** Contracts section 3.3: duplicate prefix length, in tokens. */
    private static final int DEDUPE_PREFIX_TOKENS = 6;

    /** Contracts section 3.3: the pinned hazard keyword list. */
    private static final String[] HAZARD_KEYWORDS = {
            "recall", "outbreak", "contamination", "contaminated",
            "salmonella", "listeria", "e coli", "hepatitis",
            "fda warns", "do not eat", "illness", "hospitalized",
    };

    /** Hazard keywords pre-tokenized (raw and folded sequences). */
    private static final List<List<String>> HAZARD_RAW_SEQUENCES =
            new ArrayList<>();
    private static final List<List<String>> HAZARD_FOLDED_SEQUENCES =
            new ArrayList<>();

    static {
        for (String keyword : HAZARD_KEYWORDS) {
            HAZARD_RAW_SEQUENCES.add(NewsNormalizer.tokens(keyword));
            HAZARD_FOLDED_SEQUENCES.add(
                    NewsNormalizer.foldedTokens(keyword));
        }
    }

    public List<NewsStory> match(List<RawNewsItem> items,
                                 Set<String> queryTokens,
                                 long referenceEpochMillis) {
        List<NewsStory> out = new ArrayList<>();
        if (items == null || items.isEmpty()
                || queryTokens == null || queryTokens.isEmpty()) {
            return out;
        }
        Set<String> expandedQuery = NewsNormalizer.expandTokens(queryTokens);
        if (expandedQuery.isEmpty()) {
            return out;
        }

        // Match (section 3.3), then the 30-day window (section 4).
        List<RawNewsItem> kept = new ArrayList<>();
        for (RawNewsItem item : items) {
            if (item == null || !matches(item, expandedQuery)) {
                continue;
            }
            long ageMs = referenceEpochMillis
                    - item.getPubDateEpochMillis();
            if (ageMs < 0) {
                ageMs = 0; // future-dated: tolerate as just published
            }
            if (ageMs <= WINDOW_MS) {
                kept.add(item);
            }
        }

        // Dedupe (section 3.3): same first-6-token prefix, newest wins.
        Map<List<String>, RawNewsItem> newestByPrefix = new LinkedHashMap<>();
        for (RawNewsItem item : kept) {
            List<String> prefix = dedupePrefix(item.getHeadline());
            RawNewsItem current = newestByPrefix.get(prefix);
            if (current == null || item.getPubDateEpochMillis()
                    > current.getPubDateEpochMillis()) {
                newestByPrefix.put(prefix, item);
            }
        }

        // Order pubDate DESC, cap 3 (section 4).
        // (Anonymous Comparator + Collections.sort, not
        // Comparator.comparingLong / List.sort: those are API 24+ and
        // the app's minSdk is 23 - precedent: NutritionPanelPartition.)
        List<RawNewsItem> survivors =
                new ArrayList<>(newestByPrefix.values());
        Collections.sort(survivors, new Comparator<RawNewsItem>() {
            @Override
            public int compare(RawNewsItem a, RawNewsItem b) {
                return Long.compare(b.getPubDateEpochMillis(),
                        a.getPubDateEpochMillis());
            }
        });
        if (survivors.size() > MAX_STORIES) {
            survivors = new ArrayList<>(
                    survivors.subList(0, MAX_STORIES));
        }

        for (RawNewsItem item : survivors) {
            out.add(new NewsStory(
                    item.getHeadline(),
                    item.getOutlet(),
                    item.getLink(),
                    item.getPubDateEpochMillis(),
                    NewsMetaFormatter.format(item.getOutlet(),
                            item.getPubDateEpochMillis(),
                            referenceEpochMillis)));
        }
        return out;
    }

    private static boolean matches(RawNewsItem item,
                                   Set<String> expandedQuery) {
        Set<String> titleTokens =
                NewsNormalizer.tokenSet(item.getHeadline());
        boolean hasQueryToken = false;
        for (String token : expandedQuery) {
            if (titleTokens.contains(token)) {
                hasQueryToken = true;
                break;
            }
        }
        if (!hasQueryToken) {
            return false;
        }
        return hasHazardKeyword(item.getHeadline());
    }

    private static boolean hasHazardKeyword(String headline) {
        List<String> rawTokens = NewsNormalizer.tokens(headline);
        List<String> foldedTokens = NewsNormalizer.foldedTokens(headline);
        for (int i = 0; i < HAZARD_RAW_SEQUENCES.size(); i++) {
            if (containsSequence(rawTokens, HAZARD_RAW_SEQUENCES.get(i))
                    || containsSequence(foldedTokens,
                            HAZARD_FOLDED_SEQUENCES.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSequence(List<String> tokens,
                                            List<String> sequence) {
        if (sequence.isEmpty() || sequence.size() > tokens.size()) {
            return false;
        }
        outer:
        for (int start = 0; start + sequence.size() <= tokens.size();
                start++) {
            for (int j = 0; j < sequence.size(); j++) {
                if (!tokens.get(start + j).equals(sequence.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static List<String> dedupePrefix(String headline) {
        List<String> tokens = NewsNormalizer.tokens(headline);
        int end = Math.min(DEDUPE_PREFIX_TOKENS, tokens.size());
        return new ArrayList<>(tokens.subList(0, end));
    }
}
