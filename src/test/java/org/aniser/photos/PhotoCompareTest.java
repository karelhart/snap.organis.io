package org.aniser.photos;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class PhotoCompareTest {

    @Test
    @DisplayName("GIVEN the same path SHOULD ALWAYS return false")
    public void testDuplicateWontReturnTrueOnSamePath() {
        Path original = Path.of("a/b/c");
        Assertions.assertFalse(PhotoCompare.OPERATIONS.DUPLICATE.apply(Set.of(original), original));
    }

    @Test
    @DisplayName("GIVEN the same path SHOULD ALWAYS pass and returned an unchecked exception")
    public void testDuplicateWontReturnTrueOnSubPath() {
        String commonPath = "a/b/c/";
        Path original = Path.of(commonPath + "d");
        Path subpath = Path.of(commonPath + "/e");
        Assertions.assertThrows(UncheckedIOException.class, () ->
                PhotoCompare.OPERATIONS.DUPLICATE.apply(Set.of(original), subpath)
        );
    }

}
