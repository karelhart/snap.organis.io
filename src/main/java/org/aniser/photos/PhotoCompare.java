package org.aniser.photos;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PhotoCompare {

    private final Map<Path, List<File>> originalPathFiles = new HashMap<>();

    public static boolean fileAreEqual(Path originalPath, Path otherPath) {
        File original = originalPath.toFile();
        File other = otherPath.toFile();

        if (!original.exists() || !other.exists()) {
            String message = "File 'original' or 'other' does not exist " + originalPath.toAbsolutePath() + ", " + otherPath.toAbsolutePath();
            log.error(message);
            throw new UncheckedIOException(new IOException(message));
        }

        try {
            return FileUtils.contentEquals(original, other);
        } catch (IOException e) {
            throw new IllegalStateException("Files " + original.getAbsolutePath() + " and " + other.getAbsolutePath() + " couldn't be compared!");
        }
    }


    public enum OPERATIONS {
        DUPLICATE((originals, suggestedDuplicate) -> {
            log.debug("Suggested duplicate " + suggestedDuplicate.toAbsolutePath());
            return originals.stream().anyMatch(file -> {
                boolean pathsDiffer = file.toAbsolutePath().compareTo(suggestedDuplicate.toAbsolutePath()) != 0;
                if (!pathsDiffer) {
                    log.warn("Paths of file '" + file.toAbsolutePath() + "' and '" + suggestedDuplicate.toAbsolutePath() + "' should differ, this might be a serious issue!");
                }
                if (pathsDiffer && fileAreEqual(file, suggestedDuplicate)) {
                    log.info(String.format("%50s", suggestedDuplicate.toAbsolutePath()) + " duplicates original " + file.toAbsolutePath() + "\r");
                    return true;
                }
                return false;
            });
        }),

        ORIGINALS((originals, suggestedOriginal) -> {
            log.debug("Suggested originals " + suggestedOriginal.toAbsolutePath());
            return originals.stream().allMatch(file -> !fileAreEqual(file, suggestedOriginal));
        });


        private BiFunction<Set<Path>, Path, Boolean> operation;

        OPERATIONS(BiFunction<Set<Path>, Path, Boolean> operation) {
            this.operation = operation;
        }

        Boolean apply(Set<Path> originals, Path file) {
            return operation.apply(originals, file);
        }
    }

    public void validate(Path originalPath, Path otherPath) {
        if (!originalPath.toFile().isDirectory() || !otherPath.toFile().isDirectory()) {
            log.error("Provide directories, not files!");
            throw new IllegalStateException("Not directories to compare!");
        }

        if (otherPath.startsWith(originalPath)) {
            log.warn("Provided a sub-path of one another " + originalPath.toAbsolutePath() + " is a parent of " + otherPath.toAbsolutePath() + " check the path was skipped on processing later!");
        }
    }

    public List<Path> filterDirectoryBWithFunction(Path originalPath, List<Path> otherPaths, OPERATIONS operation) {
        otherPaths.forEach(otherPath -> validate(originalPath, otherPath));

        log.info("Retrieving listing of files of '" + originalPath.toAbsolutePath() + "' and " + Arrays.toString(otherPaths.toArray()) + ", might take several minutes to complete [obtain a file size and save to a map] ...");
        List<File> originalFiles = originalPathFiles.containsKey(originalPath) ? originalPathFiles.get(originalPath) : getFilesRecursive(originalPath, file -> file.isFile(), otherPaths);
        originalPathFiles.putIfAbsent(originalPath, originalFiles);

        log.info("Original path '" + originalPath.toAbsolutePath() + "' contains " + originalPathFiles.get(originalPath).size() + " files.");

        List<File> otherFiles = new ArrayList<>();
        otherPaths.forEach(otherPath -> otherFiles.addAll(getFilesRecursive(otherPath, file -> file.isFile(), List.of(originalPath))));

        log.info("Other paths '" + otherPaths.stream().map(otherPath -> otherPath.toAbsolutePath().toString()).collect(Collectors.joining("', '")) + "' contains " + otherFiles.size() + " files.");

        log.info("Now processing the duplicate entries by comparing file sizes, wait a few minutes.");

        Map<Long, List<Path>> multimapFileSize = new HashMap<>();
        putSizeMapEntries(otherFiles, multimapFileSize, false);
        putSizeMapEntries(originalFiles, multimapFileSize, true);

        // Only take those files that are at least two in the size map and at least one from the other path
        List<Map.Entry<Long, List<Path>>> filteredSuggestedFileGroups = getSuggestedFileGroups(originalPath, otherPaths, multimapFileSize);
        statistics(filteredSuggestedFileGroups);

        return confirmSuggestions(originalPath, otherPaths, operation, filteredSuggestedFileGroups);
    }

    private List<Path> confirmSuggestions(Path originalPath, List<Path> otherPaths, OPERATIONS operation, List<Map.Entry<Long, List<Path>>> filteredSuggestedOtherFileEntries) {
        AtomicInteger checks = new AtomicInteger(0);
        log.info("Please provide a number for obtaining the process statistics [ideally 8-32], the sampleSolutionCodility will only run for the first n*128 files to keep the process quick.");
        Integer modulo = Math.max(1, filteredSuggestedOtherFileEntries.size() / 100); // Application.waitForNumber();
        log.info("Please provide a number for skipping certain number of groups, might be a number similar to previously investigated.");
        Integer skipGroups = 0; // Application.waitForNumber();

        int limit = Math.min(filteredSuggestedOtherFileEntries.size(), modulo * 128);
        return filteredSuggestedOtherFileEntries.stream()
                .limit(limit)
                .skip(skipGroups)
                .sorted(Comparator.comparing(entry -> entry.getValue().get(0).toAbsolutePath()))
                .flatMap(entry -> {
                    if (checks.getAndIncrement() % modulo == 0) {
                        log.info("===");
                        log.info("=== Just passing processing " + checks.get() + "th out of " + filteredSuggestedOtherFileEntries.size() + " groups (" + String.format("%,3d", checks.get() * 100 / Math.min(limit, filteredSuggestedOtherFileEntries.size())) + "%) [limit " + limit + "] === ");
                        log.info("===");
                    }

                    List<Path> files = entry.getValue();
                    Set<Path> originals = files.stream()
                            .filter(path -> path.startsWith(originalPath) && otherPaths.stream().allMatch(otherPath -> !path.startsWith(otherPath)))
                            .collect(Collectors.toSet());

                    List<Path> searchedOtherFiles = files.stream()
                            .filter(anyButOriginalFiles -> !originals.contains(anyButOriginalFiles))
                            .filter(suggestedSearches -> operation.apply(originals, suggestedSearches))
                            .collect(Collectors.toList());
                    return searchedOtherFiles.stream();
                }).collect(Collectors.toList());
    }

    private void statistics(List<Map.Entry<Long, List<Path>>> filterEntriesWithValidCondition) {
        // Get the statistics about the investigation
        filterEntriesWithValidCondition.stream().forEach(entry -> {
            log.debug(String.format("%,2d", entry.getValue().size()) + " files, " + String.format("%,8d", entry.getKey()) + " B each.");
        });
        AtomicLong sum = new AtomicLong(0);
        filterEntriesWithValidCondition.forEach(entry -> sum.addAndGet(entry.getKey()));
        log.info("Total file groups to investigate: " + filterEntriesWithValidCondition.size());
        log.info("In total potential duplicities reaching over " + String.format("%15d", sum.get()) + " Bytes!");
    }

    private List<Map.Entry<Long, List<Path>>> getSuggestedFileGroups(Path originalPath, List<Path> otherPaths, Map<Long, List<Path>> multimapFileSize) {
        var list = new ArrayList<>(multimapFileSize
                .entrySet().stream()
                // contains at least two entries
                .filter(entry -> entry.getValue().size() > 1)
                // Skip mac hidden dot files
                .filter(entry -> entry.getKey() != 4096)
                .filter(entry -> entry.getKey() > 0)
                .filter(entry -> entry.getValue().stream().anyMatch(path -> otherPaths.stream().anyMatch(otherPath -> path.startsWith(otherPath))))
                // contains at least a single file from the original path
                .filter(entry -> entry.getValue().stream().anyMatch(path -> path.startsWith(originalPath)))
                .collect(Collectors.toUnmodifiableSet()));
        list.sort(Comparator.comparing(Map.Entry::getKey));
        return list;
    }

    private void putSizeMapEntries(List<File> files, Map<Long, List<Path>> paths, boolean onlyEnhanceExistingEntries) {
        files.forEach(file -> {
            Path path = file.toPath();
            Long size = file.length();
            if (!paths.containsKey(size) && !onlyEnhanceExistingEntries) {
                paths.put(size, new ArrayList<>());
                paths.get(size).add(path);
            } else if (paths.containsKey(size)) {
                paths.get(size).add(path);
            }
        });
    }

    private List<File> getFilesRecursive(Path path, Function<File, Boolean> filter, List<Path> skipPaths) {
        if (skipPaths.contains(path)) {
            log.warn("Skipping the path '" + path + "' as requested.");
            return new ArrayList<>();
        }
        List<File> files = new ArrayList<>(
                Arrays.asList(path.toFile().listFiles(pathname -> filter.apply(pathname)))
                        .stream()
                        .collect(Collectors.toUnmodifiableList())
        );
        List<File> directories = Arrays.asList(
                path.toFile()
                        .list((current, name) -> new File(current, name).isDirectory()))
                .stream()
                .map(name -> path.resolve(name).toFile())
                .collect(Collectors.toList());

        log.debug("Retrieving listing of '" + path.toAbsolutePath() + "' with " + directories.size() + " directories and " + files.size() + " files.");

        directories.forEach(directory -> files.addAll(
                getFilesRecursive(directory.toPath(), filter, skipPaths)
        ));

        log.debug("Directories of " + path.toAbsolutePath() + " processed.");
        files.sort(Comparator.comparing(File::getAbsolutePath));
        log.debug(files.size() + " files sorted and returned to the caller " + path.toAbsolutePath() + ".");
        return files;
    }
}
