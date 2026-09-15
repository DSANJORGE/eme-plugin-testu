import org.entermediadb.asset.MediaArchive

// Thin: the rule lives in tech.genailabs.tutor.TestULearningModule.dailyReminder.
MediaArchive archive = context.getPageValue("mediaarchive")
int sent = archive.getModuleManager().getBean("TestULearningModule").dailyReminder(archive)
log.info("testu dailyreminder: " + sent + " sent")
