package com.chaplin.roots;

/** Bounded status classification for a completed request observation. */
public enum RequestOutcome {
    /** No response was committed before the exchange ended. */
    ABORTED,
    /** Informational or successful response. */
    SUCCESS,
    /** Redirect response. */
    REDIRECTION,
    /** Client-error response. */
    CLIENT_ERROR,
    /** Server-error response. */
    SERVER_ERROR;

    /** Classifies a response status, where zero means no response was committed.
     * @param status HTTP response status or zero
     * @return bounded outcome
     */
    public static RequestOutcome fromStatus(int status) {
        if (status == 0) {
            return ABORTED;
        }
        if (status < 300) {
            return SUCCESS;
        }
        if (status < 400) {
            return REDIRECTION;
        }
        if (status < 500) {
            return CLIENT_ERROR;
        }
        return SERVER_ERROR;
    }
}
