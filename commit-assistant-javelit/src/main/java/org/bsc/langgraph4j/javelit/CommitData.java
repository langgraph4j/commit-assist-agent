package org.bsc.langgraph4j.spring.ai.commit.javelit;

public class CommitData {
    final String file;
    boolean processed;
    boolean committed;
    String text;

    public CommitData( String file) {
        this.file = file;
    }

    public String file() {
        return file;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed( boolean processed ) {
        this.processed = processed;
    }

    public boolean isCommitted() {
        return committed;
    }

    public void setCommitted( boolean committed ) {
        this.committed = committed;
    }

    public String text() {
        return text;
    }

    public void setText( String text ) {
        this.text = text;
    }

    @Override
    public String toString() {
        return "CommitData{file='%s', processed=%b commited=%b text='%s'}".formatted( file, processed, committed, text );
    }

}
