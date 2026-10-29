# Plain-language comment tools

Three small Python scripts (no dependencies) used to give every Java file an "In plain terms:" summary and
short notes on public methods, without changing any code. Run them from the repository root with
`python3 -I`.

| Script | What it does |
| --- | --- |
| `outline.py File.java ...` | Prints each file's top comment, type declaration and member declarations, with any comment they have. |
| `undoc.py File.java ...` | Lists the methods and constructors that have no comment directly above them. |
| `comment_tool.py spec.json` | Adds the comments in a JSON spec. It refuses to write a file whose code would change once comments are stripped. |

The spec maps a file path to a `class` summary (becomes the first paragraph of the type's Javadoc; an existing
Javadoc is kept under "Technical:") and `methods`: pairs of a unique line of the declaration and the comment to
put above it. A file that already has "In plain terms" is left alone. Comment text may not contain `*/`, `/*`
or `\u`.

```json
{"companion-core/src/main/java/dev/kindred/core/Example.java": {
  "class": "what this file is for, in plain words.",
  "methods": [["public static Example of(String id)", "An example from its name."]]}}
```
