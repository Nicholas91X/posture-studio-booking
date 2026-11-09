package com.posturestudio.booking.auth;

import java.time.Instant;

/**
 * How many codes were requested in a time window, and when the oldest of them was (null if none):
 * the oldest one leaving the window is when a request is allowed again (Retry-After).
 * Built by the JPQL of LoginCodeRepository with "select new": one query, no entity loaded.
 */
record WindowUsage(long count, Instant oldest) {
}
