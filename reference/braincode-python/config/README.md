# Configuration layout

Configuration is not secret storage.

- `providers/`: provider metadata/configuration references only
- `routing/`: future routing preferences and strategies
- `policy/`: policy defaults and profiles
- `execution/`: execution defaults
- `research/`: research source/configuration defaults

API keys, tokens and credentials must remain outside Git, prompts, memory and events.