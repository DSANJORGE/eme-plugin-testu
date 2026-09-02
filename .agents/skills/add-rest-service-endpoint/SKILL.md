---
name: add-rest-service-endpoint
description: Use this skill when the user wants to add a new JSON REST API endpoint — requests like "add an API endpoint for X", "expose Y over REST", or "add a services call to do Z". Covers the .xconf/.json pair, permissions, and documenting the endpoint. Always consult this before hand-adding files under plugins/mediadb/html/services, since path-action chaining and permission conventions are project-specific.
---

# Add a REST Service Endpoint

Adds a new JSON API endpoint under `plugins/mediadb/html/services`.

## Step 1: Pick the module folder

Put the new endpoint under the existing module it belongs to (`authentication`, `entity`,
`module`, `server`, `push`, `settings`, `distribution`, ...), or create a new module folder only if
the operation doesn't fit any existing one.

## Step 2: Write the `.xconf` (the actual endpoint logic)

Add `html/services/<module>/<action>.xconf`. It's a chain of existing `path-action`s — reuse Java
modules already registered by `system`/`finder`, don't write new servlets:

```xml
<page>
	<path-action name="JsonAdminModule.preprocess" />
	<path-action name="Admin.login" />
	<path-action name="MediaAdminModule.someOperation" />

	<permission name="view">
		<blank/>
	</permission>
</page>
```

Use a real permission (not `<blank/>`) unless the endpoint is intentionally public.

## Step 3: Add the `.json` response template

Add `html/services/<module>/<action>.json` next to the `.xconf` — this is the Velocity/JSON
template rendered after the path-actions run.

## Step 4: Document the endpoint

Add or update an `<endpoint>` entry in `plugins/catalog/html/data/lists/endpoint/*.xml` (pick the
file matching the module) with a `samplerequest` so the endpoint shows up in API docs:

```xml
<endpoint id="myaction" name="My Action" url="/${applicationid}/services/<module>/<action>"
          docsection="mymodule" caption="What this does" httpmethod="POST">
	<samplerequest><![CDATA[
{ "example": "payload" }
]]></samplerequest>
</endpoint>
```

## Step 5: Validate

1. Clear the page cache (or restart) after adding the files.
2. Call the endpoint with curl (see `html/services/authentication/README.txt` for a full worked
   login example) and confirm the JSON response matches what was documented.
3. Confirm permission enforcement: an unauthenticated/unauthorized call should be rejected unless
   the endpoint is meant to be public.
