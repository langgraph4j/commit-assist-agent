package org.bsc.langgraph4j.spring.ai.commit;

import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class MCPTest {

    static McpGitAssistant mcpGitClient;

    @BeforeAll
    public static void setup() throws Exception {

        mcpGitClient = McpGitAssistant.builder()
                .mcpPath( Path.of(".") )
                .repositoryPath( Path.of( "/","tmp", "test") )
                .build( spec ->
                        spec.requestTimeout(Duration.ofSeconds(10))
                            .loggingConsumer(  notification ->
                                    Mono.create(sink ->
                                            System.out.printf("%s - %s%n", notification.level(), notification.data())))
                            .build());

        final var init = mcpGitClient.delegate.initialize().block();
        assertNotNull(init);
        final var capabilities = init.capabilities();
        assertNotNull(capabilities.logging());

    }


    @AfterAll
    public static void tearDown()  {
        mcpGitClient.delegate.close();
    }

    /*
    @Test
    public void resources() throws Exception {

        final var result = mcpGitClient.listResources();
        assertNotNull( result );
        final var resources = result.resources();
        assertFalse( resources.isEmpty() );
        assertEquals( 1, resources.size() );
        final var commitSpec = resources.getFirst();
        assertEquals( "conventional_commit_spec", commitSpec.name() );
        assertEquals( "resource:conventional-commit-spec", commitSpec.uri());
        assertEquals( "text/markdown", commitSpec.mimeType());
    }
    */

    /*
    @Test
    public void prompts() throws Exception {
        final var result = mcpGitClient.listPrompts();
        assertNotNull( result );
        final var prompts = result.prompts();
        assertFalse( prompts.isEmpty() );
        assertEquals( 2, prompts.size() );
        final var commitPrompt = prompts.getFirst();
        assertEquals( "commit_prompt", commitPrompt.name() );
        var arguments = commitPrompt.arguments();
        assertIterableEquals( List.of("GIT_DIFF", "COMMIT_SPEC" ), arguments.stream().map(McpSchema.PromptArgument::name).toList());

        final var finalPrompt = mcpGitClient.getPrompt( new McpSchema.GetPromptRequest(
                "commit_prompt",
                Map.of( "GIT_DIFF", "DIFF", "COMMIT_SPEC", "SPEC" )
        ) );

        assertNotNull( finalPrompt );
    }
    */
    @Test
    public void listFiles() throws Exception {

        final var result = mcpGitClient.listFiles( false ).join();
        assertNotNull( result );
    }
    @Test
    public void getPrompt() throws Exception {

        final var result = mcpGitClient.getCommitPromptWithTools().join();
        assertNotNull( result );
    }

}
