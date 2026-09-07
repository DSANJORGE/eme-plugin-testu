# AGENTS.md

## TestU Plugin

### Purpose

`plugins/testu` Allows java to be overrriden by custom agents and custom behavior. Any code being customized should be put into this folder instead of customizing any plugins

### Folder Map


- `html/catalog` This where we put out fields and lists that are specific to test ut. The site/catalog/_site.xconf will fallback to here
- `html/src/plugin.xml` Customize any skill or java class that will be customized. This will be inserted at the end of booting up
- `code/org/testu/` Store Customized skills and any java class that will be customized for the needs of testu.
- `lib/` Any supporting Java jars that are needed by future code

## Installation.

- `html/` This would be a place to customize any UI and would failover

Inside the main eme-server project create this file:

/webapp/testu.json

{
        "repo": "https://github.com/DSANJORGE/eme-plugin-testu.git",
        "branch": "main"
}
~       
Then run bin/plugins.sh update

You will also need to add any new jars into .vscode/launch.json

And add this to .classpath the <classpathentry kind="src" path="plugins/testu/code"/>

