package org.bsc.langgraph4j.spring.ai.commit;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.dialogs.WaitingDialog;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class TUITest {


    public static void main( String[] args ) throws Exception {


        final Screen screen = new DefaultTerminalFactory().createScreen();

        screen.startScreen();

        final var gui = new MultiWindowTextGUI(screen);

        gui.setTheme(new SimpleTheme(
                TextColor.ANSI.WHITE,
                TextColor.ANSI.BLACK));

        final var dialog = WaitingDialog.createDialog("agent", "getting files to commit.....");

        for( int i = 0 ; i < 2 ; i++) {
            var future = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new CompletionException(e);
                }
                gui.getGUIThread().invokeLater(dialog::close);
            });
            dialog.setTitle( "step %d".formatted(i) );
            dialog.showDialog(gui, true);

            future.join();
            Thread.sleep(1000);
        }

        screen.stopScreen();

        System.exit(0);
    }
}
