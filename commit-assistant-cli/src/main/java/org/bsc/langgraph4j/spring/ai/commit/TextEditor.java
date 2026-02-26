package org.bsc.langgraph4j.spring.ai.commit;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.*;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public final class TextEditor extends Panel {

    public record TextPoint(int row, int column) {}

    public record SelectionRange(TextPoint start, TextPoint end) {}

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

    private TextEditor( Builder builder ) {
        super( new LinearLayout(Direction.VERTICAL) );

        final var initValue = ofNullable(builder.initialValue).orElse("");

        final var textBoxSize = builder.terminalSize
                .withColumns(builder.terminalSize.getColumns() - 5)
                .withRows( builder.terminalSize.getRows() - 5 )
                ;

        this.textBox = new SelectableTextBox(textBoxSize, initValue);
        this.textBox
                .setVerticalFocusSwitching(true)
                .setReadOnly(false);

        final var buttons = new Panel(new LinearLayout(Direction.HORIZONTAL))
                .addComponent(new Button("Commit", builder.onClose))
                .addComponent(new Button("Skip", () -> {
                    setText("");
                    builder.onClose.run();
                }))
                .addComponent(new Separator( Direction.VERTICAL ) )
                .addComponent(new Label("Ctrl+c Copy | Ctrl+x Cut | Ctrl+v Paste | Ctrl+u Undo | Ctrl+a Select All")
                        .addStyle(SGR.ITALIC))
;


        this.addComponent(textBox)
                .addComponent(new Separator(Direction.HORIZONTAL))
                .addComponent(buttons);

    }

    public void setInitValue(String initValue) {
        textBox.setInitialValue(initValue);
    }

    public String getInitialValue() {
        return textBox. getInitialValue();
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
