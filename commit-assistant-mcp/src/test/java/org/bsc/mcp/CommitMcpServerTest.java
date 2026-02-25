package org.bsc.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.inmemory.InMemoryClientTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryServerTransportProvider;
import io.modelcontextprotocol.transport.inmemory.InMemoryTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CommitMcpServerTest {

    static final InMemoryTransport transport = new InMemoryTransport();

    static McpSyncServer mcpServer;

    @BeforeAll
    public static void createSyncMCPServer() {
        var serverProvider = new InMemoryServerTransportProvider(transport);

        mcpServer = CommitMcpServer.sync(serverProvider);
    }

    @AfterAll
    public static void shutdownMCPServer() {
        mcpServer.close();
    }

    McpSyncClient mcpClient;

    @BeforeEach
    public void createClient() {

        final var clientTransport = new InMemoryClientTransport(transport);

        System.setProperty("CWD", ".");

        mcpClient = McpClient.sync( clientTransport )
                .loggingConsumer(System.out::println)
                .build();

        mcpClient.initialize();

    }

    public void closeMcpClient() {
        mcpClient.close();
    }

    @Test
    public void listFiles() {

        assertNotNull( mcpClient );

        var result = mcpClient.callTool( McpSchema.CallToolRequest.builder()
                        .name("listFiles")
                        .arguments( Map.of("staged", true) )
                        .build() );


        assertNotNull(result);
        assertFalse(result.isError());
        assertNotNull(result.structuredContent());
        assertInstanceOf( Map.class, result.structuredContent());
        System.out.println( result );
    }

    @Test
    public void diff() {

        assertNotNull( mcpClient );

        final var staged = true;

        final var listFilesResult = mcpClient.callTool( McpSchema.CallToolRequest.builder()
                .name("listFiles")
                .arguments( Map.of("staged", staged) )
                .build() );


        assertNotNull(listFilesResult);
        assertFalse(listFilesResult.isError());
        assertNotNull(listFilesResult.structuredContent());
        assertInstanceOf( Map.class, listFilesResult.structuredContent());

        @SuppressWarnings("unchecked")
        final var map = (Map<String,Object>)listFilesResult.structuredContent();

        assertTrue( map.containsKey("files"));
        assertInstanceOf( List.class, map.get("files"));

        @SuppressWarnings("unchecked")
        final var files = (List<String>)map.get("files");

        for( var file : files ) {

            final var diffResult = mcpClient.callTool( McpSchema.CallToolRequest.builder()
                    .name("diff")
                    .arguments( Map.of("filename", file, "staged", staged) )
                    .build() );

            System.out.println( diffResult );

        }

    }

}
