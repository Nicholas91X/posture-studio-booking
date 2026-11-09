package com.posturestudio.booking.auth;

/**
 * How an access code reaches its destination (guide 24, part 2). Only EMAIL today; step 8 piece 6 adds SMS,
 * together with the migration that widens login_code_channel_ck (D-042: only the values the code produces).
 */
enum LoginChannel {
    EMAIL
}
