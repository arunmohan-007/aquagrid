package com.aquagrid.platform.gis.domain.style;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vendored icon library.
 *
 * <p>These files ship inside the build rather than arriving from a user, so they are trusted — but
 * they are still served to a browser from this origin, and the one thing that would make that
 * dangerous is a file that the upload path would have refused. Asserting that every vendored icon
 * passes the same sanitiser an upload does keeps the two from diverging: a future icon added by
 * copying it out of some other icon set would be caught here rather than by a security review nobody
 * scheduled.
 */
class SymbolLibraryTest {

    private final SymbolLibrary library = new SymbolLibrary(new com.fasterxml.jackson.databind.ObjectMapper());

    /*
     * The manifest is read in @PostConstruct, which Spring calls and a plain constructor does not.
     * Calling it explicitly keeps this a fast unit test rather than one that boots a context to
     * assert something about a hundred static files.
     */
    @BeforeEach
    void loadManifest() {
        library.load();
    }

    @Test
    @DisplayName("Every vendored icon loads and passes the upload sanitiser")
    void vendoredIconsAreClean() {
        List<SymbolLibrary.LibrarySymbol> all = library.all();
        assertThat(all).as("the manifest should not be empty").isNotEmpty();

        for (SymbolLibrary.LibrarySymbol symbol : all) {
            byte[] content = library.content(symbol.id());
            assertThat(content).as("%s has content", symbol.id()).isNotEmpty();

            /*
             * The sanitiser is what stands between an SVG and a browser that would otherwise execute
             * it. Running the vendored files through it proves they carry no script, no event
             * handler and no external reference — and, just as importantly, that the sanitiser has
             * not been tightened into refusing legitimate artwork, which would surface as icons that
             * 500 rather than draw.
             */
            if (symbol.file().endsWith(".svg")) {
                assertThat(SvgSanitizer.validate(content))
                        .as("%s survives the upload validator", symbol.id())
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("Both free sets are present, and every icon is addressable by its style id")
    void bothSetsArePresent() {
        List<SymbolLibrary.LibrarySymbol> all = library.all();

        // The two free sets the module promises: Mapbox Maki (CC0) and Google Material (Apache-2.0).
        assertThat(all).anyMatch(s -> "MAKI".equals(s.set()));
        assertThat(all).anyMatch(s -> "MATERIAL".equals(s.set()));

        /*
         * `iconName` is what a style stores and what the composer emits as `icon-image`. If it did
         * not round-trip back to a resolvable id, a saved style would reference an icon the server
         * validates and the content endpoint then cannot find — the exact silent-blank-marker
         * failure the id scheme exists to prevent.
         */
        for (SymbolLibrary.LibrarySymbol symbol : all) {
            assertThat(symbol.iconName()).isEqualTo("lib-" + symbol.id());
            assertThat(library.content(symbol.iconName().substring("lib-".length()))).isNotEmpty();
        }
    }

    @Test
    @DisplayName("An unknown icon id is refused rather than returning empty bytes")
    void unknownIdIsRefused() {
        // Empty bytes would register as an invisible image and draw nothing, which is the failure
        // mode that takes longest to diagnose. A refusal names the problem at the point of request.
        assertThatThrownBy(() -> library.content("no-such-icon"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("A traversal attempt cannot escape the bundled resource directory")
    void traversalIsRefused() {
        // The id reaches a classpath lookup, so it is an untrusted path segment even though every
        // legitimate value comes from the manifest.
        assertThatThrownBy(() -> library.content("../../application"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> library.content("maki/../../../etc/passwd"))
                .isInstanceOf(RuntimeException.class);
    }
}
