package com.aquagrid.platform.gis.domain.style;

import com.aquagrid.platform.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload guard for user-supplied SVG.
 *
 * <p>An SVG served from this origin is a document with this application's privileges, so every case
 * below is a way a marker file could become stored XSS against every operator who opens the map.
 * They are written as separate cases rather than one loop because each is a distinct bypass, and a
 * regression in any one of them is independently exploitable.
 */
class SvgSanitizerTest {

    private static byte[] svg(String body) {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">" + body + "</svg>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Accepts what a real marker glyph looks like")
    class Accepts {

        @Test
        void plainPath() {
            byte[] content = svg("<path d=\"M12 2 L22 22 L2 22 Z\"/>");
            assertThat(SvgSanitizer.validate(content)).isEqualTo(content);
        }

        @Test
        @DisplayName("The bytes come back unmodified, so an illustrator's output is what they drew")
        void doesNotRewriteTheFile() {
            byte[] content = svg("<g fill=\"#3B82F6\"><circle cx=\"12\" cy=\"12\" r=\"8\"/></g>");
            assertThat(SvgSanitizer.validate(content)).isSameAs(content);
        }

        @Test
        @DisplayName("Inline styles and gradients are ordinary artwork, not a bypass")
        void allowsPresentationalConstructs() {
            assertThatCode(() -> SvgSanitizer.validate(svg(
                    "<defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#fff\"/>"
                            + "</linearGradient></defs>"
                            + "<style>.a{fill:url(#g)}</style><rect class=\"a\" width=\"24\" height=\"24\"/>")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("An internal reference by fragment stays local and is fine")
        void allowsInternalFragmentReference() {
            assertThatCode(() -> SvgSanitizer.validate(svg(
                    "<defs><path id=\"p\" d=\"M0 0 L8 8\"/></defs><use href=\"#p\"/>")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Refuses anything that executes, fetches or embeds")
    class Refuses {

        @Test
        void script() {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg("<script>alert(document.cookie)</script>")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("<script>");
        }

        @Test
        @DisplayName("foreignObject — the usual bypass for a filter that only looks for <script>")
        void foreignObject() {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg(
                    "<foreignObject><body xmlns=\"http://www.w3.org/1999/xhtml\">"
                            + "<img src=x onerror=alert(1)/></body></foreignObject>")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("foreignObject");
        }

        @ParameterizedTest(name = "event handler: {0}")
        @ValueSource(strings = {
                "<svg onload=\"alert(1)\">",
                "<circle onclick=\"alert(1)\" r=\"4\"/>",
                "<rect onmouseover = \"alert(1)\" width=\"4\"/>",
                // Not in any allowlist anyone remembers, which is why the check matches by shape.
                "<path onfocusin=\"alert(1)\" d=\"M0 0\"/>",
        })
        void eventHandlers(String body) {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg(body)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("event handler");
        }

        @ParameterizedTest(name = "dangerous URL: {0}")
        @ValueSource(strings = {
                "<a href=\"javascript:alert(1)\"><circle r=\"4\"/></a>",
                "<a href=\"JaVaScRiPt:alert(1)\"><circle r=\"4\"/></a>",
                "<a href=\"vbscript:msgbox\"><circle r=\"4\"/></a>",
                "<image href=\"data:text/html;base64,PHNjcmlwdD4=\"/>",
        })
        void dangerousUrls(String body) {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg(body)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("URL");
        }

        @Test
        @DisplayName("An external reference would leak every viewer's address to the uploader")
        void externalReference() {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg(
                    "<image href=\"https://tracker.example/pixel.png\"/>")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("external URL");
        }

        @Test
        @DisplayName("Protocol-relative references are external too, and easy to miss")
        void protocolRelativeReference() {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg("<use xlink:href=\"//evil.example/x.svg#a\"/>")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("external URL");
        }

        @Test
        @DisplayName("DOCTYPE and ENTITY — the XXE vector, refused even though nothing here parses XML")
        void doctypeAndEntity() {
            byte[] content = ("<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                    + "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>&xxe;</text></svg>")
                    .getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> SvgSanitizer.validate(content))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("DOCTYPE");
        }

        @Test
        void embeddedDocuments() {
            assertThatThrownBy(() -> SvgSanitizer.validate(svg("<iframe src=\"/admin\"/>")))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("Refuses files that are not usable markers at all")
    class Shape {

        @Test
        void empty() {
            assertThatThrownBy(() -> SvgSanitizer.validate(new byte[0]))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("A file named .svg that contains no <svg> is something else with the wrong name")
        void notAnSvg() {
            assertThatThrownBy(() -> SvgSanitizer.validate("just text".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not an SVG");
        }

        @Test
        void tooLarge() {
            byte[] huge = new byte[SvgSanitizer.MAX_BYTES + 1];
            assertThatThrownBy(() -> SvgSanitizer.validate(huge))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("limit");
        }
    }
}
