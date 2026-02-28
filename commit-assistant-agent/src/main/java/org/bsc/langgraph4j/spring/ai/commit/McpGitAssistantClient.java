package org.bsc.langgraph4j.spring.ai.commit;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.inmemory.InMemoryClientTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryServerTransportProvider;
import io.modelcontextprotocol.transport.inmemory.InMemoryTransport;
import org.bsc.mcp.CommitMcpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class McpGitAssistantClient {
    public final McpAsyncClient delegate;

    final static class InMemoryClientTransportForMcpCommitAssistant extends InMemoryClientTransport {
        final McpSyncServer mcpServer;

        public InMemoryClientTransportForMcpCommitAssistant(Path repositoryPath) {
            super(new InMemoryTransport());

            final var serverProvider = new InMemoryServerTransportProvider(transport());

            System.setProperty("CWD", repositoryPath.toAbsolutePath().toString());

            mcpServer = CommitMcpServer.sync(serverProvider);
        }
    }

    public static class Builder {
        private Path mcpPath;
        private Path repositoryPath;

        public Builder mcpPath( Path path ) {
            this.mcpPath = path;
            return this;
        }

        public Builder repositoryPath( Path path ) {
            this.repositoryPath = path;
            return this;
        }

        McpGitAssistantClient buildExternal(Function<McpClient.AsyncSpec, McpAsyncClient> builder ) {

            requireNonNull( mcpPath, "repositoryPath cannot be null");
            requireNonNull( repositoryPath, "repositoryPath cannot be null");
            requireNonNull( builder, "builder cannot be null" );

            final var argsPath = Path.of( mcpPath.toString(), "mcp-commit-assistant", "server.ts" );

            final var stdioParams = ServerParameters.builder("bun")
                    .args( argsPath.toString() )
                    .env(Map.of("CWD", repositoryPath.toAbsolutePath().toString()))
                    .build();
            final var asyncSpec = McpClient.async(new StdioClientTransport(stdioParams, McpJsonMapper.createDefault()));
            return new McpGitAssistantClient( builder.apply( asyncSpec ) );

        }

        McpGitAssistantClient build(Function<McpClient.AsyncSpec, McpAsyncClient> builder ) {

            requireNonNull( mcpPath, "repositoryPath cannot be null");
            requireNonNull( repositoryPath, "repositoryPath cannot be null");
            requireNonNull( builder, "builder cannot be null" );

            final var asyncSpec = McpClient.async(new InMemoryClientTransportForMcpCommitAssistant(repositoryPath));

            return new McpGitAssistantClient( builder.apply( asyncSpec ) );

        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private McpGitAssistantClient(McpAsyncClient mcpAsyncClient) {
        this.delegate = requireNonNull(mcpAsyncClient, "mcpAsyncClient cannot be null");
    }

    public CompletableFuture<McpSchema.InitializeResult> initialize() {
        final var futureResult = new CompletableFuture<McpSchema.InitializeResult>();
        delegate.initialize().subscribe(futureResult::complete, futureResult::completeExceptionally);
        return futureResult;
    }

    public CompletableFuture<String> getCommitPromptWithTools() {

        final var futureResult = new CompletableFuture<String>();

        delegate.initialize().flatMap( init ->
            delegate.getPrompt(new McpSchema.GetPromptRequest(
                    "commit_prompt_with_tools",
                    Map.of()))).subscribe(result -> {

                if (result.messages().size() == 1) {
                    if (result.messages().getFirst().content() instanceof McpSchema.TextContent textContent) {
                        futureResult.complete(textContent.text());
                    }
                } else {
                    futureResult.completeExceptionally(new IllegalStateException("No prompt found"));
                }

            }, futureResult::completeExceptionally );

        return futureResult;
    }

    public CompletableFuture<Set<String>> listFiles( boolean staged ) {
        final var futureResult = new CompletableFuture<Set<String>>();

        delegate.initialize().flatMap( init ->
                delegate.callTool(new McpSchema.CallToolRequest(
                    "listFiles",
                    Map.of("staged", staged)))).subscribe( result -> {

                        if( result.structuredContent() instanceof Map<?,?> map ) {

                            if( map.containsKey("files") ) {
                                @SuppressWarnings("unchecked")
                                final var files = ((List<String>) map.get("files")).stream()
                                        .map(Paths::get)
                                        //.peek(System.out::println)
                                        .filter( p -> !Files.isDirectory( p ) )
                                        .map(Path::toString)
                                        .collect(Collectors.toSet());
                                futureResult.complete( files );
                            }
                        }
                        else {
                            futureResult.complete(Set.of());
                        }

                    }, futureResult::completeExceptionally );

        return futureResult;
    }

    public CompletableFuture<Void> commit( String  message, String filename, boolean staged ) {
        final var futureResult = new CompletableFuture<Void>();

        delegate.initialize().flatMap( init ->
            delegate.callTool(new McpSchema.CallToolRequest(
                    "commit",
                    Map.of("message", message, "filename", filename, "staged", staged, "caller", "user"))))
                    .subscribe( result ->
                            futureResult.complete( null ),
                            futureResult::completeExceptionally );

        return futureResult;
    }


}
