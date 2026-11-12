package com.posturestudio.booking.shared;

import java.util.List;

/**
 * The readable codes a module puts in the "code" of its error answers, for the OpenAPI contract (D-083): the
 * frontend gets them as a closed list of types, and its dictionary of Italian sentences must cover each one.
 * ErrorCode and the codes of ApiExceptionHandler are listed by shared itself; a module with codes of its own (auth,
 * with AuthErrorCode, kept apart from the 409s of the business rules) declares a bean of this type.
 */
public interface ProblemCodes {

    List<String> codes();
}
