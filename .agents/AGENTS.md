# AGENTS.md

## TestU Plugin

### Purpose

`plugins/testu` Allows java to be overrriden by custom agents and custom behavior. Any code being customized should be put into this folder instead of customizing any plugins

### Folder Map


- `html/plugin.xml` Customize any skill or java class that will be customized. This will be inserted at the end of booting up
- `code/plugin.xml` Customize any skill or java class that will be customized.
- `lib/` Any supporting Java jars that are needed by the code

- `html/` This would be a place to customize any UI and would failover
