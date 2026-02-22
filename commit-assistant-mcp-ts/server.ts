import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { z } from "zod";

const execFileAsync = promisify(execFile);

const CONVENTIONAL_COMMIT_SPEC = `Commits MUST be formatted as follows:

<type>[optional scope]: <description>
[optional body]
[optional footer(s)]

- <type> could be one of the following:        
    * 'feat' MUST be used when a commit adds a new feature to your application or library.
    * 'build' MUST be used when changes are made to the project configuration files, scripts, affect the build system or external dependencies.
    * 'refactor' MUST be used when code changes neither fix bugs nor add features.
    * 'docs' MUST be used when changes are related to documentation.
    * 'test' MUST be used when adding missing tests or correcting existing tests.
    * 'fix' MUST be used when a commit represents a bug fix for your application.
    * 'style' MUST be used when changes  don't affect code meaning (formatting, spacing).
    * 'perf' MUST be used when changes improve performance.
    * 'ci' MUST be used when changes affect the Continuous Integration configuration files and scripts.
    * 'revert' MUST be used when reverting changes.
- A <scope> MAY be provided after a type. 
    A scope MUST consist of a noun describing a section of the codebase surrounded by parenthesis, e.g., fix(parser):. 
    If one file is affected by the commit, the filename is used as the scope.
- A <description> MUST immediately follow the colon and space after the type/scope prefix. The description is a short summary of the code changes, e.g., fix: array parsing issue when multiple spaces were contained in string.
- A <body> MAY be provided for longer commit after the short description, providing additional contextual information about the code changes. The body MUST begin one blank line after the description. 
  A commit body is free-form and MAY consist of any number of newline separated paragraphs.`
// - One or more footers MAY be provided one blank line after the body. Each footer MUST consist of a word token, followed by either a :<space> or <space># separator, followed by a string value (this is inspired by the git trailer convention).
// - A footer’s token MUST use - in place of whitespace characters, e.g., Acked-by (this helps differentiate the footer section from a multi-paragraph body). An exception is made for BREAKING CHANGE, which MAY also be used as a token.
// - A footer’s value MAY contain spaces and newlines, and parsing MUST terminate when the next valid footer token/separator pair is observed.
// - Breaking changes MUST be indicated in the type/scope prefix of a commit, or as an entry in the footer.
// - If included as a footer, a breaking change MUST consist of the uppercase text BREAKING CHANGE, followed by a colon, space, and description, e.g., BREAKING CHANGE: environment variables now take precedence over config files.
// - If included in the type/scope prefix, breaking changes MUST be indicated by a ! immediately before the :. If ! is used, BREAKING CHANGE: MAY be omitted from the footer section, and the commit description SHALL be used to describe the breaking change.
// - Types other than feat and fix MAY be used in your commit messages, e.g., docs: update ref docs.
// - The units of information that make up Conventional Commits MUST NOT be treated as case sensitive by implementors, with the exception of BREAKING CHANGE which MUST be uppercase.
// - BREAKING-CHANGE MUST be synonymous with BREAKING CHANGE, when used as a token in a footer.`

const CONVENTIONAL_COMMIT_PROMPT = ( GIT_DIFF: string, CONVENTIONAL_COMMIT_SPEC: string ) =>
`Generate a git conventional commit message that summarizes the changes in <GIT_DIFF> following the rules below:
    
1. The result MUST be in plain text format.
2. The result MUST not be in markdown format.
3. The result MUST not be surrounded by quotes or code blocks.
4. The result MUST be compliant with <CONVENTIONAL_COMMIT_SPEC>.

<GIT_DIFF>
${GIT_DIFF}
</GIT_DIFF>

<CONVENTIONAL_COMMIT_SPEC>
${CONVENTIONAL_COMMIT_SPEC}
</CONVENTIONAL_COMMIT_SPEC>
`;

const CONVENTIONAL_COMMIT_PROMPT_WITH_TOOLS = ( CONVENTIONAL_COMMIT_SPEC: string ) =>
`Generate a git message following the conventional commit specification <CONVENTIONAL_COMMIT_SPEC> to summarize the changes in <GIT_DIFF> following the rules below:
   
* you must use tool 'diff' to achieve <GIT_DIFF>
* The result MUST be in plain text format no markdown is allowed.
* The result MUST not be surrounded by quotes or code blocks.
* The result MUST be compliant with <CONVENTIONAL_COMMIT_SPEC>.
 
<CONVENTIONAL_COMMIT_SPEC>
${CONVENTIONAL_COMMIT_SPEC}
</CONVENTIONAL_COMMIT_SPEC>

`;

const server = new McpServer({
    name: "git-mcp-server",
    version: "0.1.0",
  },
  {
    capabilities: {
      "logging": {}
    }
  });


async function log(level: "debug" | "info" | "notice" | "warning" | "error" | "critical" | "alert" | "emergency", message: string) {
  const s = server.server;
  if (s && s.sendLoggingMessage) {
    try {
      await s.sendLoggingMessage({
        level,
        data: message,
      })
    } catch(err){
      console.error("Failed to send MCP log:", err);
    }
  } else {
    console.error(`[${level}] ${message}`);
  }
}

async function runGit(args: string[]): Promise<string> {
  const cwd = process.env.CWD;
  const command = `git ${args.join(" ")}`;
  try {
    
    await log("info", `Executing: ${command} (CWD: ${cwd})`);
    
    const { stdout, stderr } = await execFileAsync("git", args, {
      encoding: "utf-8",
      cwd,
    });
    
    if (stderr) {
      await log("warning", `Git stderr: ${stderr}`);
    }
    
    await log("debug", `Git stdout: ${stdout}`);
    return stdout;

  } catch (error) {
      await log("error", `Git error: ${error}`);
      throw error;
  }
}

function parseStatusLines(status: string): string[] {
  return status
    .split("\n")
    .map((line) => line.trimEnd())
    .filter(Boolean)
    .map((line) => {
      if (line.startsWith("?? ")) {
        return line.slice(3);
      }
      const payload = line.slice(3);
      const arrowIndex = payload.indexOf("->");
      if (arrowIndex !== -1) {
        return payload.slice(arrowIndex + 2).trim();
      }
      return payload.trim();
    })
    .filter(Boolean);
}

server.registerResource( 
  "conventional_commit_spec",
  "resource:conventional-commit-spec",
  {
    description: "Short summary of the Conventional Commits specification",
    mimeType: "text/markdown",
  },
  async ( uri ) => ({
    contents: [
      {
        uri: uri.href,
        mimeType: "text/markdown",
        text: CONVENTIONAL_COMMIT_SPEC,
      },
    ],
  })
);


server.registerPrompt(
  "commit_prompt",
  {
    title: "Conventional Commit",
    description: "Generate a Conventional Commit message from a diff and spec",
    argsSchema: { 
      GIT_DIFF: z.string().min(1, "GIT_DIFF is required"), 
      COMMIT_SPEC: z.string().min(1, "COMMIT_SPEC is required") 
    },
  },
  async ( {GIT_DIFF, COMMIT_SPEC } ) => ({

    messages: [
      {
        role: "user",
        content: {
          type: "text",
          text: CONVENTIONAL_COMMIT_PROMPT(GIT_DIFF, COMMIT_SPEC),
        },
      },
    ],
  })
);
server.registerPrompt(
  "commit_prompt_with_tools",
  {
    title: "Conventional Commit",
    description: "Generate a Conventional Commit message from a diff and spec using tools",
  },
  async ( ) => ({

    messages: [
      {
        role: "user",
        content: {
          type: "text",
          text: CONVENTIONAL_COMMIT_PROMPT_WITH_TOOLS(CONVENTIONAL_COMMIT_SPEC),
        },
      },
    ],
  })
);

const CommitInputSchema = z
  .object({
    message: z.string().min(1, "message is required"),
    filename: z.string().min(1, "filename is required"),
  })
  .strict();

const CommitOutputSchema = z.object({
  message: z.string(),
  filename: z.string(),
  git_output: z.string(),
});

server.registerTool(
  "commit",
  {
    title: "Commit File",
    description: "Create a git commit for a specific file using the provided message",
    inputSchema: CommitInputSchema,
    outputSchema: CommitOutputSchema,
    annotations: {
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openWorldHint: true,
    },
  },
  async ({ message, filename }) => {

    await log("info", `Committing file ${filename} with message: ${message}`);

    //const output = await runGit(["commit", "-m", message, "--", filename]);
    const output = undefined
    const result = { message, filename, git_output: output || "Committed." };
    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
      structuredContent: result,
    };
  }
);

const ListFilesInputSchema = z
  .object({
    staged: z.boolean().optional().describe("Whether to list staged files"),
  })
  .strict();

const ListFilesOutputSchema = z.object({
  staged: z.boolean(),
  files: z.array(z.string()),
});

server.registerTool(
  "listFiles",
  {
    title: "List Files",
    description: "List files by git status; when staged=true, list staged files",
    inputSchema: ListFilesInputSchema,
    outputSchema: ListFilesOutputSchema,
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: true,
    },
  },
  async ({ staged }) => {
    if (staged) {
      const output = await runGit(["diff", "--cached", "--name-only"]);
      const files = output
        .split("\n")
        .map((line) => line.trim())
        .filter(Boolean);
      const result = { staged: true, files, count: files.length };
      return {
        content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
        structuredContent: result,
      };
    }

    const status = await runGit(["status", "--porcelain"]);
    const files = parseStatusLines(status);
    const result = { staged: false, files };
    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
      structuredContent: result,
    };
  }
);

const DiffInputSchema = z
  .object({
    staged: z.boolean(),
    filename: z.string().min(1, "filename is required"),
  })
  .strict();

const DiffOutputSchema = z.object({
  staged: z.boolean(),
  filename: z.string(),
  diff: z.string(),
});

server.registerTool(
  "diff",
  {
    title: "Get Diff",
    description: "Get diff for a file; staged controls cached vs working tree",
    inputSchema: DiffInputSchema,
    outputSchema: DiffOutputSchema,
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: true,
    },
  },
  async ({  staged, filename }) => {
    const gitArgs = staged ? ["diff", "--cached", "--", filename] : ["diff", "--", filename];
    const output = await runGit(gitArgs);
    const result = { staged, filename, diff: output };
    return {
      content: [{ type: "text", text: output }],
      structuredContent: result,
    };
  }
);

// server.registerTool(
//   "conventional_commit_spec",
//   {
//     title: "Get Conventional Commit Spec",
//     description: "Get conventional commit specification",
//     //inputSchema: DiffInputSchema,
//     outputSchema: z.object({
//       spec: z.string().describe("The conventional commit specification document"),
//     }),
//     annotations: {
//       readOnlyHint: true,
//       destructiveHint: false,
//       idempotentHint: true,
//       openWorldHint: true,
//     },
//   },
//   async () => {
//     log("info", `Executing: conventional commit spec`);
//     return {
//       content: [{ type: "text", text: CONVENTIONAL_COMMIT_SPEC }],
//       structuredContent: { spec: CONVENTIONAL_COMMIT_SPEC },
//     };
//   }
// );

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  //console.error("mcp-git server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
