package org.aniser.photos;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class Application {

    private static final boolean BY_PASS_ENABLED = true;

    private final static PhotoCompare photoCompare = new PhotoCompare();

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);

        if (args.length < 2) {
            throw new IllegalStateException("Too few arguments!");
        }

        List<Path> argumentList = Arrays
                .asList(args)
                .stream()
                .map(path -> Path.of(path))
                .collect(Collectors.toList());


        PhotoCompare.OPERATIONS operation = PhotoCompare.OPERATIONS.DUPLICATE;

        do {
            List<Path> paths = argumentList;
            Path originalPath = paths.remove(0);

            log.info("Original path is '" + originalPath + "', other paths to be processed with de-" + operation + " operation from is " + Arrays.toString(paths.toArray()) + ".");
            if (waitForYes()) {
                run(photoCompare, operation, originalPath, paths, Strings.EMPTY, path -> true);
            }

            log.info("Run again? Previous arguments were \"" + originalPath.toAbsolutePath() + "\" " +
                    argumentList.stream().map(argument -> "\"" + argument.toAbsolutePath() + "\"").collect(Collectors.joining(" "))
            );
        } while (waitForYes() && resetArguments(argumentList));
    }

    private static boolean resetArguments(List<Path> paths) {
        paths.clear();
        log.info("Please reset the arguments, for backslash use escape character '\\\\'.\nFill in the path one by one, starting with the path for originals.\nFinish with an EMPTY string.");
        try {
            String path = waitFor(Strings.EMPTY, Scanner::nextLine, (given, condition) -> condition.compareTo(given) != 0, () -> Strings.EMPTY);
            while (path.compareTo(Strings.EMPTY) != 0) {
                paths.add(Path.of(path));
                log.info("Path '" + paths.get(paths.size() - 1).toAbsolutePath() + "' successfully added.");
                path = waitFor(Strings.EMPTY, Scanner::nextLine, (given, condition) -> condition.compareTo(given) != 0, () -> Strings.EMPTY);
            }
        } catch (IllegalArgumentException e) {
            log.info("Catching the last empty string, arguments were successfully read.");
        }
        return true;
    }


    public static boolean waitForYes(boolean byPassEnabled) {
        if (byPassEnabled) {
            log.info("Condition wait for pressing yes was skipped here.");
            return true;
        } else {
            log.info("Press y to continue.");
            return waitFor('y', reader -> reader.next().charAt(0), (given, condition) -> given == condition, () -> 'n') == 'y';
        }
    }

    public static boolean waitForYes() {
        log.info("Press y to continue.");
        return waitFor('y', reader -> reader.next().charAt(0), (given, condition) -> given == condition, () -> 'n') == 'y';
    }

    public static <T> T waitFor(T condition, Function<Scanner, T> readFunction, BiFunction<T, T, Boolean> validateFunction, Supplier<T> doOtherwise) {
        Scanner reader = new Scanner(System.in);
        T input = readFunction.apply(reader);
        if (validateFunction.apply(input, condition)) {
            return input;
        }
        log.info("Condition required is not valid, going to return the otherwise value " + doOtherwise.get());
        return doOtherwise.get();
    }

    private static void run(PhotoCompare photoCompare, PhotoCompare.OPERATIONS operation, Path originalPath, List<Path> otherPaths, String message, Predicate<Path> predicate) {
        List<Path> paths = photoCompare.filterDirectoryBWithFunction(originalPath, otherPaths, operation);

        List<Path> collectEntries = paths.stream().sorted(Comparator.comparing(Path::toAbsolutePath).reversed())
                .filter(path -> predicate.test(path))
                .collect(Collectors.toCollection(ArrayList::new));

        log.info("List of " + operation.name() + (Strings.isEmpty(message) ? "" : " " + message) + " (" + collectEntries.size() + " of " + paths.size() + " unfiltered entries):");
        collectEntries.stream().limit(50000).forEach(path -> {
            log.info("" + path.toAbsolutePath());
        });
        log.info(" ... ");
        if (collectEntries.size() > 0) {
            log.info("DELETE " + collectEntries.size() + " files from " + Arrays.toString(otherPaths.toArray()) + "?");

            log.warn("Files will be DELETED after passing this point.");
            if (Application.waitForYes(BY_PASS_ENABLED)) {
                performCollectionDeletion(originalPath, otherPaths, collectEntries);
            } else {
                log.info("Per your choice files were NOT DELETED.");
            }
        } else {
            log.info("No files found to DELETE, skipping.");
        }
    }

    private static void performCollectionDeletion(Path originalPath, List<Path> otherPaths, List<Path> collectEntries) {
        collectEntries.stream()
                .filter(path -> otherPaths.stream().anyMatch(otherPath -> path.startsWith(otherPath)))
                .forEach(path -> {
                    log.info("File '" + path.toAbsolutePath() + "' " + (path.toFile().delete() ? "deleted successfully!" : "DELETED NOT SUCCESSFULLY!") + ".");

                    File directory = path.getParent().toFile();
                    log.info("Checking path '" + directory.getAbsolutePath() + "' for deletion ...");
                    while (directory.isDirectory() && directory.list().length == 0 && directory.toPath().compareTo(originalPath) != 0 && waitForYes(BY_PASS_ENABLED)) {
                       log.info("Parent path was empty '" + directory.getAbsolutePath() + "', " + (directory.delete() ? " deleted!" : " DELETE WAS NOT SUCCESSFUL!"));
                       directory = directory.toPath().getParent().toFile();
                        log.info("Checking path '" + directory.getAbsolutePath() + "' for deletion ...");
                    }
                    log.info("Non-empty parent directory found or a directory to skip out the deleting state at '" + directory.toPath().toAbsolutePath() + "'.");
                });
    }

}
