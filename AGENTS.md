## IDE tool usage

When running inside a JetBrains IDE:

- Prefer JetBrains IDE MCP tools for:
    - reading files
    - searching project files/symbols
    - navigating code
    - inspecting diagnostics
    - editing/refactoring code
    - running IDE-configured tests
- Do not use PowerShell for file reads, file searches, or code navigation when an equivalent JetBrains MCP tool is available.
- Use the shell only for commands that genuinely require a terminal, such as package managers, git, build tools, or project-specific CLI commands.
- When shell usage is justified, access it using a JetBrains IDE MCP tool, not shell executable directly.