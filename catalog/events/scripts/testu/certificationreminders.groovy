import org.entermediadb.asset.MediaArchive

// Thin: the rule lives in tech.genailabs.tutor.TestULearningModule.certificationReminders / missionNudges.
MediaArchive archive = context.getPageValue("mediaarchive")
def module = archive.getModuleManager().getBean("TestULearningModule")
int sent = module.certificationReminders(archive)
if (sent > 0) log.info("testu certificationreminders: " + sent + " sent")
int mnudged = module.missionNudges(archive)
if (mnudged > 0) log.info("testu missionnudges: " + mnudged + " sent")
