package org.bsc.langgraph4j.spring.ai.commit;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.WaitingDialog;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import io.modelcontextprotocol.spec.McpSchema;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.spring.ai.AiModel;
import org.bsc.langgraph4j.state.AgentState;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class CLI implements Closeable {

    private static void printUsage() {
        System.out.println("""
    Usage: <app>
    [--path=<repo>] [-p=<repo>]                 # repository path, default current folder
    [--staged=<true|false>] [-s=<true|false>]   # consider staged/unstaged files, default true
    [--model=<name>] [-m=<name>]                # ollama model name, default qwen3
    """);
    }

    record WaitingDialogHolder(WaitingDialog delegate, Label label)  {

        public static WaitingDialogHolder of(String title, String text) {
            final var dialog = WaitingDialog.createDialog(title, text);;

            final var panel = (Panel)dialog.getComponent();

            final var label = (Label)panel.getChildren()
                    .stream()
                    .filter( c -> c instanceof Label)
                    .findFirst()
                    .orElseThrow();
            return new WaitingDialogHolder(dialog, label);
        }

        public void open(MultiWindowTextGUI gui ) {
            delegate.showDialog(gui, true);
        }

        public void close() {
            delegate.close();
        }

        public void clear() {
            delegate.setTitle( "Commit Agent" );
            label.setText("");
        }

        public void  updateFromOutput( NodeOutput<CommitAgent.State> output ) {
            if(output.isEND() || output.isSTART()) return;

            output.state().fileToCommit().ifPresentOrElse( file ->
                    delegate.setTitle( "Commit Agent processing file [%s]".formatted( file )),
                    () -> delegate.setTitle( "Commit Agent" ));

            label.setText( "executing step: [%s] .....".formatted( output.node() ));
        }

        public void  updateFromMcpNotification( McpSchema.LoggingMessageNotification notification, TerminalSize size ) {
            label.setText( "Mcp[%s]: %s  ".formatted( notification.level().name(),
                    ellipsis(notification.data(), size.getColumns()-20) ));
        }
    }

    private final TerminalScreen screen;
    private final MultiWindowTextGUI gui;
    private final AtomicReference<WaitingDialogHolder> dialogHolder = new AtomicReference<>();
    private final WaitingDialogHolder waitingDialog;
    private final BasicWindow window;
    private final TextEditor textEditor;

    public CLI() throws IOException {
        this.screen = new DefaultTerminalFactory()
                .setMouseCaptureMode( MouseCaptureMode.CLICK_RELEASE_DRAG_MOVE )
                .createScreen();

        this.waitingDialog = WaitingDialogHolder.of("Commit Agent", "process");

        this.screen.startScreen();

        this.gui = new MultiWindowTextGUI(screen);

        this.window = new BasicWindow("Commit Description " );

        this.textEditor = TextEditor.builder()
                .terminalSize( screen.getTerminalSize() )
                .onClose(window::close)
                .build();
        this.window.setComponent(textEditor);


    }

    public void stop() throws IOException {
        requireNonNull(screen, "screen cannot be null").stopScreen();
    }

    @Override
    public void close() throws IOException {
        stop();
    }

    private static String ellipsis(String value, int size) {
        if (value == null || size <= 0) {
            return "";
        }
        if (value.length() <= size) {
            return value;
        }
        if (size <= 3) {
            return ".".repeat(size);
        }
        return value.substring(0, size - 3).concat( "...");
    }


    private void mainLoop( CompiledGraph<CommitAgent.State> agent,
                           Function<String,Optional<String>> textConfirmation) throws Exception {

        final var config = RunnableConfig.builder()
                .addMetadata( "USE_JSON_OUTPUT", false)
                .build();

        var input = GraphInput.noArgs();
        NodeOutput<CommitAgent.State> output;

        do {

            waitingDialog.clear();

            final AtomicReference<NodeOutput<CommitAgent.State>> outputHolder =
                    new AtomicReference<>(null);

            final var futureOutput = agent.stream(input, config).reduceAsync(outputHolder, ( a, b ) -> {
                // System.out.printf("reduce thread %s%n", Thread.currentThread().getName());
                gui.getGUIThread().invokeLater( () -> {

                    waitingDialog.updateFromOutput(b);

                    b.state().fileToCommit().ifPresentOrElse( file ->
                                    window.setTitle( "Commit Description [%s]".formatted( file )),
                            () -> window.setTitle( "Commit Description" ));
                });

                try {
                    Thread.sleep( 500 );
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                a.set(b);
                return a;
            }).thenApply( (a) -> {
                // System.out.printf("reduce.apply thread %s%n", Thread.currentThread().getName());
                gui.getGUIThread().invokeLater(waitingDialog::close);
                return ofNullable(a.get());
            });

            waitingDialog.open( gui );

            output = futureOutput.join().orElseThrow();

            if(output.isEND()) break;

            final var resumeData = output.state().commitDescription()
                    .flatMap(textConfirmation)
                    .map(text -> Map.<String, Object>of(CommitAgent.State.COMMIT_DESCRIPTION, text))
                    .orElse(Map.of(CommitAgent.State.COMMIT_DESCRIPTION, AgentState.MARK_FOR_REMOVAL));

            input = GraphInput.resume(resumeData);

        } while( !output.isEND() );
    }

    public void run( String[] args ) throws Exception {
        final var commandLine = CommandLine.parse(args);

        if (commandLine.flag("help", 'h')) {
            printUsage();
            return;
        }

        gui.setTheme(new SimpleTheme(
                TextColor.ANSI.WHITE,
                TextColor.ANSI.BLACK));


        final var repositoryPath = commandLine.option("path", 'p')
                .or(() -> commandLine.positionals().stream().findFirst())
                .map(Path::of)
                .orElse(Path.of("."));

        final var staged = commandLine.option("staged", 's')
                .flatMap(CommandLine::booleanOption)
                .orElse(true);

        final var modelName = commandLine.option("model", 'm')
                .orElse("qwen3");

        final var agent = CommitAgent.builder()
                //.chatModel( AiModel.OLLAMA.chatModel("qwen2.5:7b"))
                .chatModel( AiModel.OLLAMA.chatModel(modelName))
                .repositoryPath( repositoryPath )
                .staged( staged )
                .loggingConsumer( n -> waitingDialog.updateFromMcpNotification( n, screen.getTerminalSize() ) )
                .build();

        mainLoop(agent, (text) -> {

            textEditor.setInitValue(text);

            gui.addWindowAndWait(window);

            final var updatedText = textEditor.getText();

            return updatedText.isBlank() ?
                    Optional.empty() :
                    Optional.of(updatedText);
        });

    }

    public static void main(String[] args) throws Exception {

        try( final var cli = new CLI() ) {
            cli.run(args);
        }
        System.exit(0);
    }
}
