package org.bsc.langgraph4j.spring.ai.commit;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.WaitingDialog;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.spring.ai.AiModel;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class CLI {

    static final class TextEditor {

        final TextBox textBox;
        String initValue;

        public TextEditor(TextBox textBox) {
            this.textBox = textBox;
        }

        public void setInitValue(String initValue) {
            this.initValue = initValue;
            textBox.setText(initValue);
        }

        public String getInitialValue() {
            return initValue;
        }

        public String getText() {
            return textBox.getText();
        }

        public void setText( String text ) {
            textBox.setText(text);
        }
    }

    static final class WaitingDialogHolder {
        final WaitingDialog delegate;
        final Label label;

        public static WaitingDialogHolder of( String title, String text ) {
            final var dialog = WaitingDialog.createDialog(title, text);
            return new WaitingDialogHolder(dialog);
        }

        private WaitingDialogHolder(WaitingDialog dialog) {
            this.delegate = dialog;

            final var panel = (Panel)dialog.getComponent();

            label = (Label)panel.getChildren()
                    .stream()
                    .filter( c -> c instanceof Label)
                    .findFirst()
                    .orElseThrow();
        }

        public void  updateFromOutput( NodeOutput<CommitAgent.State> output ) {
            if(output.isEND() || output.isSTART()) return;

            output.state().fileToCommit().ifPresentOrElse( file ->
                    delegate.setTitle( "Commit Agent processing file [%s]".formatted( file )),
                    () -> delegate.setTitle( "Commit Agent" ));

            label.setText( "executing step: [%s] .....".formatted( output.node() ));
        }
    }

    private void mainLoop( CompiledGraph<CommitAgent.State> agent,
                           MultiWindowTextGUI gui,
                           BasicWindow window,
                           Function<String,Optional<String>> textConfirmation) throws Exception {

        final var config = RunnableConfig.builder().build();

        var input = GraphInput.noArgs();
        NodeOutput<CommitAgent.State> output;

        final var dialog = WaitingDialogHolder.of("Commit Agent", "process");

        do {

            final AtomicReference<NodeOutput<CommitAgent.State>> outputHolder =
                    new AtomicReference<>(null);

            final var futureOutput = agent.stream(input, config).reduceAsync(outputHolder, ( a, b ) -> {
                // System.out.printf("reduce thread %s%n", Thread.currentThread().getName());
                gui.getGUIThread().invokeLater( () -> {

                    dialog.updateFromOutput(b);

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
                gui.getGUIThread().invokeLater(dialog.delegate::close);
                return Optional.ofNullable(a.get());
            });

            dialog.delegate.showDialog( gui, true);

            final var optionalOutput = futureOutput.join();

            output = optionalOutput.orElseThrow();

            final var resumeData = output.state().commitDescription()
                    .flatMap(textConfirmation)
                    .map( text -> Map.<String,Object>of(CommitAgent.State.COMMIT_DESCRIPTION, text ))
                    .orElse( Map.of() );

            input = GraphInput.resume( resumeData );

        } while( !output.isEND() );
    }

    public void run( String[] args ) throws Exception {
        final Screen screen = new DefaultTerminalFactory().createScreen();

        screen.startScreen();
        var terminalSize = screen.getTerminalSize();

        final var gui = new MultiWindowTextGUI(screen);

        gui.setTheme(new SimpleTheme(
                TextColor.ANSI.WHITE,
                TextColor.ANSI.BLACK));

        final var window = new BasicWindow("Commit Description " + terminalSize);

        final var  panel = new Panel(new LinearLayout(Direction.VERTICAL));

        final var editor = new TextEditor(
                new TextBox(new TerminalSize(terminalSize.getColumns()-4, 5), "")
                        .setVerticalFocusSwitching(true)
                        .setReadOnly(false));

        final var buttons = new Panel(new LinearLayout(Direction.HORIZONTAL))
                        .addComponent(new Button("Save", window::close))
                        .addComponent(new Button("Cancel", () -> {
                            editor.setText( editor.getInitialValue() );
                            window.close();
                        }))
                        .addComponent(new Button("Skip", () -> {
                            editor.setText("");
                            window.close();
                        }));
        panel.addComponent(editor.textBox)
                .addComponent(buttons);
        window.setComponent(panel);

        final var repositoryPath = ( args.length > 0 ) ?
                Path.of( args[0] ):
                Path.of( "." );

        final var staged = args.length >= 1 && Boolean.parseBoolean(args[1]);
        final var agent = CommitAgent.builder()
                .chatModel( AiModel.OLLAMA.chatModel("qwen2.5:7b"))
                //.chatModel( AiModel.OLLAMA.chatModel("qwen3"))
                .repositoryPath( repositoryPath )
                .staged( staged )
                .build();

        mainLoop(agent, gui, window,  (text) -> {

            editor.setInitValue(text);

            gui.addWindowAndWait(window);

            final var updatedText = editor.getText();

            return updatedText.isBlank() ?
                    Optional.empty() :
                    Optional.of(updatedText);
        });


        screen.stopScreen();

    }

    public static void main(String[] args) throws Exception {

        final var cli = new CLI();
        cli.run(args);
        System.exit(0);
    }
}
