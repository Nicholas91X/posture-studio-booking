package com.posturestudio.booking.schedule;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Class-level constraint on a schedule version: every band starts before it ends, and two bands
 * of the same day do not overlap (D-036). It needs the whole list, so it sits on the class.
 * Each error is reported on the band that breaks the rule, e.g. "bands[1]": the panel knows
 * which band to fix. Bands or times that are null are left to NotNull.
 */
@Documented
@Constraint(validatedBy = ValidBands.Validator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ValidBands {

    String message() default "invalid working bands";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // created by Hibernate Validator through reflection, so public
    class Validator implements ConstraintValidator<ValidBands, ScheduleVersionRequest> {

        @Override
        public boolean isValid(ScheduleVersionRequest request, ConstraintValidatorContext context) {
            if (request == null || request.bands() == null) {
                return true;
            }
            List<WorkingBand> bands = request.bands();
            boolean valid = true;
            context.disableDefaultConstraintViolation();
            for (int i = 0; i < bands.size(); i++) {
                WorkingBand band = bands.get(i);
                if (!isComplete(band)) {
                    continue;
                }
                if (!band.startTime().isBefore(band.endTime())) {
                    report(context, i, "must start before it ends");
                    valid = false;
                    continue;
                }
                // compared with the earlier bands only: each overlap is reported once, on the later band
                for (int j = 0; j < i; j++) {
                    WorkingBand earlier = bands.get(j);
                    if (isComplete(earlier) && earlier.startTime().isBefore(earlier.endTime()) && band.overlaps(earlier)) {
                        report(context, i, "overlaps another band of the same day");
                        valid = false;
                        break;
                    }
                }
            }
            return valid;
        }

        private static boolean isComplete(WorkingBand band) {
            return band != null && band.dayOfWeek() != null && band.startTime() != null && band.endTime() != null;
        }

        // the path "bands[index]" instead of the whole object
        private static void report(ConstraintValidatorContext context, int index, String message) {
            context.buildConstraintViolationWithTemplate(message)
                    .addPropertyNode("bands")
                    .addBeanNode().inIterable().atIndex(index)
                    .addConstraintViolation();
        }
    }
}
