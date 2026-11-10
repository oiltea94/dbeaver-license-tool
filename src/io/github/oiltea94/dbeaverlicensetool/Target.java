package io.github.oiltea94.dbeaverlicensetool;
import java.nio.file.Path;
public record Target(Path home, Path plugins, Path appJar, Path modelJar, Path apiJar,
                     String productId, String version, String releaseDate, String keyEntry) {
    @Override public String toString() { return productId + "  " + version + "  —  " + home; }
}
