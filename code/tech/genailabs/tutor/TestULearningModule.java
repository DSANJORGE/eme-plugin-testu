package tech.genailabs.tutor;

import java.util.Calendar;
import java.util.Date;
import java.util.Set;
import java.util.TimeZone;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;

/** services/testu/learn/{state,next,exposure,answer,subtopicpolicy,unlockbackfill}.json -- thin wrappers over LearningEngine for the signed-in user. */
public class TestULearningModule extends TestUBaseModule
{
	public void state(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		LearningEngine engine = new LearningEngine(getMediaArchive(inReq));
		LearningEngine.Content content = engine.loadContent(visibleTopics(inReq));
		Data urec = freshUser(getMediaArchive(inReq), user);
		LearningEngine.Learner learner = engine.loadLearner(user.getId(), LearningEngine.jobrolesOf(urec), LearningEngine.primaryJobroleOf(urec));
		engine.applyProfiles(content, learner);
		String topicid = param(inReq, "topicid");
		if (topicid != null && !content.topics.containsKey(topicid))
		{
			fail(inReq, 404, "unknown_topic");
			return;
		}
		java.util.Map<String, JSONObject> unlocks = engine.subtopicStates(content, learner);
		JSONArray topics = new JSONArray();
		for (LearningEngine.Topic t : content.topics.values())
		{
			if (topicid == null || topicid.equals(t.id))
			{
				topics.add(engine.topicState(t, learner, unlocks));
			}
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("thresholdsreason", content.thresholdsreason);
		resp.put("canmanageprogression", canManageProgression(inReq));
		resp.put("topics", topics);
		resp.put("removedtopics", content.removedtopics);
		resp.put("profiles", content.profiles);
		reply(inReq, resp);
	}

	public void next(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		String mode = param(inReq, "mode");
		if (!"learn".equals(mode) && !"improve".equals(mode) && !"dailychallenge".equals(mode))
		{
			fail(inReq, 400, "bad_mode");
			return;
		}
		LearningEngine engine = new LearningEngine(getMediaArchive(inReq));
		LearningEngine.Content content = engine.loadContent(visibleTopics(inReq));
		Data urec = freshUser(getMediaArchive(inReq), user);
		LearningEngine.Learner learner = engine.loadLearner(user.getId(), LearningEngine.jobrolesOf(urec), LearningEngine.primaryJobroleOf(urec));
		engine.applyProfiles(content, learner);
		if ("dailychallenge".equals(mode))
		{
			JSONObject dc = engine.dailyChallenge(content, learner);
			if (Boolean.TRUE.equals(dc.get("complete")) && ((Number) dc.get("total")).intValue() > 0)
			{
				// Done for today: what the learner could practise next (the app's "already completed" screen offers it).
				dc.put("recommended", LearningEngine.recommend(content, learner, engine.subtopicStates(content, learner), t -> engine.requiredLevel(t, learner.jobroles)));
			}
			if (!contentUnavailable(inReq, engine, user, content, dc))
			{
				reply(inReq, dc);
			}
			return;
		}
		String topicid = param(inReq, "topicid");
		String sectionid = param(inReq, "sectionid");
		LearningEngine.Topic topic = topicid == null ? null : content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, topicid == null ? 400 : 404, topicid == null ? "missing_topicid" : "unknown_topic");
			return;
		}
		LearningEngine.Section section = null;
		if (sectionid != null)
		{
			section = content.sections.get(sectionid);
			if (section == null || !topic.id.equals(section.topicid))
			{
				fail(inReq, 404, "unknown_section");
				return;
			}
		}
		if (topic.locked)
		{
			JSONObject err = new JSONObject();
			err.put("ok", Boolean.FALSE);
			err.put("error", "topic_locked");
			err.put("topic", topic.id);
			err.put("previoustopic", topic.previoustopic);
			if (inReq.getResponse() != null)
			{
				inReq.getResponse().setStatus(409);
			}
			reply(inReq, err);
			inReq.setCancelActions(true);
			return;
		}
		String scopetype = section == null ? "topic" : "subtopic";
		// Started from the Daily Challenge "done" screen's recommendation: tagged for the engagement funnel. Anything else = null.
		String source = "dailydone".equals(param(inReq, "source")) ? "dailydone" : null;
		String scopeid = section == null ? topic.id : section.id;
		if ("learn".equals(mode))
		{
			java.util.Map<String, JSONObject> unlocks = engine.subtopicStates(content, learner);
			if (section != null && !LearningEngine.isUnlocked(unlocks, section.id))
			{
				fail(inReq, 409, "subtopic_locked");
				return;
			}
			JSONObject learn = LearningEngine.learnNext(topic, section, learner, unlocks);
			// Optional cap (the onboarding's first session asks for 3); the engine's order is kept.
			String sizeParam = param(inReq, "size");
			JSONArray learnItems = (JSONArray) learn.get("items");
			if (sizeParam != null && learnItems != null)
			{
				int cap = Math.max(1, Math.min(100, LearningEngine.intOr(sizeParam, learnItems.size())));
				while (learnItems.size() > cap)
				{
					learnItems.remove(learnItems.size() - 1);
				}
			}
			if (!contentUnavailable(inReq, engine, user, content, learn))
			{
				reply(inReq, engine.startSession(user.getId(), scopetype, scopeid, topic, learn, source));
			}
			return;
		}
		int size = Math.max(1, Math.min(100, LearningEngine.intOr(param(inReq, "size"), 10)));
		JSONObject improve = engine.improveNext(topic, section, learner, size);
		if (improve == null)
		{
			fail(inReq, 409, "improve_locked");
			return;
		}
		if (!contentUnavailable(inReq, engine, user, content, improve))
		{
			reply(inReq, engine.startSession(user.getId(), scopetype, scopeid, topic, improve, source));
		}
	}

	/**
	 * When an item to serve has content tutorial.json cannot render: flags it for administrators and replies 409
	 * {error: content_unavailable, items: [{questionid, componentid, sectionid, tutorialid, topicid, problem}]}; no session is started.
	 */
	protected boolean contentUnavailable(WebPageRequest inReq, LearningEngine engine, User user, LearningEngine.Content content, JSONObject inNext)
	{
		JSONArray bad = LearningEngine.unavailable(content, inNext);
		if (bad.isEmpty())
		{
			return false;
		}
		engine.reportUnavailable(user.getId(), bad);
		inReq.getResponse().setStatus(409);
		JSONObject err = new JSONObject();
		err.put("ok", Boolean.FALSE);
		err.put("error", "content_unavailable");
		err.put("items", bad);
		reply(inReq, err);
		inReq.setCancelActions(true);
		return true;
	}

	/**
	 * A question was shown. Mode, scope, session and question are validated like answer.json; the stored hierarchy and position are
	 * canonical. A learn/dailychallenge exposure reaches its subtopic, so an unrecorded condition unlock of that topic is recorded.
	 */
	public void exposure(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		LearningEngine engine = new LearningEngine(getMediaArchive(inReq));
		LearningEngine.Resolved r = resolve(inReq, engine, user, false);
		if (r.question == null)
		{
			fail(inReq, r.status, r.error);
			return;
		}
		Searcher searcher = getMediaArchive(inReq).getSearcher("tutorexposure");
		Data d = searcher.createNewData();
		d.setValue("user", user.getId());
		setCanonical(d, r.question, param(inReq, "mode"), param(inReq, "scopetype"), param(inReq, "scopeid"));
		d.setValue("entitytopic", r.question.topicid);
		d.setValue("position", r.question.position);
		d.setValue("learningsession", param(inReq, "sessionid"));
		d.setValue("datecreated", new Date());
		searcher.saveData(d, user);
		String mode = param(inReq, "mode");
		if ("learn".equals(mode) || "dailychallenge".equals(mode))
		{
			r.learner.exposedInSequence.add(r.question.id);
			engine.recordUnlocks(r.content.topics.get(r.question.topicid), r.learner);
		}
		JSONObject resp = canonical(r.question, param(inReq, "mode"));
		resp.put("ok", Boolean.TRUE);
		reply(inReq, resp);
	}

	/**
	 * The learner's answer, stored synchronously once the server has verified it (LearningEngine.resolve): ok means stored.
	 * attemptid is the client's retry key: the row id is <user>_<attemptid>, so a retry of a stored attempt returns it
	 * (duplicate true) and a different payload under the same key is 409 attempt_conflict. Correctness, points and the
	 * hierarchy come from the server. The tutor feedback message (chat_tutor_answer) then only references answerid.
	 * Once stored, unlocks the answer meets (completion, or mastery after an improve answer) are recorded.
	 */
	public void answer(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		String attemptid = param(inReq, "attemptid");
		String selected = param(inReq, "selectedoption");
		String confidence = param(inReq, "confidence");
		String hint = param(inReq, "hintlevel");
		String mode = param(inReq, "mode");
		String scopetype = param(inReq, "scopetype");
		String scopeid = param(inReq, "scopeid");
		String questionid = param(inReq, "questionid");
		String bad = attemptid == null ? "missing_attemptid"
			: !attemptid.matches("[A-Za-z0-9_-]{16,64}") ? "bad_attemptid"
			: selected == null ? "missing_selectedoption"
			: !selected.matches("[A-F]") ? "bad_selectedoption"
			: confidence == null ? "missing_confidence"
			: !LearningEngine.CONFIDENCES.contains(confidence) ? "bad_confidence"
			: hint == null ? "missing_hintlevel"
			: !hint.matches("[0-9]") || Integer.parseInt(hint) > LearningEngine.MAX_HINTLEVEL ? "bad_hintlevel"
			: null;
		if (bad != null)
		{
			fail(inReq, 400, bad);
			return;
		}
		int hintlevel = Integer.parseInt(hint);
		if (LearningEngine.EVALUATION.equals(mode) && hintlevel > 0)
		{
			fail(inReq, 400, "hints_not_allowed");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		Searcher searcher = archive.getSearcher("tutoranswer");
		String id = user.getId() + "_" + attemptid;
		Data existing = (Data) searcher.searchById(id);
		if (existing != null)
		{
			// Retry of a stored attempt: answer from the row, before re-validating (the challenge day may have rolled over since).
			boolean same = String.valueOf(questionid).equals(existing.get("entityquestion")) && selected.equals(existing.get("selectedoption"))
				&& confidence.equals(existing.get("answerconfidence")) && String.valueOf(mode).equals(existing.get("mode"))
				&& String.valueOf(scopetype).equals(String.valueOf(existing.get("scopetype"))) && String.valueOf(scopeid).equals(String.valueOf(existing.get("scopeid")))
				&& hintlevel == LearningEngine.intOr(existing.get("hintlevel"), -1)
				&& String.valueOf(param(inReq, "sessionid")).equals(String.valueOf(existing.get("learningsession")));
			if (!same)
			{
				fail(inReq, 409, "attempt_conflict");
				return;
			}
			if (LearningEngine.EVALUATION.equals(existing.get("mode")))
			{
				// A retry the first call answered but the app never saw: make sure the attempt carries it (idempotent, no-ops
				// on a closed attempt), so a lost reply cannot cost the learner the question.
				LearningEngine retryengine = new LearningEngine(archive);
				LearningEngine.EvalAttempt open = retryengine.loadEvalAttempt(existing.get("learningsession"));
				if (open != null)
				{
					retryengine.recordEvaluationAnswer(open, existing.get("entityquestion"), "true".equals(String.valueOf(existing.getValue("iscorrect"))));
				}
			}
			reply(inReq, answerReply(existing, true, null));
			return;
		}
		LearningEngine engine = new LearningEngine(archive);
		LearningEngine.Resolved r = resolve(inReq, engine, user, true);
		if (r.question == null)
		{
			fail(inReq, r.status, r.error);
			return;
		}
		Data question = archive.getData("entityquestion", r.question.id);
		String option = question == null ? null : question.get("option_" + selected.toLowerCase());
		if (option == null || option.trim().isEmpty())
		{
			fail(inReq, 400, "bad_selectedoption");
			return;
		}
		boolean correct = selected.equals(question.get("correctoption"));
		double allotted = listDouble(archive, "mcqcognitivelevel", question.get("mcqcognitivelevel"), "points");
		Data answer = searcher.createNewData();
		answer.setId(id);
		answer.setValue("user", user.getId());
		answer.setValue("attemptid", attemptid);
		setCanonical(answer, r.question, mode, scopetype, scopeid);
		answer.setValue("selectedoption", selected);
		answer.setValue("answerconfidence", confidence);
		answer.setValue("hintlevel", hintlevel);
		answer.setValue("iscorrect", correct);
		answer.setValue("pointsearned", correct ? allotted : 0.0);
		answer.setValue("bonusearned", allotted * listDouble(archive, "answerconfidence", confidence, "bonuspercentage") / 100.0);
		answer.setValue("channel", param(inReq, "channel"));
		answer.setValue("learningsession", param(inReq, "sessionid"));
		Date now = new Date();
		answer.setValue("datecreated", now);
		answer.setValue("lastpenalty", now);
		searcher.saveData(answer, user);
		TestUAnalyticsModule.scheduleRefresh(archive); // admin analytics see the answer within ~a minute, not at the 15 min sweep
		LearningEngine.Attempt attempt = LearningEngine.attemptOf(answer);
		attempt.at = now;
		if (r.attempt != null)
		{
			// Evaluation: the attempt keeps its own answer map (scored on submit); no unlocks, no progress event, no verdict in the reply.
			engine.recordEvaluationAnswer(r.attempt, r.question.id, correct);
		}
		else
		{
			engine.recordUnlocks(r.content.topics.get(r.question.topicid), LearningEngine.withAttempt(r.learner, attempt));
			notifyUser(user.getId(), "progress", null);
		}
		reply(inReq, answerReply(answer, false, r.question));
	}

	/** Content + learner + profiles for the signed-in user, as state.json builds them (null user already failed the request). */
	private Object[] load(WebPageRequest inReq, User inUser)
	{
		LearningEngine engine = new LearningEngine(getMediaArchive(inReq));
		LearningEngine.Content content = engine.loadContent(visibleTopics(inReq));
		Data urec = freshUser(getMediaArchive(inReq), inUser);
		LearningEngine.Learner learner = engine.loadLearner(inUser.getId(), LearningEngine.jobrolesOf(urec), LearningEngine.primaryJobroleOf(urec));
		engine.applyProfiles(content, learner);
		return new Object[] {engine, content, learner};
	}

	/**
	 * services/testu/learn/evaluation.json?topicid= -- the learner's evaluation status on the topic (LearningEngine.evaluationStatus), the
	 * usable blueprint (null when not offered) and the attempt history (attempthistory; attempts stays the finalized count).
	 * Read-only apart from finalizing an attempt past its window.
	 */
	public void evaluation(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		Object[] ctx = load(inReq, user);
		LearningEngine engine = (LearningEngine) ctx[0];
		LearningEngine.Content content = (LearningEngine.Content) ctx[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) ctx[2];
		String topicid = param(inReq, "topicid");
		LearningEngine.Topic topic = topicid == null ? null : content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, topicid == null ? 400 : 404, topicid == null ? "missing_topicid" : "unknown_topic");
			return;
		}
		engine.expireStale(learner, content);
		JSONObject resp = LearningEngine.evaluationStatus(topic, learner, new Date());
		resp.put("ok", Boolean.TRUE);
		resp.put("topic", topic.id);
		resp.put("blueprint", topic.blueprint.usable() ? topic.blueprint.toJson() : null);
		JSONArray attempts = new JSONArray();
		for (LearningEngine.EvalAttempt a : learner.evaluations)
		{
			if (topic.id.equals(a.topicid))
			{
				attempts.add(a.toResultJson());
			}
		}
		resp.put("attempthistory", attempts); // "attempts" stays the finalized count, as state.json reports it
		resp.put("now", LearningEngine.iso(new Date())); // the app times the window off the server clock, not the device's
		reply(inReq, resp);
	}

	/** 409 evaluation_not_available with the status block the learner needs to know when they may try again. */
	private void notAvailable(WebPageRequest inReq, JSONObject inStatus)
	{
		JSONObject err = new JSONObject();
		err.put("ok", Boolean.FALSE);
		err.put("error", "evaluation_not_available");
		err.put("status", inStatus.get("status"));
		err.put("reason", inStatus.get("reason"));
		err.put("nextallowedat", inStatus.get("nextallowedat"));
		if (inReq.getResponse() != null)
		{
			inReq.getResponse().setStatus(409);
		}
		reply(inReq, err);
		inReq.setCancelActions(true);
	}

	/**
	 * services/testu/learn/startevaluation.json (POST topicid) -- resumes the open attempt or creates one when evaluationStatus says
	 * canstart; replies in next.json shape (mode evaluation, sessionid = attempt id, expiresat, items with done). 409
	 * evaluation_not_available {status, reason, nextallowedat} otherwise, 409 pool_insufficient when the blueprint selects no question.
	 * A new attempt writes an auditevent evaluation.start.
	 */
	public void startEvaluation(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		Object[] ctx = load(inReq, user);
		LearningEngine engine = (LearningEngine) ctx[0];
		LearningEngine.Content content = (LearningEngine.Content) ctx[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) ctx[2];
		String topicid = param(inReq, "topicid");
		LearningEngine.Topic topic = topicid == null ? null : content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, topicid == null ? 400 : 404, topicid == null ? "missing_topicid" : "unknown_topic");
			return;
		}
		engine.expireStale(learner, content);
		Date now = new Date();
		JSONObject status = LearningEngine.evaluationStatus(topic, learner, now);
		boolean maycreate = Boolean.TRUE.equals(status.get("canstart"));
		if (!"in_progress".equals(status.get("status")) && !maycreate)
		{
			notAvailable(inReq, status);
			return;
		}
		// The gate above is the search index's view; the engine decides from storage. Passing the verdict down keeps the two
		// agreed: an in_progress attempt that turns out to be gone or closed can never become a new attempt here.
		String[] reason = new String[1];
		LearningEngine.EvalAttempt a = engine.startEvaluation(topic, learner, content, now, maycreate, reason);
		if (a == null)
		{
			if ("pool_insufficient".equals(reason[0]))
			{
				fail(inReq, 409, "pool_insufficient"); // the blueprint selects no question from this topic; nothing was created
				return;
			}
			notAvailable(inReq, LearningEngine.evaluationStatus(topic, learner, new Date()));
			return;
		}
		boolean isNew = true;
		for (LearningEngine.EvalAttempt known : learner.evaluations)
		{
			if (known.id.equals(a.id))
			{
				isNew = false;
			}
		}
		if (isNew)
		{
			JSONObject after = new JSONObject();
			after.put("topic", topic.id);
			after.put("number", a.number);
			after.put("total", a.total);
			after.put("strategy", a.strategy);
			after.put("blueprintversion", a.version);
			after.put("expiresat", LearningEngine.iso(a.expires));
			after.put("exposed", a.exposed);
			after.put("reused", a.reused);
			audit(inReq, getMediaArchive(inReq), "evaluation.start", "evaluationattempt", a.id, null, after);
		}
		JSONObject resp = LearningEngine.evaluationItems(a, content);
		resp.put("timerminutes", topic.blueprint.timerminutes);
		resp.put("now", LearningEngine.iso(new Date())); // the app counts the timer down against this, not the device clock
		reply(inReq, resp);
	}

	/**
	 * services/testu/learn/submitevaluation.json (POST sessionid) -- finalizes the learner's attempt (idempotent: an attempt already
	 * closed returns the same result with duplicate true) and replies the result, the topic's new evaluation status and Finished.
	 */
	public void submitEvaluation(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		String sessionid = param(inReq, "sessionid");
		if (sessionid == null)
		{
			fail(inReq, 400, "missing_sessionid");
			return;
		}
		Object[] ctx = load(inReq, user);
		LearningEngine engine = (LearningEngine) ctx[0];
		LearningEngine.Content content = (LearningEngine.Content) ctx[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) ctx[2];
		LearningEngine.EvalAttempt a = engine.loadEvalAttempt(sessionid);
		if (a == null || !user.getId().equals(a.user))
		{
			fail(inReq, 404, "unknown_session");
			return;
		}
		boolean duplicate = a.finalized();
		a = engine.finalizeEvaluation(a, content, "learner");
		LearningEngine.replaceAttempt(learner, a);
		LearningEngine.Topic topic = content.topics.get(a.topicid);
		JSONObject resp = a.toResultJson();
		resp.put("ok", Boolean.TRUE);
		resp.put("duplicate", duplicate);
		resp.put("failedrule", a.inputs.get("failedrule"));
		resp.put("weakest", a.inputs.get("weakest"));
		resp.put("passpercent", a.inputs.get("passpercent"));
		resp.put("subtopicminpercent", a.inputs.get("subtopicminpercent"));
		if (topic != null)
		{
			topic.finished = LearningEngine.finished(topic, learner);
			JSONObject st = LearningEngine.evaluationStatus(topic, learner, new Date());
			resp.put("evaluationstatus", st.get("status"));
			resp.put("nextallowedat", st.get("nextallowedat"));
			resp.put("attemptsleft", st.get("attemptsleft"));
			resp.put("finished", topic.position == null ? null : Boolean.valueOf(topic.finished));
		}
		if (!duplicate)
		{
			audit(inReq, getMediaArchive(inReq), "evaluation.submit", "evaluationattempt", a.id, null, a.toResultJson());
			notifyUser(user.getId(), "progress", null);
		}
		reply(inReq, resp);
	}

	/**
	 * subtopicPolicy and evaluationBlueprint are the same append-only, version-numbered config endpoint over two tables. What they
	 * share lives in VersionedConfig / configRequest / replyTopics / newestVersion / versionConflict / versionRows below; each
	 * endpoint keeps only its own validation, row writer and json builder.
	 */
	private static class VersionedConfig
	{
		protected User user;
		protected MediaArchive archive;
		protected LearningEngine.Content content;
		protected LearningEngine.Topic topic; // null = no ?topicid=, the caller replies the whole list
		protected boolean canmanage, post;
	}

	/**
	 * The preamble of both versioned-config endpoints: the signed-in user, the training_view / training_manage gate, the content and
	 * the ?topicid= topic. Returns null when it has already failed the request (401, 403 forbidden, 400 missing_topicid on a POST
	 * without a topic, 404 unknown_topic); a config with a null topic means the caller should reply the whole list.
	 */
	private VersionedConfig configRequest(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return null;
		}
		VersionedConfig config = new VersionedConfig();
		config.user = user;
		config.canmanage = canManageProgression(inReq);
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		if (!config.canmanage && (profile == null || !profile.hasPermission("training_view")))
		{
			fail(inReq, 403, "forbidden");
			return null;
		}
		config.archive = getMediaArchive(inReq);
		config.content = new LearningEngine(config.archive).loadContent();
		config.post = inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod());
		String topicid = param(inReq, "topicid");
		if (topicid == null)
		{
			if (config.post)
			{
				fail(inReq, 400, "missing_topicid");
				return null;
			}
			return config;
		}
		config.topic = config.content.topics.get(topicid);
		if (config.topic == null)
		{
			fail(inReq, 404, "unknown_topic");
			return null;
		}
		return config;
	}

	/** {ok, canmanage, topics} -- the GET-without-topicid reply of both versioned-config endpoints. */
	private void replyTopics(WebPageRequest inReq, VersionedConfig inConfig, java.util.function.Function<LearningEngine.Topic, JSONObject> inTopicJson)
	{
		JSONArray topics = new JSONArray();
		for (LearningEngine.Topic t : inConfig.content.topics.values())
		{
			JSONObject json = inTopicJson.apply(t);
			if (json != null) // null = the endpoint hides this topic from the caller
			{
				topics.add(json);
			}
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("canmanage", inConfig.canmanage);
		resp.put("topics", topics);
		reply(inReq, resp);
	}

	/**
	 * The highest stored version of inTopicid at or above inFrom. loadContent's search can lag a just-saved version, so the
	 * optimistic-concurrency check under LearningEngine.WRITE_LOCK probes the next ids with realtime gets instead of trusting it.
	 */
	private static int newestVersion(Searcher inSearcher, String inTopicid, int inFrom)
	{
		int version = inFrom;
		while (inSearcher.searchById(inTopicid + "_v" + (version + 1)) != null)
		{
			version++;
		}
		return version;
	}

	/** 409 version_conflict: the caller's expectedversion is no longer the latest, so nothing was written. */
	private void versionConflict(WebPageRequest inReq, int inCurrentVersion, JSONObject inTopic)
	{
		if (inReq.getResponse() != null)
		{
			inReq.getResponse().setStatus(409);
		}
		JSONObject err = new JSONObject();
		err.put("ok", Boolean.FALSE);
		err.put("error", "version_conflict");
		err.put("currentversion", inCurrentVersion);
		err.put("topic", inTopic);
		reply(inReq, err);
		inReq.setCancelActions(true);
	}

	/** Every stored version row of inTopicid, newest first by inVersionField. */
	private static java.util.List<Data> versionRows(Searcher inSearcher, String inTopicid, String inVersionField)
	{
		java.util.List<Data> rows = new java.util.ArrayList<>();
		for (Object o : inSearcher.query().exact("entitytopic", inTopicid).search())
		{
			rows.add((Data) o);
		}
		rows.sort((a, b) -> LearningEngine.intOr(b.get(inVersionField), 0) - LearningEngine.intOr(a.get(inVersionField), 0));
		return rows;
	}

	/**
	 * Subtopic unlock policy per topic. GET: every topic (or ?topicid=, with its version history). POST topicid + policy
	 * (+ requiredlevel for sequential_mastery) + expectedversion (the version the caller loaded; 0 = none): appends version n+1 to
	 * subtopicpolicy and an auditevent, create-only under LearningEngine.WRITE_LOCK. expectedversion no longer the latest = 409
	 * version_conflict (with currentversion; nothing written). Reading needs
	 * training_view or training_manage, writing training_manage. A new version applies to subtopics a learner has not reached
	 * (no recorded unlock, no learn/dailychallenge answer or exposure); reached subtopics are never relocked.
	 */
	public void subtopicPolicy(WebPageRequest inReq)
	{
		VersionedConfig config = configRequest(inReq);
		if (config == null)
		{
			return;
		}
		if (config.topic == null)
		{
			replyTopics(inReq, config, t -> policyJson(t));
			return;
		}
		// Unpacked so the table-specific code below reads as it did before the skeleton was shared.
		User user = config.user;
		boolean canmanage = config.canmanage;
		boolean post = config.post;
		MediaArchive archive = config.archive;
		LearningEngine.Topic topic = config.topic;
		Searcher searcher = archive.getSearcher("subtopicpolicy");
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("canmanage", canmanage);
		if (post)
		{
			if (!canmanage)
			{
				fail(inReq, 403, "forbidden");
				return;
			}
			String policy = param(inReq, "policy");
			String level = param(inReq, "requiredlevel");
			String expected = param(inReq, "expectedversion");
			boolean mastery = "sequential_mastery".equals(policy);
			String bad = policy == null || !LearningEngine.POLICIES.contains(policy) ? "bad_policy"
				: mastery && level == null ? "missing_requiredlevel"
				: mastery && LearningEngine.levelIndex(level) < 0 ? "bad_requiredlevel"
				: expected == null ? "missing_expectedversion"
				: !expected.matches("[0-9]{1,6}") ? "bad_expectedversion"
				: null;
			if (bad != null)
			{
				fail(inReq, 400, bad);
				return;
			}
			level = mastery ? level : null;
			boolean unchanged;
			synchronized (LearningEngine.WRITE_LOCK)
			{
				topic.policyversion = newestVersion(searcher, topic.id, topic.policyversion);
				Data latest = topic.policyversion == 0 ? null : (Data) searcher.searchById(topic.id + "_v" + topic.policyversion);
				String[] effective = LearningEngine.effectivePolicy(latest == null ? null : latest.get("unlockpolicy"), latest == null ? null : latest.get("requiredlevel"));
				topic.policy = effective[0];
				topic.requiredlevel = effective[1];
				topic.policyreason = effective[2];
				if (Integer.parseInt(expected) != topic.policyversion)
				{
					versionConflict(inReq, topic.policyversion, policyJson(topic));
					return;
				}
				unchanged = topic.policyversion > 0 && topic.policyreason == null && policy.equals(topic.policy) && java.util.Objects.equals(level, topic.requiredlevel);
				if (!unchanged)
				{
					JSONObject before = policyJson(topic);
					before.remove("subtopics");
					JSONArray order = new JSONArray();
					for (LearningEngine.Section s : topic.sections)
					{
						order.add(s.id);
					}
					int version = topic.policyversion + 1;
					Data row = searcher.createNewData();
					row.setId(topic.id + "_v" + version);
					row.setValue("entitytopic", topic.id);
					row.setValue("policyversion", version);
					row.setValue("unlockpolicy", policy);
					row.setValue("requiredlevel", level);
					row.setValue("appliesto", "unreached_subtopics");
					row.setValue("sectionorder", order.toJSONString());
					row.setValue("user", user.getId());
					row.setValue("datecreated", new Date());
					searcher.saveData(row, user);
					topic.policy = policy;
					topic.requiredlevel = level;
					topic.policyreason = null;
					topic.policyversion = version;
					JSONObject after = policyJson(topic);
					after.remove("subtopics");
					audit(inReq, archive, "subtopicpolicy.change", "entitytopic", topic.id, before, after);
				}
			}
			resp.put("unchanged", unchanged);
			resp.put("topic", policyJson(topic));
			reply(inReq, resp);
			return;
		}
		JSONArray versions = new JSONArray();
		for (Data d : versionRows(searcher, topic.id, "policyversion"))
		{
			JSONObject v = new JSONObject();
			v.put("version", LearningEngine.intOr(d.get("policyversion"), 0));
			v.put("policy", d.get("unlockpolicy"));
			v.put("requiredlevel", d.get("requiredlevel"));
			v.put("appliesto", d.get("appliesto"));
			v.put("user", d.get("user"));
			v.put("datecreated", LearningEngine.iso(org.openedit.util.DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("datecreated"))));
			versions.add(v);
		}
		resp.put("topic", policyJson(topic));
		resp.put("versions", versions);
		reply(inReq, resp);
	}

	/**
	 * Evaluation blueprint per topic (spec 2026-09-16-evaluation-mode). GET: every topic with its current blueprint, pool report and
	 * attempt stats (or ?topicid= with its version history). POST topicid + fields + expectedversion: appends version n+1 to
	 * evaluationblueprint and an auditevent, create-only under LearningEngine.WRITE_LOCK; expectedversion no longer the latest = 409
	 * version_conflict; active=true over a pool that cannot satisfy the blueprint = 409 pool_insufficient (nothing written; save it
	 * inactive instead). Reading needs training_view or training_manage, writing training_manage. An in-progress attempt keeps the
	 * version it started with; a new version applies from the next startevaluation.
	 */
	public void evaluationBlueprint(WebPageRequest inReq)
	{
		VersionedConfig config = configRequest(inReq);
		if (config == null)
		{
			return;
		}
		java.util.Map<String, int[]> stats = attemptStats(config.archive);
		if (config.topic == null)
		{
			replyTopics(inReq, config, t -> canManageEvaluations(inReq, config.archive, t.id) ? blueprintJson(t, stats) : null);
			return;
		}
		if (!canManageEvaluations(inReq, config.archive, config.topic.id))
		{
			fail(inReq, 403, "forbidden");
			return;
		}
		// Unpacked so the table-specific code below reads as it did before the skeleton was shared.
		User user = config.user;
		boolean canmanage = config.canmanage;
		boolean post = config.post;
		MediaArchive archive = config.archive;
		LearningEngine.Topic topic = config.topic;
		Searcher searcher = archive.getSearcher("evaluationblueprint");
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("canmanage", canmanage);
		if (post)
		{
			if (!canmanage)
			{
				fail(inReq, 403, "forbidden");
				return;
			}
			String expected = param(inReq, "expectedversion");
			if (expected == null)
			{
				fail(inReq, 400, "missing_expectedversion");
				return;
			}
			if (!expected.matches("[0-9]{1,6}"))
			{
				fail(inReq, 400, "bad_expectedversion");
				return;
			}
			String active = param(inReq, "active");
			if (!"true".equals(active) && !"false".equals(active))
			{
				fail(inReq, 400, "bad_active"); // never save a blueprint as inactive because the flag was missing or mistyped
				return;
			}
			LearningEngine.Blueprint b = new LearningEngine.Blueprint();
			b.topicid = topic.id;
			b.active = "true".equals(active);
			b.strategy = param(inReq, "strategy") == null ? "random" : param(inReq, "strategy");
			b.mix = param(inReq, "difficultymix") == null ? "proportional" : param(inReq, "difficultymix");
			b.requirelearncomplete = !"false".equals(param(inReq, "requirelearncomplete"));
			String[][] numbers = {{"maxquestions", "20"}, {"minpersubtopic", "0"}, {"passpercent", "70"}, {"subtopicminpercent", "0"}, {"timerminutes", "0"}, {"retakewaithours", "0"}, {"maxattempts", "0"}};
			int[] values = new int[numbers.length];
			for (int i = 0; i < numbers.length; i++)
			{
				String v = param(inReq, numbers[i][0]);
				if (v == null)
				{
					v = numbers[i][1];
				}
				if (!v.matches("-?[0-9]{1,6}"))
				{
					fail(inReq, 400, "bad_" + numbers[i][0]);
					return;
				}
				values[i] = Integer.parseInt(v);
			}
			b.maxquestions = values[0];
			b.minpersubtopic = values[1];
			b.passpercent = values[2];
			b.subtopicminpercent = values[3];
			b.timerminutes = values[4];
			b.retakewaithours = values[5];
			b.maxattempts = values[6];
			String excluded = param(inReq, "excludedsections");
			if (excluded != null)
			{
				Object parsed = org.json.simple.JSONValue.parse(excluded);
				if (!(parsed instanceof java.util.List))
				{
					fail(inReq, 400, "bad_excludedsections");
					return;
				}
				for (Object o : (java.util.List) parsed)
				{
					String sid = String.valueOf(o);
					boolean known = false;
					for (LearningEngine.Section s : topic.sections)
					{
						known |= s.id.equals(sid);
					}
					if (!known)
					{
						fail(inReq, 400, "unknown_section");
						return;
					}
					b.excludedsections.add(sid);
				}
			}
			String bad = LearningEngine.invalidField(b);
			if (bad != null)
			{
				fail(inReq, 400, "bad_" + bad);
				return;
			}
			LearningEngine.settle(b);
			JSONObject pool = LearningEngine.poolReport(topic, b);
			if (b.active && !Boolean.TRUE.equals(pool.get("sufficient")))
			{
				if (inReq.getResponse() != null)
				{
					inReq.getResponse().setStatus(409);
				}
				JSONObject err = new JSONObject();
				err.put("ok", Boolean.FALSE);
				err.put("error", "pool_insufficient");
				err.put("pool", pool);
				reply(inReq, err);
				inReq.setCancelActions(true);
				return;
			}
			boolean unchanged;
			synchronized (LearningEngine.WRITE_LOCK)
			{
				int newest = newestVersion(searcher, topic.id, topic.blueprint.version);
				if (newest > topic.blueprint.version)
				{
					topic.blueprint = LearningEngine.blueprintOf((Data) searcher.searchById(topic.id + "_v" + newest), topic.id);
				}
				if (Integer.parseInt(expected) != topic.blueprint.version)
				{
					versionConflict(inReq, topic.blueprint.version, blueprintJson(topic, stats));
					return;
				}
				unchanged = topic.blueprint.version > 0 && sameBlueprint(topic.blueprint, b);
				if (!unchanged)
				{
					JSONObject before = blueprintJson(topic, stats);
					before.remove("pool");
					before.remove("stats");
					int version = topic.blueprint.version + 1;
					b.version = version;
					b.user = user.getId();
					b.created = new Date();
					Data row = searcher.createNewData();
					row.setId(topic.id + "_v" + version);
					row.setValue("entitytopic", topic.id);
					row.setValue("blueprintversion", version);
					row.setValue("active", b.active);
					row.setValue("strategy", b.strategy);
					row.setValue("maxquestions", b.maxquestions);
					row.setValue("minpersubtopic", b.minpersubtopic);
					JSONArray ex = new JSONArray();
					ex.addAll(new java.util.TreeSet<>(b.excludedsections));
					row.setValue("excludedsections", ex.toJSONString());
					row.setValue("difficultymix", b.mix);
					row.setValue("passpercent", b.passpercent);
					row.setValue("subtopicminpercent", b.subtopicminpercent);
					row.setValue("timerminutes", b.timerminutes);
					row.setValue("retakewaithours", b.retakewaithours);
					row.setValue("maxattempts", b.maxattempts);
					row.setValue("requirelearncomplete", b.requirelearncomplete);
					row.setValue("user", b.user);
					row.setValue("datecreated", b.created);
					searcher.saveData(row, user);
					topic.blueprint = b;
					JSONObject after = blueprintJson(topic, stats);
					after.remove("pool");
					after.remove("stats");
					audit(inReq, archive, "evaluationblueprint.change", "entitytopic", topic.id, before, after);
				}
			}
			resp.put("unchanged", unchanged);
			resp.put("topic", blueprintJson(topic, stats));
			reply(inReq, resp);
			return;
		}
		JSONArray versions = new JSONArray();
		for (Data d : versionRows(searcher, topic.id, "blueprintversion"))
		{
			LearningEngine.Blueprint v = LearningEngine.blueprintOf(d, topic.id);
			JSONObject vj = v.toJson();
			vj.put("user", v.user);
			vj.put("datecreated", LearningEngine.iso(v.created));
			versions.add(vj);
		}
		resp.put("topic", blueprintJson(topic, stats));
		resp.put("versions", versions);
		reply(inReq, resp);
	}

	/** {id, title, ...blueprint fields, reason, pool, stats:{attempts, passed, inprogress}}; an unconfigured topic reports version 0 with the default pool. */
	private static JSONObject blueprintJson(LearningEngine.Topic t, java.util.Map<String, int[]> inStats)
	{
		JSONObject o = t.blueprint.toJson();
		o.put("id", t.id);
		o.put("title", t.title);
		o.put("pool", LearningEngine.poolReport(t, t.blueprint));
		int[] s = inStats.getOrDefault(t.id, new int[3]);
		JSONObject stats = new JSONObject();
		stats.put("attempts", s[0]);
		stats.put("passed", s[1]);
		stats.put("inprogress", s[2]);
		o.put("stats", stats);
		return o;
	}

	/** Same configured values (version, reason, user and date ignored). */
	private static boolean sameBlueprint(LearningEngine.Blueprint a, LearningEngine.Blueprint b)
	{
		JSONObject x = a.toJson();
		JSONObject y = b.toJson();
		for (String k : new String[] {"version", "reason"})
		{
			x.remove(k);
			y.remove(k);
		}
		return x.toJSONString().equals(y.toJSONString());
	}

	/** topic -> {finalized attempts, passed, in progress} over every evaluationattempt row (ponytail: full scan, fine for a pilot cohort). */
	private static java.util.Map<String, int[]> attemptStats(MediaArchive inArchive)
	{
		java.util.Map<String, int[]> out = new java.util.HashMap<>();
		HitTracker hits = inArchive.query("evaluationattempt").all().search();
		hits.enableBulkOperations();
		for (Object o : hits)
		{
			Data d = (Data) o;
			int[] s = out.computeIfAbsent(d.get("entitytopic"), k -> new int[3]);
			String status = d.get("status");
			if (status == null || "inprogress".equals(status))
			{
				s[2]++;
			}
			else
			{
				s[0]++;
				if ("true".equals(String.valueOf(d.get("passed"))))
				{
					s[1]++;
				}
			}
		}
		return out;
	}

	/**
	 * POST, training_manage: idempotent subtopicunlock backfill (LearningEngine.backfillUnlocks) for learners whose unlocks predate
	 * event-time recording; optional user = one learner. Replies {ok, learners, created}; a rerun creates 0.
	 */
	public void unlockBackfill(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		if (!canManageProgression(inReq))
		{
			fail(inReq, 403, "forbidden");
			return;
		}
		if (inReq.getRequest() == null || !"POST".equalsIgnoreCase(inReq.getRequest().getMethod()))
		{
			fail(inReq, 405, "post_required");
			return;
		}
		int[] r = new LearningEngine(getMediaArchive(inReq)).backfillUnlocks(param(inReq, "user"));
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("learners", r[0]);
		resp.put("created", r[1]);
		reply(inReq, resp);
	}

	private static JSONObject policyJson(LearningEngine.Topic t)
	{
		JSONObject o = new JSONObject();
		o.put("id", t.id);
		o.put("title", t.title);
		o.put("policy", t.policy);
		o.put("requiredlevel", t.requiredlevel);
		o.put("version", t.policyversion);
		o.put("reason", t.policyreason);
		JSONArray subs = new JSONArray();
		for (LearningEngine.Section s : t.sections)
		{
			JSONObject so = new JSONObject();
			so.put("id", s.id);
			so.put("title", s.title);
			so.put("position", subs.size() + 1);
			subs.add(so);
		}
		o.put("subtopics", subs);
		return o;
	}

	private static boolean canManageProgression(WebPageRequest inReq)
	{
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		return profile != null && profile.hasPermission("training_manage");
	}

	/** Per-topic entity permission (permissionsentity/evaluations.xml); same check as $permissions.canEntity($module,$entity,"manageevaluations"). */
	private static boolean canManageEvaluations(WebPageRequest inReq, MediaArchive inArchive, String inTopicid)
	{
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		return profile != null && profile.getPermissions().canEntity(inArchive.getCachedData("module", "entitytopic"), inArchive.getCachedData("entitytopic", inTopicid), "manageevaluations");
	}

	/** Shared by answer and exposure: mode + scope + claimed hierarchy checked against the learner's visible content. */
	private LearningEngine.Resolved resolve(WebPageRequest inReq, LearningEngine inEngine, User inUser, boolean inAnswer)
	{
		LearningEngine.Content content = inEngine.loadContent(visibleTopics(inReq));
		Data urec = freshUser(getMediaArchive(inReq), inUser);
		LearningEngine.Learner learner = inEngine.loadLearner(inUser.getId(), LearningEngine.jobrolesOf(urec), LearningEngine.primaryJobroleOf(urec));
		inEngine.applyProfiles(content, learner);
		java.util.Map<String, String> claimed = new java.util.HashMap<>();
		for (String k : new String[] {"topicid", "tutorialid", "sectionid", "componentid"})
		{
			claimed.put(k, param(inReq, k));
		}
		LearningEngine.Resolved r = inEngine.resolve(content, learner, param(inReq, "mode"), param(inReq, "scopetype"), param(inReq, "scopeid"), param(inReq, "questionid"), claimed,
			param(inReq, "sessionid"), inAnswer);
		r.content = content;
		r.learner = learner;
		return r;
	}

	private static void setCanonical(Data inRow, LearningEngine.Question q, String inMode, String inScopetype, String inScopeid)
	{
		inRow.setValue("entityquestion", q.id);
		inRow.setValue("entitytutorial", q.tutorialid);
		inRow.setValue("componentsection", q.sectionid);
		inRow.setValue("mode", inMode);
		inRow.setValue("scopetype", inScopetype); // resolve() proved it names q's own topic / section (none for dailychallenge)
		inRow.setValue("scopeid", inScopeid);
	}

	private static JSONObject canonical(LearningEngine.Question q, String inMode)
	{
		JSONObject o = new JSONObject();
		o.put("mode", inMode);
		o.put("questionid", q.id);
		o.put("topicid", q.topicid);
		o.put("tutorialid", q.tutorialid);
		o.put("sectionid", q.sectionid);
		o.put("componentid", q.componentid);
		o.put("position", q.position);
		return o;
	}

	/** inQuestion null on a duplicate (content is not reloaded): topicid, componentid and position are then omitted. */
	private static JSONObject answerReply(Data inAnswer, boolean inDuplicate, LearningEngine.Question inQuestion)
	{
		JSONObject o = inQuestion == null ? new JSONObject() : canonical(inQuestion, inAnswer.get("mode"));
		o.put("ok", Boolean.TRUE);
		o.put("answerid", inAnswer.getId());
		o.put("duplicate", inDuplicate);
		o.put("iscorrect", "true".equals(String.valueOf(inAnswer.getValue("iscorrect"))));
		o.put("mode", inAnswer.get("mode"));
		o.put("questionid", inAnswer.get("entityquestion"));
		o.put("tutorialid", inAnswer.get("entitytutorial"));
		o.put("sectionid", inAnswer.get("componentsection"));
		o.put("scopetype", inAnswer.get("scopetype"));
		o.put("scopeid", inAnswer.get("scopeid"));
		o.put("hintlevel", LearningEngine.intOr(inAnswer.get("hintlevel"), 0));
		if (LearningEngine.EVALUATION.equals(inAnswer.get("mode")))
		{
			o.remove("iscorrect"); // withheld until the attempt is submitted
			o.put("deferred", Boolean.TRUE);
		}
		return o;
	}

	private static double listDouble(MediaArchive inArchive, String inList, String inId, String inField)
	{
		Data d = inId == null ? null : inArchive.getData(inList, inId);
		Object v = d == null ? null : d.getValue(inField);
		try
		{
			return v == null ? 0.0 : Double.parseDouble(v.toString());
		}
		catch (NumberFormatException e)
		{
			return 0.0;
		}
	}

	/** Topic ids the signed-in user may see: the same security-filtered query as the app's topic service (TopicManager.getUserTopics). */
	private java.util.Set<String> visibleTopics(WebPageRequest inReq)
	{
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (Object o : getMediaArchive(inReq).query("entitytopic").all().search(inReq))
		{
			ids.add(((Data) o).getId());
		}
		return ids;
	}

	private User requireUser(WebPageRequest inReq)
	{
		User user = inReq.getUser();
		if (user == null || user.getId() == null)
		{
			fail(inReq, 401, "not signed in");
			return null;
		}
		return user;
	}

	private static String param(WebPageRequest inReq, String inName)
	{
		String v = inReq.getRequestParameter(inName);
		return v == null || v.trim().isEmpty() ? null : v.trim();
	}

	/**
	 * services/testu/learn/onboarding.json -- the learner's first-run onboarding row (learneronboarding, id = user id).
	 * GET: {ok, started, finished, skipped, goals, whenlearn, sessiontotal, sessioncorrect} (all null before the first POST).
	 * POST any of started=true | finished=true | skipped=true | goals=a,b | whenlearn=x | sessiontotal=n&sessioncorrect=n: upserts.
	 * GET report=true (personas_view or analytics_view, teams in scope): {ok, rows:[...]} for the console.
	 */
	public void onboarding(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
			return;
		MediaArchive archive = getMediaArchive(inReq);
		Searcher s = archive.getSearcher("learneronboarding");

		if ("true".equals(inReq.getRequestParameter("report")))
		{
			UserProfile p = inReq.getUserProfile();
			if (p == null || !(p.hasPermission("personas_view") || p.hasPermission("analytics_view")))
			{
				fail(inReq, 403, "forbidden");
				return;
			}
			Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");
			JSONArray rows = new JSONArray();
			HitTracker hits = s.query().all().search();
			if (hits != null)
			{
				hits.enableBulkOperations();
				for (Object hit : hits)
				{
					Data r = (Data) hit;
					if (scope != null)
					{
						Data u = archive.getCachedData("user", r.get("user"));
						if (u == null || !scope.contains(u.get("team")))
							continue;
					}
					rows.add(onboardingJson(r));
				}
			}
			JSONObject out = new JSONObject();
			out.put("ok", Boolean.TRUE);
			out.put("rows", rows);
			reply(inReq, out);
			return;
		}

		Data d = (Data) s.searchById(user.getId());
		if (inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod()))
		{
			if (d == null)
			{
				d = s.createNewData();
				d.setId(user.getId());
				d.setValue("user", user.getId());
			}
			Date now = new Date();
			if ("true".equals(inReq.getRequestParameter("started")))
			{
				if (d.getValue("started") == null)
					d.setValue("started", now);
				// Every showing of the tour: the app re-shows an unfinished one up to its cap.
				d.setValue("opens", Integer.valueOf(LearningEngine.intOr(d.get("opens"), 0) + 1));
			}
			if ("true".equals(inReq.getRequestParameter("finished")) && d.getValue("finished") == null)
				d.setValue("finished", now);
			if ("true".equals(inReq.getRequestParameter("skipped")))
				d.setValue("skipped", Boolean.TRUE);
			String notify = param(inReq, "notify");
			if (notify != null)
				d.setValue("notify", Boolean.valueOf("true".equals(notify)));
			String goals = param(inReq, "goals");
			if (goals != null)
				d.setValue("goals", goals.length() > 200 ? goals.substring(0, 200) : goals);
			String when = param(inReq, "whenlearn");
			if (when != null)
				d.setValue("whenlearn", when.length() > 40 ? when.substring(0, 40) : when);
			String total = param(inReq, "sessiontotal");
			String correct = param(inReq, "sessioncorrect");
			if (total != null && correct != null && d.getValue("sessiontotal") == null)
			{
				try
				{
					d.setValue("sessiontotal", Integer.valueOf(total));
					d.setValue("sessioncorrect", Integer.valueOf(correct));
				}
				catch (NumberFormatException e)
				{
					fail(inReq, 400, "bad_session");
					return;
				}
			}
			s.saveData(d, user);
		}
		JSONObject out = d == null ? new JSONObject() : onboardingJson(d);
		out.put("ok", Boolean.TRUE);
		// The tutor's own welcome line, so the first screen speaks in the organisation's voice.
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		out.put("greeting", persona == null ? null : persona.get("greeting"));
		reply(inReq, out);
	}

	/** Periodic (catalog event, every 30 min): one push a day per learner at the moment they chose
	 *  (learneronboarding.whenlearn), unless they turned reminders off (notify=false). shiftstart 07 · break 13 ·
	 *  dayend 17 · random = an hour 08-17 drawn per learner per day. Fires in the hour after the target;
	 *  `lastreminder` (yyyyMMdd) makes it once a day. Timezone: catalog setting testu_timezone (or the older testu.timezone), default
	 *  America/Lima. Every day of the week: mine shifts run weekends too. Returns the number sent. */
	public int dailyReminder(MediaArchive archive)
	{
		// The engine's setting is testu_timezone (LearningEngine.orgZone); the dotted name is kept so an older site's value still counts.
		String tzid = archive.getCatalogSettingValue("testu_timezone");
		if (tzid == null || tzid.isEmpty())
			tzid = archive.getCatalogSettingValue("testu.timezone");
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(tzid == null || tzid.isEmpty() ? "America/Lima" : tzid));
		int hour = cal.get(Calendar.HOUR_OF_DAY);
		String today = String.format("%04d%02d%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		String tutor = persona == null || persona.getName() == null ? "IRIS" : persona.getName();
		Searcher ob = archive.getSearcher("learneronboarding");
		Searcher ns = archive.getSearcher("learnernotification");
		TestUSocialModule social = (TestUSocialModule) getModuleManager().getBean("TestUSocialModule");
		HitTracker rows = ob.query().all().search();
		rows.enableBulkOperations();
		int sent = 0;
		for (Object hit : rows)
		{
			Data r = (Data) hit;
			String user = r.get("user");
			String when = r.get("whenlearn");
			if (user == null || when == null || "false".equals(String.valueOf(r.getValue("notify"))) || today.equals(r.get("lastreminder")))
				continue;
			int target;
			switch (when)
			{
				case "shiftstart": target = 7; break;
				case "break": target = 13; break;
				case "dayend": target = 17; break;
				case "random": target = 8 + Math.abs((user + today).hashCode()) % 10; break;
				default: continue;
			}
			if (hour != target)
				continue;
			Data u = archive.getCachedData("user", user);
			boolean en = u != null && String.valueOf(u.get("language")).startsWith("en");
			Data n = ns.createNewData();
			n.setId(user + "_reminder_" + today);
			n.setValue("user", user);
			n.setValue("actor", "tutor");
			n.setValue("actorname", tutor);
			n.setValue("type", "reminder");
			n.setValue("datecreated", new Date());
			n.setValue("read", Boolean.FALSE);
			n.setValue("text", en ? "Time to learn: today’s questions are waiting for you. A few minutes and you’re done."
					: "Es tu momento de aprender: tus preguntas de hoy te esperan. Unos minutos y listo.");
			ns.saveData(n, null);
			Data row = (Data) ob.searchById(r.getId());
			row.setValue("lastreminder", today);
			ob.saveData(row, null);
			social.push(archive, n);
			notifyUser(user, "notifications", null);
			sent++;
		}
		return sent;
	}

	/** Local hour of the Daily Challenge email. */
	public static final int EMAIL_HOUR = 9;

	/**
	 * Periodic (catalog event dailychallengeemail, every 15 min): the Daily Challenge email, Monday to Friday in the 09:00 hour of each
	 * learner's own zone (user.timezone, else the org's testu_timezone), with a one-click sign-in link that opens today's challenge.
	 * Off unless catalog setting testu_dailychallengeemail is "true" (everyone) or a comma-separated list of emails (only those).
	 * Skips disabled, internal (support) and email-less accounts, and learners whose challenge for today is already done.
	 * Once per learner and local day: the dailychallengeemail row (id <user>_<yyyyMMdd>) is saved before the send, so a crash or a
	 * failed send (status failed) is never retried into a second email. Returns the number sent.
	 */
	public int dailyChallengeEmail(MediaArchive archive)
	{
		String on = archive.getCatalogSettingValue("testu_dailychallengeemail");
		if (on == null || on.trim().isEmpty() || "false".equals(on.trim()))
		{
			return 0;
		}
		// ponytail: an email allowlist doubles as the pilot switch; a per-team or per-profile toggle when an org needs one.
		Set<String> only = null;
		if (!"true".equals(on.trim()))
		{
			only = new java.util.HashSet<>();
			for (String e : on.split(","))
			{
				only.add(e.trim().toLowerCase());
			}
		}
		String learnurl = learnUrl(archive);
		if (learnurl == null)
		{
			org.apache.commons.logging.LogFactory.getLog(TestULearningModule.class).error("testu dailychallengeemail: set catalog setting testu_learnurl (or siteroot); nothing sent");
			return 0;
		}
		LearningEngine engine = new LearningEngine(archive);
		java.time.ZoneId orgzone = (java.time.ZoneId) engine.orgZone()[0];
		java.time.Instant now = java.time.Instant.now();
		Searcher sent = archive.getSearcher("dailychallengeemail");
		HitTracker users = archive.query("user").all().search();
		users.enableBulkOperations();
		int n = 0;
		for (Object hit : users)
		{
			Data u = (Data) hit;
			String email = u.get("email");
			// "admin" is the platform's own account, not a learner.
			if (email == null || email.trim().isEmpty() || "admin".equals(u.getId()) || !TestUAnalyticsModule.countsAsPerson(u.getId(), u)
					|| (only != null && !only.contains(email.trim().toLowerCase())))
			{
				continue;
			}
			java.time.ZoneId zone = zoneOf(u.get("timezone"), orgzone);
			String key = emailDueKey(u.getId(), zone, now);
			if (key == null || sent.searchById(key) != null || engine.dailyChallengeDoneToday(u.getId()))
			{
				continue;
			}
			Data row = sent.createNewData();
			row.setId(key);
			row.setValue("user", u.getId());
			row.setValue("localdate", now.atZone(zone).toLocalDate().toString());
			row.setValue("timezone", zone.getId());
			row.setValue("sentat", new Date());
			row.setValue("status", "sent");
			sent.saveData(row, null);
			try
			{
				sendDailyChallengeEmail(archive, u, learnurl);
				n++;
			}
			catch (Exception e)
			{
				org.apache.commons.logging.LogFactory.getLog(TestULearningModule.class).error("testu dailychallengeemail " + u.getId(), e);
				row.setValue("status", "failed");
				sent.saveData(row, null);
			}
		}
		return n;
	}

	/** inNow in inZone is a Monday-Friday EMAIL_HOUR: the send key <user>_<yyyyMMdd of that local day>; otherwise null. Pure. */
	public static String emailDueKey(String inUserid, java.time.ZoneId inZone, java.time.Instant inNow)
	{
		java.time.ZonedDateTime t = inNow.atZone(inZone);
		java.time.DayOfWeek d = t.getDayOfWeek();
		if (t.getHour() != EMAIL_HOUR || d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY)
		{
			return null;
		}
		return inUserid + "_" + t.toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
	}

	/** The learner's IANA zone (user.timezone); missing or invalid = inOrg. */
	public static java.time.ZoneId zoneOf(String inUserZone, java.time.ZoneId inOrg)
	{
		try
		{
			return inUserZone == null || inUserZone.trim().isEmpty() ? inOrg : java.time.ZoneId.of(inUserZone.trim());
		}
		catch (Exception e)
		{
			return inOrg;
		}
	}

	/** The learner web app's address, with a trailing slash: catalog setting testu_learnurl, else siteroot + /site/learn/; null when neither. */
	static String learnUrl(MediaArchive archive)
	{
		String url = archive.getCatalogSettingValue("testu_learnurl");
		if (url == null || url.trim().isEmpty())
		{
			String root = archive.getCatalogSettingValue("siteroot");
			if (root == null || root.trim().isEmpty())
			{
				return null;
			}
			url = root.trim().replaceAll("/+$", "") + "/site/learn/";
		}
		url = url.trim();
		return url.endsWith("/") ? url : url + "/";
	}

	/** Mints u's sign-in link and mails the Daily Challenge email to u; returns {subject, html, text, link}. */
	protected String[] sendDailyChallengeEmail(MediaArchive archive, Data u, String inLearnurl) throws Exception
	{
		String[] mail = dailyChallengeMail(archive, u, inLearnurl);
		org.entermediadb.email.PostMail pm = (org.entermediadb.email.PostMail) getModuleManager().getBean("postMail");
		pm.postMail(new String[] {u.get("email")}, mail[0], mail[1], mail[2], archive.getCatalogSettingValue("system_from_email"), mail[4]);
		return mail;
	}

	/** {subject, html, text, link, fromname} for u, with a freshly minted sign-in link (which replaces u's previous one). */
	protected String[] dailyChallengeMail(MediaArchive archive, Data u, String inLearnurl)
	{
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		String tutor = persona == null || persona.getName() == null ? "TestU" : persona.getName();
		String lang = u.get("language");
		if (lang == null || lang.isEmpty())
		{
			lang = persona == null ? null : persona.get("tutorlanguage");
		}
		// Only the link's fragment carries the token: a fragment never reaches a server log or a Referer header.
		String link = inLearnurl + "#/desafio?login=" + org.entermediadb.asset.modules.AdminModule.createLoginLink(archive.getSearcherManager(), u.getId());
		String[] m = emailContent(lang != null && lang.startsWith("en"), givenName(u.get("firstName")), tutor, link);
		return new String[] {m[0], m[1], m[2], link, tutor};
	}

	/** "RENZO ALDAIR" -> "Renzo"; null/blank -> "". */
	public static String givenName(String inFirstName)
	{
		String f = inFirstName == null ? "" : inFirstName.trim();
		if (f.isEmpty())
		{
			return "";
		}
		f = f.split("\\s+")[0];
		return f.substring(0, 1).toUpperCase() + f.substring(1).toLowerCase();
	}

	/**
	 * {subject, html, text} of the Daily Challenge email (copy: docs/copy/desafio-diario-minsur.es.md; the {minutos} clause is left
	 * out, nothing estimates it before the day's set is built). Pure.
	 */
	public static String[] emailContent(boolean inEnglish, String inName, String inTutor, String inLink)
	{
		boolean named = inName != null && !inName.isEmpty();
		String subject = inEnglish ? (named ? inName + ", your" : "Your") + " Daily Challenge is waiting"
				: (named ? inName + ", tu" : "Tu") + " Desafío Diario te espera";
		String pre = inEnglish ? "It only takes a few minutes. " + inTutor + " has it ready." : "Solo te toma unos minutos. " + inTutor + " ya lo tiene listo.";
		String hello = inEnglish ? (named ? "Hi " + inName + "," : "Hi,") : (named ? "Hola " + inName + ":" : "Hola:");
		String p1 = inEnglish ? "Today’s Daily Challenge is ready. It’s just a few questions." : "Tu Desafío Diario de hoy ya está listo. Son pocas preguntas.";
		String p2 = inEnglish ? "Every day you complete it, you reinforce what you already know and " + inTutor + " learns which topics you need to practise most."
				: "Cada día que lo completas, refuerzas lo que ya sabes y " + inTutor + " aprende qué temas necesitas practicar más.";
		String button = inEnglish ? "Start my challenge" : "Empezar mi desafío";
		String bye = inEnglish ? "See you in the app!" : "¡Nos vemos en la app!";
		String foot = inEnglish ? "You’re receiving this email because you have a TestU account. If the button doesn’t work, open the app and sign in with your email."
				: "Recibes este correo porque tienes una cuenta en TestU. Si el botón no funciona, abre la app e ingresa con tu correo.";
		String text = hello + "\n\n" + p1 + "\n\n" + p2 + "\n\n" + button + ": " + inLink + "\n\n" + bye + "\n" + inTutor + "\n\n" + foot + "\n";
		String p = "<p style=\"margin:0 0 16px;font-size:16px;line-height:1.5;color:#1a1a1a\">";
		String html = "<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f4f4f5\">"
				+ "<span style=\"display:none;max-height:0;overflow:hidden;opacity:0\">" + esc(pre) + "</span>"
				+ "<div style=\"max-width:520px;margin:0 auto;padding:32px 24px;font-family:Helvetica,Arial,sans-serif;background:#ffffff\">"
				+ p + esc(hello) + "</p>" + p + esc(p1) + "</p>" + p + esc(p2) + "</p>"
				+ "<p style=\"margin:28px 0;text-align:center\"><a href=\"" + esc(inLink) + "\" style=\"display:inline-block;padding:14px 28px;border-radius:8px;"
				+ "background:#18181b;color:#ffffff;font-size:16px;font-weight:bold;text-decoration:none\">" + esc(button) + "</a></p>"
				+ p + esc(bye) + "<br>" + esc(inTutor) + "</p>"
				+ "<p style=\"margin:32px 0 0;font-size:12px;line-height:1.5;color:#71717a\">" + esc(foot) + "</p>"
				+ "</div></body></html>";
		return new String[] {subject, html, text};
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * services/testu/learn/dailychallengeemail.json -- the signed-in learner's own Daily Challenge email, for QA: GET = preview
	 * {ok, to, subject, text, html, link, due} with a freshly minted link (replaces any link emailed earlier); POST send=true also
	 * mails it to that learner. Never another user's: a link is a sign-in.
	 */
	public void dailyChallengeEmailPreview(WebPageRequest inReq) throws Exception
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		String learnurl = learnUrl(archive);
		if (learnurl == null)
		{
			fail(inReq, 409, "learnurl_not_configured");
			return;
		}
		Data u = freshUser(archive, user);
		boolean send = "true".equals(inReq.getRequestParameter("send")) && inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod());
		String[] mail = send ? sendDailyChallengeEmail(archive, u, learnurl) : dailyChallengeMail(archive, u, learnurl);
		JSONObject out = new JSONObject();
		out.put("ok", Boolean.TRUE);
		out.put("to", u.get("email"));
		out.put("subject", mail[0]);
		out.put("html", mail[1]);
		out.put("text", mail[2]);
		out.put("link", mail[3]);
		out.put("sent", send);
		out.put("due", emailDueKey(u.getId(), zoneOf(u.get("timezone"), (java.time.ZoneId) new LearningEngine(archive).orgZone()[0]), java.time.Instant.now()));
		reply(inReq, out);
	}

	private static JSONObject onboardingJson(Data d)
	{
		JSONObject o = new JSONObject();
		o.put("user", d.get("user"));
		o.put("started", TestUTermsModule.iso(d.getValue("started")));
		o.put("finished", TestUTermsModule.iso(d.getValue("finished")));
		o.put("skipped", Boolean.valueOf("true".equals(String.valueOf(d.getValue("skipped")))));
		o.put("opens", TestUTermsModule.number(d.get("opens")));
		// Absent = on: choosing a moment in the onboarding is the opt-in.
		o.put("notify", Boolean.valueOf(!"false".equals(String.valueOf(d.getValue("notify")))));
		o.put("goals", d.get("goals"));
		o.put("whenlearn", d.get("whenlearn"));
		o.put("sessiontotal", TestUTermsModule.number(d.get("sessiontotal")));
		o.put("sessioncorrect", TestUTermsModule.number(d.get("sessioncorrect")));
		return o;
	}

}
