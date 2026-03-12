package org.bsc.langgraph4j.spring.ai.commit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.spec.McpSchema;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.serializer.std.SpringAIStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import reactor.core.publisher.Mono;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.bsc.langgraph4j.action.AsyncCommandAction.command_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.utils.CollectionsUtils.mergeMap;

public interface CommitAgent {

    record CommitOutput(
            @JsonPropertyDescription("commit type")
            @JsonProperty(value = "type", required = true)
            String type,
            @JsonPropertyDescription("commit scope")
            @JsonProperty("scope")
            String scope,
            @JsonPropertyDescription("commit description")
            @JsonProperty(value="description", required = true)
            String description,
            @JsonPropertyDescription("commit body")
            @JsonProperty("body")
            String body,
            @JsonPropertyDescription("commit footer")
            @JsonProperty("footers")
            String footers) {

        private Optional<String> sanitizeScope() {
            return ofNullable( scope ).map( s -> {
                try {
                    final var p = Paths.get(s);
                    final var fileName = Objects.toString(p.getFileName());
                    int dotIndex = fileName.lastIndexOf('.');
                    final var result =  (dotIndex > 0) ?
                            fileName.substring(0, dotIndex) :
                            fileName;
                    return "(%s)".formatted(result);
                } catch (InvalidPathException e) {
                    return "";
                }
            });

        }
        @Override
        public String toString() {
            return """
                    %s%s: %s
                    
                    %s
                    
                    %s
                    """.formatted( type,
                        sanitizeScope().orElse(""),
                        description,
                        ofNullable(body).orElse(""),
                        ofNullable(footers).orElse("") );
        }
    }

    class State extends MessagesState<Message> {

        public static final String FILES = "files";
        public static final String PROMPT = "prompt";
        public static final String COMMIT_DESCRIPTION = "commit_description";

        public static final Map<String, Channel<?>> SCHEMA =
                mergeMap( MessagesState.SCHEMA,
                        Map.of(FILES, Channels.base( () -> new LinkedHashSet<String>() )) );


        public State(Map<String, Object> initData) {
            super(initData);
        }

        Optional<String> commitPrompt() {
            return value(PROMPT);
        }

        public Set<String> filesToCommit() {
            return this.<Set<String>>value(FILES)
                    .orElseThrow();
        }

        public Set<String> filesToCommit$removeFirst() {
            return filesToCommit().stream().skip(1).collect(Collectors.toSet());
        }

        public Optional<String> commitDescription() {
            return value(COMMIT_DESCRIPTION);
        }

        public Optional<String> fileToCommit() {
            return filesToCommit().stream().findFirst();
        }
    }

    class RetrieveCommitPrompt implements AsyncNodeActionWithConfig<State> {

        private final McpGitAssistantClient mcpGitAssistant;

        public RetrieveCommitPrompt(McpGitAssistantClient mcpGitAssistant) {
            this.mcpGitAssistant = requireNonNull( mcpGitAssistant, "mcpGitAssistant cannot be null");

        }

        @Override
        public CompletableFuture<Map<String, Object>> apply(State state, RunnableConfig config) {
            return mcpGitAssistant.getCommitPromptWithTools()
                    .thenApply( prompt -> Map.of( State.PROMPT, prompt ));
        }
    }

    class ProcessCommitDescriptionWithTools implements AsyncNodeActionWithConfig<State> {

        private final McpGitAssistantClient mcpGitAssistant;
        private final ChatModel chatModel;
        private ChatClient chatClient;

        public ProcessCommitDescriptionWithTools(ChatModel chatModel, McpGitAssistantClient mcpGitAssistant) {
            this.mcpGitAssistant = requireNonNull( mcpGitAssistant, "mcpGitAssistant cannot be null");
            this.chatModel = requireNonNull( chatModel, "chatModel cannot be null");

        }

        @Override
        public CompletableFuture<Map<String, Object>> apply(State state, RunnableConfig config) {
            final var useJsonOutput = config.metadata("USE_JSON_OUTPUT")
                                        .map( v -> (Boolean)v ).orElse(false);
            final var lastMessage = state.lastMessage();

            if( lastMessage.isEmpty() ) {
                return failedFuture( new IllegalStateException("No message provided"));
            }

            if( lastMessage.get() instanceof UserMessage message ) {

                if (chatClient == null) { // lazy initialization
                    final var prompt = state.commitPrompt();

                    if( prompt.isEmpty() ) {
                        return failedFuture( new IllegalStateException("No prompt provided"));
                    }

                    chatClient = ChatClient.builder(chatModel)
                            .defaultSystem(prompt.get())
                            .defaultToolCallbacks(AsyncMcpToolCallbackProvider.builder()
                                    .mcpClients(mcpGitAssistant.delegate)
                                    .build())
                            .build();
                }

                if( useJsonOutput ) {
                    final var response = chatClient.prompt()
                            .messages( message )
                            .call()
                            .entity( CommitOutput.class )
                            ;

                    if( response == null ) {
                        return failedFuture( new IllegalStateException("No response provided") );
                    }
                    return completedFuture(Map.of( State.COMMIT_DESCRIPTION, response.toString() ));

                }
                else  {
                    final var response = chatClient.prompt()
                            .messages( message )
                            .call()
                            .chatResponse()
                            ;

                    if( response == null ) {
                        return failedFuture( new IllegalStateException("No response provided") );
                    }

                    return completedFuture(Map.of( State.COMMIT_DESCRIPTION, ofNullable(response.getResult().getOutput().getText()).orElseThrow() ));

                }
            }

            return failedFuture( new IllegalStateException("No user message provided"));
        }
    }

    static AsyncNodeActionWithConfig<State> filesToCommit(McpGitAssistantClient mcpGitAssistant, boolean staged ) {
        requireNonNull(mcpGitAssistant, "mcpGitAssistant cannot be null");
        return (state, config) -> mcpGitAssistant.listFiles(staged)
                .thenApply( files -> Map.of( State.FILES, files ) )
                ;
    }

    static AsyncCommandAction<State> nextFileToCommit( boolean staged) {

        return command_async( (state, config) -> {
            if( state.filesToCommit().isEmpty() ) {
                return new Command( StateGraph.END );
            }
            final var file = state.fileToCommit().orElseThrow();
            final var userMessage = UserMessage.builder()
                    .text("the %s file %s has been modified, let compute commit message"
                            .formatted( staged ? "staged" : "unstaged", file))
                    .build();
            return new Command( "generate_commit_message",
                    Map.of( State.MESSAGES_STATE, userMessage ));
        });
    }

    static AsyncNodeActionWithConfig<State> verifyCommitMessage(String issueRef ) {
        return (state, config) ->
                state.commitDescription()
                    .map(description ->
                        ofNullable(issueRef)
                            .map( issue -> "%s\n\nworking on %s".formatted(description,issue))
                            .orElse(description))
                .map(description ->
                    completedFuture( Map.<String,Object>of( State.COMMIT_DESCRIPTION, description )) )
                .orElseGet(() -> completedFuture(Map.of()));
    }

    static AsyncNodeActionWithConfig<State> executeCommit( McpGitAssistantClient mcpGitAssistant, boolean staged ) {
        requireNonNull( mcpGitAssistant, "mcpGitAssistant cannot be null");
        return (state, config) -> state.commitDescription()
                .flatMap(description ->
                    state.fileToCommit()
                            .map(file -> mcpGitAssistant.commit(description, file, staged)))
                .orElseGet(() -> completedFuture(null))
                .thenApply($1 -> Map.<String, Object>of(
                        State.FILES, state.filesToCommit$removeFirst()));
    }

    static NodeHook.AfterCall<State> resetAttribute( String attributeKey ) {
        return (nodeId, state, config,lastResult ) ->
                // Remove Messages
                completedFuture( mergeMap( lastResult,
                        Map.of( attributeKey, AgentState.MARK_FOR_RESET) ));

    }

    static NodeHook.AfterCall<State> removeAttribute( String attributeKey ) {
        return (nodeId, state, config,lastResult ) ->
                // Remove Messages
                completedFuture( mergeMap( lastResult,
                        Map.of( attributeKey, AgentState.MARK_FOR_REMOVAL) ));

    }

    class Builder {

        private ChatModel chatModel;
        final StateSerializer<State> stateSerializer;
        private Path repositoryPath;
        private Consumer<McpSchema.LoggingMessageNotification> loggingConsumer;
        private boolean staged = false;
        private String issueRef;

        public Builder chatModel( ChatModel chatModel ) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder repositoryPath( Path path ) {
            this.repositoryPath = path;
            return this;
        }

        public Builder loggingConsumer( Consumer<McpSchema.LoggingMessageNotification> loggingConsumer ) {
            this.loggingConsumer = loggingConsumer;
            return this;
        }

        public Builder staged( boolean staged ) {
            this.staged = staged;
            return this;
        }

        public Builder issue(String issueRef) {
            this.issueRef = issueRef;
            return this;
        }

        private Builder() {
            stateSerializer = new SpringAIStateSerializer<>(State::new);
            //stateSerializer = new SpringAIJacksonStateSerializer<>(State::new);

        }
        private CompiledGraph<State> generateCommitAgent( McpGitAssistantClient mcpGitAssistant ) throws GraphStateException {

            final var compileConfig = CompileConfig.builder().build();

            return new StateGraph<>( State.SCHEMA, stateSerializer)
                    .addNode( "retrieve_commit_prompt",
                            new RetrieveCommitPrompt(mcpGitAssistant) )
                    .addNode( "process_commit_description_with_tools",
                            new ProcessCommitDescriptionWithTools(chatModel, mcpGitAssistant) )
                    .addConditionalEdges( StateGraph.START, edge_async( state ->
                                    state.commitPrompt()
                                            .map(v -> "process_commit_description_with_tools")
                                            .orElse("retrieve_commit_prompt")),
                            EdgeMappings.builder()
                                    .to( "retrieve_commit_prompt" )
                                    .to( "process_commit_description_with_tools" )
                                    .build())
                    .addEdge( "retrieve_commit_prompt", "process_commit_description_with_tools" )
                    .addEdge("process_commit_description_with_tools", StateGraph.END  )
                    .compile(compileConfig);

        }


        public CompiledGraph<State> build() throws GraphStateException {

            final var loggingConsumer = ofNullable(this.loggingConsumer)
                    .orElseGet( () -> notification -> {} );

            final var mcpGitAssistant = McpGitAssistantClient.builder()
                    .mcpPath( Path.of(".") )
                    .repositoryPath( requireNonNull(repositoryPath, "repositoryPath cannot be null") )
                    .build( spec ->
                            spec.requestTimeout(Duration.ofSeconds(10))
                                    .loggingConsumer(notification ->
                                            Mono.create(sink -> loggingConsumer.accept(notification)))
                                                    //System.out.printf("%s - %s%n", notification.level(), notification.data())))
                                    .build());

            final var saver = new MemorySaver();
            final var compileConfig = CompileConfig.builder()
                    .checkpointSaver( saver )
                    .interruptBefore("execute_commit")
                    .releaseThread(true)
                    .build();

            return new StateGraph<>( State.SCHEMA, stateSerializer)
                    .addNode( "get_files_to_commit", filesToCommit(mcpGitAssistant, staged) )
                    .addAfterCallNodeHook( "get_files_to_commit", resetAttribute(State.MESSAGES_STATE))
                    .addNode( "generate_commit_message", generateCommitAgent( mcpGitAssistant ) )
                    // fix when the interruption is after a subgraph move out from the subgraph
                    .addNode( "verify_commit_message", verifyCommitMessage( issueRef) )
                    .addNode( "execute_commit", executeCommit(mcpGitAssistant, staged ) )
                    .addAfterCallNodeHook( "execute_commit", removeAttribute(State.MESSAGES_STATE))
                    .addNode( "next_file_to_commit",
                            nextFileToCommit( staged ),
                            EdgeMappings.builder()
                                .toEND()
                                .to( "generate_commit_message" )
                                .build())
                    .addEdge( StateGraph.START, "get_files_to_commit")
                    .addEdge( "get_files_to_commit", "next_file_to_commit")
                    .addEdge( "generate_commit_message", "verify_commit_message")
                    .addEdge( "verify_commit_message", "execute_commit")
                    .addEdge( "execute_commit", "next_file_to_commit")
                    .compile( compileConfig );
        }

    }


    static Builder builder() {
        return new Builder();
    }

}
