package com.faceless.ai.exceptions;

/**
 * Raised by the Facebook OAuth exchange when the connecting account has no
 * Page directly accessible to it — i.e. {@code GET /me/accounts} returned an
 * empty list even though {@code pages_show_list} was granted.
 *
 * <p>Distinct from a generic {@link IllegalStateException} so the
 * {@code GlobalExceptionHandler} can return a 422 with a stable error code
 * the frontend keys off to render an empty-state card (with a "Create a
 * Page" call to action) instead of a raw red error string.
 */
public class NoFacebookPageException extends RuntimeException {

    /**
     * Stable code the frontend matches on. Don't rename without updating the
     * matching string in {@code ConnectionsPage.tsx}.
     */
    public static final String CODE = "NO_FACEBOOK_PAGE";

    public NoFacebookPageException(String userFacingMessage) {
        super(userFacingMessage);
    }
}