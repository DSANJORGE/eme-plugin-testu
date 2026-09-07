#!/bin/sh
# Copies this plugin into an eMe server tree. Usage: ./deploy.sh [SERVER_ROOT]
# ponytail: plain rsync of four roots; a real plugin loader comes when EnterMedia merges us upstream.
set -e
cd "$(dirname "$0")"
S="${1:-/Users/DSANJORGE/Code/eme-server-minsur}"
rsync -a --exclude '.DS_Store' html/ "$S/webapp/site/mediadb/"
rsync -a data/fields/ "$S/webapp/WEB-INF/data/site/catalog/fields/"
[ -d data/lists ] && mkdir -p "$S/webapp/WEB-INF/data/site/catalog/lists" && rsync -a data/lists/ "$S/webapp/WEB-INF/data/site/catalog/lists/"
mkdir -p "$S/webapp/WEB-INF/data/system/fields" && rsync -a data/system/fields/ "$S/webapp/WEB-INF/data/system/fields/"
[ -d catalog ] && mkdir -p "$S/webapp/site/catalog" && rsync -a catalog/ "$S/webapp/site/catalog/"
echo "deployed to $S"
