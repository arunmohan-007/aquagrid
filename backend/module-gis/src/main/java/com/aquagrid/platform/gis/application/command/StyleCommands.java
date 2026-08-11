package com.aquagrid.platform.gis.application.command;

import com.aquagrid.platform.gis.domain.enums.StyleOperator;
import com.aquagrid.platform.gis.domain.enums.StyleType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the Layer Style service is asked to do. */
public final class StyleCommands {

    private StyleCommands() {
    }

    /**
     * Create or replace a style, rules and all.
     *
     * <p>One command for the whole style rather than separate calls per rule, and that is not
     * laziness about REST. A classified style is only meaningful complete: three of four bands is a
     * style that renders, looks wrong, and gives no clue that the fourth is still being typed.
     * Saving it as one transaction means the map never sees a half-edited classification.
     *
     * @param rules replaces the style's rules wholesale. A rule dropped from the editor must
     *              disappear; a merge would keep drawing a class the administrator deleted.
     */
    public record Save(
            UUID layerId,
            String name,
            String description,
            StyleType styleType,
            String classifyField,
            Boolean active,
            Boolean defaultStyle,
            Integer minZoom,
            Integer maxZoom,
            Map<String, Object> symbol,
            Map<String, Object> label,
            List<Rule> rules
    ) {
    }

    /** One class of a classified style. */
    public record Rule(
            String fieldName,
            StyleOperator operator,
            String value1,
            String value2,
            List<String> valueList,
            String label,
            Map<String, Object> symbol,
            Integer sortOrder
    ) {
    }
}
