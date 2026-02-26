package org.bsc.langgraph4j.spring.ai.commit;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.WaitingDialog;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import io.modelcontextprotocol.spec.McpSchema;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.spring.ai.AiModel;

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

    public static final class TextEditor extends Panel {

        private record TextPoint(int row, int column) {}

        private record SelectionRange(TextPoint start, TextPoint end) {}

        private static final class SelectableTextBox extends TextBox {
            private static final TextColor SELECTION_BACKGROUND = TextColor.ANSI.BLUE_BRIGHT;
            private static final TextColor SELECTION_FOREGROUND = TextColor.ANSI.WHITE;

            private TextPoint selectionAnchor;
            private TextPoint selectionCaret;
            private String clipboard = "";

            private SelectableTextBox(TerminalSize size, String initialValue) {
                super(size, initialValue);
                setRenderer(new SelectionTextBoxRenderer());
            }

            @Override
            public synchronized TextBox setText(String text) {
                clearSelection();
                return super.setText(text);
            }

            @Override
            public synchronized Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
                final var beforeCaret = toPoint(getCaretPosition());
                final boolean ctrlDown = keyStroke.isCtrlDown();
                final boolean shiftDown = keyStroke.isShiftDown();
                final var keyType = keyStroke.getKeyType();

                if (ctrlDown && keyType == KeyType.Character && keyStroke.getCharacter() != null) {
                    final char c = Character.toLowerCase(keyStroke.getCharacter());
                    return switch (c) {
                        case 'a' -> {
                            selectAll();
                            yield Interactable.Result.HANDLED;
                        }
                        case 'c' -> {
                            copySelection();
                            yield Interactable.Result.HANDLED;
                        }
                        case 'x' -> {
                            cutSelection();
                            yield Interactable.Result.HANDLED;
                        }
                        case 'v' -> {
                            pasteClipboard();
                            yield Interactable.Result.HANDLED;
                        }
                        default -> super.handleKeyStroke(keyStroke);
                    };
                }

                if (hasSelection() && isReplacingSelectionStroke(keyStroke)) {
                    final boolean deleted = deleteSelection();
                    if (deleted && isDeleteOnlyStroke(keyStroke)) {
                        return Interactable.Result.HANDLED;
                    }
                }

                final var result = super.handleKeyStroke(keyStroke);
                final var afterCaret = toPoint(getCaretPosition());

                if (keyType == KeyType.MouseEvent && keyStroke instanceof MouseAction mouseAction) {
                    handleMouseSelection(mouseAction, beforeCaret, afterCaret);
                    return result;
                }

                if (isKeyboardSelectionNavigation(keyStroke)) {
                    if (selectionAnchor == null) {
                        selectionAnchor = beforeCaret;
                    }
                    selectionCaret = afterCaret;
                    if (!hasSelection()) {
                        clearSelection();
                    }
                    invalidate();
                    return result;
                }

                if (!samePoint(beforeCaret, afterCaret) && !hasSelectionModifier(keyStroke)) {
                    clearSelection();
                }
                return result;
            }

            synchronized void selectAll() {
                final int lastRow = Math.max(0, getLineCount() - 1);
                final int lastCol = getLine(lastRow).length();
                selectionAnchor = new TextPoint(0, 0);
                selectionCaret = new TextPoint(lastRow, lastCol);
                setCaretPosition(lastCol, lastRow);
                invalidate();
            }

            synchronized void copySelection() {
                getSelectionRange().ifPresent(range -> clipboard = extractText(range));
            }

            synchronized void cutSelection() {
                if (!hasSelection()) {
                    return;
                }
                copySelection();
                deleteSelection();
            }

            synchronized void pasteClipboard() {
                if (clipboard == null || clipboard.isEmpty()) {
                    return;
                }
                replaceSelection(clipboard);
            }

            synchronized void deleteSelectionIfAny() {
                deleteSelection();
            }

            synchronized boolean hasSelection() {
                return getSelectionRange().isPresent();
            }

            synchronized Optional<SelectionRange> getSelectionRange() {
                if (selectionAnchor == null || selectionCaret == null) {
                    return Optional.empty();
                }
                if (samePoint(selectionAnchor, selectionCaret)) {
                    return Optional.empty();
                }
                return compare(selectionAnchor, selectionCaret) <= 0
                        ? Optional.of(new SelectionRange(selectionAnchor, selectionCaret))
                        : Optional.of(new SelectionRange(selectionCaret, selectionAnchor));
            }

            private void handleMouseSelection(MouseAction mouseAction, TextPoint beforeCaret, TextPoint afterCaret) {
                if (mouseAction.getButton() != 1) {
                    return;
                }
                final var actionType = mouseAction.getActionType();
                if (actionType == MouseActionType.CLICK_DOWN) {
                    if (mouseAction.isShiftDown() && selectionAnchor != null) {
                        selectionCaret = afterCaret;
                    } else if (mouseAction.isShiftDown()) {
                        selectionAnchor = beforeCaret;
                        selectionCaret = afterCaret;
                    } else {
                        selectionAnchor = afterCaret;
                        selectionCaret = afterCaret;
                    }
                    invalidate();
                    return;
                }
                if (actionType == MouseActionType.DRAG) {
                    if (selectionAnchor == null) {
                        selectionAnchor = beforeCaret;
                    }
                    selectionCaret = afterCaret;
                    invalidate();
                    return;
                }
                if (actionType == MouseActionType.CLICK_RELEASE) {
                    if (selectionAnchor == null) {
                        selectionAnchor = afterCaret;
                    }
                    selectionCaret = afterCaret;
                    if (!mouseAction.isShiftDown() && !hasSelection()) {
                        clearSelection();
                    } else {
                        invalidate();
                    }
                }
            }

            private boolean isReplacingSelectionStroke(KeyStroke keyStroke) {
                if (keyStroke.isCtrlDown() || keyStroke.isAltDown()) {
                    return false;
                }
                return switch (keyStroke.getKeyType()) {
                    case Character, Backspace, Delete, Enter -> true;
                    default -> false;
                };
            }

            private boolean isDeleteOnlyStroke(KeyStroke keyStroke) {
                return switch (keyStroke.getKeyType()) {
                    case Backspace, Delete -> true;
                    default -> false;
                };
            }

            private boolean isKeyboardSelectionNavigation(KeyStroke keyStroke) {
                if (!keyStroke.isShiftDown()) {
                    return false;
                }
                return switch (keyStroke.getKeyType()) {
                    case ArrowLeft, ArrowRight, ArrowUp, ArrowDown, Home, End, PageUp, PageDown -> true;
                    default -> false;
                };
            }

            private boolean hasSelectionModifier(KeyStroke keyStroke) {
                if (keyStroke.isShiftDown()) {
                    return true;
                }
                return keyStroke.isCtrlDown() && keyStroke.getKeyType() == KeyType.Character && keyStroke.getCharacter() != null
                        && Character.toLowerCase(keyStroke.getCharacter()) == 'a';
            }

            private boolean deleteSelection() {
                final var rangeOpt = getSelectionRange();
                if (rangeOpt.isEmpty()) {
                    return false;
                }
                final var range = rangeOpt.get();
                replaceRange(range, "");
                setCaretPosition(range.start.column(), range.start.row());
                clearSelection();
                return true;
            }

            private void replaceSelection(String text) {
                final var range = getSelectionRange().orElseGet(() -> {
                    final var caret = toPoint(getCaretPosition());
                    return new SelectionRange(caret, caret);
                });
                replaceRange(range, text);
                final var caretAfterInsert = advance(range.start, text);
                setCaretPosition(caretAfterInsert.column(), caretAfterInsert.row());
                clearSelection();
            }

            private void replaceRange(SelectionRange range, String replacement) {
                final String normalizedText = normalizedText();
                final int start = toOffset(range.start);
                final int end = toOffset(range.end);
                final String updated = normalizedText.substring(0, start)
                        + replacement
                        + normalizedText.substring(end);
                super.setText(updated);
            }

            private String extractText(SelectionRange range) {
                final String text = normalizedText();
                return text.substring(toOffset(range.start), toOffset(range.end));
            }

            private String normalizedText() {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < getLineCount(); i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    sb.append(getLine(i));
                }
                return sb.toString();
            }

            private int toOffset(TextPoint point) {
                int offset = 0;
                for (int row = 0; row < point.row(); row++) {
                    offset += getLine(row).length();
                    offset += 1;
                }
                return offset + point.column();
            }

            private TextPoint advance(TextPoint start, String text) {
                int row = start.row();
                int column = start.column();
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) == '\n') {
                        row++;
                        column = 0;
                    } else {
                        column++;
                    }
                }
                return new TextPoint(row, column);
            }

            private TextPoint toPoint(TerminalPosition position) {
                return new TextPoint(position.getRow(), position.getColumn());
            }

            private int compare(TextPoint a, TextPoint b) {
                if (a.row() != b.row()) {
                    return Integer.compare(a.row(), b.row());
                }
                return Integer.compare(a.column(), b.column());
            }

            private boolean samePoint(TextPoint a, TextPoint b) {
                return a.row() == b.row() && a.column() == b.column();
            }

            private void clearSelection() {
                selectionAnchor = null;
                selectionCaret = null;
                invalidate();
            }

            private final class SelectionTextBoxRenderer extends TextBox.DefaultTextBoxRenderer {
                @Override
                public void drawComponent(TextGUIGraphics graphics, TextBox component) {
                    super.drawComponent(graphics, component);
                    paintSelection(graphics);
                }

                private void paintSelection(TextGUIGraphics graphics) {
                    final var rangeOpt = getSelectionRange();
                    if (rangeOpt.isEmpty()) {
                        return;
                    }
                    final var range = rangeOpt.get();
                    final var topLeft = getViewTopLeft();
                    final var size = graphics.getSize();

                    final int visibleRowStart = topLeft.getRow();
                    final int visibleRowEnd = visibleRowStart + size.getRows() - 1;

                    for (int row = Math.max(range.start.row(), visibleRowStart);
                         row <= Math.min(range.end.row(), visibleRowEnd);
                         row++) {
                        final String line = getLine(row);
                        final int rowStartCol = (row == range.start.row()) ? range.start.column() : 0;
                        final int rowEndColExclusive = (row == range.end.row()) ? range.end.column() : line.length();

                        final int visibleColStart = topLeft.getColumn();
                        final int visibleColEndExclusive = topLeft.getColumn() + size.getColumns();

                        final int drawStart = Math.max(rowStartCol, visibleColStart);
                        final int drawEnd = Math.min(rowEndColExclusive, visibleColEndExclusive);

                        if (drawStart >= drawEnd) {
                            continue;
                        }

                        final int y = row - topLeft.getRow();
                        for (int col = drawStart; col < drawEnd; col++) {
                            final int x = col - topLeft.getColumn();
                            final TextCharacter original = graphics.getCharacter(x, y);
                            graphics.setCharacter(x, y, original
                                    .withForegroundColor(SELECTION_FOREGROUND)
                                    .withBackgroundColor(SELECTION_BACKGROUND));
                        }
                    }
                }
            }
        }

        public static class Builder {
            private TerminalSize terminalSize;
            private String initialValue;
            private Runnable onClose;

            public Builder terminalSize( TerminalSize terminalSize ) {
                this.terminalSize = terminalSize;
                return this;
            }

            public Builder initialValue( String initialValue ) {
                this.initialValue = initialValue;
                return this;
            }

            public Builder onClose( Runnable onClose ) {
                this.onClose = onClose;
                return this;
            }

            public TextEditor build() {
                requireNonNull(onClose, "onClose handler cannot be null");
                requireNonNull(terminalSize, "terminalSize cannot be null");

                return new TextEditor( this );
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        final SelectableTextBox textBox;
        String initValue;

        private TextEditor( Builder builder ) {
            super( new LinearLayout(Direction.VERTICAL) );

            this.initValue = ofNullable(builder.initialValue).orElse("");

            final var textBoxSize = builder.terminalSize
                    .withColumns(builder.terminalSize.getColumns() - 5)
                    .withRows( builder.terminalSize.getRows() - 5 )
                    ;

            this.textBox = new SelectableTextBox(textBoxSize, this.initValue);
            this.textBox
                    .setVerticalFocusSwitching(true)
                    .setReadOnly(false);

            final var buttons = new Panel(new LinearLayout(Direction.HORIZONTAL))
                    .addComponent(new Button("Commit", builder.onClose))
                    .addComponent(new Button("Undo", () -> {
                        setText( getInitialValue() );
                    }))
                    .addComponent(new Button("Select All", textBox::selectAll))
                    .addComponent(new Button("Copy", textBox::copySelection))
                    .addComponent(new Button("Cut", textBox::cutSelection))
                    .addComponent(new Button("Paste", textBox::pasteClipboard))
                    .addComponent(new Button("Delete", textBox::deleteSelectionIfAny))
                    .addComponent(new Button("Skip", () -> {
                        setText("");
                        builder.onClose.run();
                    }));


            this.addComponent(textBox)
                    .addComponent(new Separator(Direction.HORIZONTAL))
                    .addComponent(buttons);

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

        private SelectableTextBox textBox() {
            return textBox;
        }
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
                    .orElse(Map.of());

            input = GraphInput.resume(resumeData);

        } while( !output.isEND() );
    }

    public void run( String[] args ) throws Exception {

        gui.setTheme(new SimpleTheme(
                TextColor.ANSI.WHITE,
                TextColor.ANSI.BLACK));


        final var repositoryPath = ( args.length > 0 ) ?
                Path.of( args[0] ):
                Path.of( "." );

        final var staged = true; //args.length >= 1 && Boolean.parseBoolean(args[1]);

        final var agent = CommitAgent.builder()
                //.chatModel( AiModel.OLLAMA.chatModel("qwen2.5:7b"))
                .chatModel( AiModel.OLLAMA.chatModel("qwen3"))
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
