package org.bsc.langgraph4j.spring.ai.commit;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.Optional;

public final class SelectableTextBox extends TextBox {
    private static final TextColor SELECTION_BACKGROUND = TextColor.ANSI.BLUE_BRIGHT;
    private static final TextColor SELECTION_FOREGROUND = TextColor.ANSI.WHITE;


    private final class Renderer extends TextBox.DefaultTextBoxRenderer {
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

            for (int row = Math.max(range.start().row(), visibleRowStart);
                 row <= Math.min(range.end().row(), visibleRowEnd);
                 row++) {
                final String line = getLine(row);
                final int rowStartCol = (row == range.start().row()) ? range.start().column() : 0;
                final int rowEndColExclusive = (row == range.end().row()) ? range.end().column() : line.length();

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

    private TextEditor.TextPoint selectionAnchor;
    private TextEditor.TextPoint selectionCaret;
    private String clipboard = "";
    private String initialValue;

    SelectableTextBox(TerminalSize size, String initialValue) {
        super(size, initialValue);
        this.initialValue = initialValue;
        setRenderer(new Renderer());
    }


    public void setInitialValue(String initialValue) {
        this.initialValue = initialValue;
        setText(initialValue);
    }


    public String getInitialValue() {
        return initialValue;
    }

    @Override
    public synchronized TextBox setText(String text) {
        clearSelection();
        return super.setText(text);
    }

    @Override
    public synchronized Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
        final var beforeCaret = toPoint(getCaretPosition());
        final var keyType = keyStroke.getKeyType();

        final var shortcut = shortcutKey(keyStroke);
        if (shortcut.isPresent()) {
            return switch (shortcut.get()) {
/*
                case 'z' -> {
                    undo();
                    yield Interactable.Result.HANDLED;
                }
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
 */
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

    public void undo() {
        super.setText(initialValue);
    }

    synchronized void selectAll() {
        final int lastRow = Math.max(0, getLineCount() - 1);
        final int lastCol = getLine(lastRow).length();
        selectionAnchor = new TextEditor.TextPoint(0, 0);
        selectionCaret = new TextEditor.TextPoint(lastRow, lastCol);
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

    synchronized Optional<TextEditor.SelectionRange> getSelectionRange() {
        if (selectionAnchor == null || selectionCaret == null) {
            return Optional.empty();
        }
        if (samePoint(selectionAnchor, selectionCaret)) {
            return Optional.empty();
        }
        return compare(selectionAnchor, selectionCaret) <= 0
                ? Optional.of(new TextEditor.SelectionRange(selectionAnchor, selectionCaret))
                : Optional.of(new TextEditor.SelectionRange(selectionCaret, selectionAnchor));
    }

    private void handleMouseSelection(MouseAction mouseAction, TextEditor.TextPoint beforeCaret, TextEditor.TextPoint afterCaret) {
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
        return shortcutKey(keyStroke)
                .map(c -> c == 'a')
                .orElse(false);
    }

    private Optional<Character> shortcutKey(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() != KeyType.Character || keyStroke.getCharacter() == null) {
            return Optional.empty();
        }
        final char c = keyStroke.getCharacter();

        if (keyStroke.isCtrlDown()) {
            return Optional.of(Character.toLowerCase(c));
        }

        // Some terminals deliver Ctrl+[A-Z] as ASCII control characters (1..26)
        // instead of setting ctrlDown=true.
        if (c >= 1 && c <= 26) {
            return Optional.of((char) ('a' + (c - 1)));
        }

        return Optional.empty();
    }

    private boolean deleteSelection() {
        final var rangeOpt = getSelectionRange();
        if (rangeOpt.isEmpty()) {
            return false;
        }
        final var range = rangeOpt.get();
        replaceRange(range, "");
        setCaretPosition(range.start().column(), range.start().row());
        clearSelection();
        return true;
    }

    private void replaceSelection(String text) {
        final var range = getSelectionRange().orElseGet(() -> {
            final var caret = toPoint(getCaretPosition());
            return new TextEditor.SelectionRange(caret, caret);
        });
        replaceRange(range, text);
        final var caretAfterInsert = advance(range.start(), text);
        setCaretPosition(caretAfterInsert.column(), caretAfterInsert.row());
        clearSelection();
    }

    private void replaceRange(TextEditor.SelectionRange range, String replacement) {
        final String normalizedText = normalizedText();
        final int start = toOffset(range.start());
        final int end = toOffset(range.end());
        final String updated = normalizedText.substring(0, start)
                + replacement
                + normalizedText.substring(end);
        super.setText(updated);
    }

    private String extractText(TextEditor.SelectionRange range) {
        final String text = normalizedText();
        return text.substring(toOffset(range.start()), toOffset(range.end()));
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

    private int toOffset(TextEditor.TextPoint point) {
        int offset = 0;
        for (int row = 0; row < point.row(); row++) {
            offset += getLine(row).length();
            offset += 1;
        }
        return offset + point.column();
    }

    private TextEditor.TextPoint advance(TextEditor.TextPoint start, String text) {
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
        return new TextEditor.TextPoint(row, column);
    }

    private TextEditor.TextPoint toPoint(TerminalPosition position) {
        return new TextEditor.TextPoint(position.getRow(), position.getColumn());
    }

    private int compare(TextEditor.TextPoint a, TextEditor.TextPoint b) {
        if (a.row() != b.row()) {
            return Integer.compare(a.row(), b.row());
        }
        return Integer.compare(a.column(), b.column());
    }

    private boolean samePoint(TextEditor.TextPoint a, TextEditor.TextPoint b) {
        return a.row() == b.row() && a.column() == b.column();
    }

    private void clearSelection() {
        selectionAnchor = null;
        selectionCaret = null;
        invalidate();
    }
}
