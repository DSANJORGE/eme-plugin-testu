package tech.genailabs.tutor;

import java.time.ZoneId;
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
	private static final org.apache.commons.logging.Log log = org.apache.commons.logging.LogFactory.getLog(TestULearningModule.class);

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
		JSONArray certs = new JSONArray();
		Date now = new Date(); ZoneId zone = (ZoneId) engine.orgZone()[0];
		for (LearningEngine.Topic t : content.topics.values())
		{
			JSONObject c = LearningEngine.certificationStatus(t, learner, now, zone);
			if (c == null)
			{
				continue;
			}
			JSONObject row = new JSONObject();
			row.put("topic", t.id); row.put("title", t.title); row.put("status", c.get("status")); row.put("expiry", c.get("expiry"));
			row.put("scheduledfor", c.get("scheduledfor"));
			row.put("canstart", Boolean.TRUE.equals(LearningEngine.evaluationStatus(t, learner, now, zone).get("canstart")));
			certs.add(row);
		}
		certs.sort(java.util.Comparator.comparing((Object o) -> certRank(((JSONObject) o).get("status"))).thenComparing(o -> String.valueOf(((JSONObject) o).get("expiry"))));
		resp.put("certifications", certs);
		resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}

	private static int certRank(Object s)
	{
		return switch (String.valueOf(s))
		{
			case "expired" -> 0;
			case "renewal_due" -> 1;
			case "not_certified" -> 2;
			default -> 3;
		};
	}

	/** learningsession.source values (next.json source). */
	static final Set<String> SESSION_SOURCES = Set.of("dailydone", "email", "push", "app");

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
			dc.put("daycopy", dayCopyJson(getMediaArchive(inReq), urec, learner.attempts));
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
		// What started it: the Daily Challenge "done" screen's recommendation (dailydone, the engagement funnel), or the entry that
		// brought the learner in (email | push | app). Anything else = null.
		String source = SESSION_SOURCES.contains(String.valueOf(param(inReq, "source"))) ? param(inReq, "source") : null;
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
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		JSONObject resp = LearningEngine.evaluationStatus(topic, learner, new Date(), zone);
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
		err.put("validuntil", inStatus.get("validuntil"));
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
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		JSONObject status = LearningEngine.evaluationStatus(topic, learner, now, zone);
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
			notAvailable(inReq, LearningEngine.evaluationStatus(topic, learner, new Date(), zone));
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
		resp.put("passpercent", a.passpercent);
		resp.put("subtopicminpercent", a.inputs.get("subtopicminpercent"));
		if (topic != null)
		{
			ZoneId zone = (ZoneId) engine.orgZone()[0];
			if (!duplicate && a.passed && topic.certification())
			{
				LearningEngine.CertRow row;
				synchronized (LearningEngine.WRITE_LOCK) { row = engine.recordCertificationPass(topic, a, zone); }
				learner.certifications.put(topic.id, row);
				audit(inReq, getMediaArchive(inReq), "certification.pass", "certification", row.id(), null, LearningEngine.certificationStatus(topic, learner, new Date(), zone));
			}
			topic.finished = LearningEngine.finished(topic, learner);
			JSONObject st = LearningEngine.evaluationStatus(topic, learner, new Date(), zone);
			resp.put("evaluationstatus", st.get("status"));
			resp.put("nextallowedat", st.get("nextallowedat"));
			resp.put("attemptsleft", st.get("attemptsleft"));
			resp.put("finished", topic.position == null ? null : Boolean.valueOf(topic.finished));
			resp.put("certification", LearningEngine.certificationStatus(topic, learner, new Date(), zone));
		}
		if (!duplicate)
		{
			audit(inReq, getMediaArchive(inReq), "evaluation.submit", "evaluationattempt", a.id, null, a.toResultJson());
			notifyUser(user.getId(), "progress", null);
		}
		reply(inReq, resp);
	}

	/**
	 * services/testu/learn/schedulecertification.json (POST topicid, date -- YYYY-MM-DD, blank clears) -- the learner's commitment
	 * for the renewal (spec 2026-09-23). date_past when the date isn't today-or-later; date_after_expiry when it falls past the
	 * current cycle's expiry -- except an already-expired cycle accepts any date from today onward (controller ruling).
	 */
	public void scheduleCertification(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		Object[] loaded = load(inReq, user);
		LearningEngine engine = (LearningEngine) loaded[0];
		LearningEngine.Content content = (LearningEngine.Content) loaded[1];
		LearningEngine.Learner learner = (LearningEngine.Learner) loaded[2];
		LearningEngine.Topic topic = content.topics.get(param(inReq, "topicid"));
		if (topic == null)
		{
			fail(inReq, 404, "unknown_topic");
			return;
		}
		if (!topic.certification())
		{
			fail(inReq, 400, "not_certification_topic");
			return;
		}
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		String raw = param(inReq, "date");
		Date day = null;
		if (raw != null)
		{
			day = LearningEngine.parseYmd(raw);
			if (day == null)
			{
				fail(inReq, 400, "bad_date");
				return;
			}
			if (LearningEngine.endOfDay(day, zone).before(now))
			{
				fail(inReq, 400, "date_past");
				return;
			}
			LearningEngine.CertRow existing = learner.certifications.get(topic.id);
			Date expiry = LearningEngine.expiryOf(existing, topic, zone);
			// An already-expired cycle has no upper bound: only a still-current expiry (status not "expired") constrains the date.
			boolean expired = "expired".equals(LearningEngine.certStatus(topic, existing, now, zone));
			if (expiry != null && day.after(expiry) && !expired)
			{
				fail(inReq, 400, "date_after_expiry");
				return;
			}
		}
		LearningEngine.CertRow row = learner.certifications.get(topic.id);
		if (row == null)
		{
			row = new LearningEngine.CertRow();
			row.user = user.getId();
			row.topicid = topic.id;
		}
		JSONObject before = LearningEngine.certificationStatus(topic, learner, now, zone);
		row.scheduledfor = day;
		synchronized (LearningEngine.WRITE_LOCK) { engine.saveCertification(row); }
		learner.certifications.put(topic.id, row);
		JSONObject after = LearningEngine.certificationStatus(topic, learner, now, zone);
		audit(inReq, getMediaArchive(inReq), "certification.schedule", "certification", row.id(), before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("certification", after);
		resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}

	/**
	 * services/testu/learn/certifications.json?status&topicid&profile&team -- the admin compliance list (spec 2026-09-23): one row per
	 * user x certification topic the caller may manage. Read-only; training_view (or training_manage) plus per-topic manageevaluations.
	 */
	public void certifications(WebPageRequest inReq)
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		boolean manage = canManageProgression(inReq);
		if (!manage && (profile == null || !profile.hasPermission("training_view")))
		{
			fail(inReq, 403, "forbidden");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine engine = new LearningEngine(archive);
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		String fstatus = param(inReq, "status"), ftopic = param(inReq, "topicid"), fprofile = param(inReq, "profile"), fteam = param(inReq, "team");
		JSONArray rows = new JSONArray();
		java.util.Map<String, Integer> counts = new java.util.TreeMap<>(java.util.Map.of("certified", 0, "renewal_due", 0, "expired", 0, "not_certified", 0));
		java.util.Set<String> hidden = new java.util.HashSet<>(); // certification topics dropped by manageevaluations -- distinct topics, the check is per topic, not per learner
		for (Object o : archive.query("user").all().search())
		{
			Data u = (Data) o;
			if ("agent".equals(u.get("role")) || "false".equals(String.valueOf(u.get("enabled"))))
			{
				continue;
			}
			if (fteam != null && !fteam.equals(u.get("team")))
			{
				continue;
			}
			java.util.Collection<String> roles = LearningEngine.jobrolesOf(u);
			if (fprofile != null && !roles.contains(fprofile))
			{
				continue;
			}
			if (roles.isEmpty())
			{
				continue;
			}
			// ponytail: loadContent per user. Re-checked in the 2026-09-23 review: LearningEngine has no copy constructor or
			// loadContent(Content base), and applyProfiles mutates Topic per learner (assignment order, finished, locked), so
			// memoizing by sorted-jobroles key would hand two learners the same mutated Topic. Ceiling: O(roster) content loads;
			// upgrade path is a Content/Topic deep copy in the engine, worth writing only when the roster passes a few hundred.
			LearningEngine.Content content = engine.loadContent();
			LearningEngine.Learner l = engine.loadLearner(u.getId(), roles, LearningEngine.primaryJobroleOf(u));
			engine.applyProfiles(content, l);
			for (LearningEngine.Topic t : content.topics.values())
			{
				if (!t.certification() || (ftopic != null && !ftopic.equals(t.id)))
				{
					continue;
				}
				if (!canManageEvaluations(inReq, archive, t.id))
				{
					hidden.add(t.id);
					continue;
				}
				JSONObject c = LearningEngine.certificationStatus(t, l, now, zone);
				if (fstatus != null && !fstatus.equals(c.get("status")))
				{
					continue;
				}
				counts.merge(String.valueOf(c.get("status")), 1, Integer::sum);
				rows.add(certRow(u, t, l, c, now, zone));
			}
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("canmanage", manage);
		resp.put("counts", new JSONObject(counts));
		resp.put("hidden", Integer.valueOf(hidden.size()));
		resp.put("rows", rows);
		resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}

	/** certifications.json row: certificationStatus() plus user/topic identity and per-row fields not on the pure status block. */
	private static JSONObject certRow(Data u, LearningEngine.Topic t, LearningEngine.Learner l, JSONObject c, Date now, ZoneId zone)
	{
		JSONObject r = new JSONObject(c);
		LearningEngine.CertRow row = l.certifications.get(t.id);
		r.put("user", u.getId());
		r.put("name", TestUAnalyticsModule.formatUserName(u));
		r.put("team", u.get("team"));
		r.put("topic", t.id);
		r.put("topictitle", t.title);
		r.put("scorepercent", row == null ? null : row.scorepercent);
		r.put("extendeduntil", row == null ? null : LearningEngine.ymd(row.extendeduntil, zone));
		r.put("extendreason", row == null ? null : row.extendreason);
		r.put("attempts", LearningEngine.evaluationStatus(t, l, now, zone).get("attempts"));
		return r;
	}

	/** training_view (reads) / training_manage (writes) + per-topic manageevaluations. null = already failed the request. */
	private LearningEngine.Topic certAdminTopic(WebPageRequest inReq, boolean inWrite, LearningEngine.Content inContent, String inTopicid)
	{
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		boolean manage = canManageProgression(inReq);
		if (inWrite ? !manage : !(manage || (profile != null && profile.hasPermission("training_view"))))
		{
			fail(inReq, 403, "forbidden");
			return null;
		}
		LearningEngine.Topic t = inContent.topics.get(inTopicid);
		if (t == null)
		{
			fail(inReq, 404, "unknown_topic");
			return null;
		}
		if (!canManageEvaluations(inReq, getMediaArchive(inReq), t.id))
		{
			fail(inReq, 403, "forbidden");
			return null;
		}
		return t;
	}

	/**
	 * services/testu/learn/extendcertification.json (POST user, topicid, until -- YYYY-MM-DD, reason) -- pushes the current cycle's
	 * expiry out (spec 2026-09-23). training_manage + per-topic manageevaluations. Clears the 7d/1d/expired reminder flags so the
	 * moved expiry gets its own reminders.
	 */
	public void extendCertification(WebPageRequest inReq)
	{
		User admin = requireUser(inReq);
		if (admin == null)
		{
			return;
		}
		if (!canManageProgression(inReq))
		{
			fail(inReq, 403, "forbidden"); // before any lookup: a learner probing other users must not learn who exists (404 vs 403)
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine engine = new LearningEngine(archive);
		String userid = param(inReq, "user"), topicid = param(inReq, "topicid"), untilRaw = param(inReq, "until"), reason = param(inReq, "reason");
		Data u = userid == null ? null : (Data) archive.getSearcher("user").searchById(userid);
		if (u == null)
		{
			fail(inReq, 404, "unknown_user");
			return;
		}
		LearningEngine.Content content = engine.loadContent();
		LearningEngine.Learner l = engine.loadLearner(u.getId(), LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
		engine.applyProfiles(content, l);
		LearningEngine.Topic t = certAdminTopic(inReq, true, content, topicid);
		if (t == null)
		{
			return;
		}
		if (!t.certification())
		{
			fail(inReq, 400, "not_certification_topic");
			return;
		}
		if (t.validitymonths == 0)
		{
			fail(inReq, 400, "never_expires"); // validity 0 has no expiry to push out; expiryOf ignores extendeduntil for it
			return;
		}
		if (reason == null)
		{
			fail(inReq, 400, "missing_reason");
			return;
		}
		LearningEngine.CertRow row = l.certifications.get(t.id);
		if (row == null || row.passedat == null)
		{
			fail(inReq, 400, "not_certified_yet");
			return;
		}
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		Date until = LearningEngine.parseYmd(untilRaw);
		if (until == null)
		{
			fail(inReq, 400, "bad_date");
			return;
		}
		Date current = LearningEngine.expiryOf(row, t, zone);
		if (current != null && !LearningEngine.endOfDay(until, zone).after(current))
		{
			fail(inReq, 400, "date_not_later");
			return;
		}
		if (until.after(LearningEngine.plusMonths(now, 12, zone)))
		{
			fail(inReq, 400, "date_too_far");
			return;
		}
		JSONObject before = LearningEngine.certificationStatus(t, l, now, zone);
		row.extendeduntil = until;
		row.extendreason = reason;
		row.extendedby = admin.getId();
		row.reminderssent.removeIf(s -> s.equals("7d") || s.equals("1d") || s.equals("expired"));
		synchronized (LearningEngine.WRITE_LOCK) { engine.saveCertification(row); }
		JSONObject after = LearningEngine.certificationStatus(t, l, now, zone);
		audit(inReq, archive, "certification.extend", "certification", row.id(), before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("row", certRow(u, t, l, after, now, zone));
		resp.put("now", LearningEngine.iso(now));
		reply(inReq, resp);
	}

	/**
	 * services/testu/learn/manualcertification.json (POST user, topicid, passedat -- YYYY-MM-DD, reason) -- records an off-platform
	 * pass (spec 2026-09-23): starts a fresh cycle (attemptid/scorepercent cleared, manual=true) and drops any prior extension.
	 * training_manage + per-topic manageevaluations.
	 */
	public void manualCertification(WebPageRequest inReq)
	{
		User admin = requireUser(inReq);
		if (admin == null)
		{
			return;
		}
		if (!canManageProgression(inReq))
		{
			fail(inReq, 403, "forbidden"); // before any lookup: a learner probing other users must not learn who exists (404 vs 403)
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine engine = new LearningEngine(archive);
		String userid = param(inReq, "user"), topicid = param(inReq, "topicid"), passedatRaw = param(inReq, "passedat"), reason = param(inReq, "reason");
		Data u = userid == null ? null : (Data) archive.getSearcher("user").searchById(userid);
		if (u == null)
		{
			fail(inReq, 404, "unknown_user");
			return;
		}
		LearningEngine.Content content = engine.loadContent();
		LearningEngine.Learner l = engine.loadLearner(u.getId(), LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
		engine.applyProfiles(content, l);
		LearningEngine.Topic t = certAdminTopic(inReq, true, content, topicid);
		if (t == null)
		{
			return;
		}
		if (!t.certification())
		{
			fail(inReq, 400, "not_certification_topic");
			return;
		}
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		Date passedat = LearningEngine.parseYmd(passedatRaw);
		if (passedat == null)
		{
			fail(inReq, 400, "bad_date");
			return;
		}
		Date today = LearningEngine.parseYmd(LearningEngine.ymd(now, zone)); // zone-aware day compare: today (org zone) is allowed, tomorrow+ is not
		if (passedat.after(today))
		{
			fail(inReq, 400, "date_future");
			return;
		}
		if (reason == null)
		{
			fail(inReq, 400, "missing_reason");
			return;
		}
		JSONObject before = LearningEngine.certificationStatus(t, l, now, zone);
		LearningEngine.CertRow row = l.certifications.get(t.id);
		if (row == null)
		{
			row = new LearningEngine.CertRow();
			row.user = u.getId();
			row.topicid = t.id;
		}
		row.passedat = passedat;
		row.attemptid = null;
		row.scorepercent = null;
		row.manual = true;
		row.manualby = admin.getId();
		row.manualreason = reason;
		row.validuntil = t.validitymonths == 0 ? null : LearningEngine.plusMonths(passedat, t.validitymonths, zone);
		row.extendeduntil = null;
		row.extendreason = null;
		row.extendedby = null;
		row.scheduledfor = null;
		// Fresh cycle first: stagesAlreadyPast subtracts what reminderssent already holds, so last cycle's stages would come back
		// un-marked here and re-fire against the new expiry.
		row.reminderssent = new java.util.ArrayList<>();
		row.reminderssent = LearningEngine.stagesAlreadyPast(row, t, now, zone);
		l.certifications.put(t.id, row);
		synchronized (LearningEngine.WRITE_LOCK) { engine.saveCertification(row); }
		JSONObject after = LearningEngine.certificationStatus(t, l, now, zone);
		audit(inReq, archive, "certification.manual", "certification", row.id(), before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("row", certRow(u, t, l, after, now, zone));
		resp.put("now", LearningEngine.iso(now));
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
		String tutor = tutorName(archive);
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

	/** The org's configured tutor persona name (catalog setting tutorpersona, default iris); "IRIS" when unset/unnamed. */
	String tutorName(MediaArchive archive)
	{
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		return persona == null || persona.getName() == null ? "IRIS" : persona.getName();
	}

	/** audit(archive, actor, ...) with actor "system", for background jobs that have no signed-in user. */
	private void auditSystem(MediaArchive archive, String action, String targettype, String targetid, Object before, Object after)
	{
		audit(archive, "system", action, targettype, targetid, before, after);
	}

	/** 15-minute event: certification reminders (spec 2026-09-23). One learnernotification per stage per cycle; expired also audits. */
	public int certificationReminders(MediaArchive archive)
	{
		LearningEngine engine = new LearningEngine(archive);
		ZoneId zone = (ZoneId) engine.orgZone()[0];
		Date now = new Date();
		TestUSocialModule social = (TestUSocialModule) getModuleManager().getBean("TestUSocialModule");
		Searcher ns = archive.getSearcher("learnernotification");
		int sent = 0;
		java.util.Map<String, Data> users = new java.util.HashMap<>();
		for (Object o : archive.query("user").all().search())
		{
			Data u = (Data) o;
			users.put(u.getId(), u);
		}
		for (Object o : archive.query("certification").all().search())
		{
			LearningEngine.CertRow r = LearningEngine.certRowOf((Data) o);
			try
			{
				Data u = users.get(r.user);
				// Missing enabled = enabled, as certifications() reads it: only an explicit "false" is disabled.
				if (u == null || "false".equals(String.valueOf(u.get("enabled"))))
				{
					continue;
				}
				LearningEngine.Content content = engine.loadContent();
				LearningEngine.Learner l = engine.loadLearner(r.user, LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
				engine.applyProfiles(content, l);
				LearningEngine.Topic t = content.topics.get(r.topicid);
				if (t == null || !t.certification() || t.blueprint == null || !t.blueprint.usable())
				{
					continue;
				}
				java.util.List<String> due = LearningEngine.dueStages(r, t, now, zone);
				if (due.isEmpty())
				{
					continue;
				}
				synchronized (LearningEngine.WRITE_LOCK)
				{
					for (String stage : due)
					{
						Data n = ns.createNewData();
						n.setId(r.id() + "_" + stage + "_" + LearningEngine.ymd(r.passedat, zone));
						n.setValue("user", r.user);
						n.setValue("actor", "tutor");
						n.setValue("actorname", tutorName(archive));
						n.setValue("type", "certification");
						n.setValue("datecreated", now);
						n.setValue("read", false);
						n.setValue("entitytopic", t.id);
						n.setValue("text", certificationText(stage, t.title, LearningEngine.ymd(LearningEngine.expiryOf(r, t, zone), zone)));
						ns.saveData(n, null);
						social.push(archive, n);
						if ("expired".equals(stage))
						{
							auditSystem(archive, "certification.expire", "certification", r.id(), null, LearningEngine.certificationStatus(t, l, now, zone));
						}
						sent++;
					}
					r.reminderssent.addAll(due);
					engine.saveCertification(r);
				}
				notifyUser(r.user, "notifications", null);
			}
			catch (Exception e)
			{
				log.error("certification reminders: row " + r.id(), e); // one bad row must not stop the sweep for the other learners
			}
		}
		return sent;
	}

	static String certificationText(String stage, String topic, String expiry)
	{
		return switch (stage)
		{
			case "window_open" -> "Ya puedes renovar tu certificación de " + topic + " (vence el " + expiry + ").";
			case "7d" -> "Tu certificación de " + topic + " vence en 7 días. Rinde la evaluación esta semana.";
			case "1d" -> "Tu certificación de " + topic + " vence mañana.";
			case "expired" -> "Tu certificación de " + topic + " venció. Rinde la evaluación para renovarla.";
			default -> "Hoy es el día que elegiste para renovar tu certificación de " + topic + ".";
		};
	}

	/**
	 * services/testu/learn/certificationreminders.json (GET) -- runs certificationReminders(archive) on demand: how the 15-minute
	 * event is exercised from check_certification.sh, and how an admin can kick reminders right after a bulk certification import.
	 * training_manage.
	 */
	public void runCertificationReminders(WebPageRequest inReq)
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
		int sent = certificationReminders(getMediaArchive(inReq));
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("sent", sent);
		reply(inReq, resp);
	}

	/** Local hour of the Daily Challenge email. */
	public static final int EMAIL_HOUR = 9;

	/**
	 * Periodic (catalog event dailychallengeemail, every 15 min): the Daily Challenge email, Monday to Friday in the 09:00 hour of each
	 * learner's own zone (user.timezone, else the org's testu_timezone), with a one-click sign-in link that opens today's challenge.
	 * Who: see mayReceive (master switch testu_dailychallengeemail, role permission EMAIL_PERMISSION, test allowlist
	 * testu_dailychallengeemail_only). Skips disabled, internal (support) and email-less accounts, and learners whose challenge for
	 * today is already done. Once per learner and local day: the dailychallengeemail row (id <user>_<yyyyMMdd>) is saved before the
	 * send, so a crash or a failed send (status failed) is never retried into a second email. Returns the number sent.
	 */
	public int dailyChallengeEmail(MediaArchive archive)
	{
		grantEmailPermissionOnce(archive);
		String on = archive.getCatalogSettingValue("testu_dailychallengeemail");
		String only = archive.getCatalogSettingValue("testu_dailychallengeemail_only");
		if (!mayReceive(on, only, null, null))
		{
			return 0;
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
		java.util.Map<String, java.util.Collection> rolePerms = new java.util.HashMap<>();
		HitTracker users = archive.query("user").all().search();
		users.enableBulkOperations();
		int n = 0;
		for (Object hit : users)
		{
			Data u = (Data) hit;
			String email = u.get("email");
			// "admin" is the platform's own account, not a learner.
			if (email == null || email.trim().isEmpty() || "admin".equals(u.getId()) || !TestUAnalyticsModule.countsAsPerson(u.getId(), u))
			{
				continue;
			}
			java.time.ZoneId zone = zoneOf(u.get("timezone"), orgzone);
			String key = emailDueKey(u.getId(), zone, now);
			if (key == null || !mayReceive(on, only, email, rolePermissions(archive, u.getId(), rolePerms)) || sent.searchById(key) != null
					|| engine.dailyChallengeDoneToday(u.getId()))
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

	/** Role permission (Settings > Roles) that lets a learner receive the Daily Challenge email. */
	public static final String EMAIL_PERMISSION = "testu_dailychallengeemail";

	/**
	 * Whether the Daily Challenge email goes to inEmail, whose role carries inRolePermissions. Pure.
	 * inOn = catalog setting testu_dailychallengeemail, the org's master switch: on unless it is "false" (unset = on).
	 * inOnly = catalog setting testu_dailychallengeemail_only, a comma-separated test allowlist: when set, only those emails get it,
	 * even with the master switch off, so a tester can receive it before the org is switched on.
	 * Either way the learner's role must carry EMAIL_PERMISSION. inEmail null = "could anyone get it?" (the job's early exit).
	 */
	public static boolean mayReceive(String inOn, String inOnly, String inEmail, java.util.Collection inRolePermissions)
	{
		return mayReceive(inOn, inOnly, inEmail, inRolePermissions, EMAIL_PERMISSION);
	}

	/** mayReceive for any TestU email: the same switch, allowlist and role rule, with inPermission as the role permission. Pure. */
	public static boolean mayReceive(String inOn, String inOnly, String inEmail, java.util.Collection inRolePermissions, String inPermission)
	{
		Set<String> only = new java.util.HashSet<>();
		for (String e : (inOnly == null ? "" : inOnly).split(","))
		{
			if (!e.trim().isEmpty())
			{
				only.add(e.trim().toLowerCase());
			}
		}
		boolean on = only.isEmpty() ? inOn == null || !"false".equalsIgnoreCase(inOn.trim()) : inEmail == null || only.contains(inEmail.trim().toLowerCase());
		return on && (inEmail == null || inRolePermissions != null && inRolePermissions.contains(inPermission));
	}

	/**
	 * System permissions of inUserid's role (userprofile.settingsrole, legacy settingsgroup, else the catalog's defaultrole, else
	 * "users" -- UserProfileManager's rule), read from the settingsrole row as UserProfile.hasPermission does; cached per role in
	 * inCache for one run. Without loading a full UserProfile, which would create and save one for users who never signed in.
	 */
	protected java.util.Collection rolePermissions(MediaArchive archive, String inUserid, java.util.Map<String, java.util.Collection> inCache)
	{
		Data p = (Data) archive.getSearcher("userprofile").searchById(inUserid);
		String role = p == null ? null : TestUUserModule.roleOf(p);
		if (role == null || role.isEmpty())
		{
			role = archive.getCatalogSettingValue("defaultrole");
			role = role == null || role.isEmpty() ? "users" : role;
		}
		return inCache.computeIfAbsent(role, r -> {
			Data row = (Data) archive.getSearcher("settingsrole").searchById(r);
			java.util.Collection v = row == null ? null : row.getValues("permissions");
			return v == null ? java.util.Collections.emptyList() : v;
		});
	}

	/**
	 * One-time default: EMAIL_PERMISSION on every role but guest, and its permissionsapp row so Settings > Roles lists it. Roles live
	 * in the database, seeded from XML only when the table is first created, so an XML edit alone never reaches an existing site.
	 * Catalog setting testu_dailychallengeemail_granted records it: a role an admin unticks afterwards stays unticked.
	 */
	protected void grantEmailPermissionOnce(MediaArchive archive)
	{
		grantPermissionOnce(archive, EMAIL_PERMISSION, "Desafío Diario: recibir el email", "930", "testu_dailychallengeemail_granted");
	}

	/** grantEmailPermissionOnce for any email permission: inPermission (Settings > Roles label inName) on every role but guest, once. */
	protected void grantPermissionOnce(MediaArchive archive, String inPermission, String inName, String inOrdering, String inGrantedSetting)
	{
		if ("true".equals(archive.getCatalogSettingValue(inGrantedSetting)))
		{
			return;
		}
		Searcher apps = archive.getSearcher("permissionsapp");
		if (apps.searchById(inPermission) == null)
		{
			Data d = apps.createNewData();
			d.setId(inPermission);
			d.setName(inName);
			d.setValue("permissiontype", "application");
			d.setValue("ordering", inOrdering);
			apps.saveData(d, null);
		}
		Searcher roles = archive.getSearcher("settingsrole");
		int seen = 0;
		for (Object hit : roles.query().all().search())
		{
			Data r = (Data) roles.searchById(((Data) hit).getId());
			if (r == null || "guest".equals(r.getId()))
			{
				continue;
			}
			seen++;
			java.util.Collection v = r.getValues("permissions");
			java.util.List<String> perms = new java.util.ArrayList<>(v == null ? java.util.Collections.emptyList() : v);
			if (!perms.contains(inPermission))
			{
				perms.add(inPermission);
				r.setValue("permissions", perms);
				roles.saveData(r, null);
			}
		}
		if (seen > 0) // no roles yet (table not seeded): try again next run
		{
			archive.setCatalogSettingValue(inGrantedSetting, "true");
			org.apache.commons.logging.LogFactory.getLog(TestULearningModule.class).info("testu email: granted " + inPermission + " to " + seen + " roles");
		}
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

	/** Mints u's sign-in link and mails the Daily Challenge email to u, HTML only; returns {subject, html, link, fromname, image}. */
	protected Object[] sendDailyChallengeEmail(MediaArchive archive, Data u, String inLearnurl) throws Exception
	{
		Object[] mail = dailyChallengeMail(archive, u, inLearnurl);
		sendMail(archive, u.get("email"), mail);
		return mail;
	}

	/** Mails inMail = {subject, html, link, fromname, inline image or null} to inTo, HTML only, from emailFrom. */
	protected void sendMail(MediaArchive archive, String inTo, Object[] mail) throws Exception
	{
		org.entermediadb.email.PostMail pm = (org.entermediadb.email.PostMail) getModuleManager().getBean("postMail");
		javax.mail.internet.InternetAddress from = new javax.mail.internet.InternetAddress();
		from.setAddress(emailFrom(archive));
		from.setPersonal(String.valueOf(mail[3]));
		// No text part: PostMail wraps text + html in multipart/mixed (not alternative), so Gmail showed both, one after the other.
		// The tutor's picture travels with the message (multipart/related), so no mail client has to fetch it.
		pm.postMail(pm.parseEmails(new String[] {inTo}), mail[0] == null ? null : String.valueOf(mail[0]), String.valueOf(mail[1]), null, from,
			mail[4] == null ? null : java.util.Collections.singletonList(mail[4]), null);
	}

	/** Sender address: catalog setting testu_email_from, else system_from_email. */
	static String emailFrom(MediaArchive archive)
	{
		String from = archive.getCatalogSettingValue("testu_email_from");
		return from == null || from.trim().isEmpty() ? archive.getCatalogSettingValue("system_from_email") : from.trim();
	}

	/** {subject, html, link, fromname, inline image or null} for u on u's local today, with a freshly minted sign-in link (which replaces u's previous one). */
	protected Object[] dailyChallengeMail(MediaArchive archive, Data u, String inLearnurl)
	{
		Data persona = persona(archive);
		String tutor = persona == null || persona.getName() == null ? "TestU" : persona.getName();
		String lang = u.get("language");
		if (lang == null || lang.isEmpty())
		{
			lang = persona == null ? null : persona.get("tutorlanguage");
		}
		LearningEngine engine = new LearningEngine(archive);
		java.time.ZoneId orgzone = (java.time.ZoneId) engine.orgZone()[0];
		java.time.LocalDate today = java.time.Instant.now().atZone(zoneOf(u.get("timezone"), orgzone)).toLocalDate();
		// The challenge days are org-local (LearningEngine.challengeDate), so the streak is counted on the org's calendar.
		int[] recent = recentChallenges(engine, archive, u.getId(), LearningEngine.challengeDate(new Date(), orgzone), null);
		// Only the link's fragment carries the token: a fragment never reaches a server log or a Referer header.
		String link = inLearnurl + "#/desafio?src=email&campaign=dailychallenge&login=" + org.entermediadb.asset.modules.AdminModule.createLoginLink(archive.getSearcherManager(), u.getId());
		Object[] avatar = avatarRef(archive, persona, inLearnurl);
		String[] m = emailContent(lang != null && lang.startsWith("en"), givenName(u.get("firstName")), tutor, (String) avatar[0], link, today, recent);
		return new Object[] {m[0], m[1], link, tutor, avatar[1]};
	}

	/** The org's tutor persona (catalog setting tutorpersona, else iris); null when missing. */
	static Data persona(MediaArchive archive)
	{
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		return archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
	}

	/** Periodic (catalog event weeklysummaryemail): see WeeklySummaryEmail.run. Returns the number sent. */
	public int weeklySummaryEmail(MediaArchive archive)
	{
		return new WeeklySummaryEmail(this, archive).run();
	}

	/**
	 * services/testu/learn/weeklysummaryemail.json -- the signed-in user's own weekly summary, for QA: GET = preview {ok, to, from,
	 * subject, html, kind, eligible, due}; as=learner|admin picks the version (default: the one the job would send; admin needs an
	 * admin scope). POST send=true also mails it to that user. user=<id> (org admins only): that person's summary, as the job would
	 * build it, but its link carries no sign-in token and it is never sent.
	 */
	public void weeklySummaryEmailPreview(WebPageRequest inReq) throws Exception
	{
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		if (learnUrl(archive) == null)
		{
			fail(inReq, 409, "learnurl_not_configured");
			return;
		}
		WeeklySummaryEmail w = new WeeklySummaryEmail(this, archive);
		Data u = freshUser(archive, user);
		java.util.Map<String, java.util.Collection> cache = new java.util.HashMap<>();
		java.util.Collection perms = rolePermissions(archive, u.getId(), cache);
		Set<String> scope = w.adminScope(u.getId(), perms);
		String other = param(inReq, "user");
		boolean mine = other == null || other.equals(u.getId());
		if (!mine)
		{
			if (scope == null || !scope.isEmpty())
			{
				fail(inReq, 403, "org_admins_only");
				return;
			}
			u = (Data) archive.getSearcher("user").searchById(other);
			if (u == null)
			{
				fail(inReq, 404, "unknown_user");
				return;
			}
			perms = rolePermissions(archive, u.getId(), cache);
			scope = w.adminScope(u.getId(), perms);
		}
		String as = param(inReq, "as");
		boolean admin = as == null ? scope != null : "admin".equals(as);
		if (admin && scope == null)
		{
			fail(inReq, 403, "not_an_admin");
			return;
		}
		Object[] mail = admin ? w.adminMail(u, scope) : w.learnerMail(u, mine);
		boolean send = mine && "true".equals(inReq.getRequestParameter("send")) && inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod());
		if (send)
		{
			sendMail(archive, u.get("email"), mail);
		}
		JSONObject out = new JSONObject();
		out.put("ok", Boolean.TRUE);
		out.put("to", u.get("email"));
		out.put("from", emailFrom(archive));
		out.put("kind", admin ? "admin" : "learner");
		out.put("subject", mail[0]);
		out.put("html", mail[1]);
		out.put("eligible", mayReceive(archive.getCatalogSettingValue(WeeklySummaryEmail.PERMISSION), archive.getCatalogSettingValue(WeeklySummaryEmail.PERMISSION + "_only"),
				u.get("email"), perms, WeeklySummaryEmail.PERMISSION));
		out.put("due", WeeklySummaryEmail.dueKey(u.getId(), zoneOf(u.get("timezone"), (java.time.ZoneId) new LearningEngine(archive).orgZone()[0]), java.time.Instant.now()));
		out.put("sent", send);
		reply(inReq, out);
	}

	/** Content-ID of the tutor's picture inside a TestU email; the HTML points at it with src="cid:...". */
	public static final String AVATAR_CID = "tutoravatar";

	/** Width and height of the embedded picture (shown at 44px, twice that for retina) and the cap on its bytes. */
	public static final int AVATAR_PX = 96, AVATAR_MAX_BYTES = 40 * 1024;

	// One small copy per avatar path, built on the first email and kept until the next restart.
	// ponytail: no invalidation; a changed persona picture needs a restart to show up in emails.
	private static final java.util.Map<String, byte[]> AVATAR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * {src for the HTML, PostMail.InlineImage or null} of inPersona's picture: embedded in the message (src="cid:") when a small
	 * copy could be made, otherwise its absolute URL (which a mail client behind an image proxy may fail to fetch), and {null, null}
	 * without a picture. Never throws: an email is worth more than its picture.
	 */
	protected Object[] avatarRef(MediaArchive archive, Data inPersona, String inLearnurl)
	{
		String path = inPersona == null ? null : inPersona.get("avatar");
		byte[] small = null;
		try
		{
			small = path == null || path.trim().isEmpty() ? null : AVATAR_CACHE.computeIfAbsent(path.trim(), k -> smallAvatar(archive, k));
		}
		catch (Exception e)
		{
			org.apache.commons.logging.LogFactory.getLog(TestULearningModule.class).error("testu email avatar " + path, e);
		}
		return avatarRef(small, absoluteUrl(inLearnurl, path));
	}

	/** {src, inline image or null} from the small copy (null = fall back to inUrl, which may itself be null). Pure. */
	public static Object[] avatarRef(byte[] inSmall, String inUrl)
	{
		if (inSmall == null || inSmall.length == 0)
		{
			return new Object[] {inUrl, null};
		}
		return new Object[] {"cid:" + AVATAR_CID, new org.entermediadb.email.PostMail.InlineImage(AVATAR_CID, "image/png", inSmall)};
	}

	/** The site file at inPath (e.g. /site/mediadb/testu/iris.png) as an AVATAR_PX PNG, or null when it cannot be read or scaled. */
	protected static byte[] smallAvatar(MediaArchive archive, String inPath)
	{
		try (java.io.InputStream in = archive.getPageManager().getPage(inPath).getInputStream())
		{
			return scaleAvatar(in, AVATAR_PX, AVATAR_MAX_BYTES);
		}
		catch (Exception e)
		{
			org.apache.commons.logging.LogFactory.getLog(TestULearningModule.class).error("testu email avatar " + inPath, e);
			return null;
		}
	}

	/**
	 * inImage scaled to inSize x inSize as a PNG, keeping its transparency; null when it is not an image or still bigger than
	 * inMaxBytes (then the email links the picture instead of carrying it). Pure.
	 */
	public static byte[] scaleAvatar(java.io.InputStream inImage, int inSize, int inMaxBytes) throws java.io.IOException
	{
		java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(inImage);
		if (source == null)
		{
			return null;
		}
		// Square crop first, so a portrait is not squashed into the circle.
		int side = Math.min(source.getWidth(), source.getHeight());
		java.awt.image.BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
		java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(inSize, inSize, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = small.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(square.getScaledInstance(inSize, inSize, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
		g.dispose();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(small, "png", out);
		return out.size() > inMaxBytes ? null : out.toByteArray();
	}

	/** inPath made absolute against inBase's origin ("/site/x.png" -> "https://host/site/x.png"); http(s) kept; else null. Pure. */
	public static String absoluteUrl(String inBase, String inPath)
	{
		if (inPath == null || inPath.trim().isEmpty())
		{
			return null;
		}
		String p = inPath.trim();
		if (p.startsWith("http://") || p.startsWith("https://"))
		{
			return p;
		}
		try
		{
			java.net.URI b = java.net.URI.create(inBase);
			return p.startsWith("/") && b.getScheme() != null && b.getRawAuthority() != null ? b.getScheme() + "://" + b.getRawAuthority() + p : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * {streak, correct, total} of the learner's finished Daily Challenges before inToday (see challengeStats), from the stored
	 * sets and Daily Challenge answers. A set is finished when each of its questions has a Daily Challenge answer since it was
	 * built (LearningEngine.challengeItemDone); a question's first such answer is the one scored.
	 */
	protected int[] recentChallenges(LearningEngine engine, MediaArchive archive, String inUserid, java.time.LocalDate inToday,
			java.util.List<LearningEngine.Attempt> inAttempts)
	{
		java.util.Map<java.time.LocalDate, int[]> done = new java.util.HashMap<>();
		HitTracker sets = archive.query("dailychallengeset").exact("user", inUserid).search();
		java.util.List<LearningEngine.Attempt> attempts = inAttempts; // null = loaded on the first finished-looking set
		for (Object o : sets)
		{
			Data set = (Data) o;
			java.time.LocalDate day;
			try
			{
				day = java.time.LocalDate.parse(set.get("localdate"));
			}
			catch (Exception e)
			{
				continue;
			}
			if (!day.isBefore(inToday) || day.isBefore(inToday.minusDays(60)))
			{
				continue;
			}
			Object parsed = org.json.simple.JSONValue.parse(String.valueOf(set.get("questionlist")));
			if (!(parsed instanceof java.util.List) || ((java.util.List) parsed).isEmpty())
			{
				continue;
			}
			if (attempts == null)
			{
				attempts = engine.loadLearner(inUserid, null).attempts; // oldest first
			}
			Date created = org.openedit.util.DateStorageUtil.getStorageUtil().parseFromObject(set.getValue("datecreated"));
			int correct = 0, total = 0;
			for (Object it : (java.util.List) parsed)
			{
				String q = it instanceof java.util.Map ? String.valueOf(((java.util.Map) it).get("questionid")) : null;
				LearningEngine.Attempt first = null;
				for (LearningEngine.Attempt a : attempts)
				{
					if (a.questionid != null && a.questionid.equals(q) && "dailychallenge".equals(a.mode) && a.at != null && (created == null || !a.at.before(created)))
					{
						first = a;
						break;
					}
				}
				if (first == null)
				{
					total = -1;
					break;
				}
				total++;
				correct += first.correct ? 1 : 0;
			}
			if (total > 0)
			{
				done.put(day, new int[] {correct, total});
			}
		}
		return challengeStats(done, inToday);
	}

	/** The last Monday-Friday before inDay. Pure. */
	public static java.time.LocalDate previousWorkday(java.time.LocalDate inDay)
	{
		java.time.LocalDate d = inDay.minusDays(1);
		while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY)
		{
			d = d.minusDays(1);
		}
		return d;
	}

	/**
	 * {streak, correct, total} from finished challenges (day -> {correct, total}). streak = finished workdays in a row ending on the
	 * workday before inToday (weekends neither count nor break it); correct/total = that workday's score. {0, 0, 0} when it was not
	 * finished. Pure.
	 */
	public static int[] challengeStats(java.util.Map<java.time.LocalDate, int[]> inDone, java.time.LocalDate inToday)
	{
		java.time.LocalDate last = previousWorkday(inToday);
		int streak = 0;
		for (java.time.LocalDate d = last; inDone.containsKey(d); d = previousWorkday(d))
		{
			streak++;
		}
		int[] score = inDone.get(last);
		return score == null ? new int[] {0, 0, 0} : new int[] {streak, score[0], score[1]};
	}

	// Per day, Monday..Friday then the weekend (Saturday/Sunday: the app's card only, no email goes out): preheader, title, mood
	// sentence, "it's ready" sentence (email only; "" = none), closing. The email body = mood + ready; the Hoy card = title + mood,
	// then the app's own question counts. Spanish copy: docs/copy/desafio-diario-minsur.es.md.
	private static final String[][] DAY_ES = {
		{"Arranca la semana con tu Desafío: son unos minutos.", "Empieza la semana con fuerza", "Espero que hayas descansado el fin de semana.",
			"Tu Desafío de hoy ya está listo: son pocas preguntas y te toma unos minutos.", "Vamos por una gran semana."},
		{"Tu Desafío de hoy ya está listo.", "Mantén el ritmo", "La semana ya está en marcha y cada día suma.",
			"Tu Desafío de hoy ya está listo: pocas preguntas para seguir reforzando lo que sabes.", "Paso a paso se llega lejos."},
		{"Mitad de semana: unos minutos para tu Desafío.", "Mitad de semana, buen momento para avanzar",
			"Ya vamos por la mitad de la semana. Unos minutos hoy te ayudan a fijar lo que aprendiste.", "Tu Desafío te espera.", "Hoy también cuenta. ¡Tú puedes!"},
		{"Ya casi es viernes. Tu Desafío está listo.", "Ya casi llegamos", "Un día más y cierras la semana.",
			"Tu Desafío de hoy ya está listo para que sigas avanzando.", "Un empujón más y llegamos al viernes."},
		{"Último Desafío de la semana.", "Último esfuerzo de la semana",
			"Completa tu Desafío de hoy y cierra la semana con todo. Después, a disfrutar del fin de semana.", "", "¡Que tengas un gran fin de semana!"},
		{"Tu Desafío de fin de semana está listo.", "Un Desafío de fin de semana", "Aunque sea fin de semana, unos minutos te ayudan a no perder el ritmo.",
			"Tu Desafío de hoy ya está listo.", "Disfruta tu fin de semana."}};
	private static final String[][] DAY_EN = {
		{"Start the week with your challenge: just a few minutes.", "Start the week strong", "I hope you had a restful weekend.",
			"Today’s challenge is ready: just a few questions and a few minutes.", "Here’s to a great week."},
		{"Today’s challenge is ready.", "Keep the momentum", "The week is under way and every day adds up.",
			"Today’s challenge is ready: a few questions to keep strengthening what you know.", "Step by step, you go far."},
		{"Midweek: a few minutes for your challenge.", "Midweek, a good moment to push ahead",
			"We’re halfway through the week. A few minutes today help what you learned stick.", "Your challenge is waiting.", "Today counts too. You’ve got this!"},
		{"Almost Friday. Your challenge is ready.", "Almost there", "One more day and the week is done.",
			"Today’s challenge is ready so you can keep moving forward.", "One more push and it’s Friday."},
		{"Last challenge of the week.", "One last push this week", "Finish today’s challenge and close the week strong. Then enjoy your weekend.", "",
			"Have a great weekend!"},
		{"Your weekend challenge is ready.", "A weekend challenge", "Even on the weekend, a few minutes help you keep your rhythm.",
			"Today’s challenge is ready.", "Enjoy your weekend."}};

	/** {preheader, title, mood, ready, closing} for inToday's weekday (weekend = one variant). Pure. */
	public static String[] dayCopy(boolean inEnglish, java.time.LocalDate inToday)
	{
		int d = inToday.getDayOfWeek().getValue(); // 1 = Monday
		return (inEnglish ? DAY_EN : DAY_ES)[d <= 5 ? d - 1 : 5];
	}

	/**
	 * The streak / last-score sentence for inRecent = {streak, correct, total} (challengeStats) on inToday, or null when there is
	 * no streak (then nothing is said, no number is made up). Pure.
	 */
	public static String recentLine(boolean inEnglish, java.time.LocalDate inToday, int[] inRecent)
	{
		if (inRecent == null || inRecent[0] <= 0)
		{
			return null;
		}
		java.util.Locale loc = inEnglish ? java.util.Locale.ENGLISH : new java.util.Locale("es");
		java.time.LocalDate last = previousWorkday(inToday);
		boolean yesterday = last.equals(inToday.minusDays(1));
		String lastday = last.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, loc);
		String when = inEnglish ? (yesterday ? "yesterday" : "on " + lastday) : (yesterday ? "ayer" : "el " + lastday);
		String score = inRecent[1] > 0 ? (inEnglish ? "you got " + inRecent[1] + " of " + inRecent[2] + " right" : "acertaste " + inRecent[1] + " de " + inRecent[2]) : null;
		if (inRecent[0] >= 2)
		{
			return inEnglish ? "You’re on a " + inRecent[0] + "-day streak" + (score == null ? "" : ", and " + when + " " + score) + ". Keep it up!"
					: "Llevas " + inRecent[0] + " días seguidos completando tu Desafío" + (score == null ? "" : " y " + when + " " + score) + ". ¡Sigue así!";
		}
		String w = when.substring(0, 1).toUpperCase() + when.substring(1);
		return inEnglish ? w + " " + (score == null ? "you completed your challenge" : score + " in your challenge") + ". Let’s go for another!"
				: w + " " + (score == null ? "completaste tu Desafío" : score + " en tu Desafío") + ". ¡Vamos por otro!";
	}

	/**
	 * next.json?mode=dailychallenge "daycopy": {language, title, body, recent} for the Hoy card, the same copy as that day's email
	 * (learner-local weekday, the learner's language as in the email). recent = null without a streak. inAttempts = the learner's
	 * already-loaded answers.
	 */
	protected JSONObject dayCopyJson(MediaArchive archive, Data u, java.util.List<LearningEngine.Attempt> inAttempts)
	{
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		String lang = u == null ? null : u.get("language");
		if (lang == null || lang.isEmpty())
		{
			lang = persona == null ? null : persona.get("tutorlanguage");
		}
		boolean en = lang != null && lang.startsWith("en");
		LearningEngine engine = new LearningEngine(archive);
		java.time.ZoneId orgzone = (java.time.ZoneId) engine.orgZone()[0];
		java.time.LocalDate today = java.time.Instant.now().atZone(zoneOf(u == null ? null : u.get("timezone"), orgzone)).toLocalDate();
		String[] day = dayCopy(en, today);
		JSONObject o = new JSONObject();
		o.put("language", en ? "en" : "es");
		o.put("title", day[1]);
		o.put("body", day[2]);
		o.put("recent", u == null ? null : recentLine(en, today, recentChallenges(engine, archive, u.getId(), LearningEngine.challengeDate(new Date(), orgzone), inAttempts)));
		return o;
	}

	/**
	 * {subject, html} of the Daily Challenge email for inToday (the learner's local date). HTML only, TestU Learn's dark theme
	 * (app-genailabs lib/testu/testu_theme.dart tokens) as inline CSS in tables, for Gmail, Outlook and Apple Mail. inRecent =
	 * {streak, correct, total} (challengeStats) or null: the streak/score line only when there is one. inAvatar = absolute URL of the
	 * tutor's picture, or null (then just the name). Copy: dayCopy / recentLine, docs/copy/desafio-diario-minsur.es.md. Pure.
	 */
	public static String[] emailContent(boolean inEnglish, String inName, String inTutor, String inAvatar, String inLink, java.time.LocalDate inToday,
			int[] inRecent)
	{
		boolean named = inName != null && !inName.isEmpty();
		String dayname = inToday.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, inEnglish ? java.util.Locale.ENGLISH : new java.util.Locale("es"));
		String[] c = dayCopy(inEnglish, inToday);
		String[] day = {c[0], c[1], c[3].isEmpty() ? c[2] : c[2] + " " + c[3], c[4]}; // preheader, heading, body, closing
		String subject = inEnglish ? (named ? "Hi " + inName + ", your " : "Your ") + dayname + " challenge"
				: (named ? "Hola " + inName + ", tu" : "Tu") + " desafío del " + dayname;
		String hello = inEnglish ? (named ? "Hi " + inName + "," : "Hi,") : (named ? "Hola " + inName + ":" : "Hola:");
		String recent = recentLine(inEnglish, inToday, inRecent);
		String button = inEnglish ? "Start my challenge" : "Empezar mi desafío";
		String foot = inEnglish ? "You’re receiving this email because you have a TestU account. If the button doesn’t work, open the app and sign in with your email."
				: "Recibes este correo porque tienes una cuenta en TestU. Si el botón no funciona, abre la app e ingresa con tu correo.";
		String p = "<p style=\"margin:0 0 16px;" + SANS + "font-size:16px;line-height:1.6;color:#D6D4D0\">";
		String inner = p + esc(hello) + "</p>" + p + esc(day[2]) + "</p>"
				+ (recent == null ? ""
						: "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:4px 0 20px\"><tr>"
								+ "<td bgcolor=\"#17171B\" style=\"background:#17171B;border:1px solid #222227;border-left:3px solid #E8703A;border-radius:8px;padding:12px 14px;"
								+ SANS + "font-size:15px;line-height:1.5;color:#ECEBE7\">" + esc(recent) + "</td></tr></table>")
				+ "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:12px 0 28px\"><tr>"
				+ "<td align=\"center\" bgcolor=\"#F4F2EE\" style=\"background:#F4F2EE;border-radius:8px\">"
				+ "<a href=\"" + esc(inLink) + "\" style=\"display:block;padding:15px 20px;" + SANS + "font-size:15px;font-weight:700;letter-spacing:0.05em;"
				+ "color:#0A0A0B;text-decoration:none;border-radius:8px\">" + esc(button) + "</a></td></tr></table>";
		String html = emailShell(inEnglish, subject, day[0], inTutor, inAvatar, inEnglish ? "DAILY CHALLENGE" : "DESAFÍO DIARIO", day[1], inner, day[3], foot);
		return new String[] {subject, html};
	}

	// Tokens (app-genailabs lib/testu/testu_theme.dart): bg #0A0A0B, card #121215, line #222227, card2 #17171B, ink #ECEBE7,
	// inkSoft #D6D4D0, mut #8B8F98, faint #6B6F78, orange #E8703A (brand/progress), CTA #F4F2EE on #0A0A0B. Sora = display,
	// Geist = text, GeistMono = eyebrow labels.
	static final String SANS = "font-family:Geist,-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;";
	static final String MONO = "font-family:GeistMono,'Geist Mono',ui-monospace,Menlo,Consolas,monospace;";

	/**
	 * The TestU email shell every TestU email shares (Daily Challenge, login code): dark theme, table layout with inline CSS for
	 * Gmail, Outlook and Apple Mail, 480px card that narrows on a phone. Header = tutor avatar (inAvatar, absolute URL; null = none)
	 * + name + inEyebrow; then inHeading, inInnerHtml (already HTML), inClosing signed by the tutor, and inFoot under the card. Pure.
	 */
	public static String emailShell(boolean inEnglish, String inTitle, String inPreheader, String inTutor, String inAvatar, String inEyebrow, String inHeading,
			String inInnerHtml, String inClosing, String inFoot)
	{
		String tutorCell = "<td style=\"vertical-align:middle\"><div style=\"" + SANS + "font-size:15px;font-weight:600;color:#ECEBE7\">" + esc(inTutor) + "</div>"
				+ "<div style=\"" + MONO + "font-size:11px;font-weight:500;letter-spacing:0.12em;color:#8B8F98;padding-top:3px\">" + esc(inEyebrow) + "</div></td>";
		String avatarCell = inAvatar == null ? ""
				: "<td width=\"56\" style=\"width:56px;vertical-align:middle\"><img src=\"" + esc(inAvatar) + "\" width=\"44\" height=\"44\" alt=\"" + esc(inTutor)
						+ "\" style=\"display:block;width:44px;height:44px;border-radius:22px;border:1px solid #2C2C33;object-fit:cover\"></td>";
		return "<!DOCTYPE html><html lang=\"" + (inEnglish ? "en" : "es") + "\"><head><meta charset=\"utf-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"dark\">"
				+ "<meta name=\"supported-color-schemes\" content=\"dark\"><title>" + esc(inTitle) + "</title>"
				+ "<link href=\"https://fonts.googleapis.com/css2?family=Geist:wght@400;600;700&family=Geist+Mono:wght@500;600&family=Sora:wght@700&display=swap\" rel=\"stylesheet\">"
				+ "<style>:root{color-scheme:dark}@media (max-width:520px){.tu-card{padding:28px 20px !important}.tu-h1{font-size:22px !important}}</style></head>"
				+ "<body style=\"margin:0;padding:0;background:#0A0A0B\" bgcolor=\"#0A0A0B\">"
				+ "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:#0A0A0B\">" + esc(inPreheader) + "</div>"
				+ "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"#0A0A0B\" style=\"background:#0A0A0B\"><tr><td align=\"center\" style=\"padding:32px 12px\">"
				+ "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%;max-width:480px\"><tr>"
				+ "<td class=\"tu-card\" bgcolor=\"#121215\" style=\"background:#121215;border:1px solid #222227;border-radius:14px;padding:32px 28px\">"
				+ "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 28px\"><tr>" + avatarCell + tutorCell + "</tr></table>"
				+ "<h1 class=\"tu-h1\" style=\"margin:0 0 18px;font-family:Sora,-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;font-size:24px;font-weight:700;"
				+ "line-height:1.25;letter-spacing:-0.01em;color:#ECEBE7\">" + esc(inHeading) + "</h1>"
				+ inInnerHtml
				+ "<p style=\"margin:0;" + SANS + "font-size:16px;line-height:1.6;color:#D6D4D0\">" + esc(inClosing) + "</p>"
				+ "<p style=\"margin:4px 0 0;" + SANS + "font-size:15px;font-weight:600;color:#ECEBE7\">" + esc(inTutor) + "</p>"
				+ "</td></tr><tr><td style=\"padding:20px 8px 0;" + SANS + "font-size:12px;line-height:1.55;color:#6B6F78\">" + esc(inFoot) + "</td></tr></table>"
				+ "</td></tr></table></body></html>";
	}

	/** How long an eMe login code works: TempSecurityKeyAuthenticator accepts codes from the last hour; one use (it deletes them). */
	public static final int LOGIN_CODE_HOURS = 1;

	/**
	 * {subject, html} of the TestU login-code email (copy: docs/copy/otp-email.es.md): the code big, in one piece so a copy takes
	 * exactly it, its validity, and what to do if it was not asked for. Same shell as the Daily Challenge email. Pure.
	 */
	public static String[] loginCodeEmailContent(boolean inEnglish, String inName, String inTutor, String inAvatar, String inCode, String inEmail)
	{
		boolean named = inName != null && !inName.isEmpty();
		String subject = inEnglish ? "Your TestU sign-in code" : "Tu código para entrar a TestU";
		String hello = inEnglish ? (named ? "Hi " + inName + "," : "Hi,") : (named ? "Hola " + inName + ":" : "Hola:");
		String use = inEnglish ? "Use this code to sign in to TestU:" : "Usa este código para entrar a TestU:";
		String valid = inEnglish ? "It works for " + LOGIN_CODE_HOURS + " hour and only once." : "Vale por " + LOGIN_CODE_HOURS + " hora y solo se puede usar una vez.";
		String notyou = inEnglish ? "If you didn’t ask for it, you can ignore this email: nobody can sign in without the code."
				: "Si no lo pediste, puedes ignorar este correo: nadie puede entrar sin el código.";
		String p = "<p style=\"margin:0 0 16px;" + SANS + "font-size:16px;line-height:1.6;color:#D6D4D0\">";
		String inner = p + esc(hello) + "</p>" + p + esc(use) + "</p>"
				+ "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:4px 0 16px\"><tr>"
				+ "<td align=\"center\" bgcolor=\"#17171B\" style=\"background:#17171B;border:1px solid #2C2C33;border-radius:10px;padding:18px 12px\">"
				+ "<span style=\"" + MONO + "font-size:34px;font-weight:600;letter-spacing:0.28em;padding-left:0.28em;color:#ECEBE7;-webkit-user-select:all;user-select:all\">"
				+ esc(inCode == null ? "" : inCode) + "</span></td></tr></table>"
				+ p + esc(valid) + "</p>"
				+ "<p style=\"margin:0 0 24px;" + SANS + "font-size:14px;line-height:1.55;color:#8B8F98\">" + esc(notyou) + "</p>";
		String foot = inEnglish ? "You’re receiving this email because a TestU sign-in code was requested for " + inEmail + "."
				: "Recibes este correo porque se pidió un código para entrar a TestU con " + inEmail + ".";
		String pre = inEnglish ? "Your code: " + inCode + " · valid for " + LOGIN_CODE_HOURS + " hour." : "Tu código: " + inCode + " · vale por " + LOGIN_CODE_HOURS + " hora.";
		String html = emailShell(inEnglish, subject, pre, inTutor, inAvatar, inEnglish ? "SIGN-IN CODE" : "CÓDIGO DE ACCESO",
				inEnglish ? "Your code to sign in" : "Tu código para entrar", inner, inEnglish ? "See you in the app." : "Nos vemos en la app.", foot);
		return new String[] {subject, html};
	}

	/**
	 * Page action of the TestU site's own login-code email template (webapp/site/mediadb/authentication/sendusercodeemail.xconf,
	 * which overrides the stock community one for this site only): renders loginCodeEmailContent into page value testuloginhtml and
	 * sets the email's subject, sender (testu_email_from, else system_from_email) and sender name (the tutor). The stock flow
	 * (Admin.emailUserLoginCode -> PasswordHelper -> TemplateWebEmail) reads subject and sender after the template has rendered,
	 * and sends the template as the HTML part only. Language: the user's, else the tutor persona's.
	 */
	public void loginCodeEmail(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Object code = inReq.getPageValue("templogincode");
		String email = (String) inReq.getPageValue("mail");
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		String tutor = persona == null || persona.getName() == null ? "TestU" : persona.getName();
		User u = email == null ? null : getUserManager(inReq).getUserByEmail(email);
		String lang = u == null ? null : u.get("language");
		if (lang == null || lang.isEmpty())
		{
			lang = persona == null ? null : persona.get("tutorlanguage");
		}
		Object[] avatar = avatarRef(archive, persona, learnUrl(archive));
		String[] m = loginCodeEmailContent(lang != null && lang.startsWith("en"), givenName(u == null ? null : u.getFirstName()), tutor, (String) avatar[0],
				code == null ? "" : String.valueOf(code), email == null ? "" : email);
		Object settings = inReq.getPageValue("emailsettings"); // SendMailModule.EMAIL_SETTINGS, put by PasswordHelper before the render
		if (settings instanceof org.entermediadb.email.WebEmail)
		{
			org.entermediadb.email.WebEmail w = (org.entermediadb.email.WebEmail) settings;
			w.setSubject(m[0]);
			w.setFrom(emailFrom(archive));
			w.setFromName(tutor);
			if (avatar[1] != null && w instanceof org.entermediadb.email.TemplateWebEmail)
			{
				// PostMail puts an InlineImage of the attachment list into the message itself (multipart/related).
				((org.entermediadb.email.TemplateWebEmail) w).getFileAttachments().add(avatar[1]);
			}
		}
		inReq.putPageValue("testuloginhtml", m[1]);
	}

	/**
	 * /.well-known/apple-app-site-association (webapp/.well-known/*.xconf): lets the iOS app (Universal Links) open the email's
	 * https link, scoped to the learn app's path. App ids = catalog setting testu_ios_appids (comma-separated TEAMID.bundleid),
	 * default the app-genailabs Runner's VJ8RCF92K4.world.eme.genailabs. application/json, no redirect.
	 */
	public void appleAppSiteAssociation(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		String ids = archive.getCatalogSettingValue("testu_ios_appids");
		writeWellKnown(inReq, appleAppSiteAssociation(ids == null || ids.trim().isEmpty() ? "VJ8RCF92K4.world.eme.genailabs" : ids, learnPath(archive)).toJSONString());
	}

	/**
	 * /.well-known/assetlinks.json: Android App Links verification for the same link. Package = catalog setting
	 * testu_android_package (default world.eme.genailabs, the app-genailabs applicationId); signing certificates = catalog setting
	 * testu_android_sha256 (comma-separated SHA-256 fingerprints, AA:BB:...). No fingerprint = [] (valid, verifies nothing).
	 */
	public void assetLinks(WebPageRequest inReq) throws Exception
	{
		MediaArchive archive = getMediaArchive(inReq);
		String pkg = archive.getCatalogSettingValue("testu_android_package");
		writeWellKnown(inReq, assetLinks(pkg == null || pkg.trim().isEmpty() ? "world.eme.genailabs" : pkg.trim(), archive.getCatalogSettingValue("testu_android_sha256")).toJSONString());
	}

	/** Path of the learn app ("/site/learn/"), from testu_learnurl / siteroot; that default when neither is set. */
	static String learnPath(MediaArchive archive)
	{
		String url = learnUrl(archive);
		try
		{
			String p = url == null ? null : java.net.URI.create(url).getPath();
			return p == null || p.isEmpty() ? "/site/learn/" : p.endsWith("/") ? p : p + "/";
		}
		catch (Exception e)
		{
			return "/site/learn/";
		}
	}

	/** {"applinks": {"details": [{appIDs, components [{"/": <path>*}], paths}]}}: every link under inPath opens the app. Pure. */
	public static JSONObject appleAppSiteAssociation(String inAppIds, String inPath)
	{
		JSONArray ids = new JSONArray();
		for (String id : inAppIds.split(","))
		{
			if (!id.trim().isEmpty())
			{
				ids.add(id.trim());
			}
		}
		JSONObject component = new JSONObject();
		component.put("/", inPath + "*");
		component.put("comment", "TestU Learn: the Daily Challenge email link");
		JSONArray components = new JSONArray();
		components.add(component);
		JSONArray paths = new JSONArray(); // iOS 12 and older read "paths"
		paths.add(inPath + "*");
		JSONObject detail = new JSONObject();
		detail.put("appIDs", ids);
		detail.put("components", components);
		detail.put("paths", paths);
		if (ids.size() == 1)
		{
			detail.put("appID", ids.get(0)); // with "paths", the pre-iOS 13 shape
		}
		JSONArray details = new JSONArray();
		details.add(detail);
		JSONObject applinks = new JSONObject();
		applinks.put("apps", new JSONArray());
		applinks.put("details", details);
		JSONObject o = new JSONObject();
		o.put("applinks", applinks);
		return o;
	}

	/** [{relation [handle_all_urls], target {android_app, inPackage, fingerprints}}]; [] without a fingerprint. Pure. */
	public static JSONArray assetLinks(String inPackage, String inSha256s)
	{
		JSONArray prints = new JSONArray();
		for (String f : (inSha256s == null ? "" : inSha256s).split(","))
		{
			if (!f.trim().isEmpty())
			{
				prints.add(f.trim().toUpperCase());
			}
		}
		JSONArray out = new JSONArray();
		if (prints.isEmpty())
		{
			return out;
		}
		JSONArray relation = new JSONArray();
		relation.add("delegate_permission/common.handle_all_urls");
		JSONObject target = new JSONObject();
		target.put("namespace", "android_app");
		target.put("package_name", inPackage);
		target.put("sha256_cert_fingerprints", prints);
		JSONObject statement = new JSONObject();
		statement.put("relation", relation);
		statement.put("target", target);
		out.add(statement);
		return out;
	}

	/** Content type of both .well-known files. */
	public static final String WELL_KNOWN_TYPE = "application/json";

	private void writeWellKnown(WebPageRequest inReq, String inJson) throws Exception
	{
		if (inReq.getResponse() != null)
		{
			inReq.getResponse().setContentType(WELL_KNOWN_TYPE);
			inReq.getResponse().setCharacterEncoding("UTF-8");
			inReq.getResponse().setHeader("Cache-Control", "max-age=3600");
		}
		java.io.Writer w = inReq.getWriter();
		w.write(inJson);
		w.flush();
		inReq.setHasRedirected(true); // the JSON is the whole response: no generator runs after it
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

	static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * services/testu/learn/dailychallengeemail.json -- the signed-in learner's own Daily Challenge email, for QA: GET = preview
	 * {ok, to, from, subject, html, link, due, eligible} with a freshly minted link (replaces any link emailed earlier); eligible = the
	 * job would mail this learner (mayReceive). POST send=true also mails it to that learner, whatever eligible says. Never another
	 * user's: a link is a sign-in.
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
		Object[] mail = send ? sendDailyChallengeEmail(archive, u, learnurl) : dailyChallengeMail(archive, u, learnurl);
		JSONObject out = new JSONObject();
		out.put("ok", Boolean.TRUE);
		out.put("to", u.get("email"));
		out.put("from", emailFrom(archive));
		out.put("subject", mail[0]);
		out.put("html", mail[1]);
		out.put("link", mail[2]);
		out.put("embeddedavatarbytes", mail[4] == null ? null : ((org.entermediadb.email.PostMail.InlineImage) mail[4]).data.length);
		out.put("eligible", mayReceive(archive.getCatalogSettingValue("testu_dailychallengeemail"), archive.getCatalogSettingValue("testu_dailychallengeemail_only"),
				u.get("email"), rolePermissions(archive, u.getId(), new java.util.HashMap<>())));
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
