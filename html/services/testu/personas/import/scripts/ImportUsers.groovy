import org.openedit.Data
import org.entermediadb.asset.util.Row
import org.entermediadb.asset.importer.BaseImporter
import groovy.json.JsonOutput
// ponytail: model.importer.BaseImporter / org.openedit.entermedia.util.Row (the brief's primary imports,
// copied from plugins/mediadb/html/services/settings/users/import/scripts/ImportCsvFile.groovy) do not
// resolve on this server's Groovy classpath at runtime ("unable to resolve class"); using the brief's own
// documented fallback classes instead, which do resolve and have the same addProperties(Row,Data) shape.

// Only these headers may reach the user table; BaseImporter would auto-create a field for anything else.
class UsersImporter extends BaseImporter {
  static final ALLOWED = ["id", "email", "firstName", "lastName", "team"] as Set
  int count = 0
  protected void addProperties(Row inRow, Data inData) {
    List names = inRow.getHeader().getHeaderNames()
    for (int i = 0; i < names.size(); i++) {
      // strip a leading UTF-8 BOM and stray whitespace so Excel exports don't trip the check;
      // mutate in place so super.addProperties() below maps the cleaned name too, not the raw one.
      String h = (names[i] ?: "").trim().replace("﻿", "")
      names[i] = h
      if (!(h in ALLOWED)) throw new IllegalArgumentException("unexpected column " + h)
    }
    if (getSearcher().searchById(inData.getId()) != null) throw new IllegalArgumentException("user exists: " + inData.getId())
    String email = String.valueOf(inRow.get("email") ?: inData.getId()).trim().toLowerCase()
    if (!(email ==~ /[^@\s]+@[^@\s]+\.[^@\s]+/)) throw new IllegalArgumentException("invalid email: " + email)
    String team = inRow.get("team")
    if (team && getMediaArchive().getCachedData("team", team) == null) throw new IllegalArgumentException("unknown team: " + team)
    super.addProperties(inRow, inData)
    inData.setValue("email", email)
    inData.setValue("enabled", "true")
    // Ruling R8: random secret, never returned or logged; eMe sessions need md5(password), OTP stays the only login path
    inData.setValue("password", UUID.randomUUID().toString())
    count++
  }
}
UsersImporter imp = new UsersImporter()
imp.setModuleManager(moduleManager)
imp.setContext(context)
imp.setLog(log)
imp.setMakeId(false)
try {
  imp.importData()
  context.putPageValue("importedcount", imp.count)
} catch (Exception ex) {
  // ponytail: importData() throws before any row is saved (BaseImporter buffers Data objects and only
  // calls saveAllData() after the whole loop completes without error), so no partial import to roll back.
  // cancelActions stops the xconf's next path-action (Script.run scripts/importdone.groovy) from running,
  // so no ok:true / audit row is ever written for a failed import.
  context.getResponse().setStatus(400)
  context.putPageValue("json", JsonOutput.toJson([ok: false, error: ex.getMessage()]))
  context.setCancelActions(true)
}
