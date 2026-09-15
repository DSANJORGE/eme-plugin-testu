package tech.genailabs.tutor;

import java.util.Date;
import java.util.Set;
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
		String topicid = param(inReq, "topicid");
		if (topicid != null && !content.topics.containsKey(topicid))
		{
			fail(inReq, 404, "unknown_topic");
			return;
		}
		LearningEngine.Learner learner = engine.loadLearner(user.getId(), LearningEngine.jobrolesOf(user));
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
		LearningEngine.Learner learner = engine.loadLearner(user.getId(), LearningEngine.jobrolesOf(user));
		if ("dailychallenge".equals(mode))
		{
			JSONObject dc = engine.dailyChallenge(content, learner);
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
		String scopetype = section == null ? "topic" : "subtopic";
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
				reply(inReq, engine.startSession(user.getId(), scopetype, scopeid, topic, learn));
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
			reply(inReq, engine.startSession(user.getId(), scopetype, scopeid, topic, improve));
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
		LearningEngine.Attempt attempt = LearningEngine.attemptOf(answer);
		attempt.at = now;
		engine.recordUnlocks(r.content.topics.get(r.question.topicid), LearningEngine.withAttempt(r.learner, attempt));
		notifyUser(user.getId(), "progress", null);
		reply(inReq, answerReply(answer, false, r.question));
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
		User user = requireUser(inReq);
		if (user == null)
		{
			return;
		}
		boolean canmanage = canManageProgression(inReq);
		org.openedit.profile.UserProfile profile = inReq.getUserProfile();
		if (!canmanage && (profile == null || !profile.hasPermission("training_view")))
		{
			fail(inReq, 403, "forbidden");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		String topicid = param(inReq, "topicid");
		boolean post = inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod());
		if (topicid == null)
		{
			if (post)
			{
				fail(inReq, 400, "missing_topicid");
				return;
			}
			JSONArray topics = new JSONArray();
			for (LearningEngine.Topic t : content.topics.values())
			{
				topics.add(policyJson(t));
			}
			JSONObject resp = new JSONObject();
			resp.put("ok", Boolean.TRUE);
			resp.put("canmanage", canmanage);
			resp.put("topics", topics);
			reply(inReq, resp);
			return;
		}
		LearningEngine.Topic topic = content.topics.get(topicid);
		if (topic == null)
		{
			fail(inReq, 404, "unknown_topic");
			return;
		}
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
				// loadContent's search can lag a just-saved version: probe the next ids with realtime gets.
				while (searcher.searchById(topic.id + "_v" + (topic.policyversion + 1)) != null)
				{
					topic.policyversion++;
				}
				Data latest = topic.policyversion == 0 ? null : (Data) searcher.searchById(topic.id + "_v" + topic.policyversion);
				String[] effective = LearningEngine.effectivePolicy(latest == null ? null : latest.get("unlockpolicy"), latest == null ? null : latest.get("requiredlevel"));
				topic.policy = effective[0];
				topic.requiredlevel = effective[1];
				topic.policyreason = effective[2];
				if (Integer.parseInt(expected) != topic.policyversion)
				{
					if (inReq.getResponse() != null)
					{
						inReq.getResponse().setStatus(409);
					}
					JSONObject err = new JSONObject();
					err.put("ok", Boolean.FALSE);
					err.put("error", "version_conflict");
					err.put("currentversion", topic.policyversion);
					err.put("topic", policyJson(topic));
					reply(inReq, err);
					inReq.setCancelActions(true);
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
		java.util.List<Data> rows = new java.util.ArrayList<>();
		for (Object o : searcher.query().exact("entitytopic", topic.id).search())
		{
			rows.add((Data) o);
		}
		rows.sort((a, b) -> LearningEngine.intOr(b.get("policyversion"), 0) - LearningEngine.intOr(a.get("policyversion"), 0));
		JSONArray versions = new JSONArray();
		for (Data d : rows)
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

	/** Shared by answer and exposure: mode + scope + claimed hierarchy checked against the learner's visible content. */
	private LearningEngine.Resolved resolve(WebPageRequest inReq, LearningEngine inEngine, User inUser, boolean inAnswer)
	{
		LearningEngine.Content content = inEngine.loadContent(visibleTopics(inReq));
		LearningEngine.Learner learner = inEngine.loadLearner(inUser.getId(), LearningEngine.jobrolesOf(inUser));
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
			if ("true".equals(inReq.getRequestParameter("started")) && d.getValue("started") == null)
				d.setValue("started", now);
			if ("true".equals(inReq.getRequestParameter("finished")) && d.getValue("finished") == null)
				d.setValue("finished", now);
			if ("true".equals(inReq.getRequestParameter("skipped")))
				d.setValue("skipped", Boolean.TRUE);
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

	private static JSONObject onboardingJson(Data d)
	{
		JSONObject o = new JSONObject();
		o.put("user", d.get("user"));
		o.put("started", TestUTermsModule.iso(d.getValue("started")));
		o.put("finished", TestUTermsModule.iso(d.getValue("finished")));
		o.put("skipped", Boolean.valueOf("true".equals(String.valueOf(d.getValue("skipped")))));
		o.put("goals", d.get("goals"));
		o.put("whenlearn", d.get("whenlearn"));
		o.put("sessiontotal", TestUTermsModule.number(d.get("sessiontotal")));
		o.put("sessioncorrect", TestUTermsModule.number(d.get("sessioncorrect")));
		return o;
	}

}
