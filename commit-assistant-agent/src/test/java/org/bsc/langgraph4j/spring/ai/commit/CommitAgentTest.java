package org.bsc.langgraph4j.spring.ai.commit;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.spring.ai.AiModel;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommitAgentTest {


    public static void main( String[] args) throws Exception {

        final var agent = CommitAgent.builder()
                .chatModel( AiModel.OLLAMA.chatModel("qwen3"))
                .repositoryPath( Path.of( "/Users/bsorrentino/WORKSPACES/GITHUB.langgraph4j/langgraph4j-commit-assistant-springai" ))
                .staged( false )
                .loggingConsumer(System.out::println)
                .build();

        // System.out.println(agent.getGraph( GraphRepresentation.Type.MERMAID, "", false).content());

        final var config = RunnableConfig.builder()
                .addMetadata( "USE_JSON_OUTPUT", false)
                .build();

        GraphInput input = GraphInput.noArgs();
        NodeOutput<CommitAgent.State> output;

        do {
            final var iterator = agent.stream(input, config);

            final var optionalOutput = iterator.stream().reduce((a, b) -> b);
            assertNotNull(optionalOutput);
            assertTrue(optionalOutput.isPresent());

            output = optionalOutput.get();

            //assertTrue(output.state().lastMessage().isPresent());

            final var result = GraphResult.from(iterator);

            assertNotNull(result);

            input = GraphInput.resume( Map.of(CommitAgent.State.COMMIT_DESCRIPTION, "TEST") );

            System.out.printf( "commit description %n%s%n", output.state().commitDescription().orElse("no description") );

        } while( !output.isEND() );

        System.out.printf( "commit description %n%s%n", output.state().commitDescription().orElse("no description") );

        System.exit(0);
    }

}
