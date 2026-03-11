## Commit Assistant

An agent that assist you in evaluate, describe and commit code updates

## JBang App

This repository includes a JBang launcher for the CLI at `/Users/bsorrentino/WORKSPACES/GITHUB.langgraph4j/langgraph4j-commit-assistant-springai/jbang/CommitAssistant.java`.

### Prerequisites

- `jbang` installed
- local Maven artifacts installed (the CLI depends on local `1.0-SNAPSHOT` modules)

```bash
mvn -q -DskipTests -pl commit-assistant-cli -am install
```

### Run directly with JBang

```bash
jbang jbang/CommitAssistant.java --path=. --model=qwen3
```

### Install as a JBang app

```bash
jbang app install --name commit-assistant jbang/CommitAssistant.java
```

Then run:

```bash
commit-assistant --path=.
```

### Install via local catalog

```bash
jbang app install --catalog jbang/jbang-catalog.json commit-assistant
```
