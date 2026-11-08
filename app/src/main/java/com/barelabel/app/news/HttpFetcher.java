package com.barelabel.app.news;

import java.io.IOException;

/**
 * Injectable network seam for the news HTTP clients (Contracts
 * sections 2 and 7). Implementations return the response body of a
 * GET on HTTP 200 and throw {@link IOException} on every failure —
 * network error, timeout, or any non-200 status — so clients can
 * treat all failures uniformly (empty result for that source).
 *
 * <p>URLs handed to this interface carry the user's search term;
 * implementations must not log them (Contract section 8).
 * {@link UrlConnectionHttpFetcher} is the production implementation;
 * unit tests substitute fakes, so no test needs a network or a
 * device.
 */
public interface HttpFetcher {

    /**
     * GETs {@code url} and returns the response body on HTTP 200.
     *
     * @throws IOException on any failure, including non-200 status
     *         and timeouts; never returns an error body.
     */
    String fetch(String url) throws IOException;
}
