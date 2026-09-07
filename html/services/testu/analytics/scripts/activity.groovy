import groovy.json.JsonOutput
import org.openedit.Data
Map a = context.getPageValue("analytics"); if (a == null) return
def archive = context.getPageValue("mediaarchive")
Map hours = [:]
def ah = archive.query("tutoranswer").after("datecreated", a.from).search(); ah.enableBulkOperations()
// The heatmap has to honour the topic filter the rest of the page uses. tutoranswer carries no topic,
// only componentsection -- and a.perSection is already built from topic-filtered mastery rows, so its
// key set is exactly the sections of the filtered topic.
for (Data x in ah) { Date d = x.getDate("datecreated"); if (!a.users.containsKey(x.get("user")) || d >= a.to) continue
  if (a.topicFilter && !a.perSection.containsKey(x.get("componentsection"))) continue
  def c = d.toCalendar(); String k = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + "_" + c.get(Calendar.HOUR_OF_DAY); hours[k] = (hours[k] ?: 0) + 1 }
Map funnel = [cohort: a.cohort.total, signedin: a.users.values().count { it.get("lastlogin") }, answered: a.cohort.activated, active7d: a.cohort.active7d, active30d: a.cohort.active30d]
context.putPageValue("json", JsonOutput.toJson([ok: true, series: a.series, funnel: funnel, hours: hours.collect { k, v -> [k.split("_")[0] as int, k.split("_")[1] as int, v] }, inactive: a.inactive, iris: a.iris]))
