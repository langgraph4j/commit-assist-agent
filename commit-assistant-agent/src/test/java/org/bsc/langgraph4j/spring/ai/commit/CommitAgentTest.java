package org.bsc.langgraph4j.spring.ai.commit;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.spring.ai.AiModel;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommitAgentTest {


    public static void main( String[] args) throws Exception {

        final var agent = CommitAgent.builder()
                .chatModel( AiModel.OLLAMA.chatModel("qwen2.5:7b"))
                .repositoryPath( Path.of( "/Users/bsorrentino/WORKSPACES/GITHUB.langgraph4j/langgraph4j" ))
                .build();

        // System.out.println(agent.getGraph( GraphRepresentation.Type.MERMAID, "", false).content());

        final var config = RunnableConfig.builder().build();

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

            input = GraphInput.resume();

        } while( !output.isEND() );

        System.exit(0);
    }

}
