import groovy.json.JsonOutput
Map a = context.getPageValue("analytics"); if (a == null) return
context.putPageValue("json", JsonOutput.toJson([ok: true, from: a.day(a.from), to: a.day(a.to - 1), cohort: a.cohort, series: a.series, levels: a.levels, topics: a.topicStats, calibration: a.calibration,
  teams: a.teamStats, median: a.median, previous: a.previous, gaps: a.gaps, iris: a.iris]))
