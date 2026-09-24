import org.entermediadb.asset.MediaArchive

// Thin: the rule lives in tech.genailabs.tutor.WeeklySummaryEmail.
MediaArchive archive = context.getPageValue("mediaarchive")
int sent = archive.getModuleManager().getBean("TestULearningModule").weeklySummaryEmail(archive)
if (sent > 0) log.info("testu weeklysummaryemail: " + sent + " sent")
