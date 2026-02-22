//DEPS com.fasterxml.jackson.core:jackson-core:2.21.0
//DEPS org.bsc.langgraph4j:langgraph4j-javelit:1.8-SNAPSHOT
//DEPS net.sourceforge.plantuml:plantuml-mit:1.2025.10
//DEPS org.springframework.ai:spring-ai-bom:1.1.2@pom
//DEPS org.springframework.ai:spring-ai-client-chat
//DEPS org.springframework.ai:spring-ai-openai
//DEPS org.springframework.ai:spring-ai-ollama
//DEPS org.springframework.ai:spring-ai-mcp
//DEPS org.bsc.langgraph4j:langgraph4j-springai-agentexecutor:1.8.2

//SOURCES org/bsc/langgraph4j/spring/ai/commit/*.java

import io.javelit.core.Jt;
import org.bsc.langgraph4j.spring.ai.commit.javelit.JtSessionValue;


public class JtTestApp {
    public static void main(String[] args) throws InterruptedException {

        var app = new JtTestApp();
        app.view();
    }

    JtSessionValue<Boolean> proceedState = new JtSessionValue<>("proceed");

    public void view() {

        Jt.button("proceed")
                .onClick(b -> proceedState.setValue(true))
                .use();

        final var proceed = proceedState.value().orElse(false);
        System.out.printf( "proceed %b", proceed);

        final var form = Jt.form().use();
        final var textToCommit = Jt.textArea("Commit description")
                .key("text_to_commit")
                .disabled( !proceed  )
                .use(form);
        final var proceedToCommit = Jt.formSubmitButton("commit")
                .disabled( !proceed )
                .use(form);

        System.out.printf("Proceed to commit '%b' - %s %n", proceedToCommit, textToCommit);
        if (proceedToCommit) {
            {
                Jt.text(textToCommit).use();

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                proceedState.clear();
                Jt.rerun();
            }

        }
    }
}