///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22
//REPOS sonatype-central=https://central.sonatype.com/repository/maven-snapshots/
//DEPS org.bsc.langgraph4j:langgraph4j-commit-assistant-cli-springai:1.0-SNAPSHOT

public class CommitAssistant {
    public static void main(String... args) throws Exception {
        org.bsc.langgraph4j.spring.ai.commit.CLI.main(args);
    }
}
