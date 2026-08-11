package com.aquagrid.platform.gis.domain.style;

import com.aquagrid.platform.common.error.BusinessException;
import com.aquagrid.platform.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The built-in symbol library — free, open-licensed map icons an administrator can use immediately.
 *
 * <p>Uploading is the answer for a utility that has its own glyphs. It is a poor answer for the far
 * more common case of somebody who wants a valve to look like a valve and has no artwork at all: the
 * upload button asks them to go and find a file, and they either draw a circle or give up. This is
 * the list they pick from instead.
 *
 * <p>Two sets, both genuinely free and both vendored into this module's resources rather than
 * fetched at runtime:
 *
 * <ul>
 *   <li><b>Maki</b>, from Mapbox — <b>CC0-1.0</b>, public domain, no attribution required. Purpose-built
 *       for maps: designed to read at marker size, which most icon sets are not.</li>
 *   <li><b>Material Symbols</b>, from Google — <b>Apache-2.0</b>. Broader vocabulary, and the source of
 *       the plant, metering and instrumentation glyphs Maki has no equivalent for.</li>
 * </ul>
 *
 * <p>Vendored rather than pulled from a CDN for the reason every other asset here is: a field-facing
 * utility console must render on a deployment with no route to the internet, and an icon set that
 * 404s produces markers that silently draw nothing. The licence text of each set ships beside its
 * icons.
 *
 * <p>The subset is curated for water utilities — roughly a hundred icons, not the full two-and-a-half
 * thousand. Every one of them is loaded into the map's image atlas when a style references it, and a
 * picker with two thousand entries is a picker nobody scrolls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolLibrary {

    /** {@code maki-water}, {@code material-water_drop} — the id a style stores, minus the prefix. */
    private static final Pattern SAFE_ID = Pattern.compile("^[a-z][a-z0-9_-]*$");

    private final ObjectMapper objectMapper;

    /**
     * Loaded once at startup, keyed by id.
     *
     * <p>Immutable and shared: these are files on the classpath, so nothing can change them at
     * runtime and re-reading the manifest per request would be work done to get the same answer.
     */
    private Map<String, LibrarySymbol> byId = Map.of();

    /** One icon in the built-in library. */
    public record LibrarySymbol(String id, String name, String set, String file) {

        /** What a style stores in its {@code icon} property. */
        public String iconName() {
            return "lib-" + id;
        }
    }

    @PostConstruct
    void load() {
        try (InputStream manifest = new ClassPathResource("symbols/manifest.json").getInputStream()) {
            List<LibrarySymbol> entries = objectMapper.readValue(manifest,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, LibrarySymbol.class));

            Map<String, LibrarySymbol> loaded = new LinkedHashMap<>();
            for (LibrarySymbol entry : entries) {
                /*
                 * The id reaches a classpath lookup, so it is validated here even though it comes
                 * from a file this build produced. A manifest is exactly the sort of thing a later
                 * script regenerates, and a path-shaped id would turn a resource read into a
                 * traversal — the check belongs next to the use, not next to the generation.
                 */
                if (entry.id() == null || !SAFE_ID.matcher(entry.id()).matches()) {
                    log.warn("Skipping library symbol with unusable id '{}'", entry.id());
                    continue;
                }
                loaded.put(entry.id(), entry);
            }
            byId = Map.copyOf(loaded);
            log.info("Built-in symbol library: {} icons", byId.size());
        } catch (IOException e) {
            /*
             * A missing manifest is a packaging fault, not a reason to refuse to start. The library
             * is a convenience — uploading still works, and so does every built-in shape — so this
             * degrades to an empty list and says so loudly rather than taking the application down.
             */
            log.error("Built-in symbol library could not be loaded; the picker will offer uploads "
                    + "and the built-in shapes only", e);
            byId = Map.of();
        }
    }

    public List<LibrarySymbol> all() {
        return List.copyOf(byId.values());
    }

    public LibrarySymbol require(String id) {
        LibrarySymbol symbol = byId.get(id);
        if (symbol == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "'" + id + "' is not in the built-in symbol library.");
        }
        return symbol;
    }

    /**
     * The SVG bytes for one library icon.
     *
     * <p>Read from the classpath on each request rather than held in memory. The files are around a
     * kilobyte each, the browser caches them for a year (they are immutable by construction), and
     * holding a hundred of them resident to save a read that happens once per client is the wrong
     * trade.
     */
    public byte[] content(String id) {
        LibrarySymbol symbol = require(id);
        try (InputStream in = new ClassPathResource("symbols/" + symbol.file()).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "The symbol file for '" + id + "' is missing from this build.");
        }
    }
}
