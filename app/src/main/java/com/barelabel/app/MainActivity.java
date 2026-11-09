package com.barelabel.app;

import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;



import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.widget.NestedScrollView;

import com.google.android.flexbox.FlexboxLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.barelabel.app.model.AlternateSearchResult;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.model.ScannedProduct;
import com.barelabel.app.network.UsdaApiClient;
import com.barelabel.app.nutrition.CohortPeer;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionConfigLoader;
import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.billing.PremiumGates;
import com.barelabel.app.images.ProductImageResolver;
import com.barelabel.app.ocr.OcrModelManager;
import com.barelabel.app.ocr.OcrPreprocessor;
import com.barelabel.app.ocr.ServerSpellChecker;
import com.barelabel.app.llm.LlmSpellChecker;
import com.barelabel.app.llm.VerdictExplainer;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionConfigLoader;
import com.barelabel.app.nutrition.OverallScoreCalculator;
import com.barelabel.app.search.BarcodeSeed;
import com.barelabel.app.search.CleanAlternateFinder;
import com.barelabel.app.search.DisambiguationDecider;
import com.barelabel.app.search.NutritionCohortService;
import com.barelabel.app.search.IngredientSectionExtractor;
import com.barelabel.app.search.OcrCorrector;
import com.barelabel.app.news.SafetyAlert;
import com.barelabel.app.news.SafetyAlertService;
import com.barelabel.app.ui.AlternatesCardController;
import com.barelabel.app.ui.CompareViewBuilder;
import com.barelabel.app.ui.SearchSafetyBinder;
import com.barelabel.app.ui.NutritionPanelController;
import com.barelabel.app.ui.ProductDetailDialog;
import com.barelabel.app.ui.SafetyReportsPanelController;
import com.barelabel.app.util.CategorySearchDecider;
import com.barelabel.app.util.GenericWords;
import com.barelabel.app.util.StringNormalizer;

import android.view.ViewGroup;
import android.widget.CheckBox;


public class MainActivity extends AppCompatActivity {

    private static final int C_GREEN_800 = android.graphics.Color.parseColor("#166534");
    private static final int C_GREEN_900 = android.graphics.Color.parseColor("#14532D");
    private static final int C_GRAY_600 = android.graphics.Color.parseColor("#4B5563");
    private static final int C_GRAY_700 = android.graphics.Color.parseColor("#374151");
    private static final int C_RED_900 = android.graphics.Color.parseColor("#7F1D1D");
    private static final int C_RED_800 = android.graphics.Color.parseColor("#991B1B");
    private static final int C_AMBER_800 = android.graphics.Color.parseColor("#92400E");
    // Single-sourced from SearchSafetyBinder (NA-07, NA-05 MINOR 1).
    private static final int C_AMBER_900 = SearchSafetyBinder.C_AMBER_900;

    // Member Variable Declarations
    private EditText searchBox;
    private Button searchButton;
    private ProgressBar progress;
    private TextView statusText, verdictText, flaggedText, ingredientsText;
    private TextView explanationText;
    private LinearLayout resultCard;
    private LinearLayout disambiguationCard, disambiguationList;
    private TextView disambiguationSubtitle;
    /** Set by lookupBarcodeAndSearch; barcode scans skip disambiguation. */
    private boolean pendingBarcodeSearch;
    private com.barelabel.app.ui.CleanScoreView cleanScoreView;
    private com.google.android.flexbox.FlexboxLayout flaggedList;
    private androidx.recyclerview.widget.RecyclerView recentsRecycler;
    private TextView recentsLabel;
    private com.barelabel.app.ui.RecentsAdapter recentsAdapter;
    private View skeletonCard;
    private android.animation.ObjectAnimator skeletonPulse;

    // Separate Panels & Expandable Ingredients
    private View ingredientsCard;
    private TextView toggleIngredientsButton;
    private TextView productTitleText;
    private android.widget.ImageView productImageView;
    private TextView matchNoticeText;
	private com.google.android.flexbox.FlexboxLayout categoryCheckboxContainer;

    // Filter collapse: full checkbox panel vs. compact summary bar
    private LinearLayout categoryFilterPanel;
    private LinearLayout filterSummaryBar;
    private TextView filterSummaryText;

    // Sticky results header overlay
    private LinearLayout stickyResultsBar;
    private View compareTabContent;
    private View historyTabContent;
    private com.barelabel.app.ui.HistoryTabController historyTabController;
    private com.barelabel.app.ui.CompareTabController compareTabController;
    private com.google.android.material.bottomnavigation.BottomNavigationView bottomNav;
    private CheckBox verdictCompareBox;
    private ProductResult currentPrimaryResult;
    private final LinkedHashMap<String, ProductResult> comparePicks = new LinkedHashMap<>();
    private boolean syncingCompareUi;
    private TextView stickyResultsText;

    // Verdict pass/fail chips on the primary product card (no longer
    // rendered for found products — the verdict bubbles replace them,
    // Contract §7.1; the container is only ever cleared and hidden now)
    private FlexboxLayout verdictChipsContainer;

    // Verdict card fixed top block + bubbles + detail slot (Contract §7.1)
    private LinearLayout scoreStrip;
    private TextView ingredientScoreText, nutritionScoreText;
    private LinearLayout verdictBubblesRow;
    private TextView ingredientsVerdictBubble, nutritionVerdictBubble;
    private LinearLayout ingredientDetailPanel, nutritionDetailPanel;
    private NutritionPanelController nutritionPanelController;

    // Safety reports section (NA-06, Contract §6.4): appended content
    // below the BUY! button, bound by its own controller.
    private LinearLayout safetyReportsSection;
    private SafetyReportsPanelController safetyReportsController;

    // Cached flagged-category names from flagged_ingredients.json
    private List<String> flaggedCategoriesCache = null;

    private boolean isIngredientsExpanded = false;

    // Collaborators: USDA network, clean-alternate search, and the alternates card UI.
    private UsdaApiClient usdaApiClient;
    private CleanAlternateFinder alternateFinder;
    // Nutrition orchestration (Contract §5): config loaded once and cached;
    // a null config means nutrition is UNAVAILABLE and the flow is unchanged.
    private NutritionConfig nutritionConfig;
    private boolean nutritionConfigLoaded;
    private NutritionCohortService nutritionCohortService;
    private AlternatesCardController alternatesController;
    private FoodAutoCompleteManager autoCompleteManager;
    // USDA record behind the last tapped autocomplete suggestion (0 = none/typed query).
    private long selectedSuggestionFdcId = 0;
    private String selectedSuggestionLabel = "";
    /**
     * Monotonic search generation. Bumped on every search() so a slow
     * earlier search can never overwrite a newer search's results or
     * re-enable the UI while a newer search is still running.
     */
    private volatile int searchGeneration = 0;

    // Search safety surface (NA-05, Contracts §6.2/§6.3/§6.5): count
    // line under the SEARCH button + badges on disambiguation rows.
    // All decisions live in the news package's pure presentation /
    // state classes; the binder only renders them.
    private TextView safetyCountLine;
    private SearchSafetyBinder searchSafetyBinder;
    private SafetyAlertService safetyAlertService;

    private final Set<String> superiorTerms = new HashSet<>();

    private final ActivityResultLauncher<Intent> ingredientScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String text = result.getData().getStringExtra(
                            IngredientScanActivity.EXTRA_INGREDIENT_TEXT);
                    if (text != null && !text.trim().isEmpty()) {
                        onIngredientTextScanned(text);
                    }
                }
            }
    );

    private final ActivityResultLauncher<String> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    ocrGalleryImage(uri);
                }
            }
    );

    private final ActivityResultLauncher<Intent> barcodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                boolean success = false;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String scannedBarcode = result.getData().getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE);
                    if (scannedBarcode != null && !scannedBarcode.trim().isEmpty()) {
                        success = true;
                        lookupBarcodeAndSearch(scannedBarcode.trim());
                    }
                }
                AnalyticsTracker.barcodeScanned(success);
            }
    );
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    setTitle("BareLabel");

    AnalyticsTracker.init(this);

    // Refresh affiliate mappings in the background (throttled, silent on
    // failure); bundled mappings are used until a newer remote version lands.
    com.barelabel.app.affiliate.AffiliateManager.checkForUpdates(this);

    // 1. Bind UI Components
    bindViews();
    searchSafetyBinder = new SearchSafetyBinder(this, safetyCountLine);
    setupVerdictBubbleToggles();

    // 2. Load Superior Terms Engine Set
    loadSuperiorTerms();

    // 3. Collaborators: USDA client, alternate search, alternates card UI.
    usdaApiClient = new UsdaApiClient(this);
    alternateFinder = new CleanAlternateFinder(this, usdaApiClient);
    alternatesController = new AlternatesCardController(
            this,
            findViewById(R.id.alternatesCard),
            findViewById(R.id.alternatesTitle),
            findViewById(R.id.alternatesText),
            (SwitchCompat) findViewById(R.id.preferOrganicSwitch),
            product -> openShoppingSearch(product, "alternate"));
    alternatesController.setSuperiorTerms(superiorTerms);
    alternatesController.setOnClearFilters(this::clearAllFiltersAndSearch);
    alternatesController.setOnUncheckFilter(category -> {
        uncheckFilterCategory(category);
        search();
    });

    // 4. Setup Barcode Scanner Button
    ImageButton scanBarcodeButton = findViewById(R.id.scanBarcodeButton);
    if (scanBarcodeButton != null) {
        scanBarcodeButton.setOnClickListener(v -> openBarcodeScanner());
    }
    // Scan-first hero: same scanner, bigger target.
    Button scanHeroButton = findViewById(R.id.scanHeroButton);
    if (scanHeroButton != null) {
        scanHeroButton.setOnClickListener(v -> openBarcodeScanner());
    }
    Button scanLabelButton = findViewById(R.id.scanLabelButton);
    if (scanLabelButton != null) {
        scanLabelButton.setOnClickListener(v -> {
            if (!PremiumGates.isOcrScanEnabled(this)) {
                showOcrPaywall();
                return;
            }
            showLabelScanChooser();
        });
    }

    // Recent scans strip (Search tab): tap a card to re-run that search.
    if (recentsRecycler != null) {
        recentsRecycler.setLayoutManager(
                new androidx.recyclerview.widget.LinearLayoutManager(
                        this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                        false));
        recentsAdapter = new com.barelabel.app.ui.RecentsAdapter(p -> {
            searchBox.setText(p.displayName());
            search();
        });
        recentsRecycler.setAdapter(recentsAdapter);
    }

    // 4b. Settings (wrench): preferred retailer for Buy links.
    ImageButton settingsButton = findViewById(R.id.settingsButton);
    if (settingsButton != null) {
        settingsButton.setOnClickListener(v -> showRetailerSettings());
    }

setupCategoryFilterPanel();
    maybeShowOnboarding();
    // 4c. Bottom navigation: Search | Compare | History. Search is the default.
    bottomNav = findViewById(R.id.bottomNav);
    View searchTabContent = findViewById(R.id.mainRootLayout);
    compareTabContent = findViewById(R.id.compareTabContent);
    historyTabContent = findViewById(R.id.historyTabContent);
    if (bottomNav != null && searchTabContent != null) {
        historyTabController = new com.barelabel.app.ui.HistoryTabController(
                this, historyTabContent);
        compareTabController = new com.barelabel.app.ui.CompareTabController(
                this, compareTabContent);
        compareTabController.setCompareActionListener(
                new CompareViewBuilder.CompareActionListener() {
                    @Override
                    public void onBuy(ScannedProduct p) {
                        ProductResult pr = new ProductResult();
                        pr.found = true;
                        pr.name = p.name;
                        pr.brand = p.brand;
                        AnalyticsTracker.buyTapped("compare");
                        openShoppingSearch(pr, "compare");
                    }

                    @Override
                    public boolean onSave(ScannedProduct p) {
                        return saveScannedToHistory(p);
                    }
                });
        final View searchContent = searchTabContent;
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            int position = id == R.id.nav_compare ? 1
                    : id == R.id.nav_history ? 2 : 0;
            showTab(position, searchContent);
            return true;
        });
        showTab(0, searchContent);
    }

    // Compare boxes: one on the verdict card, one per clean-alternate row.
    // Checking any 2 auto-opens the Compare tab with the pair.
    if (verdictCompareBox != null) {
        verdictCompareBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingCompareUi || currentPrimaryResult == null) return;
            AnalyticsTracker.compareCheckboxToggled(isChecked, "verdict_card");
            toggleComparePick(currentPrimaryResult);
        });
    }
    if (alternatesController != null) {
        alternatesController.setComparePickListener(
                new AlternatesCardController.ComparePickListener() {
                    @Override
                    public boolean isSelectedForCompare(ProductResult pr) {
                        return comparePicks.containsKey(compareKey(pr));
                    }

                    @Override
                    public void onToggleComparePick(ProductResult pr) {
                        toggleComparePick(pr);
                    }
                });
    }
    if (alternatesController != null) {
        alternatesController.setSaveListener(p -> {
            boolean saved = saveProductToHistory(p);
            if (saved) AnalyticsTracker.productSaved("alternate");
            return saved;
        });
    }
    if (alternatesController != null) {
        alternatesController.setShareListener(p -> {
            AnalyticsTracker.productShared("alternate");
            com.barelabel.app.sharing.ShareHelper.shareProduct(this, p);
        });
    }
    if (alternatesController != null) {
        alternatesController.setAlternateSearchListener(alt -> {
            if (alt == null) return;
            // The tapped alternate becomes the next search candidate: exact
            // GTIN lookup when we have one (same path as a barcode scan),
            // exact fdcId fetch when the record's id is known (same path
            // as a tapped suggestion: the record behind the row, never a
            // fuzzy re-search), bare-name search as the last resort. The
            // old name fallback appended " (BRAND)", which matched no
            // USDA description and landed on an unrelated top pick whose
            // category panel then replaced the tapped product entirely
            // (Broccoli (Mambo) -> "Clean options in Ketchup, Mustard,
            // BBQ & Cheese Sauce").
            if (!TextUtils.isEmpty(alt.gtinUpc)) {
                lookupBarcodeAndSearch(alt.gtinUpc.trim());
            } else if (alt.fdcId > 0) {
                String q = alt.name == null ? "" : alt.name.trim();
                selectedSuggestionFdcId = alt.fdcId;
                selectedSuggestionLabel = q;
                searchBox.setText(q);
                search();
            } else {
                searchBox.setText(alt.name == null ? "" : alt.name);
                search();
            }
            NestedScrollView rootScroll = findViewById(R.id.mainRootLayout);
            if (rootScroll != null) rootScroll.smoothScrollTo(0, 0);
        });
    }
    // 5. Attach AutoComplete Manager
    // Unbranded completions (offline dictionary) take rank 1-2; USDA fills the rest.
    SuggestionProvider suggestionProvider = new CombinedSuggestionProvider(
            new UnbrandedSuggestionProvider(this), new UsdaSuggestionProvider(this));
    autoCompleteManager = new FoodAutoCompleteManager(this, suggestionProvider);
    autoCompleteManager.attachToEditText(searchBox);
    autoCompleteManager.setOnSuggestionSelected(suggestion -> {
        selectedSuggestionFdcId = suggestion.fdcId;
        selectedSuggestionLabel = suggestion.label;
    });
    autoCompleteManager.setOnRecentSearchSelected(query -> {
        selectedSuggestionFdcId = 0;
        selectedSuggestionLabel = "";
        searchBox.setText(query);
        search();
    });

    // Filter summary bar: tap to expand the full filter panel again.
    if (filterSummaryBar != null) {
        filterSummaryBar.setOnClickListener(v -> {
            if (categoryFilterPanel != null) {
                categoryFilterPanel.setVisibility(View.VISIBLE);
            }
            filterSummaryBar.setVisibility(View.GONE);
        });
    }

    // Sticky results header: appears once the alternates card scrolls into
    // view; tapping it scrolls back to the card.
    NestedScrollView mainRoot = findViewById(R.id.mainRootLayout);
    View alternatesCardView = findViewById(R.id.alternatesCard);
    if (mainRoot != null && alternatesCardView != null && stickyResultsBar != null) {
        mainRoot.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
            boolean show = alternatesCardView.getVisibility() == View.VISIBLE
                    && scrollY > alternatesCardView.getTop();
            stickyResultsBar.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        stickyResultsBar.setOnClickListener(v ->
                mainRoot.smoothScrollTo(0, Math.max(0, alternatesCardView.getTop())));
    }

    // 6. Setup Primary Retail Buy Button
    Button buyButton = findViewById(R.id.walmartButton);
    if (buyButton != null) {
        buyButton.setOnClickListener(v -> {
            String currentQuery = searchBox.getText().toString().trim();
            if (!currentQuery.isEmpty()) {
                AnalyticsTracker.buyTapped("search_box");
                openShoppingSearch(currentQuery);
            }
        });
    }

    // 7. Attach Search Action Listeners
    searchButton.setOnClickListener(v -> search());
    searchBox.setOnEditorActionListener((v, actionId, event) -> {
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            search();
            return true;
        }
        return false;
    });

    // 8. App Links deep link: a shared product URL opens its verdict directly.
    handleDeepLink(getIntent());
}

@Override
protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleDeepLink(intent);
}

/**
 * App Links entry point: barelabel.online/p/{gtin} resolves to the same
 * GTIN-exact search a barcode scan uses. Non-share intents are ignored.
 */
private void handleDeepLink(Intent intent) {
    if (intent == null) return;
    if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
    android.net.Uri uri = intent.getData();
    if (uri == null) return;
    String gtin = com.barelabel.app.sharing.ShareLinks.extractGtin(
            uri.getHost(), uri.getPath());
    if (gtin == null) return;
    lookupBarcodeAndSearch(gtin);
}


@Override
protected void onResume() {
    super.onResume();
    refreshRecents();
}

/** Reloads the Recent scans strip from disk (newest first, max 10). */
private void refreshRecents() {
    if (recentsAdapter == null) return;
    new Thread(() -> {
        List<ScannedProduct> all = ScanHistoryRepository.getAll(this);
        final List<ScannedProduct> top = all.size() > 10 ? all.subList(0, 10) : all;
        runOnUiThread(() -> {
            recentsAdapter.setItems(top);
            boolean has = !top.isEmpty();
            // Don't resurrect the strip over displayed search results:
            // it yields while a verdict, clean-choices, or
            // disambiguation card is on screen (see the search
            // handler, which hides it when a search is issued).
            boolean resultsShowing =
                    (resultCard != null && resultCard.getVisibility() == View.VISIBLE)
                    || (disambiguationCard != null
                            && disambiguationCard.getVisibility() == View.VISIBLE)
                    || (alternatesController != null
                            && alternatesController.isCardShowing());
            boolean showStrip = has && !resultsShowing;
            if (recentsRecycler != null) {
                recentsRecycler.setVisibility(showStrip ? View.VISIBLE : View.GONE);
            }
            if (recentsLabel != null) {
                recentsLabel.setVisibility(showStrip ? View.VISIBLE : View.GONE);
            }
        });
    }).start();
}


    private void bindViews() {
        searchBox = findViewById(R.id.searchBox);
        searchButton = findViewById(R.id.searchButton);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        verdictText = findViewById(R.id.verdictText);
        explanationText = findViewById(R.id.explanationText);
        flaggedText = findViewById(R.id.flaggedText);
        ingredientsText = findViewById(R.id.ingredientsText);
        resultCard = findViewById(R.id.resultCard);
        disambiguationCard = findViewById(R.id.disambiguationCard);
        disambiguationList = findViewById(R.id.disambiguationList);
        disambiguationSubtitle = findViewById(R.id.disambiguationSubtitle);
        cleanScoreView = findViewById(R.id.cleanScoreView);
        flaggedList = findViewById(R.id.flaggedList);
        recentsRecycler = findViewById(R.id.recentsRecycler);
        recentsLabel = findViewById(R.id.recentsLabel);
        safetyCountLine = findViewById(R.id.safetyCountLine);
        skeletonCard = findViewById(R.id.skeletonCard);

        productTitleText = findViewById(R.id.productTitleText);
        productImageView = findViewById(R.id.productImageView);
        matchNoticeText = findViewById(R.id.matchNoticeText);

        ingredientsCard = findViewById(R.id.ingredientsCard);
        toggleIngredientsButton = findViewById(R.id.toggleIngredientsButton);
		categoryCheckboxContainer = findViewById(R.id.categoryCheckboxContainer);
        categoryFilterPanel = findViewById(R.id.categoryFilterPanel);
        filterSummaryBar = findViewById(R.id.filterSummaryBar);
        filterSummaryText = findViewById(R.id.filterSummaryText);
        stickyResultsBar = findViewById(R.id.stickyResultsBar);
        stickyResultsText = findViewById(R.id.stickyResultsText);
        verdictChipsContainer = findViewById(R.id.verdictChipsContainer);
        verdictCompareBox = findViewById(R.id.verdictCompareBox);
        scoreStrip = findViewById(R.id.scoreStrip);
        ingredientScoreText = findViewById(R.id.ingredientScoreText);
        nutritionScoreText = findViewById(R.id.nutritionScoreText);
        verdictBubblesRow = findViewById(R.id.verdictBubblesRow);
        ingredientsVerdictBubble = findViewById(R.id.ingredientsVerdictBubble);
        nutritionVerdictBubble = findViewById(R.id.nutritionVerdictBubble);
        ingredientDetailPanel = findViewById(R.id.ingredientDetailPanel);
        nutritionDetailPanel = findViewById(R.id.nutritionDetailPanel);
        nutritionPanelController = new NutritionPanelController(this);
        safetyReportsSection = findViewById(R.id.safetyReportsSection);
        safetyReportsController = new SafetyReportsPanelController(this);
    }

    /** Bubble click wiring: each bubble toggles only its own detail panel. */
    private void setupVerdictBubbleToggles() {
        if (ingredientsVerdictBubble != null) {
            ingredientsVerdictBubble.setOnClickListener(
                    v -> toggleVerdictPanel(true));
        }
        if (nutritionVerdictBubble != null) {
            nutritionVerdictBubble.setOnClickListener(
                    v -> toggleVerdictPanel(false));
        }
    }

    /**
     * Accordion for the detail slot (Contract §7.1): exactly one panel
     * visible at a time, tapping the open panel's bubble closes it.
     * Only the detail slot and the bubbles' expanded/collapsed content
     * descriptions change here — the fixed top block (header, LLM
     * summary, score ring, score strip) is never rebound or moved.
     */
    private void toggleVerdictPanel(boolean ingredients) {
        if (ingredientDetailPanel == null || nutritionDetailPanel == null) {
            return;
        }
        View target = ingredients ? ingredientDetailPanel
                : nutritionDetailPanel;
        boolean expand = target.getVisibility() != View.VISIBLE;
        ingredientDetailPanel.setVisibility(
                ingredients && expand ? View.VISIBLE : View.GONE);
        nutritionDetailPanel.setVisibility(
                !ingredients && expand ? View.VISIBLE : View.GONE);
        updateBubbleContentDescriptions();
    }

    /** Bubbles expose their expanded/collapsed state (Contract §7.3). */
    private void updateBubbleContentDescriptions() {
        if (ingredientsVerdictBubble != null) {
            boolean open = ingredientDetailPanel != null
                    && ingredientDetailPanel.getVisibility() == View.VISIBLE;
            ingredientsVerdictBubble.setContentDescription(open
                    ? "Ingredients verdict, expanded"
                    : "Ingredients verdict, collapsed");
        }
        if (nutritionVerdictBubble != null) {
            boolean open = nutritionDetailPanel != null
                    && nutritionDetailPanel.getVisibility() == View.VISIBLE;
            nutritionVerdictBubble.setContentDescription(open
                    ? "Nutrition verdict, expanded"
                    : "Nutrition verdict, collapsed");
        }
    }

    /**
     * Resets the verdict card's detail slot for a new search/result:
     * both panels closed, the nutrition panel cleared (which also
     * collapses the remaining-nutrients toggle), bubbles collapsed.
     */
    private void resetVerdictPanels() {
        if (ingredientDetailPanel != null) {
            ingredientDetailPanel.setVisibility(View.GONE);
        }
        if (nutritionDetailPanel != null) {
            nutritionDetailPanel.setVisibility(View.GONE);
        }
        if (nutritionPanelController != null) {
            nutritionPanelController.reset(nutritionDetailPanel);
        }
        if (safetyReportsController != null) {
            // NA-06: a new card starts with no safety section — the
            // previous product's alerts and collapse state never carry.
            safetyReportsController.reset(safetyReportsSection);
        }
        updateBubbleContentDescriptions();
    }

    /**
     * NA-06 (Contract §6.4/§6.5, BL-S4): loads safety alerts for the
     * product just bound and inserts the "Safety reports" section
     * below the BUY! button when they arrive. Runs off the UI thread
     * through the NA-04 service (which never throws, §7); an empty
     * result or any failure leaves the card exactly as it was — no
     * spinner, no placeholder. The stale-result guard mirrors the
     * LLM summary's: if the user has moved on to another product,
     * the late result is dropped. Read-only display: nothing here
     * touches verdict, score, or history state (BL-S6), and there
     * is no premium gate in the path (BL-S7).
     */
    private void loadSafetyReports(ProductResult result, String foodType) {
        if (result == null || safetyReportsController == null
                || safetyReportsSection == null) {
            return;
        }
        final ProductResult forResult = result;
        final String productName = result.name != null ? result.name : "";
        final String category = !TextUtils.isEmpty(result.foodCategory)
                ? result.foodCategory
                : (foodType != null ? foodType : "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SafetyAlert> alerts;
                try {
                    alerts = new SafetyAlertService(MainActivity.this)
                            .alertsForProduct(productName, category);
                } catch (Throwable t) {
                    alerts = Collections.emptyList();
                }
                final List<SafetyAlert> arrived = alerts;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (currentPrimaryResult != forResult) {
                            return; // stale: user moved on
                        }
                        safetyReportsController.bind(
                                safetyReportsSection, arrived);
                    }
                });
            }
        }).start();
    }

    /**
     * Binds the fixed top block ONCE per result (Contract §7.1): the
     * score ring becomes the Overall Score (ingredient score blended
     * with the Nutrition Score per the config; ingredient score alone
     * when nutrition is unavailable) and the strip shows the two
     * component scores. No blend weights are displayed anywhere.
     * Disambiguation rows and share text keep the ingredient score —
     * only this ring changes meaning.
     */
    private void bindVerdictTopBlock(ProductResult result,
                                     int ingredientScore) {
        NutritionAnalysis analysis = result != null
                ? result.nutritionAnalysis : null;
        NutritionConfig config = NutritionConfigLoader.load(this);
        Integer nutritionScore = analysis != null
                ? analysis.nutritionScore : null;
        int overallScore = OverallScoreCalculator.blend(
                ingredientScore, nutritionScore, config);
        if (cleanScoreView != null) {
            cleanScoreView.setScore(overallScore);
            cleanScoreView.setContentDescription("Overall Score");
        }
        if (ingredientScoreText != null) {
            ingredientScoreText.setText(
                    ingredientScore == CleanScoreCalculator.UNAVAILABLE
                            ? "—" : String.valueOf(ingredientScore));
        }
        if (nutritionScoreText != null) {
            nutritionScoreText.setText(nutritionScore == null
                    ? "—" : String.valueOf(nutritionScore));
        }
        if (scoreStrip != null) {
            scoreStrip.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Binds the verdict bubbles and the nutrition detail panel for a
     * found product (Contract §7.1): bubbles replace the large
     * verdictText and the per-category chips, show the ACTUAL verdicts
     * with per-verdict colors set here at bind time, and start with
     * both detail panels closed.
     */
    private void bindVerdictBubblesAndPanels(ProductResult result,
                                             boolean isClean) {
        if (verdictText != null) {
            verdictText.setVisibility(View.GONE);
        }
        if (verdictChipsContainer != null) {
            verdictChipsContainer.removeAllViews();
            verdictChipsContainer.setVisibility(View.GONE);
        }
        if (verdictBubblesRow != null) {
            verdictBubblesRow.setVisibility(View.VISIBLE);
        }
        if (ingredientsVerdictBubble != null) {
            ingredientsVerdictBubble.setText(
                    isClean ? "Ingredients · CLEAN" : "Ingredients · DIRTY");
            ingredientsVerdictBubble.setBackgroundResource(isClean
                    ? R.drawable.chip_clean_background
                    : R.drawable.chip_dirty_background);
            ingredientsVerdictBubble.setTextColor(
                    isClean ? C_GREEN_900 : C_RED_900);
        }
        NutritionAnalysis analysis = result != null
                ? result.nutritionAnalysis : null;
        com.barelabel.app.nutrition.NutritionStatus status =
                analysis != null ? analysis.status : null;
        if (nutritionVerdictBubble != null) {
            if (status == com.barelabel.app.nutrition.NutritionStatus.SAFE) {
                nutritionVerdictBubble.setText("Nutrition · SAFE");
                nutritionVerdictBubble.setBackgroundResource(
                        R.drawable.chip_clean_background);
                nutritionVerdictBubble.setTextColor(C_GREEN_900);
            } else if (status
                    == com.barelabel.app.nutrition.NutritionStatus.CONCERNING) {
                nutritionVerdictBubble.setText("Nutrition · CONCERNING");
                nutritionVerdictBubble.setBackgroundResource(
                        R.drawable.chip_concern_background);
                nutritionVerdictBubble.setTextColor(C_AMBER_900);
            } else {
                nutritionVerdictBubble.setText("Nutrition · Unavailable");
                nutritionVerdictBubble.setBackgroundResource(
                        R.drawable.chip_unavailable_background);
                nutritionVerdictBubble.setTextColor(C_GRAY_700);
            }
        }
        if (nutritionPanelController != null) {
            nutritionPanelController.bind(nutritionDetailPanel, analysis,
                    result != null ? result.nutritionProfile : null,
                    NutritionConfigLoader.load(this));
        }
        updateBubbleContentDescriptions();
    }

    /**
     * States that keep the large verdictText (Contract §7.1): not-found
     * and ingredients-unavailable hide the bubbles, the score strip,
     * and the chips, and close the detail panels.
     */
    private void showVerdictTextState() {
        if (verdictText != null) {
            verdictText.setVisibility(View.VISIBLE);
        }
        if (verdictBubblesRow != null) {
            verdictBubblesRow.setVisibility(View.GONE);
        }
        if (scoreStrip != null) {
            scoreStrip.setVisibility(View.GONE);
        }
        resetVerdictPanels();
    }

    private void loadSuperiorTerms() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("superior_ingredients.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = StringNormalizer.normalize(line);
                if (!line.isEmpty() && !line.startsWith("#")) {
                    superiorTerms.add(line);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not load superior ingredient list.", Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * Decides whether a query is an unbranded category search (e.g. "peanut butter")
     * rather than a branded product search (e.g. "jif peanut butter") — with no
     * toggle needed. When the query does not name the matched product's brand
     * (brandName or brandOwner), it is treated as a category search and the app
     * always shows clean choices for that category.
     */
    private boolean isCategorySearch(String query, ProductResult product) {
        // Pure decision lives in CategorySearchDecider (unit-tested); this only
        // supplies the GenericWords-backed generic-word lookup.
        return CategorySearchDecider.isCategorySearch(query, product,
                token -> GenericWords.isGeneric(this, token));
    }

    /**
     * Nutrition config (Contract §1), loaded once and cached in a field;
     * the loader itself also caches process-wide. Null when the config
     * asset failed validation — nutrition is then UNAVAILABLE and the
     * ingredient flow is unchanged. Called from the search worker thread.
     *
     * <p>The field is latched only after a load attempt that succeeded
     * or failed validation permanently (the loader latches the latter
     * itself and reports it via
     * {@link NutritionConfigLoader#isPermanentlyFailed()}). A transient
     * asset-read failure — {@code load} returning null without that
     * latch — leaves {@code nutritionConfigLoaded} unset so the next
     * search retries instead of disabling nutrition for the process
     * lifetime. Both lazy-init methods here are {@code synchronized}:
     * they are called from concurrent search worker threads, and the
     * monitor makes each check-and-init atomic and safely published
     * (the nested call is reentrant on the same monitor).
     */
    private synchronized NutritionConfig nutritionConfig() {
        if (!nutritionConfigLoaded) {
            NutritionConfig loaded = null;
            try {
                loaded = NutritionConfigLoader.load(this);
            } catch (Exception ignored) {
                loaded = null;
            }
            nutritionConfig = loaded;
            if (loaded != null || NutritionConfigLoader.isPermanentlyFailed()) {
                nutritionConfigLoaded = true;
            }
        }
        return nutritionConfig;
    }

    /** Cohort service over the cached config + the USDA page source. */
    private synchronized NutritionCohortService nutritionCohortService() {
        if (nutritionCohortService == null) {
            NutritionCohortService service = new NutritionCohortService(
                    nutritionConfig(),
                    NutritionCohortService.usdaPageSource(usdaApiClient));
            // Cache the service only once the config load has reached a
            // terminal state (see nutritionConfig()): a service built
            // over a transiently-null config would permanently pin
            // nutrition to UNAVAILABLE even if a later load succeeds.
            // Until then each call returns a fresh, uncached service.
            if (nutritionConfigLoaded) {
                nutritionCohortService = service;
            }
            return service;
        }
        return nutritionCohortService;
    }

    /** Lazily built NA-04 service (production clients, app cache dir). */
    private synchronized SafetyAlertService safetyAlertService() {
        if (safetyAlertService == null) {
            safetyAlertService = new SafetyAlertService(this);
        }
        return safetyAlertService;
    }

    /**
     * NA-05 alert fetch (Contract §6.5): runs on its own thread,
     * concurrent with the USDA search in {@link #search()}. The
     * service never throws (§7) and returns an empty list on any
     * failure, which the binder renders as nothing at all; the
     * binder's generation token drops the result if a newer search
     * has superseded this one. No verdict, score, or history state
     * is touched here (§6 guards / BL-S6), and no premium check
     * exists on this path (BL-S7).
     */
    private void startSafetyAlertFetch(final String term) {
        if (searchSafetyBinder == null) {
            return;
        }
        final int safetyGeneration = searchSafetyBinder.beginSearch(term);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SafetyAlert> fetched;
                try {
                    fetched = safetyAlertService().alertsForSearch(term);
                } catch (Throwable t) {
                    fetched = Collections.emptyList();
                }
                final List<SafetyAlert> alerts = fetched != null
                        ? fetched : Collections.<SafetyAlert>emptyList();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (searchSafetyBinder != null) {
                            searchSafetyBinder.applyAlerts(
                                    safetyGeneration, alerts);
                        }
                    }
                });
            }
        }).start();
    }

    private void search() {
        clearComparePicks();
        // Barcode-initiated searches name an exact product; they always go
        // straight to the verdict, never to disambiguation.
        final boolean fromBarcode = pendingBarcodeSearch;
        pendingBarcodeSearch = false;
        // Suggestions are no longer needed once the user commits to a search.
        if (autoCompleteManager != null) {
            autoCompleteManager.dismissSuggestions();
        }
        final String product = searchBox.getText().toString().trim();
        if (TextUtils.isEmpty(product)) {
            searchBox.setError("Enter a food product name");
            return;
        }
        AnalyticsTracker.searchPerformed(product.length());

        // Consume the tapped-suggestion state here on the UI thread, before
        // the worker starts: a second search issued while the first is still
        // fetching must never see the first search's fdcId.
        final long fdcId = selectedSuggestionLabel.equals(product)
                ? selectedSuggestionFdcId : 0;
        selectedSuggestionFdcId = 0;
        selectedSuggestionLabel = "";

        // Every search bumps the generation; a slow earlier search's UI
        // posts are dropped once a newer search has started.
        final int generation = ++searchGeneration;

        // NA-05 (§6.5): safety alerts fetch concurrently with the USDA
        // search below; results render first, the count line and row
        // badges insert when the alerts arrive.
        startSafetyAlertFetch(product);

        // Collapse the filter panel into its summary bar once a search is issued.
        if (categoryFilterPanel != null && filterSummaryBar != null) {
            categoryFilterPanel.setVisibility(View.GONE);
            filterSummaryBar.setVisibility(View.VISIBLE);
            updateFilterSummary();
        }
        if (stickyResultsBar != null) {
            stickyResultsBar.setVisibility(View.GONE);
        }
        // Recent scans are home chrome: hide the strip while a search
        // runs and its results are on screen. The strip sits above the
        // result cards in the layout, and on a phone screen it pushed
        // the first result below the fold — searches read as "no
        // results" even when the results card was populated below it.
        // refreshRecents() restores the strip when no results show.
        if (recentsLabel != null) recentsLabel.setVisibility(View.GONE);
        if (recentsRecycler != null) recentsRecycler.setVisibility(View.GONE);

        progress.setVisibility(ProgressBar.VISIBLE);
        searchButton.setEnabled(false);
        resultCard.setVisibility(LinearLayout.GONE);
        resetVerdictPanels();
        if (disambiguationCard != null) disambiguationCard.setVisibility(View.GONE);
        alternatesController.resetToggle();
        statusText.setText("Searching USDA FoodData Central…");
        statusText.setVisibility(View.VISIBLE);
        setSkeletonVisible(true);
        ingredientsText.setText("");

        new Thread(() -> {
            // Disk I/O off the UI thread.
            RecentSearches.add(MainActivity.this, product);
            try {
                // 1. Fetch primary product. A tapped USDA suggestion carries its
                //    fdcId, so fetch that exact record instead of re-running a
                //    fuzzy text search on the display label.
                final boolean fetchedById = fdcId > 0;
                final ProductResult primaryResult;
                final List<JSONObject> candidates;
                if (fetchedById) {
                    primaryResult = usdaApiClient.fetchFoodById(fdcId);
                    candidates = Collections.emptyList();
                } else {
                    UsdaApiClient.SearchOutcome outcome =
                            usdaApiClient.searchWithCandidates(product);
                    primaryResult = outcome.primary;
                    candidates = outcome.candidates;
                }
                runOnUiThread(() -> {
                    if (generation == searchGeneration) {
                        statusText.setText("Checking ingredients…");
                    }
                });

                // 2. Unbranded search intent: when the query does not name the product's
                //    brand, treat it as a category search and always show clean choices.
                //    An fdcId fetch is exempt: it only happens when the user tapped one
                //    specific product (a suggestion, or a clean-choice row via
                //    onOpenAlternate), so its verdict must render even when the
                //    product's name is a generic word like "Broccoli" — otherwise
                //    tapping that row buries the very verdict the user asked for.
                boolean categoryIntent = !fetchedById
                        && isCategorySearch(product, primaryResult);

                // 2b. Disambiguation for branded text searches: when the best
                // title match is strong but inexact, offer the top choices
                // instead of auto-picking one verdict. Tapped suggestions
                // (fdcId) and barcode scans already name an exact product.
                final List<ProductResult> disambiguationOptions;
                if (!fetchedById && !fromBarcode && !categoryIntent
                        && primaryResult.found) {
                    List<JSONObject> options = DisambiguationDecider.disambiguate(
                            product, candidates);
                    if (!options.isEmpty()) {
                        disambiguationOptions = new ArrayList<>();
                        for (JSONObject o : options) {
                            disambiguationOptions.add(
                                    usdaApiClient.productResultFor(o));
                        }
                    } else {
                        disambiguationOptions = null;
                    }
                } else {
                    disambiguationOptions = null;
                }
                if (disambiguationOptions != null) {
                    runOnUiThread(() -> {
                        if (generation != searchGeneration) return; // superseded
                        showDisambiguation(disambiguationOptions, product);
                    });
                    return;
                }

                // 3. Category: for a category search the category should represent the
                //    query, not the single top hit — one record can be miscategorized by
                //    USDA (e.g. a "BREAD" filed under "Cookies & Biscuits"), which would
                //    otherwise retitle and refilter the whole result set. Use the
                //    majority vote over the top results. For a branded search keep the
                //    product's own foodCategory; fall back to the rule-based classifier
                //    only when the API has none.
                String foodType;
                boolean apiCategory;
                if (categoryIntent && !TextUtils.isEmpty(primaryResult.queryCategory)) {
                    foodType = primaryResult.queryCategory;
                    apiCategory = true;
                } else {
                    foodType = primaryResult.foodCategory;
                    apiCategory = !TextUtils.isEmpty(foodType.trim());
                    if (!apiCategory) {
                        foodType = new RuleBasedFoodClassifier().classify(primaryResult.name);
                    }
                }
                if (TextUtils.isEmpty(foodType)) foodType = "Uncategorized";

                // 4. Nutrition analysis of the primary (Contract §5 step 3):
                //    the cohort is seeded from the search candidates (empty
                //    on the tapped-suggestion/FDC path, where the category
                //    top-up supplies the cohort instead) and the analyzer
                //    stores its result on primaryResult.nutritionAnalysis.
                //    Everything runs on this background thread; any failure
                //    degrades nutrition to UNAVAILABLE, never the search.
                NutritionAnalysis primaryAnalysis = null;
                List<CohortPeer> cohortPeers = Collections.emptyList();
                try {
                    List<CohortPeer> seedPeers = NutritionCohortService
                            .seedPeersFromCandidates(candidates, nutritionConfig());
                    NutritionCohortService.CohortResult cohortResult =
                            nutritionCohortService().analyzePrimaryWithCohort(
                                    primaryResult, seedPeers, foodType);
                    primaryAnalysis = cohortResult.analysis;
                    // Seeds + top-up peers actually collected: the
                    // finder rebuilds the final cohort from these,
                    // extended with its scanned pool (§5 step 5).
                    cohortPeers = cohortResult.peers;
                } catch (Exception ignored) {
                    // Best-effort only: a null analysis reads as UNAVAILABLE
                    // in the sourcing decision below.
                }

                // 5. Sourcing decision (Contract §5 step 4, PRD §9.1) from
                //    the ingredient verdict x nutrition status; category
                //    searches always source. This decision replaces the
                //    finder's old clean-primary early return.
                boolean ingredientClean = primaryResult.flagged == null
                        || primaryResult.flagged.isEmpty();
                boolean sourceAlternates = NutritionCohortService.shouldSource(
                        ingredientClean,
                        primaryAnalysis != null ? primaryAnalysis.status : null,
                        categoryIntent);

                // 6. Fetch clean candidates (+ which flagged categories
                //    blocked the rest), only when the decision sources.
                //    When it does not, an empty result flows to the UI call
                //    below so the alternates card hides exactly as it did
                //    for clean products under the old early return.
                AlternateSearchResult altSearch;
                if (sourceAlternates) {
                    runOnUiThread(() -> {
                        if (generation == searchGeneration) {
                            statusText.setText("Finding clean alternates…");
                        }
                    });
                    altSearch = alternateFinder.findCleanAlternates(
                            foodType, apiCategory, categoryIntent, primaryResult, product,
                            primaryAnalysis, cohortPeers, nutritionConfig());
                } else {
                    altSearch = new AlternateSearchResult();
                }
                // 6b. The finder refreshed the primary's analysis
                //     against the final cohort (§5 step 5): adopt it
                //     before UI binding so the verdict card, the
                //     alternates ranking context, and the LLM summary
                //     all read the final-cohort analysis.
                if (altSearch.primaryAnalysis != null) {
                    primaryAnalysis = altSearch.primaryAnalysis;
                    primaryResult.nutritionAnalysis = altSearch.primaryAnalysis;
                }
                List<ProductResult> rawAlternates = altSearch.alternates;
                // Candidate context for nutrition-aware alternates ranking
                // (Contract §7.2), set before the pool: the finder's
                // refreshed primary analysis when sourcing ran, else the
                // analysis stored on the primary, else the step-3 one.
                NutritionAnalysis contextAnalysis = altSearch.primaryAnalysis != null
                        ? altSearch.primaryAnalysis
                        : (primaryResult.nutritionAnalysis != null
                                ? primaryResult.nutritionAnalysis : primaryAnalysis);
                alternatesController.setCandidateContext(primaryResult,
                        contextAnalysis, nutritionConfig());
                alternatesController.setPool(rawAlternates, StringNormalizer.wordTokens(product),
                        categoryIntent, foodType, apiCategory);

                // 7. Send to UI (the controller ranks each stack itself)
                final String finalCategory = foodType;
                final boolean finalCategoryIntent = categoryIntent;
                final Set<String> finalFlaggedCategories = altSearch.flaggedCategories;
                final Map<String, Integer> finalReliefCounts = altSearch.filterBlockCounts;
                runOnUiThread(() -> {
                    if (generation != searchGeneration) return; // superseded
                    showResult(primaryResult, finalCategory, finalCategoryIntent,
                            finalFlaggedCategories, finalReliefCounts, fetchedById, true);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (generation != searchGeneration) return; // superseded
                    statusText.setText("Couldn't retrieve product information.");
                    ingredientsText.setText("Please check your internet connection and try again.");
                });
            } finally {
                runOnUiThread(() -> {
                    // Only the latest search may hide progress / re-enable the
                    // button — an older search finishing late must not undo
                    // the newer search's in-flight UI state.
                    if (generation != searchGeneration) return;
                    progress.setVisibility(ProgressBar.GONE);
                    setSkeletonVisible(false);
                    searchButton.setEnabled(true);
                });
            }
        }).start();
    }


    private void openShoppingSearch(String query) {
        com.barelabel.app.affiliate.AffiliateNavigator.openBuyForQuery(
                this, query, "search_box");
    }

    private void openShoppingSearch(ProductResult product, String source) {
        com.barelabel.app.images.ProductImageResolver.OffProductInfo off =
                com.barelabel.app.images.ProductImageResolver.getCached(
                        this, product == null ? "" : product.gtinUpc);
        com.barelabel.app.affiliate.AffiliateNavigator.openBuy(
                this, product, off, source);
    }

    /** Settings (wrench): let the user pick their preferred Buy retailer. */
    private void showRetailerSettings() {
        java.util.List<com.barelabel.app.affiliate.AffiliateProvider> providers =
                com.barelabel.app.affiliate.AffiliateManager.get(this).allProviders();
        String current = com.barelabel.app.affiliate.AffiliateConfig
                .getPreferredRetailer(this);
        CharSequence[] names = new CharSequence[providers.size()];
        int checked = 0;
        for (int i = 0; i < providers.size(); i++) {
            com.barelabel.app.affiliate.AffiliateProvider provider = providers.get(i);
            names[i] = provider.displayName()
                    + (provider.supportsDirectLinks() ? "" : " · Search only");
            if (provider.retailerId().equals(current)) checked = i;
        }
        // Note: no setMessage() here — AlertDialog drops the item list when
        // a message and items are combined, so the title carries the context.
        new android.app.AlertDialog.Builder(this)
                .setTitle("Preferred retailer for Buy links")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    String retailerId = providers.get(which).retailerId();
                    com.barelabel.app.affiliate.AffiliateConfig
                            .setPreferredRetailer(this, retailerId);
                    AnalyticsTracker.preferredRetailerChanged(retailerId);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    /**
     * Shows the disambiguation list for a branded text search whose best
     * title match was strong but inexact (see DisambiguationDecider).
     * Tapping a row searches that exact product name, which scores 100 and
     * renders its verdict directly through the normal flow.
     */
    private void showDisambiguation(List<ProductResult> options, String query) {
        if (statusText != null) statusText.setVisibility(View.GONE);
        setSkeletonVisible(false);
        if (resultCard != null) resultCard.setVisibility(View.GONE);
        alternatesController.hide();
        if (ingredientsCard != null) ingredientsCard.setVisibility(View.GONE);
        disambiguationList.removeAllViews();
        if (searchSafetyBinder != null) searchSafetyBinder.clearRows();
        disambiguationSubtitle.setText(
                "Top " + options.size() + " matches for \u201C" + query.trim() + "\u201D");
        for (ProductResult opt : options) {
            disambiguationList.addView(buildDisambiguationRow(opt));
        }
        disambiguationCard.setVisibility(View.VISIBLE);
    }

    private View buildDisambiguationRow(final ProductResult opt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        row.setPadding(pad, pad, pad, pad);
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(StringNormalizer.toTitleCase(
                opt.name == null ? "" : opt.name));
        name.setTextColor(Color.parseColor("#111827"));
        name.setTextSize(15f);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(name);

        String brand = opt.brand == null ? "" : opt.brand.trim();
        if (!brand.isEmpty()) {
            TextView brandView = new TextView(this);
            brandView.setText(StringNormalizer.toTitleCase(brand));
            brandView.setTextColor(Color.parseColor("#6B7280"));
            brandView.setTextSize(12f);
            brandView.setMaxLines(1);
            brandView.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(brandView);
        }
        row.addView(textCol);

        boolean isClean = opt.flagged == null || opt.flagged.isEmpty();
        int flaggedCount = opt.flagged == null ? 0 : opt.flagged.size();
        int categoryCount = (opt.matchResult != null
                && opt.matchResult.categoryMap != null)
                ? opt.matchResult.categoryMap.size() : 0;
        int score = CleanScoreCalculator.score(true, flaggedCount, categoryCount);

        LinearLayout metaCol = new LinearLayout(this);
        metaCol.setOrientation(LinearLayout.VERTICAL);
        metaCol.setGravity(Gravity.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(12);
        metaCol.setLayoutParams(metaLp);

        TextView pill = new TextView(this);
        pill.setText(isClean ? "\u2713 CLEAN" : "\u26A0 DIRTY");
        pill.setTextColor(isClean ? C_GREEN_800 : C_RED_800);
        pill.setBackgroundResource(isClean
                ? R.drawable.chip_clean_background
                : R.drawable.chip_dirty_background);
        pill.setTextSize(11f);
        pill.setTypeface(null, Typeface.BOLD);
        int pillH = dp(3), pillW = dp(8);
        pill.setPadding(pillW, pillH, pillW, pillH);
        // NA-05 (§6.3): the pill sits in a horizontal pill row so the
        // recall badge can insert beside it when alerts arrive; with
        // no badge the row renders exactly as the bare pill did.
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.CENTER_VERTICAL);
        pillRow.addView(pill);
        metaCol.addView(pillRow);
        if (searchSafetyBinder != null) {
            searchSafetyBinder.registerRow(pillRow, opt.brand, opt.name);
        }

        TextView scoreView = new TextView(this);
        scoreView.setText(score + "/100");
        scoreView.setTextColor(Color.parseColor("#374151"));
        scoreView.setTextSize(13f);
        scoreView.setTypeface(null, Typeface.BOLD);
        scoreView.setGravity(Gravity.END);
        LinearLayout.LayoutParams scoreLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scoreLp.topMargin = dp(4);
        scoreView.setLayoutParams(scoreLp);
        metaCol.addView(scoreView);
        row.addView(metaCol);

        row.setOnClickListener(v -> {
            disambiguationCard.setVisibility(View.GONE);
            searchBox.setText(opt.name);
            search();
        });
        return row;
    }

    private void showResult(ProductResult result, String foodType, boolean categoryIntent,
                            Set<String> flaggedCategories, Map<String, Integer> reliefCounts,
                            boolean fetchedById, boolean saveToHistory) {
        currentPrimaryResult = result;
        clearComparePicks();
        resetVerdictPanels();
        if (disambiguationCard != null) disambiguationCard.setVisibility(View.GONE);
        if (statusText != null) statusText.setVisibility(View.GONE);
        setSkeletonVisible(false);
    // Unbranded category search (e.g. "cookies"): the top hit is just one random
    // branded product in the category, not what the user asked about, so hide the
    // primary verdict card and show only the clean-choices card — but only when
    // the clean-choices card has something to show. When it comes back empty
    // (produce searches like "broccoli": the candidates scatter across USDA
    // categories and no alternate survives ranking), fall through and show the
    // primary verdict instead of leaving the user with no result at all.
    boolean hidePrimary = CategorySearchDecider.hidePrimaryCard(
            categoryIntent, alternatesController.displayableCount());
    if (hidePrimary) {
        if (resultCard != null) resultCard.setVisibility(View.GONE);
        alternatesController.show(foodType, true, true, flaggedCategories,
                countActiveFilters(), reliefCounts);
        updateSourceLine();
        updateStickyBar();
        return;
    }

    resultCard.setVisibility(View.VISIBLE);
    // Verdict reveal: quick fade + rise so the verdict lands as a moment.
    resultCard.setAlpha(0f);
    resultCard.setTranslationY(dp(12));
    resultCard.animate().alpha(1f).translationY(0f).setDuration(250).start();
    bindFlaggedList(null, true);

    int colorClean = C_GREEN_800;
    int colorDirty = C_RED_800;
    int colorMuted = C_GRAY_600;

    // Hide the separate primary ingredients card entirely
    if (ingredientsCard != null) {
        ingredientsCard.setVisibility(View.GONE);
    }

    Button buyButton = findViewById(R.id.walmartButton);
    View flaggedTitle = findViewById(R.id.flaggedTitle);
    android.widget.ImageButton shareVerdictButton =
            findViewById(R.id.shareVerdictButton);

    // Bind or dynamic click listener for viewing ingredients inside the verdict card
    Button viewIngredientsButton = findViewById(R.id.viewIngredientsButton);
    if (viewIngredientsButton != null) {
        if (result != null && !TextUtils.isEmpty(result.ingredients)) {
            viewIngredientsButton.setVisibility(View.VISIBLE);
            viewIngredientsButton.setOnClickListener(v -> showFullIngredientsDialog(result));
        } else {
            viewIngredientsButton.setVisibility(View.GONE);
        }
    }

    // Handle case where product was not found
    if (result == null || !result.found) {
        if (cleanScoreView != null) {
            cleanScoreView.setScore(CleanScoreCalculator.UNAVAILABLE);
        }
        if (matchNoticeText != null) matchNoticeText.setVisibility(View.GONE);
        if (productTitleText != null) productTitleText.setText("No Matching Product");
        if (productImageView != null) productImageView.setVisibility(View.GONE);
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        showVerdictTextState();
        verdictText.setText("? PRODUCT NOT FOUND");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);
        if (explanationText != null) explanationText.setVisibility(View.GONE);
        if (buyButton != null) buyButton.setVisibility(View.GONE);
        if (shareVerdictButton != null) shareVerdictButton.setVisibility(View.GONE);
        clearVerdictChips();
        alternatesController.hide();
        return;
    }

    String displayName = result.name;
    if (!TextUtils.isEmpty(result.brand)) {
        displayName += " (" + result.brand + ")";
    }
    if (productTitleText != null) {
        productTitleText.setText(StringNormalizer.toTitleCase(displayName));
        productTitleText.setVisibility(View.VISIBLE);
    }
    // Product thumbnail + friendlier OFF name (conditional: hidden/absent when unavailable).
    final android.widget.TextView affiliateDisclosureView =
            findViewById(R.id.affiliateDisclosure);
    if (productImageView != null) {
        productImageView.setVisibility(View.GONE);
        productImageView.setTag(result.gtinUpc);
        final String verdictGtin = result.gtinUpc;
        final ProductResult verdictResult = result;
        // Warm the memory cache from disk so the Buy flow's synchronous
        // getCached() hits even before the async resolve() below completes.
        com.barelabel.app.images.ProductImageResolver.warmFromDisk(this, verdictGtin);
        com.barelabel.app.images.ProductImageResolver.resolve(
                this, verdictGtin, info -> {                    if (!java.util.Objects.equals(verdictGtin, productImageView.getTag())) return;
                    if (info == null) return;
                    if (info.hasImage()) {
                        productImageView.setVisibility(View.VISIBLE);
                        com.bumptech.glide.Glide.with(this)
                                .load(info.imageUrl)
                                .centerCrop()
                                .into(productImageView);
                    }
                    if (info.hasName() && productTitleText != null) {
                        productTitleText.setText(
                                StringNormalizer.toTitleCase(info.displayName()));
                    }
                    // OFF data arrived after bind: re-evaluate the FTC
                    // disclosure, which may have been decided on a cold cache.
                    updateAffiliateDisclosure(verdictResult, info,
                            affiliateDisclosureView, buyButton);
                });
    }

    String userQuery = normalizeForMatch(searchBox.getText().toString());
    String resultName = normalizeForMatch(result.name);
    // Match either way: the query may carry extras (brand in parens,
    // recent-scan title) the USDA name lacks, or be a shorter substring.
    boolean isExactMatch = !userQuery.isEmpty() &&
            (resultName.contains(userQuery) || userQuery.contains(resultName));

    if (matchNoticeText != null) {
        if (!isExactMatch) {
            matchNoticeText.setText("No exact match found in USDA. Showing result for " + result.name);
            matchNoticeText.setVisibility(View.VISIBLE);
        } else {
            matchNoticeText.setVisibility(View.GONE);
        }
    }

    // Handle missing/empty ingredients
    if (TextUtils.isEmpty(result.ingredients.trim())) {
        if (cleanScoreView != null) {
            cleanScoreView.setScore(CleanScoreCalculator.UNAVAILABLE);
        }
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        showVerdictTextState();
        verdictText.setText("? INGREDIENTS UNAVAILABLE");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);
        if (explanationText != null) explanationText.setVisibility(View.GONE);
        if (buyButton != null) buyButton.setVisibility(View.GONE);
        if (shareVerdictButton != null) shareVerdictButton.setVisibility(View.GONE);
        clearVerdictChips();
        alternatesController.hide();
        return;
    }

    boolean isClean = result.flagged == null || result.flagged.isEmpty();

    // Clean Score next to the binary verdict (degrees for comparisons).
    int flaggedCount = result.flagged == null ? 0 : result.flagged.size();
    int categoryCount = (result.matchResult != null
            && result.matchResult.categoryMap != null)
            ? result.matchResult.categoryMap.size() : 0;
    int cleanScore = CleanScoreCalculator.score(true, flaggedCount, categoryCount);
    // Fixed top block (Contract §7.1): the ring is now the Overall
    // Score and the strip shows both component scores, bound once here.
    bindVerdictTopBlock(result, cleanScore);

    // Persist this scan to history (deduplicated, newest-first), unless the
    // caller opted out (OCR label scans are transient reads, not products).
    // Disk I/O off the UI thread.
    if (saveToHistory) {
        final ProductResult scannedResult = result;
        new Thread(() -> ScanHistoryRepository.saveScan(MainActivity.this, scannedResult)).start();
    }

    // 1. BUY Button Visibility (+ FTC disclosure shown with it)
    if (buyButton != null) {
        if (isClean) {
            buyButton.setVisibility(View.VISIBLE);
            buyButton.setText("BUY!");
            buyButton.setOnClickListener(v -> {
                AnalyticsTracker.buyTapped("primary_product");
                openShoppingSearch(result, "primary_product");
            });
        } else {
            buyButton.setVisibility(View.GONE);
        }
        // Immediate pass on whatever the memory cache holds (may be cold —
        // the resolve() callback above re-evaluates once OFF data arrives).
        updateAffiliateDisclosure(result,
                com.barelabel.app.images.ProductImageResolver.getCached(
                        this, result == null ? "" : result.gtinUpc),
                affiliateDisclosureView, buyButton);
    }

    // 1b. Share button: system share sheet with verdict + deep link.
    if (shareVerdictButton != null) {
        shareVerdictButton.setVisibility(View.VISIBLE);
        final ProductResult shareResult = result;
        shareVerdictButton.setOnClickListener(v -> {
            AnalyticsTracker.productShared("verdict_card");
            com.barelabel.app.sharing.ShareHelper.shareProduct(
                    MainActivity.this, shareResult);
        });
    }

    // 2. Verdict Card Styling & Flagged List Output
    // Paid users get the LLM verdict explanation in-card, replacing the
    // per-row expandable rule-based reasons; free users keep the rows.
    boolean premium = PremiumGates.isOcrScanEnabled(this);
    if (isClean) {
        resultCard.setBackgroundResource(R.drawable.verdict_clean);
        // Ingredients detail panel (below the bubbles) shows the
        // no-flagged message for a CLEAN product (Contract §7.1).
        if (flaggedTitle != null) {
            ((TextView) flaggedTitle).setText("Ingredient check");
            ((TextView) flaggedTitle).setTextColor(colorClean);
            flaggedTitle.setVisibility(View.VISIBLE);
        }
        if (flaggedText != null) {
            flaggedText.setText("No flagged ingredients. Every ingredient"
                    + " passed the filters you selected.");
            flaggedText.setTextColor(colorMuted);
            flaggedText.setVisibility(View.VISIBLE);
        }
        bindFlaggedList(null, !premium);
    } else {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        if (flaggedTitle != null) {
            ((TextView) flaggedTitle).setText("Flagged Ingredients");
            ((TextView) flaggedTitle).setTextColor(colorDirty);
            flaggedTitle.setVisibility(View.VISIBLE);
        }
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);

        // Expandable per-ingredient rows with plain-English reasons
        // (free users; premium gets the LLM explanation instead).
        bindFlaggedList(result, !premium);
    }
    bindVerdictExplanation(result, isClean, cleanScore, premium);

    // 3. Verdict bubbles + nutrition panel (Contract §7.1): these
    //    replace the large verdictText and the per-category verdict
    //    chips for found products — the chips are no longer rendered.
    bindVerdictBubblesAndPanels(result, isClean);

    // 3b. Safety reports section (NA-06): async, appended below the
    //     BUY! button; inserts only when alerts arrive (§6.5).
    loadSafetyReports(result, foodType);

    // 4. Clean Alternates Section. hidePrimary (not the raw category
    //    flag): a category search that fell through to the primary
    //    verdict renders this card in branded mode, so its empty state
    //    reads "for this item" instead of naming the category.
    alternatesController.show(foodType, hidePrimary, isClean, flaggedCategories,
            countActiveFilters(), reliefCounts);
    updateSourceLine();
    updateStickyBar();
}

/** Cached flagged-category names from flagged_ingredients.json. */
private List<String> getFlaggedCategories() {
    if (flaggedCategoriesCache == null) {
        flaggedCategoriesCache = new ArrayList<>();
        try (InputStream is = getAssets().open("flagged_ingredients.json");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            JSONObject rootJson = new JSONObject(jsonBuilder.toString());
            Iterator<String> keys = rootJson.keys();
            while (keys.hasNext()) {
                flaggedCategoriesCache.add(keys.next());
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to load flagged categories", e);
        }
    }
    return flaggedCategoriesCache;
}

/** Number of currently enabled flagged-category filters. */
private int countActiveFilters() {
    int count = 0;
    if (categoryCheckboxContainer != null) {
        for (int i = 0; i < categoryCheckboxContainer.getChildCount(); i++) {
            View child = categoryCheckboxContainer.getChildAt(i);
            if (child instanceof CheckBox && ((CheckBox) child).isChecked()) {
                count++;
            }
        }
    }
    return count;
}

private void updateFilterSummary() {
    if (filterSummaryText == null) return;
    int active = countActiveFilters();
    filterSummaryText.setText("Filters • " + active + " active");
}

/** Disables every flagged-category filter and re-runs the current search. */
private void clearAllFiltersAndSearch() {
    if (categoryCheckboxContainer != null) {
        for (int i = 0; i < categoryCheckboxContainer.getChildCount(); i++) {
            View child = categoryCheckboxContainer.getChildAt(i);
            if (child instanceof CheckBox) {
                ((CheckBox) child).setChecked(false);
            }
        }
    }
    search();
}

/** Unchecks one filter category (smart relief) — the checkbox listener persists it. */
private void uncheckFilterCategory(String category) {
    if (categoryCheckboxContainer == null || category == null) return;
    for (int i = 0; i < categoryCheckboxContainer.getChildCount(); i++) {
        View child = categoryCheckboxContainer.getChildAt(i);
        if (child instanceof CheckBox
                && category.equals(((CheckBox) child).getText().toString())) {
            ((CheckBox) child).setChecked(false);
            return;
        }
    }
}

/**
 * FTC disclosure visibility for the verdict card's Buy button. Shown only
 * when the item itself has affiliate links, not for plain search-fallback
 * Buy links. Called twice per bind: immediately on the (possibly cold)
 * memory cache, and again when the async OFF resolve delivers — so the
 * disclosure corrects itself once product data arrives.
 */
private void updateAffiliateDisclosure(ProductResult result,
        com.barelabel.app.images.ProductImageResolver.OffProductInfo off,
        android.widget.TextView disclosureView, android.widget.Button buyButton) {
    if (disclosureView == null || buyButton == null) return;
    boolean buyVisible = buyButton.getVisibility() == View.VISIBLE;
    boolean hasAffiliate = buyVisible
            && com.barelabel.app.affiliate.AffiliateNavigator
                    .hasAffiliateLinks(this, result, off);
    disclosureView.setVisibility(hasAffiliate ? View.VISIBLE : View.GONE);
}

/**
 * One expandable row per flagged ingredient: tap to reveal the plain-English
 * reason for its category. Replaces the old static grouped text dump.
 */
private void bindFlaggedList(ProductResult result, boolean expandable) {
    if (flaggedList == null) return;
    flaggedList.removeAllViews();
    if (result == null || result.matchResult == null
            || !result.matchResult.hasMatches()) {
        flaggedList.setVisibility(View.GONE);
        return;
    }
    boolean any = false;
    for (Map.Entry<String, List<String>> entry : result.matchResult.categoryMap.entrySet()) {
        String category = entry.getKey();
        for (String ingredient : entry.getValue()) {
            flaggedList.addView(expandable
                    ? buildFlaggedRow(ingredient, category)
                    : buildFlaggedChip(ingredient));
            any = true;
        }
    }
    flaggedList.setVisibility(any ? View.VISIBLE : View.GONE);
}

/**
 * Compact wrap-content chip for premium users: text width plus padding so
 * several fit per row. No expansion; the in-card LLM explanation covers
 * the "why".
 */
private View buildFlaggedChip(String ingredient) {
    TextView chip = new TextView(this);
    chip.setText("⚠ " + ingredient);
    chip.setTextSize(13f);
    chip.setTypeface(chip.getTypeface(), Typeface.BOLD);
    chip.setTextColor(C_RED_900);
    chip.setBackgroundResource(R.drawable.bg_search_box);
    chip.setPadding(dp(12), dp(6), dp(12), dp(6));
    FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, dp(4), dp(8), dp(4));
    chip.setLayoutParams(lp);
    return chip;
}

/** A single tappable flagged-ingredient row with a collapsible reason. */
private View buildFlaggedRow(final String ingredient, final String category) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(12), dp(8), dp(12), dp(8));
    row.setBackgroundResource(R.drawable.bg_search_box);
    FlexboxLayout.LayoutParams rowParams = new FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rowParams.topMargin = dp(6);
    rowParams.setFlexBasisPercent(1.0f);
    row.setLayoutParams(rowParams);
    row.setClickable(true);
    row.setFocusable(true);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);

    TextView name = new TextView(this);
    name.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    name.setText("⚠ " + ingredient);
    name.setTextSize(14f);
    name.setTypeface(name.getTypeface(), Typeface.BOLD);
    name.setTextColor(C_RED_900);
    header.addView(name);

    final TextView chevron = new TextView(this);
    chevron.setText("›");
    chevron.setTextSize(18f);
    chevron.setTextColor(C_GRAY_600);
    header.addView(chevron);
    row.addView(header);

    final TextView reason = new TextView(this);
    reason.setText(IngredientExplainer.reasonFor(category)
            + "\nFlagged under: " + category);
    reason.setTextSize(13f);
    reason.setTextColor(C_GRAY_600);
    reason.setPadding(0, dp(4), 0, 0);
    reason.setVisibility(View.GONE);
    row.addView(reason);

    row.setOnClickListener(v -> {
        boolean expand = reason.getVisibility() != View.VISIBLE;
        reason.setVisibility(expand ? View.VISIBLE : View.GONE);
        chevron.setText(expand ? "⌄" : "›");
    });
    return row;
}

/**
 * LLM verdict explanation for premium users, shown inside the verdict card.
 * Free users keep the per-row expandable rule-based reasons and never see
 * this view. Fetched async; a stale result (user moved on) is dropped.
 */
private void bindVerdictExplanation(ProductResult result, boolean isClean,
                                    int cleanScore, boolean premium) {
    if (explanationText == null) {
        return;
    }
    if (!premium) {
        explanationText.setVisibility(View.GONE);
        return;
    }
    explanationText.setVisibility(View.VISIBLE);
    explanationText.setText("Explaining…");
    final ProductResult forResult = result;
    final int score = cleanScore;
    // NUT-10: nutrition-aware explanation inputs. The analysis rides on
    // the result being bound (null until the orchestration in NUT-05
    // has run); the config load is a cached singleton. When the config
    // is unavailable the overall score is just the ingredient score —
    // OverallScoreCalculator.blend documents the same null behavior.
    final NutritionAnalysis analysis = result != null
            ? result.nutritionAnalysis : null;
    final NutritionConfig nutritionConfig = NutritionConfigLoader.load(this);
    final int overallScore = nutritionConfig == null ? cleanScore
            : OverallScoreCalculator.blend(cleanScore,
                    analysis != null ? analysis.nutritionScore : null,
                    nutritionConfig);
    new Thread(() -> {
        List<VerdictExplainer.Flag> flags = new ArrayList<>();
        String ingredients = "";
        String allergen = "";
        if (forResult != null) {
            ingredients = forResult.ingredients == null
                    ? "" : forResult.ingredients;
            allergen = extractAllergenAdvice(ingredients);
            if (forResult.matchResult != null
                    && forResult.matchResult.categoryMap != null) {
                for (Map.Entry<String, List<String>> e
                        : forResult.matchResult.categoryMap.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    for (String ing : e.getValue()) {
                        flags.add(new VerdictExplainer.Flag(ing, e.getKey()));
                    }
                }
            }
        }
        String explanation = VerdictExplainer.explain(forResult, score,
                analysis, overallScore, flags, ingredients,
                enabledFilterNames(), superiorIn(ingredients), allergen,
                nutritionConfig);
        runOnUiThread(() -> {
            if (currentPrimaryResult != forResult) {
                return; // stale: user moved on
            }
            if (explanation != null && !explanation.isEmpty()) {
                explanationText.setText(explanation);
            } else {
                explanationText.setVisibility(View.GONE);
            }
        });
    }).start();
}

/** Names of the user's currently enabled flagged-category filters. */
private List<String> enabledFilterNames() {
    List<String> out = new ArrayList<>();
    for (String category : getFlaggedCategories()) {
        if (CategoryPreferenceManager.isCategoryEnabled(this, category)) {
            out.add(category);
        }
    }
    return out;
}

/** Superior ingredients present in the ingredient list (deduped). */
private List<String> superiorIn(String ingredients) {
    List<String> out = new ArrayList<>();
    if (ingredients == null) {
        return out;
    }
    for (String token : ingredients.split(",")) {
        String t = token.trim();
        if (!t.isEmpty()
                && FlaggedIngredientManager.isSuperiorIngredient(this, t)
                && !out.contains(t)) {
            out.add(t);
        }
    }
    return out;
}

/** Best-effort extraction of an "allergen advice" sentence, if present. */
private String extractAllergenAdvice(String ingredients) {
    if (ingredients == null) {
        return "";
    }
    java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?i)allergen advice[^.]*\\.?")
            .matcher(ingredients);
    return m.find() ? m.group().trim() : "";
}

/**
 * Normalizes a product name for match comparison: lowercase, drops
 * parenthetical suffixes (brand extras, truncated "(BACI…"), strips
 * punctuation, collapses whitespace. Used to decide whether the USDA
 * result is what the user asked for.
 */
private String normalizeForMatch(String s) {
    if (s == null) {
        return "";
    }
    String out = s.toLowerCase(Locale.US);
    out = out.replaceAll("\\(.*", " "); // drop "(" and everything after
    out = out.replaceAll("[^a-z0-9 ]", " ");
    return out.replaceAll("\\s+", " ").trim();
}

/**
 * Per-category pass/fail chips under the verdict: green "✓ No X" for enabled
 * categories with no flagged match, red "⚠ X" for the ones that fired.
 */
/**
 * Clears and hides the legacy verdict-chips container. The NUT-08
 * verdict card (bubbles + detail panels) replaced the category chips,
 * so the container is never populated anymore; the predecessor
 * bindVerdictChips(ProductResult) was only ever called with null
 * after that rework, and its chip-building branch was removed in
 * NUT-11 as provably dead (private method, two call sites, both
 * passing the null literal).
 */
private void clearVerdictChips() {
    if (verdictChipsContainer == null) return;
    verdictChipsContainer.removeAllViews();
    verdictChipsContainer.setVisibility(View.GONE);
}

/** Shows/hides the pulsing skeleton card displayed while a search runs. */
private void setSkeletonVisible(boolean show) {
    if (skeletonCard == null) return;
    if (skeletonPulse != null) {
        skeletonPulse.cancel();
        skeletonPulse = null;
    }
    skeletonCard.setVisibility(show ? View.VISIBLE : View.GONE);
    if (show) {
        skeletonPulse = android.animation.ObjectAnimator.ofFloat(
                skeletonCard, "alpha", 0.35f, 1f);
        skeletonPulse.setDuration(700);
        skeletonPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        skeletonPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        skeletonPulse.start();
    } else {
        skeletonCard.setAlpha(1f);
    }
}

/** "USDA FoodData Central • N clean picks shown" provenance line. */
private void updateSourceLine() {
    TextView sourceText = findViewById(R.id.sourceText);
    if (sourceText == null) return;
    int shown = alternatesController.getExactCount()
            + alternatesController.getMoreCount();
    if (!alternatesController.isCardShowing() || shown == 0) {
        sourceText.setVisibility(View.GONE);
        return;
    }
    sourceText.setText("USDA FoodData Central • " + shown + " clean picks shown");
    sourceText.setVisibility(View.VISIBLE);
}

/** Refreshes the sticky results bar from the alternates controller's state. */
private void updateStickyBar() {
    if (stickyResultsBar == null || stickyResultsText == null) return;
    if (!alternatesController.isCardShowing()) {
        stickyResultsBar.setVisibility(View.GONE);
        return;
    }
    stickyResultsText.setText(alternatesController.getHeading()
            + " • Exact (" + alternatesController.getExactCount() + ")"
            + " • More (" + alternatesController.getMoreCount() + ")");
}

/** Key identifying a compare pick (verdict card or alternate row). */
private String compareKey(ProductResult p) {
    String n = p.name == null ? "" : p.name.trim().toLowerCase(Locale.US);
    String b = p.brand == null ? "" : p.brand.trim().toLowerCase(Locale.US);
    return n + "|" + b;
}

/** Toggle a search result (verdict card or alternate row) in the compare picks (max 2). */
private void toggleComparePick(ProductResult p) {
    if (p == null) return;
    String key = compareKey(p);
    if (comparePicks.containsKey(key)) {
        comparePicks.remove(key);
    } else if (comparePicks.size() >= 2) {
        Toast.makeText(this, "You can compare at most 2 products",
                Toast.LENGTH_SHORT).show();
    } else {
        comparePicks.put(key, p);
    }
    syncCompareBoxes();
    if (comparePicks.size() == 2) {
        openCompareForPicks();
    }
}

/** Re-check every compare box from the pick set (call after any toggle/clear). */
private void syncCompareBoxes() {
    syncingCompareUi = true;
    try {
        if (verdictCompareBox != null) {
            verdictCompareBox.setChecked(currentPrimaryResult != null
                    && comparePicks.containsKey(compareKey(currentPrimaryResult)));
        }
        if (alternatesController != null) {
            alternatesController.syncCompareBoxes();
        }
    } finally {
        syncingCompareUi = false;
    }
}

private void clearComparePicks() {
    if (compareTabController != null) compareTabController.clearExternal();
    if (comparePicks.isEmpty()) return;
    comparePicks.clear();
    syncCompareBoxes();
}

/** Save a search result to history. Returns true when actually saved. */
private boolean saveProductToHistory(ProductResult p) {
    if (p == null || !p.found || TextUtils.isEmpty(p.ingredients)
            || p.ingredients.trim().isEmpty()) {
        return false;
    }
    // Disk I/O off the UI thread; the toast confirms intent, not completion.
    new Thread(() -> ScanHistoryRepository.saveScan(MainActivity.this, p)).start();
    Toast.makeText(this, "Saved to history", Toast.LENGTH_SHORT).show();
    return true;
}

private boolean saveScannedToHistory(ScannedProduct p) {
    if (p == null) return false;
    ProductResult pr = new ProductResult();
    pr.found = true;
    pr.name = p.name;
    pr.brand = p.brand;
    pr.ingredients = p.ingredients;
    if (p.flagged != null) pr.flagged.addAll(p.flagged);
    pr.gtinUpc = p.gtin == null ? "" : p.gtin;
    boolean saved = saveProductToHistory(pr);
    if (saved) AnalyticsTracker.productSaved("compare");
    return saved;
}

/** Two picks made: pin the pair on the Compare tab and switch to it. */
private void openCompareForPicks() {
    if (comparePicks.size() != 2 || compareTabController == null) return;
    List<ProductResult> picked = new ArrayList<>(comparePicks.values());
    compareTabController.compareExternal(
            ScannedProduct.fromProductResult(picked.get(0)),
            ScannedProduct.fromProductResult(picked.get(1)));
    if (bottomNav != null) {
        if (bottomNav.getSelectedItemId() == R.id.nav_compare) {
            compareTabController.refresh();
        } else {
            // Selecting fires the nav listener -> showTab(1) -> refresh.
            bottomNav.setSelectedItemId(R.id.nav_compare);
        }
    }
}

/** Switch the visible tab pane: 0 = Search, 1 = Compare, 2 = History. */
private void showTab(int position, View searchContent) {
    searchContent.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
    if (compareTabContent != null) {
        compareTabContent.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
    }
    if (historyTabContent != null) {
        historyTabContent.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
    }
    // The sticky results bar only belongs to the search tab.
    if (stickyResultsBar != null && position != 0) {
        stickyResultsBar.setVisibility(View.GONE);
    }
    if (position == 1 && compareTabController != null) {
        AnalyticsTracker.compareTabOpened();
        compareTabController.refresh();
    }
    if (position == 2 && historyTabController != null) {
        AnalyticsTracker.historyOpened();
        historyTabController.refresh();
    }
}

private int dp(int dps) {
    return Math.round(dps * getResources().getDisplayMetrics().density);
}

private void showFullIngredientsDialog(ProductResult product) {
    String title = product.name;
    if (!TextUtils.isEmpty(product.brand)) {
        title += " (" + product.brand + ")";
    }
    ProductDetailDialog.show(this, title, product.ingredients, product.flagged);
}



private void maybeShowOnboarding() {
    final SharedPreferences prefs =
            getSharedPreferences("barelabel_onboarding", MODE_PRIVATE);
    if (prefs.getBoolean("onboarded_v1", false)) return;

    final List<String> categories = getFlaggedCategories();
    final List<AvoidListPresets.Preset> presets = AvoidListPresets.presets();
    CharSequence[] labels = new CharSequence[presets.size()];
    for (int i = 0; i < presets.size(); i++) {
        AvoidListPresets.Preset p = presets.get(i);
        labels[i] = p.label + " — " + p.description;
    }
    new AlertDialog.Builder(this)
            .setTitle("What do you avoid?")
            .setSingleChoiceItems(labels, 0, null)
            .setPositiveButton("Save", (dialog, which) -> {
                int selected = ((AlertDialog) dialog).getListView()
                        .getCheckedItemPosition();
                applyOnboardingPreset(
                        presets.get(Math.max(0, selected)), categories);
                prefs.edit().putBoolean("onboarded_v1", true).apply();
            })
            .setNegativeButton("Skip", (dialog, which) ->
                    prefs.edit().putBoolean("onboarded_v1", true).apply())
            .show();
}

/** Applies a first-run preset: stores the category toggles and rebuilds the filter panel. */
private void applyOnboardingPreset(AvoidListPresets.Preset preset,
                                   List<String> categories) {
    Set<String> enabled = AvoidListPresets.enabledFor(preset, categories);
    for (String category : categories) {
        boolean on = enabled.contains(category);
        CategoryPreferenceManager.setCategoryEnabled(this, category, on);
        AnalyticsTracker.categoryToggled(category, on);
    }
    setupCategoryFilterPanel();
    updateFilterSummary();
}



    private void openBarcodeScanner() {
        Intent intent = new Intent(this, BarcodeScannerActivity.class);
        barcodeLauncher.launch(intent);
    }

    /** Paywall for the OCR scanner. "Get Premium" simulates the purchase for now. */
    private void showOcrPaywall() {
        new AlertDialog.Builder(this)
                .setTitle("Premium feature")
                .setMessage("The ingredient label scanner is a BareLabel Premium feature.\n\nOne-time lifetime unlock.")
                .setPositiveButton("Get Premium", (dialog, which) -> {
                    PremiumGates.setOcrScanUnlocked(this, true);
                    Toast.makeText(this, "Premium unlocked", Toast.LENGTH_SHORT).show();
                    showLabelScanChooser();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Chooser for the ingredient-label scan: camera capture or gallery photo. */
    private void showLabelScanChooser() {
        new AlertDialog.Builder(this)
                .setTitle("Scan ingredient label")
                .setItems(new String[]{"Take photo", "Choose from gallery"},
                        (dialog, which) -> {
                            if (which == 0) {
                                ingredientScanLauncher.launch(
                                        new Intent(this, IngredientScanActivity.class));
                            } else {
                                galleryLauncher.launch("image/*");
                            }
                        })
                .show();
    }

    /** OCRs a gallery image with ML Kit, then runs the scanned-label pipeline. */
    private void ocrGalleryImage(Uri uri) {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Preparing scanner…", Toast.LENGTH_SHORT).show();
        OcrModelManager.ensureReady(this, new OcrModelManager.Listener() {
            @Override
            public void onPreparing(long bytesDownloaded, long totalBytes) {
                // Spinner already visible; nothing more to show here.
            }

            @Override
            public void onReady(TextRecognizer recognizer) {
                runGalleryOcr(uri, recognizer);
            }

            @Override
            public void onError() {
                runOnUiThread(() -> {
                    if (progress != null) progress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            "Couldn't prepare the scanner. Check your connection and try again.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Runs ML Kit OCR on a gallery image; the model is already ensured. */
    private void runGalleryOcr(Uri uri, TextRecognizer recognizer) {
        new Thread(() -> {
            android.graphics.Bitmap enhanced =
                    OcrPreprocessor.fromGalleryUri(getContentResolver(), uri);
            if (enhanced == null) {
                recognizer.close();
                runOnUiThread(() -> {
                    if (progress != null) progress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            "Couldn't open that photo.",
                            Toast.LENGTH_SHORT).show();
                });
                return;
            }
            try {
                InputImage image = InputImage.fromBitmap(enhanced, 0);
                recognizer.process(image)
                        .addOnSuccessListener(visionText -> {
                            recognizer.close();
                            String text = IngredientSectionExtractor.extract(
                                    OcrModelManager.toOcrLines(visionText)).trim();
                            if (text.replaceAll("\\s+", "").length() >= 15) {
                                onIngredientTextScanned(text);
                            } else {
                                runOnUiThread(() -> {
                                    if (progress != null)
                                        progress.setVisibility(View.GONE);
                                    Toast.makeText(MainActivity.this,
                                            "No text found in that photo.",
                                            Toast.LENGTH_SHORT).show();
                                });
                            }
                        })
                        .addOnFailureListener(e -> {
                            recognizer.close();
                            runOnUiThread(() -> {
                                if (progress != null)
                                    progress.setVisibility(View.GONE);
                                Toast.makeText(MainActivity.this,
                                        "Couldn't read that photo.",
                                        Toast.LENGTH_SHORT).show();
                            });
                        })
                        .addOnCompleteListener(task -> enhanced.recycle());
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (progress != null) progress.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            "Couldn't open that photo.",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * Server BERT spellcheck toggle. Experiment 2026-10-14: with the flag
     * off, the local fixes (input upscaling + OCR confusion matrix) fully
     * remedied the "AM purpose Fou" phrase-fragmentation class, but
     * word-level typos on non-flagged words ("Sugr", "fecilty", "esame")
     * still needed the server's 50k vocabulary. Re-enabled: local handles
     * phrases, server handles the long tail of word typos.
     */
    private static final boolean USE_SERVER_SPELLCHECK = true;

    /**
     * LLM contextual spellcheck (2026-10-15): final step of the
     * OCR -&gt; rudimentary spellcheck -&gt; LLM spellcheck pipeline.
     * Validated against device OCR output with qwen/qwen3.8-27b on the Groq
     * free tier. Null-safe: without GROQ_API_KEY the step is a no-op.
     */
    private static final boolean USE_LLM_SPELLCHECK = true;

    /**
     * Scan -> verdict pipeline for OCR'd ingredient text (camera or gallery).
     * No confirmation screen: OcrCorrector fixes OCR misspellings against the
     * flagged-ingredient dictionary before the verdict runs.
     */
    private void onIngredientTextScanned(String ocrText) {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (statusText != null) {
            statusText.setText("Checking ingredients\u2026");
            statusText.setVisibility(View.VISIBLE);
        }
        new Thread(() -> {
            Set<String> dictWords = new HashSet<>();
            Set<String> multiWordTerms = new HashSet<>();
            for (String term : FlaggedIngredientManager.getFlaggedTerms(
                    MainActivity.this)) {
                String norm = term.toLowerCase(Locale.US).trim()
                        .replaceAll("\\s+", " ");
                if (norm.contains(" ")) {
                    multiWordTerms.add(norm);
                }
                for (String w : norm.split("[^a-z]+")) {
                    if (!w.isEmpty()) {
                        dictWords.add(w);
                    }
                }
            }
            // 1. Phrase-level first (most context): fixes multi-word terms
            //    like "AM purpose Fou" -> "all purpose flour" before any
            //    word-level step can fragment them ("AM" -> "ham").
            String phraseFixed = OcrCorrector.correctPhrases(ocrText, multiWordTerms);
            // 2. Server-side BERT refinement for remaining OOV words
            //    (currently disabled: local fixes under test, see
            //    USE_SERVER_SPELLCHECK).
            // 3. Local token-level finish (also the offline fallback when
            //    the server is unreachable).
            String serverCorrected = USE_SERVER_SPELLCHECK
                    ? ServerSpellChecker.correct(phraseFixed) : null;
            String base = serverCorrected != null ? serverCorrected : phraseFixed;
            String corrected = OcrCorrector.correct(base, dictWords);
            // 4. LLM contextual polish for residual misspellings
            //    (experimental; needs GROQ_API_KEY + premium decision).
            if (USE_LLM_SPELLCHECK) {
                String llmFixed = LlmSpellChecker.correct(corrected);
                if (llmFixed != null && !llmFixed.isEmpty()) {
                    corrected = llmFixed;
                }
            }
            FlaggedIngredientManager.MatchResult matchResult =
                    FlaggedIngredientManager.analyzeIngredients(
                            MainActivity.this, corrected);
            ProductResult result = new ProductResult(
                    true, "Scanned label", "", corrected, matchResult);
            runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                showScannedLabelResult(result);
            });
        }).start();
    }

    /**
     * Renders a scanned-label verdict through the normal flow, without
     * saving to history: a label read is transient (no product identity),
     * unlike a barcode/product scan. The label came from the camera, not
     * USDA: alternates, the "no exact match" notice and the USDA source
     * line are all suppressed.
     */
    private void showScannedLabelResult(ProductResult result) {
        // Not a search surface (§6.1): no safety line or badges here.
        if (searchSafetyBinder != null) searchSafetyBinder.clear();
        showResult(result, "Uncategorized", false,
                Collections.emptySet(), Collections.emptyMap(), false, false);
        alternatesController.hide();
        updateSourceLine();
        updateStickyBar();
        if (matchNoticeText != null) matchNoticeText.setVisibility(View.GONE);
    }

    private void lookupBarcodeAndSearch(String gtin) {
        clearComparePicks();
        if (progress != null) progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            String usdaDescription = "";
            String usdaBrand = "";

            try {
                // Try GTIN spellings: as-scanned, then with leading zeros stripped
                // (USDA stores gtinUpc as printed, e.g. 12-digit UPC-A).
                // The spellings are independent queries, so run them in
                // parallel; candidate order still decides priority.
                List<String> candidates = gtinCandidates(gtin);
                JSONObject match = null;
                if (candidates.size() == 1) {
                    match = usdaApiClient.findFoodByGtin(candidates.get(0));
                } else {
                    ExecutorService pool = Executors.newFixedThreadPool(candidates.size());
                    try {
                        List<Future<JSONObject>> futures = new ArrayList<>();
                        for (String candidate : candidates) {
                            final String c = candidate;
                            futures.add(pool.submit(
                                    () -> usdaApiClient.findFoodByGtin(c)));
                        }
                        for (Future<JSONObject> f : futures) {
                            JSONObject m = f.get(30, TimeUnit.SECONDS);
                            if (m != null) {
                                match = m;
                                break;
                            }
                        }
                    } finally {
                        pool.shutdownNow();
                    }
                }

                if (match != null) {
                    usdaDescription = match.optString("description", "");
                    String matchBrandName = match.optString("brandName", "");
                    usdaBrand = !matchBrandName.isEmpty() ? matchBrandName
                            : match.optString("brandOwner", "");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // USDA carries no record for this GTIN — a data gap, not a code
            // bug (e.g. Rice Krispies 038000265013). Ask Open Food Facts for
            // the product name so the normal text search lands on the nearest
            // USDA variant instead of searching the raw barcode digits.
            String offName = "";
            if (usdaDescription.trim().isEmpty()) {
                offName = ProductImageResolver.fetchDisplayName(
                        MainActivity.this, gtin);
            }

            final String finalProductName = BarcodeSeed.resolve(
                    usdaDescription, usdaBrand, offName, gtin);
            // When the GTIN lookup hit, the exact record is already in
            // hand — keep its id so the UI thread can fetch THAT record
            // instead of degrading to a fuzzy text search (below).
            final long matchFdcId = match != null ? match.optLong("fdcId", 0) : 0;
            final String exactName = usdaDescription.trim();
            runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                if (matchFdcId > 0 && !exactName.isEmpty()) {
                    // Exact record known: fetch it by id via the
                    // tapped-suggestion state. The BarcodeSeed text seed
                    // ("BROCCOLI (MAMBO)") matches no USDA description,
                    // so the text re-search landed on an unrelated top
                    // pick and the scanned/tapped product's verdict was
                    // lost — this is the path GTIN-bearing clean-choice
                    // rows (e.g. Mambo broccoli) take when tapped.
                    selectedSuggestionFdcId = matchFdcId;
                    selectedSuggestionLabel = exactName;
                    searchBox.setText(exactName);
                    search();
                } else {
                    // True GTIN data gap: seed the OFF name / raw digits
                    // for the nearest-variant text search, as before.
                    searchBox.setText(finalProductName);
                    pendingBarcodeSearch = true;
                    search();
                }
            });
        }).start();
    }

    /** GTIN spellings worth trying against the USDA index. */
    private List<String> gtinCandidates(String gtin) {
        List<String> out = new ArrayList<>();
        String digits = gtin.replaceAll("\\D", "");
        if (!digits.isEmpty()) out.add(digits);
        String stripped = digits.replaceFirst("^0+", "");
        if (!stripped.isEmpty() && !stripped.equals(digits)) out.add(stripped);
        return out;
    }

    /**
     * Queries USDA for a GTIN and returns the food whose gtinUpc matches exactly
     * (comparing without leading zeros), or null when nothing matches. Falls back
     * to the first branded result with an ingredient list when no exact gtinUpc
     * match exists.
     */

private void setupCategoryFilterPanel() {
    if (categoryCheckboxContainer == null) return;
    categoryCheckboxContainer.removeAllViews();

    List<String> categories = getFlaggedCategories();

    for (String category : categories) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(category);
        checkBox.setTextSize(12f);
        // The filter panel is light; the theme default text color is not.
        checkBox.setTextColor(Color.parseColor("#111827"));

        // Check preference manager (or SharedPreferences) for sticky state
        boolean isEnabled = CategoryPreferenceManager.isCategoryEnabled(this, category);
        checkBox.setChecked(isEnabled);

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            CategoryPreferenceManager.setCategoryEnabled(MainActivity.this, category, isChecked);
            AnalyticsTracker.categoryToggled(category, isChecked);
        });

        com.google.android.flexbox.FlexboxLayout.LayoutParams params =
                new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        params.setMargins(0, 0, 16, 4);
        checkBox.setLayoutParams(params);

        categoryCheckboxContainer.addView(checkBox);
    }
}
}
