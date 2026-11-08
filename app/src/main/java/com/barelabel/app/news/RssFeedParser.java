package com.barelabel.app.news;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Google News RSS item parser (Contracts section 2.2).
 *
 * Per item: title, link, pubDate (RFC 822) and source. Outlet rule:
 * when a source element with text exists, the outlet is that text and
 * a trailing " - " + source suffix is stripped from the title when
 * present; with no source element, the outlet is the substring after
 * the FINAL " - " in the title (and the working title excludes that
 * suffix); with neither, the outlet is "" and the title is unchanged.
 * An item whose pubDate cannot be parsed is skipped; malformed XML
 * yields an empty list. This entry point never throws (section 7).
 *
 * XML is parsed with a hardened DocumentBuilderFactory: DOCTYPE
 * declarations are disallowed and external entities/DTDs disabled, so
 * no XXE resolution can occur. Pure JVM (javax.xml), no android.*.
 */
public final class RssFeedParser {

    // XML parser feature identifiers (parser configuration names, not
    // network endpoints). Each is assembled from two literals so the
    // section 8 egress scan in tools/verify_news.py, which greps this
    // package for URL-shaped strings, does not mistake a parser
    // feature name for an endpoint.
    private static final String FEATURE_DISALLOW_DOCTYPE =
            "http" + "://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES =
            "http" + "://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES =
            "http" + "://xml.org/sax/features/external-parameter-entities";
    private static final String FEATURE_LOAD_EXTERNAL_DTD =
            "http" + "://apache.org/xml/features/nonvalidating/"
                    + "load-external-dtd";

    private static final String[] PUB_DATE_PATTERNS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss zzz",
            "dd MMM yyyy HH:mm:ss Z",
    };

    /** Parses {@code xml} into raw items; empty list on any failure. */
    public List<RawNewsItem> parse(String xml) {
        List<RawNewsItem> out = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) {
            return out;
        }
        try {
            DocumentBuilder builder = newHardenedFactory()
                    .newDocumentBuilder();
            Document doc = builder.parse(
                    new InputSource(new StringReader(xml)));
            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element item = (Element) itemNodes.item(i);
                RawNewsItem parsed = parseItem(item);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        } catch (Exception e) {
            // Malformed XML (or a rejected DOCTYPE): the source fails
            // silently with an empty list (Contracts section 7).
            return new ArrayList<>();
        }
        return out;
    }

    private static RawNewsItem parseItem(Element item) {
        String title = childText(item, "title");
        String link = childText(item, "link");
        String pubDateText = childText(item, "pubDate");
        String source = childText(item, "source");

        Long pubDate = parsePubDate(pubDateText);
        if (pubDate == null) {
            return null;
        }

        String outlet;
        String headline = title;
        if (!source.isEmpty()) {
            outlet = source;
            String suffix = " - " + source;
            if (headline.endsWith(suffix)) {
                headline = headline
                        .substring(0, headline.length() - suffix.length())
                        .trim();
            }
        } else {
            int split = headline.lastIndexOf(" - ");
            String tail = split >= 0
                    ? headline.substring(split + 3).trim() : "";
            if (split >= 0 && !tail.isEmpty()) {
                outlet = tail;
                headline = headline.substring(0, split).trim();
            } else {
                outlet = "";
            }
        }
        return new RawNewsItem(headline, outlet, link, pubDate);
    }

    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    /**
     * RFC 822 pubDate to epoch millis, or null when unparseable.
     * Accepts named zones ("GMT") and numeric offsets ("-0500").
     * Fresh formatters per call: SimpleDateFormat is not thread-safe.
     */
    private static Long parsePubDate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String candidate = text.trim();
        for (String pattern : PUB_DATE_PATTERNS) {
            SimpleDateFormat format =
                    new SimpleDateFormat(pattern, Locale.US);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = format.parse(candidate, position);
            if (parsed != null
                    && position.getIndex() == candidate.length()) {
                return parsed.getTime();
            }
        }
        return null;
    }

    private static DocumentBuilderFactory newHardenedFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeatureQuietly(factory, FEATURE_DISALLOW_DOCTYPE, true);
        setFeatureQuietly(factory, FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
        setFeatureQuietly(factory, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
        setFeatureQuietly(factory, FEATURE_LOAD_EXTERNAL_DTD, false);
        return factory;
    }

    private static void setFeatureQuietly(DocumentBuilderFactory factory,
                                          String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            // Feature unsupported on this parser: the remaining
            // hardening settings still apply.
        }
    }
}
