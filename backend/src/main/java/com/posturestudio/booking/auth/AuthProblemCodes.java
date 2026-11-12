package com.posturestudio.booking.auth;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.posturestudio.booking.shared.ProblemCodes;

// The codes of the sign-in in the OpenAPI contract (D-083), next to those of the business rules: the frontend
// must have a sentence for each. AuthErrorCode stays package-private; only the names leave the module
@Component
class AuthProblemCodes implements ProblemCodes {

    @Override
    public List<String> codes() {
        return Arrays.stream(AuthErrorCode.values()).map(Enum::name).toList();
    }
}
