import org.entermediadb.asset.MediaArchive

// Thin: the rule lives in tech.genailabs.tutor.TestULearningModule.dailyChallengeEmail.
MediaArchive archive = context.getPageValue("mediaarchive")
int sent = archive.getModuleManager().getBean("TestULearningModule").dailyChallengeEmail(archive)
if (sent > 0) log.info("testu dailychallengeemail: " + sent + " sent")
