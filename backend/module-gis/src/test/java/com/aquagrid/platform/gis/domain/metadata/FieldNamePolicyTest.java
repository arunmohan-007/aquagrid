package com.aquagrid.platform.gis.domain.metadata;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The field-name rules, which are the module's one irreversible decision.
 *
 * <p>A field name becomes a JSONB key, an export column heading and a shapefile DBF field the
 * moment it is saved, and renaming it later rewrites stored data on every affected asset. Each case
 * here is a way that could go wrong silently rather than loudly.
 */
class FieldNamePolicyTest {

    @Test
    @DisplayName("accepts a well-formed name unchanged")
    void acceptsWellFormedName() {
        assertThat(FieldNamePolicy.normaliseAndValidate("consumer_no")).isEqualTo("consumer_no");
        assertThat(FieldNamePolicy.normaliseAndValidate("ward_code_2024")).isEqualTo("ward_code_2024");
    }

    @Test
    @DisplayName("folds case and trims, because Postgres would fold it anyway")
    void foldsCaseAndTrims() {
        // Meter_No and meter_no are the same unquoted column but two different JSON keys, which
        // would produce two attributes an administrator believes are one.
        assertThat(FieldNamePolicy.normaliseAndValidate("  Meter_No  ")).isEqualTo("meter_no");
        assertThat(FieldNamePolicy.normaliseAndValidate("CONSUMER_NO")).isEqualTo("consumer_no");
    }

    @ParameterizedTest
    @DisplayName("rejects anything that is not letters, digits and underscore from a letter")
    @ValueSource(strings = {
            "meter no",      // space
            "meter-no",      // hyphen
            "1st_reading",   // leading digit
            "_private",      // leading underscore
            "meter.no",      // dot
            "meter#no",      // punctuation
            "meterño",       // non-ASCII letter
    })
    void rejectsInvalidCharacters(String candidate) {
        assertThatThrownBy(() -> FieldNamePolicy.normaliseAndValidate(candidate))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not valid")
                // The message has to say what is allowed. "Invalid field name" sends the operator
                // back to guess, which is how you get consumer_no_2.
                .hasMessageContaining("underscore");
    }

    @ParameterizedTest
    @DisplayName("rejects reserved words, which would need quoting in every generated query")
    @ValueSource(strings = {"order", "select", "table", "user", "check", "default", "primary"})
    void rejectsReservedWords(String reserved) {
        assertThatThrownBy(() -> FieldNamePolicy.normaliseAndValidate(reserved))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reserved word");
        assertThat(FieldNamePolicy.isReserved(reserved)).isTrue();
    }

    @Test
    @DisplayName("rejects the platform's own asset columns, which an attribute would shadow")
    void rejectsShadowingColumnNames() {
        for (String shadowed : new String[] {"id", "geom", "attributes", "organization_id", "created_at"}) {
            assertThatThrownBy(() -> FieldNamePolicy.normaliseAndValidate(shadowed))
                    .as("%s is a real column on gis.assets", shadowed)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("permits non-reserved SQL keywords, which are exactly the words utilities want")
    void permitsNonReservedKeywords() {
        // `name`, `type` and `value` are keywords but legal unquoted. Banning them would make the
        // module annoying for no safety gained.
        assertThat(FieldNamePolicy.normaliseAndValidate("type")).isEqualTo("type");
        assertThat(FieldNamePolicy.normaliseAndValidate("value")).isEqualTo("value");
        assertThat(FieldNamePolicy.normaliseAndValidate("zone")).isEqualTo("zone");
    }

    @Test
    @DisplayName("rejects a name longer than Postgres would keep")
    void rejectsOverlongName() {
        String tooLong = "a".repeat(FieldNamePolicy.MAX_LENGTH + 1);
        assertThatThrownBy(() -> FieldNamePolicy.normaliseAndValidate(tooLong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(FieldNamePolicy.MAX_LENGTH));

        // Exactly at the limit is fine: the boundary is where truncation starts, not where it looms.
        assertThat(FieldNamePolicy.normaliseAndValidate("a".repeat(FieldNamePolicy.MAX_LENGTH)))
                .hasSize(FieldNamePolicy.MAX_LENGTH);
    }

    @Test
    @DisplayName("rejects a blank name as a validation failure, not a null pointer")
    void rejectsBlank() {
        assertThatThrownBy(() -> FieldNamePolicy.normaliseAndValidate("   "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> FieldNamePolicy.normaliseAndValidate(null))
                .isInstanceOf(BusinessException.class);
    }
}
