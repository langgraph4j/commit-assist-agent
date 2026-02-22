//DEPS com.fasterxml.jackson.core:jackson-core:2.21.0
//DEPS org.bsc.langgraph4j:langgraph4j-javelit:1.8-SNAPSHOT
//DEPS net.sourceforge.plantuml:plantuml-mit:1.2025.10
//DEPS org.springframework.ai:spring-ai-bom:1.1.2@pom
//DEPS org.springframework.ai:spring-ai-client-chat
//DEPS org.springframework.ai:spring-ai-openai
//DEPS org.springframework.ai:spring-ai-ollama
//DEPS org.springframework.ai:spring-ai-mcp
//DEPS org.bsc.langgraph4j:langgraph4j-springai-agentexecutor:1.8.2

//SOURCES org/bsc/langgraph4j/spring/ai/commit/javelit/*.java

import io.javelit.core.Jt;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.bsc.javelit.JtMultiSelect;
import org.bsc.javelit.JtSelectAiModel;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.spring.ai.AiModel;
import org.bsc.langgraph4j.spring.ai.commit.javelit.JtCacheValue;
import org.bsc.langgraph4j.spring.ai.commit.javelit.CommitData;
import org.bsc.langgraph4j.spring.ai.commit.javelit.JtSessionValue;
import org.bsc.langgraph4j.spring.ai.serializer.std.SpringAIStateSerializer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;


public class JtCommitAssistantApp {
    public static void main(String[] args) {

        var app = new JtCommitAssistantApp();
        app.view();
    }


    final JtCacheValue<Set<CommitData>> processedFilesState =
                    new JtCacheValue<>("processed-files");
    final JtCacheValue<Set<String>> selectedFilesState =
                    new JtCacheValue<>("selected-files");
    final JtCacheValue<Set<String>> listFilesState =
                    new JtCacheValue<>("files");
    final JtCacheValue<String> promptState =
                    new JtCacheValue<>("prompt");
    final JtSessionValue<McpSyncClient> mcpSyncClientState =
                    new JtSessionValue<>("mcp_commit_assistant");
    final JtSessionValue<Boolean> proceedState = new JtSessionValue<>("proceed");

    public void view() {
        Jt.title("LangGraph4J Commit Assistant").use();
        Jt.markdown("### Powered by LangGraph4j and SpringAI").use();

        var modelOptional = JtSelectAiModel.get();
        //var streaming = Jt.toggle("Streaming output").value(false).use();

        Jt.divider().use();

        if (modelOptional.isEmpty()) return;

        var model = modelOptional.get();

        var chatModel = switch (model.provider()) {
            case OPENAI -> AiModel.OPENAI.chatModel(model.name(), model.attributes());
            case GITHUB -> AiModel.GITHUB_MODEL.chatModel(model.name(), model.attributes());
            case VERTEX -> {
                Jt.error("GEMINI IS NOT SUPPORTED YET");
                yield null;
            }
            case OLLAMA -> AiModel.OLLAMA.chatModel(model.name(), model.attributes());
        };

        final var repositoryPath = Path.of(".").toAbsolutePath().toString();

        Jt.markdown("**Repository Path:** *%s*".formatted(repositoryPath)).use();

        setupMcpClient( repositoryPath );

        var selectedFiles = JtMultiSelect.builder()
                .disabled( chatModel == null )
                .items(listFiles(false))
                .use();

        Jt.button("proceed")
                    .onClick(b -> proceedState.setValue(true) )
                    .disabled( selectedFiles.isEmpty() )
                    .use();

        if (proceedState.value().orElse(false)) {

            //final var agent = buildAgent( chatModel, false );
            //final var config = RunnableConfig.builder().build();

            for (var candidateFile : selectedFiles) {

                    /*
                    var spinner = SpinnerComponent.builder()
                            .message("**starting the agent** ....")
                            .showTime(true)
                            .use(commitForm);
                    */
                    //final var userMessage = "the unstaged file %s has been modified, let compute commit message".formatted(file);
                    //final var result = agent.invoke(GraphInput.args( Map.of("messages", new UserMessage(userMessage))), config);

                    final var result = Optional.of("build(pom.xml): add new module spring-ai/commit-assistant, filename: pom.xml");

                    result.ifPresentOrElse(text -> {
                        //final var text = r.lastMessage().map(Content::getText).orElseThrow();

                        final var form = Jt.form()
                                .key("commit_form_%s".formatted(candidateFile))
                                .use();
                        final var textToCommit = Jt.textArea("Commit description")
                                .key("commit_text_%s".formatted(candidateFile))
                                .value(text)
                                .use(form);
                        final var proceedToCommit = Jt.formSubmitButton("commit")
                                .key("commit_button_%s".formatted(candidateFile))
                                .use(form);

                        System.out.printf( "Proceed to commit '%b'%n", proceedToCommit);
                        if( proceedToCommit ) {
                            commit(textToCommit, candidateFile);
                        }

                    }, () -> Jt.error("result is not present!"));

                }

            //closeMcpClient();
        }
    }

    public CompiledGraph<AgentExecutor.State> buildAgent( ChatModel chatModel, boolean streaming)  {

        final var mcpSyncClient = getMcpSyncClient();

        final var instructions = loadPrompt();

        var stateSerializer = new SpringAIStateSerializer<>(AgentExecutor.State::new);
        // Fix problem with Gemini logprobs serialization
        //stateSerializer.mapper().register(VertexAiGeminiApi.LogProbs.class, new LogProbsSerializer());

        var compileConfig = CompileConfig.builder().build();

        try {
            return AgentExecutor.builder()
                    .stateSerializer(stateSerializer)
                    .chatModel(chatModel, streaming)
                    .defaultSystem( instructions )
                    .tools(SyncMcpToolCallbackProvider.builder()
                            .mcpClients( List.of(mcpSyncClient))
                            .build())
                    .build()
                    .compile(compileConfig);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Agent compilation failed", e);
        }

    }

    public McpSyncClient setupMcpClient( String repositoryPath )  {

        final var mcp = mcpSyncClientState.computeIfAbsent(  (key) -> {
            try {
                System.out.printf( "setupMcpClient: repository path: %s%n", repositoryPath);

                var stdioParams = ServerParameters.builder("bun")
                        .args("spring-ai/commit-assistant/mcp-commit-assistant/server.ts")
                        .env(Map.of("CWD", repositoryPath))
                        .build();
                var mcpGitClient = McpClient.sync(new StdioClientTransport(stdioParams, McpJsonMapper.createDefault()))
                        .requestTimeout(Duration.ofSeconds(10))
                        .loggingConsumer( notification -> {
                            System.out.printf("%s - %s%n", notification.level(), notification.data());
                        })
                        .build();


                mcpGitClient.initialize();
                return mcpGitClient;
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        });

        if( mcp == null ) {
            throw new IllegalStateException("No MCP client found");
        }

        return mcp;
    }

    public void closeMcpClient() {
        mcpSyncClientState.clear().ifPresent( previousMcp ->{
            listFilesState.clear();
            previousMcp.close();
            System.out.println("MCP client closed");

        });
    }

    public void commit( String  message, String filename ) {
        final var mcp = getMcpSyncClient();

        final var result = mcp.callTool(new McpSchema.CallToolRequest(
                "commit",
                Map.of("message", message, "filename", filename)));

        if (result.structuredContent() instanceof Map<?, ?> map) {
            System.out.printf("commit result: %s%n", map);
        }
    }

    public String loadPrompt() {
        final var mcp = getMcpSyncClient();

        final var prompt = promptState.computeIfAbsent((key) -> {

            final var result = mcp.getPrompt(new McpSchema.GetPromptRequest(
                    "commit_prompt_with_tools",
                    Map.of()));
            if (result.messages().size() == 1) {
                if (result.messages().getFirst().content() instanceof McpSchema.TextContent textContent) {
                    return textContent.text();
                }
            }
            return null;
        });

        if( prompt == null ) {
            throw new IllegalStateException("No prompt found");
        }
        return prompt;
    }


    @SuppressWarnings("unchecked")
    public Set<String> listFiles( boolean staged ) {
        final var mcp = getMcpSyncClient();

        return listFilesState.computeIfAbsent( (key) -> {
            final var result = mcp.callTool(new McpSchema.CallToolRequest(
                    "listFiles",
                    Map.of("staged", staged)));

            if( result.structuredContent() instanceof Map<?,?> map ) {

                if( map.containsKey("files") ) {

                    return ((List<String>) map.get("files")).stream()
                            .map(Paths::get)
                            .peek(System.out::println)
                            .filter( p -> !Files.isDirectory( p ) )
                            .map(Path::toString)
                            .collect(Collectors.toSet());
                }
            };
            return Set.of();
        });
    }


    public McpSyncClient getMcpSyncClient() {
        return mcpSyncClientState.value()
                .orElseThrow(() -> new IllegalStateException("No MCP client found"));
    }
}
