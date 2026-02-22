package org.bsc.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public final class CommitMcpServer {

    private static final String CONVENTIONAL_COMMIT_SPEC = """
            Commits MUST be formatted as follows:

            <type>[optional scope]: <description>
            [optional body]
            [optional footer(s)]

            - <type> could be one of the following:
                * 'feat' MUST be used when a commit adds a new feature to your application or library.
                * 'build' MUST be used when changes are made to the project configuration files, scripts, affect the build system or external dependencies.
                * 'refactor' MUST be used when code changes neither fix bugs nor add features.
                * 'docs' MUST be used when changes are related to documentation.
                * 'test' MUST be used when adding missing tests or correcting existing tests.
                * 'fix' MUST be used when a commit represents a bug fix for your application.
                * 'style' MUST be used when changes  don't affect code meaning (formatting, spacing).
                * 'perf' MUST be used when changes improve performance.
                * 'ci' MUST be used when changes affect the Continuous Integration configuration files and scripts.
                * 'revert' MUST be used when reverting changes.
            - A <scope> MAY be provided after a type.
                A scope MUST consist of a noun describing a section of the codebase surrounded by parenthesis, e.g., fix(parser):.
                If one file is affected by the commit, the filename is used as the scope.
            - A <description> MUST immediately follow the colon and space after the type/scope prefix. The description is a short summary of the code changes, e.g., fix: array parsing issue when multiple spaces were contained in string.
            - A <body> MAY be provided for longer commit after the short description, providing additional contextual information about the code changes. The body MUST begin one blank line after the description.
              A commit body is free-form and MAY consist of any number of newline separated paragraphs.
            """;


    //private static  final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());
    private static  final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    private final McpSchema.ToolAnnotations COMMIT_ANNOTATIONS =
            new McpSchema.ToolAnnotations(null, false, true, false, true, null);
    private final McpSchema.ToolAnnotations READ_ONLY_ANNOTATIONS =
            new McpSchema.ToolAnnotations(null, true, false, true, true, null);

    private final BiFunction<String,String,String>  conventionalCommitPrompt = """
            Generate a git conventional commit message that summarizes the changes in <GIT_DIFF> following the rules below:
            
            1. The result MUST be in plain text format.
            2. The result MUST not be in markdown format.
            3. The result MUST not be surrounded by quotes or code blocks.
            4. The result MUST be compliant with <CONVENTIONAL_COMMIT_SPEC>.
            
            <GIT_DIFF>
            %s
            </GIT_DIFF>
            
            <CONVENTIONAL_COMMIT_SPEC>
            %s
            </CONVENTIONAL_COMMIT_SPEC>
            """::formatted;

    private final Function<String,String>  conventionalCommitPromptWithTools = """
            Generate a git message following the conventional commit specification <CONVENTIONAL_COMMIT_SPEC> to summarize the changes in <GIT_DIFF> following the rules below:
            
            * you must use tool 'diff' to achieve <GIT_DIFF>
            * The result MUST be in plain text format no markdown is allowed.
            * The result MUST not be surrounded by quotes or code blocks.
            * The result MUST be compliant with <CONVENTIONAL_COMMIT_SPEC>.
            
            <CONVENTIONAL_COMMIT_SPEC>
            %s
            </CONVENTIONAL_COMMIT_SPEC>
            """::formatted;

    public static McpSyncServer sync(McpServerTransportProvider transportProvider) {
        final var mcp = new CommitMcpServer();
        return McpServer.sync(transportProvider)
                .serverInfo("git-mcp-server", "0.1.0")
                .requestTimeout(Duration.ofSeconds(30))
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .logging()
                        .prompts(true)
                        .resources(false, false)
                        .tools(false)
                        .build())
                .resources(mcp.conventionalCommitResource())
                .prompts(mcp.commitPrompt(), mcp.commitPromptWithTools())
                .tools(mcp.commitTool(), mcp.listFilesTool(), mcp.diffTool())
                .build();

    }

    public static void main(String[] args) throws InterruptedException {
        var transport = new StdioServerTransportProvider(JSON_MAPPER);

        var server = sync(transport);

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));

        // Keep the JVM alive while stdio transport handles MCP messages.
        new CountDownLatch(1).await();
    }

    private CommitMcpServer() {}

    private McpServerFeatures.SyncResourceSpecification conventionalCommitResource() {
        String RESOURCE_URI = "resource:conventional-commit-spec";

        final var resource = McpSchema.Resource.builder()
                .name("conventional_commit_spec")
                .uri(RESOURCE_URI)
                .description("Short summary of the Conventional Commits specification")
                .mimeType("text/markdown")
                .build();

        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                new McpSchema.ReadResourceResult(
                        List.of(new McpSchema.TextResourceContents(
                                request.uri(),
                                "text/markdown",
                                CONVENTIONAL_COMMIT_SPEC
                        )))

        );
    }

    private McpServerFeatures.SyncPromptSpecification commitPrompt() {
        var prompt = new McpSchema.Prompt(
                "commit_prompt",
                "Conventional Commit",
                "Generate a Conventional Commit message from a diff and spec",
                List.of(
                        new McpSchema.PromptArgument("GIT_DIFF", "Git diff", "Git diff content", true),
                        new McpSchema.PromptArgument("COMMIT_SPEC", "Commit spec", "Commit specification text", true)
                )
        );

        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            var gitDiff = requiredString(request.arguments(), "GIT_DIFF");
            var commitSpec = requiredString(request.arguments(), "COMMIT_SPEC");
            var text = conventionalCommitPrompt.apply(gitDiff, commitSpec);

            return new McpSchema.GetPromptResult(
                    "Conventional Commit prompt",
                    List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
        });
    }

    private McpServerFeatures.SyncPromptSpecification commitPromptWithTools() {
        var prompt = new McpSchema.Prompt(
                "commit_prompt_with_tools",
                "Conventional Commit",
                "Generate a Conventional Commit message from a diff and spec using tools",
                List.of()
        );

    return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) ->
                new McpSchema.GetPromptResult(
                        "Conventional Commit prompt with tools",
                       List.of( new McpSchema.PromptMessage(
                                McpSchema.Role.USER,
                                new McpSchema.TextContent(conventionalCommitPromptWithTools.apply(CONVENTIONAL_COMMIT_SPEC))
                        ))));
    }

    private McpServerFeatures.SyncToolSpecification commitTool() {
        final var INPUT_SCHEMA = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["message", "filename"],
                  "properties": {
                    "message": { "type": "string", "minLength": 1, "description": "Commit message" },
                    "filename": { "type": "string", "minLength": 1, "description": "File to commit" }
                  }
                }
                """;
        final var OUTPUT_SCHEMA = """
                {
                  "type": "object",
                  "required": ["filename"],
                  "properties": {
                    "filename": { "type": "string" }
                  }
                }
                """;
        final var tool = McpSchema.Tool.builder()
                .name("commit")
                .title("Commit File")
                .description("Create a git commit for a specific file using the provided message")
                .inputSchema(JSON_MAPPER, INPUT_SCHEMA)
                .outputSchema(JSON_MAPPER, OUTPUT_SCHEMA)
                .annotations(COMMIT_ANNOTATIONS)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var arguments = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                        var message = requiredString(arguments, "message");
                        var filename = requiredString(arguments, "filename");

                        log(exchange, McpSchema.LoggingLevel.INFO,
                                "Committing file %s with message: %s".formatted(filename, message));

                            // Keep behavior equivalent to server.ts where commit execution is currently disabled.
                        final var result  =  Map.<String, Object>of("filename", filename );
                        return McpSchema.CallToolResult.builder()
                                .structuredContent(result)
                                .isError(false)
                                .build();

                    } catch (Exception e) {
                        return errorResult(e);
                    }
                })
                .build();
    }

    private McpServerFeatures.SyncToolSpecification listFilesTool() {
        final var OUTPUT_SCHEMA = """
                {
                  "type": "object",
                  "required": ["staged", "files"],
                  "properties": {
                    "staged": { "type": "boolean" },
                    "files": { "type": "array", "items": { "type": "string" } }
                  }
                }
                """;
        final var INPUT_SCHEMA = """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "staged": { "type": "boolean", "description": "Whether to list staged files" }
                  }
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("listFiles")
                .title("List Files")
                .description("List files by git status; when staged=true, list staged files")
                .inputSchema(JSON_MAPPER, INPUT_SCHEMA)
                .outputSchema(JSON_MAPPER, OUTPUT_SCHEMA)
                .annotations(READ_ONLY_ANNOTATIONS)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        final var staged = optionalBoolean(request.arguments(), "staged").orElse(false);

                        final var outputFuture = (staged) ?
                                runGit(exchange, "diff", "--cached", "--name-only") :
                                runGit(exchange, "status", "--porcelain");

                        return outputFuture.thenApply( output -> {
                            final var files = (staged) ?
                                    output.lines()
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .toList() :
                                    parseStatusLines(output);

                            var result = Map.<String, Object>of(
                                    "staged", staged,
                                    "files", files);

                            return McpSchema.CallToolResult.builder()
                                    .structuredContent(result)
                                    .isError(false)
                                    .build();
                        }).get();

                    } catch (Exception e) {
                        return errorResult(e);
                    }
                })
                .build();
    }

    private McpServerFeatures.SyncToolSpecification diffTool() {
        final var DIFF_INPUT_SCHEMA = new McpSchema.JsonSchema(
                "object", // type
                Map.of( // properties
                        "filename", Map.of("type", "string"),
                        "staged", Map.of("type", "boolean")

                ),
                List.of("filename", "staged"), // required
                false, // additionalProperties
                null, // defs
                null // definitionsa
        );

        final var  DIFF_OUTPUT_SCHEMA = """
                {
                  "type": "object",
                  "required": ["staged", "filename", "diff"],
                  "properties": {
                    "staged": { "type": "boolean" },
                    "filename": { "type": "string" },
                    "diff": { "type": "string" }
                  }
                }
                """;
        var tool = McpSchema.Tool.builder()
                .name("diff")
                .title("Get Diff")
                .description("Get diff for a file; staged controls cached vs working tree")
                .inputSchema(DIFF_INPUT_SCHEMA)
                .outputSchema(JSON_MAPPER, DIFF_OUTPUT_SCHEMA)
                .annotations(READ_ONLY_ANNOTATIONS)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        var staged = requiredBoolean(request.arguments(), "staged");
                        var filename = requiredString(request.arguments(), "filename");

                        var outputFuture = staged
                                ? runGit(exchange, "diff", "--cached", "--", filename)
                                : runGit(exchange, "diff", "--", filename);

                        return outputFuture.thenApply( output -> {
                            var result = Map.<String, Object>of(
                                    "staged", staged,
                                    "filename", filename,
                                    "diff", output
                            );

                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(output)
                                    .structuredContent(result)
                                    .isError(false)
                                    .build();

                        }).get();
                    } catch (Exception e) {
                        return errorResult(e);
                    }
                })
                .build();
    }

    private static void log( McpSyncServerExchange exchange, McpSchema.LoggingLevel level, String message) {
        exchange.loggingNotification(new McpSchema.LoggingMessageNotification(level, "git-mcp-server", message));
    }

    private static String read(InputStream stream) {
        try(stream) {
            return new String(stream.readAllBytes());
        }
        catch( IOException ex ) {
            throw new CompletionException(ex);
        }
    }

    private static CompletableFuture<String> runGit(McpSyncServerExchange exchange, String... args) {
        var cwd = ofNullable(System.getenv("CWD"))
                .orElseGet(() -> System.getProperty("CWD", "."));

        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        log(exchange, McpSchema.LoggingLevel.INFO,
                "Executing: git %s (CWD: %s)".formatted(String.join(" ", List.of(args)), cwd));

        final var processBuilder = new ProcessBuilder(command)
                                        .directory(Path.of(cwd).toFile())
                                        .redirectErrorStream(true);

        try {

            var process = processBuilder.start();

            final var stdoutFuture =
                    CompletableFuture.supplyAsync(() -> read(process.getInputStream()));

            return process.onExit().thenCombine( stdoutFuture, ( p, stdout ) -> {

                final var exitCode = p.exitValue();
                if( exitCode != 0 ) {
                    log(exchange, McpSchema.LoggingLevel.ERROR,
                            "Git command failed (exit=%d): %s".formatted(exitCode, String.join(" ", command)));
                    throw new CompletionException(new IOException("Git command failed with exit code %d. stderr: %s".formatted(exitCode, stdout)));
                }
                log(exchange, McpSchema.LoggingLevel.DEBUG, "Git stdout: " + stdout);

                return stdout;
            });

        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static List<String> parseStatusLines(String status) {
        return status.lines()
                .map(CommitMcpServer::parseStatusLine)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String parseStatusLine(String line) {
        var trimmed = line.stripTrailing();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.startsWith("?? ")) {
            return trimmed.substring(3);
        }
        if (trimmed.length() <= 3) {
            return "";
        }

        final var payload = trimmed.substring(3);
        final var arrowIndex = payload.indexOf("->");

        return (arrowIndex >= 0) ?
            payload.substring(arrowIndex + 2).trim() :
            payload.trim();
    }

    private static McpSchema.CallToolResult successJsonResult(Map<String, Object> payload) throws IOException {
           return  McpSchema.CallToolResult.builder()
                    .addTextContent(JSON_MAPPER.writeValueAsString(payload))
                    .structuredContent(payload)
                    .isError(false)
                    .build();
    }

    private McpSchema.CallToolResult errorResult(Exception error) {
        final var rootCause = getRootCause(error);
        return McpSchema.CallToolResult.builder()
                .addTextContent( ofNullable(rootCause.getMessage()).orElseGet(rootCause::toString))
                .isError(true)
                .build();
    }

    private String requiredString(Map<String,Object> arguments, String key) {
        final var value = ofNullable(arguments)
                .map( arg -> arg.get(key))
                .orElse( null );
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException("%s is required".formatted(key));
    }

    private boolean requiredBoolean(Map<String,Object> arguments, String key) {
        final var value = ofNullable(arguments)
                .map( arg -> arg.get(key))
                .orElse( null );
        if( value instanceof Boolean b ) {
            return b;
        }
        throw new IllegalArgumentException("%s is required".formatted(key));
    }

    private Optional<Boolean> optionalBoolean(Map<String,Object> arguments, String key) {
        final var value = ofNullable(arguments)
                            .map( arg -> arg.get(key))
                            .orElse(null);
        if (value != null) {
            if (value instanceof Boolean b) {
                return Optional.of(b);
            }
            if (value instanceof String s) {
                return Optional.of(Boolean.parseBoolean(s.toLowerCase(Locale.ROOT)));
            }
        }
        return Optional.empty();
    }

    private Throwable getRootCause(Throwable throwable) {
        requireNonNull(throwable);
        return Stream.iterate(throwable, Objects::nonNull, Throwable::getCause)
                .reduce((first, second) -> second)
                .orElse(throwable);
    }

}
