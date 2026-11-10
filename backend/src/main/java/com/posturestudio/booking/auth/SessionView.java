package com.posturestudio.booking.auth;

// The open session, as the frontend sees it: only the role, which says which area to show. Whether a
// customer still has to give first and last name, the frontend asks GET /api/customer/profile (guide 25, 7.3)
public record SessionView(Role role) {
}
