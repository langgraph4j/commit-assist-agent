package org.bsc.langgraph4j.spring.ai.commit;

import java.util.*;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

record CommandLine(Map<String, String> longOptions,
                   Map<Character, String> shortOptions,
                   List<String> positionals) {

    private static final Pattern LONG_OPTION_WITH_VALUE = Pattern.compile("^--([^=]+)=(.*)$");
    private static final Pattern LONG_OPTION = Pattern.compile("^--(.+)$");
    private static final Pattern SHORT_OPTION_WITH_VALUE = Pattern.compile("^-([^-=])=(.*)$");
    private static final Pattern SHORT_OPTION = Pattern.compile("^-([^-=])$");

    CommandLine {
        requireNonNull(longOptions, "longOptions cannot be null");
        requireNonNull(shortOptions, "shortOptions cannot be null");
        requireNonNull(positionals, "positionals cannot be null");
    }

    public Optional<String> option(String longName, char shortName) {
        return ofNullable(longOptions.get(longName))
                .or(() -> ofNullable(shortOptions.get(shortName)));
    }

    public boolean flag(String longName, char shortName) {
        return longOptions.containsKey(longName) || shortOptions.containsKey(shortName);
    }

    public static CommandLine parse(String[] args) {
        final var longOptions = new LinkedHashMap<String, String>();
        final var shortOptions = new LinkedHashMap<Character, String>();
        final var positional = new ArrayList<String>();

        boolean positionalOnly = false;

        for (int i = 0; i < args.length; i++) {
            final String token = args[i];

            if (positionalOnly) {
                positional.add(token);
                continue;
            }
            if ("--".equals(token)) {
                positionalOnly = true;
                continue;
            }

            final var longWithValue = LONG_OPTION_WITH_VALUE.matcher(token);
            if (longWithValue.matches()) {
                longOptions.put(longWithValue.group(1), longWithValue.group(2));
                continue;
            }

            final var longOption = LONG_OPTION.matcher(token);
            if (longOption.matches()) {
                final String name = longOption.group(1);
                if ((i + 1) < args.length && !args[i + 1].startsWith("-")) {
                    longOptions.put(name, args[++i]);
                } else {
                    longOptions.put(name, "true");
                }
                continue;
            }

            final var shortWithValue = SHORT_OPTION_WITH_VALUE.matcher(token);
            if (shortWithValue.matches()) {
                shortOptions.put(shortWithValue.group(1).charAt(0), shortWithValue.group(2));
                continue;
            }

            final var shortOption = SHORT_OPTION.matcher(token);
            if (shortOption.matches()) {
                final char name = shortOption.group(1).charAt(0);
                if ((i + 1) < args.length && !args[i + 1].startsWith("-")) {
                    shortOptions.put(name, args[++i]);
                } else {
                    shortOptions.put(name, "true");
                }
                continue;
            }

            if (token.startsWith("--")) {
                throw new IllegalArgumentException("Invalid option: %s".formatted(token));
            }
            if (token.startsWith("-") && token.length() > 1) {
                throw new IllegalArgumentException("Short options must use a single character: %s".formatted( token ));
            }
            positional.add(token);
        }

        return new CommandLine(longOptions, shortOptions, positional);
    }

    public static Optional<Boolean> booleanOption(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "y", "on" -> Optional.of(true);
            case "0", "false", "no", "n", "off" -> Optional.of(false);
            default -> throw new IllegalArgumentException( "Invalid boolean value: %b".formatted(value) );
        };
    }

}
