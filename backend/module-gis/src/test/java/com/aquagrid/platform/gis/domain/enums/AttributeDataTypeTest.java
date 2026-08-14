package com.aquagrid.platform.gis.domain.enums;

import com.aquagrid.platform.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coercion — the point where a cell in a contractor's spreadsheet becomes a typed value.
 *
 * <p>Every case here is one where the obvious implementation is silently wrong rather than loudly
 * wrong, which is the only kind of import bug that survives to production: a column of "Y"/"N" that
 * {@code Boolean.parseBoolean} turns into a column of all-false, a capacity truncated into a
 * narrower field, a range error indistinguishable from a typo.
 */
class AttributeDataTypeTest {

    private static Object coerce(AttributeDataType type, String raw) {
        return type.coerce(raw, "Test field", null, null, null);
    }

    @Test
    @DisplayName("absence is null, not an empty value")
    void treatsBlankAsAbsent() {
        // "" in the bag would make "column mapped but empty" indistinguishable from "populated",
        // and the difference matters to every consumer downstream.
        assertThat(coerce(AttributeDataType.TEXT, "")).isNull();
        assertThat(coerce(AttributeDataType.TEXT, "   ")).isNull();
        assertThat(coerce(AttributeDataType.INTEGER, null)).isNull();
    }

    @Test
    @DisplayName("text honours the configured maximum length")
    void enforcesTextLength() {
        assertThat(AttributeDataType.TEXT.coerce("Kadambur", "Panchayat", 20, null, null))
                .isEqualTo("Kadambur");

        assertThatThrownBy(() -> AttributeDataType.TEXT.coerce("Kadambur", "Panchayat", 4, null, null))
                .isInstanceOf(BusinessException.class)
                // The message has to carry both numbers; "too long" leaves the operator counting.
                .hasMessageContaining("8 characters")
                .hasMessageContaining("allows 4");
    }

    @ParameterizedTest
    @DisplayName("booleans accept the spellings registers actually use")
    @CsvSource({
            "TRUE,true", "true,true", "T,true", "Yes,true", "y,true", "1,true",
            "FALSE,false", "false,false", "N,false", "no,false", "0,false",
    })
    void parsesBooleanSpellings(String raw, boolean expected) {
        assertThat(coerce(AttributeDataType.BOOLEAN, raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unrecognised boolean is rejected rather than quietly read as false")
    void rejectsUnknownBoolean() {
        // Boolean.parseBoolean maps everything that is not "true" to false, silently, which turns a
        // column of "Working"/"Faulty" into a column of all-false that looks like clean data.
        assertThatThrownBy(() -> coerce(AttributeDataType.BOOLEAN, "Working"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yes/no");
    }

    @Test
    @DisplayName("integral types report out-of-range differently from unparseable")
    void distinguishesRangeFromGarbage() {
        assertThatThrownBy(() -> coerce(AttributeDataType.SHORT_INTEGER, "40000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("outside the range");

        assertThatThrownBy(() -> coerce(AttributeDataType.SHORT_INTEGER, "twelve"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a whole number");

        assertThat(coerce(AttributeDataType.SHORT_INTEGER, "-32768")).isEqualTo(-32768L);
        assertThat(coerce(AttributeDataType.LONG_INTEGER, "9007199254740991"))
                .isEqualTo(9007199254740991L);
    }

    @Test
    @DisplayName("decimal rounds to the declared scale rather than refusing the row")
    void roundsToScale() {
        // A source with more resolution than the field has more information, not wrong data;
        // refusing the row would lose the 12.35 that is genuinely wanted.
        assertThat(AttributeDataType.DECIMAL.coerce("12.3456", "Diameter", null, 7, 2))
                .isEqualTo(new BigDecimal("12.35"));
        assertThat(AttributeDataType.DECIMAL.coerce("150", "Capacity", null, 10, 2))
                .isEqualTo(new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("decimal rejects a value with more integer digits than the field holds")
    void rejectsExcessPrecision() {
        // Silently truncating a 12-digit capacity into a 10-digit field is how a reservoir ends up
        // a hundredth of its size. This is a different magnitude, not a rounding question.
        assertThatThrownBy(() -> AttributeDataType.DECIMAL.coerce("123456789.12", "Capacity", null, 6, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("precision");
    }

    @ParameterizedTest
    @DisplayName("date rejects anything that is not dd-MMM-yyyy, including the old ISO form")
    @ValueSource(strings = {"14/03/2026", "March 2026", "2026-13-01", "2026-03-14", "32-Jan-2026"})
    void rejectsNonDdMmmYyyyDates(String raw) {
        assertThatThrownBy(() -> coerce(AttributeDataType.DATE, raw))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dd-MMM-yyyy");
    }

    @Test
    @DisplayName("date accepts dd-MMM-yyyy and normalises the month's case")
    void acceptsDdMmmYyyyDate() {
        assertThat(coerce(AttributeDataType.DATE, "02-Jan-2025")).isEqualTo("02-Jan-2025");
        // A source spreadsheet in all caps is still unambiguous, so it is normalised rather than
        // refused for a reason no operator would guess.
        assertThat(coerce(AttributeDataType.DATE, "02-JAN-2025")).isEqualTo("02-Jan-2025");
    }

    @Test
    @DisplayName("date-time tolerates a space where the standard wants a T")
    void acceptsSpaceSeparatedDateTime() {
        // Every spreadsheet on earth writes "2026-03-14 09:30:00". Refusing it would fail files
        // that are, in every sense that matters, correct.
        assertThat(coerce(AttributeDataType.DATE_TIME, "2026-03-14 09:30:00"))
                .isEqualTo("2026-03-14T09:30");
        assertThat(coerce(AttributeDataType.TIME, "09:30")).isEqualTo("09:30");
    }

    @Test
    @DisplayName("geometry cannot be filled from a mapped column")
    void refusesGeometryFromColumn() {
        assertThatThrownBy(() -> coerce(AttributeDataType.GEOMETRY, "POINT (78.14 11.66)"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source file");
    }

    @Test
    @DisplayName("the type's own facets say which configuration fields apply")
    void reportsApplicableFacets() {
        // The create form renders Length and Precision/Scale from these, so a wrong answer here is
        // a form that offers a length for a boolean.
        assertThat(AttributeDataType.TEXT.usesLength()).isTrue();
        assertThat(AttributeDataType.TEXT.usesPrecisionScale()).isFalse();
        assertThat(AttributeDataType.DECIMAL.usesPrecisionScale()).isTrue();
        assertThat(AttributeDataType.DECIMAL.usesLength()).isFalse();
        assertThat(AttributeDataType.BOOLEAN.usesLength()).isFalse();
        assertThat(AttributeDataType.DOUBLE.isNumeric()).isTrue();
        assertThat(AttributeDataType.DATE.isNumeric()).isFalse();
    }
}
