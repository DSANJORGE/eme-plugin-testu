import org.entermediadb.asset.MediaArchive

// Thin: the rule lives in tech.genailabs.tutor.TestULearningModule.certificationReminders.
MediaArchive archive = context.getPageValue("mediaarchive")
int sent = archive.getModuleManager().getBean("TestULearningModule").certificationReminders(archive)
if (sent > 0) log.info("testu certificationreminders: " + sent + " sent")
