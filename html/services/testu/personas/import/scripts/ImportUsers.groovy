import org.openedit.Data
import org.entermediadb.asset.util.Row
import org.entermediadb.asset.importer.BaseImporter
// ponytail: model.importer.BaseImporter / org.openedit.entermedia.util.Row (the brief's primary imports,
// copied from plugins/mediadb/html/services/settings/users/import/scripts/ImportCsvFile.groovy) do not
// resolve on this server's Groovy classpath at runtime ("unable to resolve class"); using the brief's own
// documented fallback classes instead, which do resolve and have the same addProperties(Row,Data) shape.

// Only these headers may reach the user table; BaseImporter would auto-create a field for anything else.
class UsersImporter extends BaseImporter {
  static final ALLOWED = ["id", "email", "firstName", "lastName", "team"] as Set
  int count = 0
  protected void addProperties(Row inRow, Data inData) {
    inRow.getHeader().getHeaderNames().each { h -> if (!(h in ALLOWED)) throw new IllegalArgumentException("unexpected column " + h) }
    super.addProperties(inRow, inData)
    inData.setValue("email", String.valueOf(inData.get("email") ?: inData.getId()).trim().toLowerCase())
    inData.setValue("enabled", "true")
    count++
  }
}
UsersImporter imp = new UsersImporter()
imp.setModuleManager(moduleManager)
imp.setContext(context)
imp.setLog(log)
imp.setMakeId(false)
imp.importData()
context.putPageValue("importedcount", imp.count)
