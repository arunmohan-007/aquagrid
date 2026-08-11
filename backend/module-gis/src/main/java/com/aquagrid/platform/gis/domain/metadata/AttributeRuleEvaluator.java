package com.aquagrid.platform.gis.domain.metadata;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.aquagrid.platform.gis.api.AttributeDefinition;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Applies an attribute's validation rules to a coerced value.
 *
 * <p>Runs after {@code AttributeDataType.coerce}, never instead of it: a MIN_VALUE rule on a value
 * that is not yet a number would either have to parse it again or compare strings, and comparing
 * "9" to "10" as strings says nine is larger. By the time a rule sees a value it is already the
 * type the field declares.
 *
 * <p>Patterns are compiled per call rather than cached. That looks wasteful in an import loop and
 * is deliberate: caching compiled patterns keyed by rule text is a map that grows with tenant
 * input, and the cost of {@code Pattern.compile} on a short expression is far below the cost of the
 * row's own database write. If profiling ever says otherwise, the cache belongs on the definition
 * snapshot, which is already loaded once per import.
 */
public final class AttributeRuleEvaluator {

    private AttributeRuleEvaluator() {
    }

    /**
     * Checks every active rule on the attribute.
     *
     * @throws BusinessException on the first rule that rejects, carrying the rule's own error
     *                           message where one was configured. Rules are evaluated in the order
     *                           the catalogue gives them, so the most informative failure — the one
     *                           the administrator wrote a message for — is the one the operator
     *                           sees.
     */
    public static void check(AttributeDefinition attribute, Object value) {
        if (value == null) {
            // Absence is the mandatory flag's business, not a rule's. A MIN_LENGTH rule on an
            // optional field must not turn "not supplied" into "too short".
            return;
        }
        for (AttributeDefinition.ValidationRule rule : attribute.rules()) {
            switch (rule.type()) {
                case MIN_LENGTH -> {
                    int min = intOf(rule.value());
                    if (value.toString().length() < min) {
                        throw reject(attribute, rule, "must be at least " + min + " characters");
                    }
                }
                case MAX_LENGTH -> {
                    int max = intOf(rule.value());
                    if (value.toString().length() > max) {
                        throw reject(attribute, rule, "must be at most " + max + " characters");
                    }
                }
                case MIN_VALUE -> {
                    BigDecimal min = decimalOf(rule.value());
                    if (numeric(attribute, value).compareTo(min) < 0) {
                        throw reject(attribute, rule, "must be " + min.toPlainString() + " or more");
                    }
                }
                case MAX_VALUE -> {
                    BigDecimal max = decimalOf(rule.value());
                    if (numeric(attribute, value).compareTo(max) > 0) {
                        throw reject(attribute, rule, "must be " + max.toPlainString() + " or less");
                    }
                }
                case PATTERN -> {
                    if (!compile(attribute, rule.value()).matcher(value.toString()).matches()) {
                        throw reject(attribute, rule, "is not in the required format");
                    }
                }
                case ALLOWED_VALUES -> {
                    String[] allowed = rule.value().split(",");
                    boolean ok = Arrays.stream(allowed)
                            .map(String::trim)
                            .anyMatch(candidate -> candidate.equalsIgnoreCase(value.toString()));
                    if (!ok) {
                        throw reject(attribute, rule,
                                "must be one of: " + String.join(", ", trimAll(allowed)));
                    }
                }
            }
        }
    }

    private static BigDecimal numeric(AttributeDefinition attribute, Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        // A range rule configured on a text field. The rule is the mistake, not the data, so the
        // message names the rule rather than blaming the row the operator happens to be importing.
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "'" + attribute.displayName() + "' has a numeric range rule but is a "
                        + attribute.dataType() + " field. Remove the rule or change the field's type.");
    }

    private static Pattern compile(AttributeDefinition attribute, String expression) {
        try {
            return Pattern.compile(expression);
        } catch (PatternSyntaxException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'" + attribute.displayName() + "' has an invalid pattern rule: " + e.getDescription());
        }
    }

    private static int intOf(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Validation rule value '" + raw + "' is not a whole number.");
        }
    }

    private static BigDecimal decimalOf(String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Validation rule value '" + raw + "' is not a number.");
        }
    }

    private static String[] trimAll(String[] values) {
        return Arrays.stream(values).map(String::trim).toArray(String[]::new);
    }

    private static BusinessException reject(AttributeDefinition attribute,
                                            AttributeDefinition.ValidationRule rule,
                                            String fallback) {
        String message = rule.errorMessage() != null && !rule.errorMessage().isBlank()
                ? rule.errorMessage()
                : attribute.displayName() + " " + fallback + ".";
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
