package tech.genailabs.tutor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.util.DateStorageUtil;

/**
 * TestU learning engine v1 (spec: plugins/testu/docs/superpowers/specs/2026-09-14-learning-engine.md).
 * Server-owned learning sequence, answered state, weighted mastery, bands, expert evidence, requirements and selection.
 * Shared by TestULearningModule (endpoints) and events/testu/computemastery.groovy (stored tutormastery).
 * ponytail: content and a learner's attempts are reloaded per call, in memory; fine for ~300 questions and a pilot cohort.
 */
public class LearningEngine
{
	public static final List<String> LEVELS = List.of("beginner", "competent", "expert");
	/** Modes the server can verify and accept on answer.json / exposure.json. */
	public static final Set<String> MODES = Set.of("learn", "dailychallenge", "improve");
	/** Stored mode of an attempt the server could not verify (missing mode, pre-v1 rows, legacy app builds). Never advances the sequence. */
	public static final String LEGACY = "legacy";
	public static final Set<String> CONFIDENCES = Set.of("noidea", "notsure", "mostlysure", "confident");
	public static final int MAX_HINTLEVEL = 3;
	public static final int[] DEFAULT_THRESHOLDS = {60, 85};

	protected final MediaArchive fieldArchive;

	public LearningEngine(MediaArchive inArchive)
	{
		fieldArchive = inArchive;
	}

	// ---------------------------------------------------------------- content

	public static class Question
	{
		public String id, componentid, sectionid, tutorialid, topicid, difficulty;
		/** Why tutorial.json cannot render this question (contentProblem); null = renderable. */
		public String contentproblem;
		public int weight, position, topicindex;
	}

	public static class Section
	{
		public String id, title, tutorialid, topicid;
		public List<Question> questions = new ArrayList<>();
	}

	public static class Topic
	{
		public String id, title, thresholdsreason; // reason = why the topic override was ignored; null when valid or absent
		public int index, competentmin, expertmin;
		/** Effective subtopic unlock policy (effectivePolicy of the latest subtopicpolicy row); policyreason says why it fell back. */
		public String policy = "open", requiredlevel, policyreason = "policy_not_configured";
		public int policyversion; // highest stored version; 0 = never configured
		public List<Section> sections = new ArrayList<>();
		public List<Question> questions = new ArrayList<>();
		// Job profiles (spec 2026-09-16), set by applyProfiles; position null = not assigned by any profile of the learner.
		public Integer position;
		public String profile, profilename, previoustopic, afterfinish = "keep", assignedlevel;
		public boolean mandatory, requiresprevious, locked, finished, removed;
	}

	public static class Content
	{
		public Map<String, Topic> topics = new LinkedHashMap<>();
		public Map<String, Section> sections = new HashMap<>();
		public Map<String, Question> questions = new HashMap<>();
		public String thresholdsreason; // why the org boundaries fell back to defaults; null when valid
		public List<String> removedtopics = new ArrayList<>(); // assigned topics finished with afterfinish=remove (stripped from topics/sections/questions)
		public List<JSONObject> profiles = new ArrayList<>(); // [{id, name, primary}] of the learner, primary first
	}

	/** Topics (with tutorials) in topic-service order -> tutorials -> sections by ordering -> mcq slots by ordering; evaluation-reserved excluded. */
	public Content loadContent()
	{
		return loadContent(null);
	}

	/**
	 * @param inVisibleTopicIds topics the learner may see (platform entity security, as the app's topic service applies it);
	 *                          null = all topics (background jobs).
	 */
	public Content loadContent(Collection<String> inVisibleTopicIds)
	{
		Content c = new Content();
		Object[] org = orgThresholds();
		int[] defaults = (int[]) org[0];
		c.thresholdsreason = (String) org[1];
		Map<String, Data> topicData = new LinkedHashMap<>();
		for (Object o : fieldArchive.query("entitytopic").all().search())
		{
			Data t = (Data) o;
			if (inVisibleTopicIds == null || inVisibleTopicIds.contains(t.getId()))
			{
				topicData.put(t.getId(), t);
			}
		}
		if (topicData.isEmpty())
		{
			return c;
		}
		// Same queries and order as TopicManager.getUserTopics (the app's topic service).
		Map<String, List<String>> tutorialsByTopic = new LinkedHashMap<>();
		Map<String, String> topicOfTutorial = new HashMap<>();
		HitTracker tuts = fieldArchive.query("entitytutorial").orgroup("entitytopic", topicData.keySet()).search();
		tuts.enableBulkOperations();
		for (Object o : tuts)
		{
			Data t = (Data) o;
			String topicid = t.get("entitytopic");
			if (topicid != null && topicData.containsKey(topicid))
			{
				tutorialsByTopic.computeIfAbsent(topicid, k -> new ArrayList<>()).add(t.getId());
				topicOfTutorial.put(t.getId(), topicid);
			}
		}
		if (topicOfTutorial.isEmpty())
		{
			return c;
		}
		Map<String, List<Data>> sectionsByTutorial = new HashMap<>();
		HitTracker secs = fieldArchive.query("componentsection").orgroup("playbackentityid", topicOfTutorial.keySet()).exact("playbackentitymoduleid", "entitytutorial").sort("ordering").search();
		secs.enableBulkOperations();
		List<String> sectionIds = new ArrayList<>();
		for (Object o : secs)
		{
			Data s = (Data) o;
			sectionsByTutorial.computeIfAbsent(s.get("playbackentityid"), k -> new ArrayList<>()).add(s);
			sectionIds.add(s.getId());
		}
		Map<String, List<Data>> slotsBySection = new HashMap<>();
		Set<String> questionIds = new HashSet<>();
		if (!sectionIds.isEmpty())
		{
			HitTracker slots = fieldArchive.query("componentcontent").orgroup("componentsectionid", sectionIds).exact("componenttype", "mcq").sort("ordering").search();
			slots.enableBulkOperations();
			for (Object o : slots)
			{
				Data s = (Data) o;
				String qid = s.get("questionid");
				if (qid != null && !qid.isEmpty())
				{
					slotsBySection.computeIfAbsent(s.get("componentsectionid"), k -> new ArrayList<>()).add(s);
					questionIds.add(qid);
				}
			}
		}
		Map<String, Data> questionData = new HashMap<>();
		if (!questionIds.isEmpty())
		{
			HitTracker qs = fieldArchive.query("entityquestion").ids(questionIds).search();
			qs.enableBulkOperations();
			for (Object o : qs)
			{
				Data q = (Data) o;
				questionData.put(q.getId(), q);
			}
		}
		Map<String, Data> policies = new HashMap<>();
		for (Object o : fieldArchive.query("subtopicpolicy").orgroup("entitytopic", topicData.keySet()).search())
		{
			Data p = (Data) o;
			Data prev = policies.get(p.get("entitytopic"));
			if (prev == null || intOr(p.get("policyversion"), 0) > intOr(prev.get("policyversion"), 0))
			{
				policies.put(p.get("entitytopic"), p);
			}
		}
		for (Map.Entry<String, List<String>> e : tutorialsByTopic.entrySet())
		{
			Data td = topicData.get(e.getKey());
			Topic topic = new Topic();
			topic.id = td.getId();
			topic.title = td.getName();
			topic.index = c.topics.size();
			Data policy = policies.get(topic.id);
			String[] effective = effectivePolicy(policy == null ? null : policy.get("unlockpolicy"), policy == null ? null : policy.get("requiredlevel"));
			topic.policy = effective[0];
			topic.requiredlevel = effective[1];
			topic.policyreason = effective[2];
			topic.policyversion = policy == null ? 0 : intOr(policy.get("policyversion"), 0);
			// Blank = org default. eMe stores a cleared number field as 0, so <= 0 also means "no override".
			// The resulting pair must still satisfy 1 <= competentmin < expertmin <= 100, else the org pair is used.
			int[] pair = {positiveOr(td.get("competentmin"), defaults[0]), positiveOr(td.get("expertmin"), defaults[1])};
			topic.thresholdsreason = thresholdsError(pair[0], pair[1]);
			if (topic.thresholdsreason != null)
			{
				pair = defaults;
			}
			topic.competentmin = pair[0];
			topic.expertmin = pair[1];
			for (String tutorialid : e.getValue())
			{
				for (Data sd : sectionsByTutorial.getOrDefault(tutorialid, Collections.emptyList()))
				{
					Section section = new Section();
					section.id = sd.getId();
					section.title = sd.getName();
					section.tutorialid = tutorialid;
					section.topicid = topic.id;
					for (Data slot : slotsBySection.getOrDefault(sd.getId(), Collections.emptyList()))
					{
						Data qd = questionData.get(slot.get("questionid"));
						if (qd == null || "true".equals(qd.get("evaluationreserved")) || c.questions.containsKey(qd.getId()))
						{
							continue; // ponytail: a question in two slots/topics counts once, at its first position
						}
						Question q = new Question();
						q.id = qd.getId();
						q.componentid = slot.getId();
						q.sectionid = section.id;
						q.tutorialid = tutorialid;
						q.topicid = topic.id;
						q.contentproblem = contentProblem(qd::get);
						q.difficulty = difficultyOf(qd.get("mcqcognitivelevel"));
						q.weight = weightOf(q.difficulty);
						q.topicindex = topic.index;
						q.position = topic.questions.size() + 1;
						section.questions.add(q);
						topic.questions.add(q);
						c.questions.put(q.id, q);
					}
					topic.sections.add(section);
					c.sections.put(section.id, section);
				}
			}
			c.topics.put(topic.id, topic);
		}
		return c;
	}

	/**
	 * Why the app cannot render an entityquestion from tutorial.json, or null: blank question, a blank option_a..option_d (the template
	 * always emits those four), or correctoption not a letter a-f (or option_x) naming a non-blank option.
	 */
	public static String contentProblem(Function<String, String> inField)
	{
		if (blank(inField.apply("question")))
		{
			return "missing_question";
		}
		for (String o : new String[] {"a", "b", "c", "d"})
		{
			if (blank(inField.apply("option_" + o)))
			{
				return "missing_option_" + o;
			}
		}
		String correct = inField.apply("correctoption");
		correct = correct == null ? "" : correct.trim().toLowerCase().replaceFirst("^option_", "");
		return correct.matches("[a-f]") && !blank(inField.apply("option_" + correct)) ? null : "bad_correctoption";
	}

	private static boolean blank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	/**
	 * Items of a next.json response (not done) whose content cannot be served: [{questionid, componentid, sectionid, tutorialid, topicid,
	 * problem}]. Empty = servable. A session is not started over such items, since Learn order would stall on them.
	 */
	public static JSONArray unavailable(Content c, JSONObject inNext)
	{
		JSONArray out = new JSONArray();
		JSONArray items = (JSONArray) inNext.get("items");
		for (Object o : items == null ? Collections.emptyList() : items)
		{
			Map item = (Map) o;
			Question q = c.questions.get(item.get("questionid"));
			String problem = q == null ? "unknown_question" : q.contentproblem;
			if (problem != null && !Boolean.TRUE.equals(item.get("done")))
			{
				JSONObject u = new JSONObject();
				u.put("questionid", item.get("questionid"));
				u.put("componentid", q == null ? null : q.componentid);
				u.put("sectionid", q == null ? null : q.sectionid);
				u.put("tutorialid", q == null ? null : q.tutorialid);
				u.put("topicid", q == null ? null : q.topicid);
				u.put("problem", problem);
				out.add(u);
			}
		}
		return out;
	}

	/** One open questionflag per unavailable question (reason content_unavailable, create-only) so administrators see it. */
	public void reportUnavailable(String inUserid, JSONArray inUnavailable)
	{
		Searcher searcher = fieldArchive.getSearcher("questionflag");
		synchronized (WRITE_LOCK)
		{
			for (Object o : inUnavailable)
			{
				Map u = (Map) o;
				String id = "contentunavailable_" + u.get("questionid");
				if (u.get("tutorialid") == null || searcher.searchById(id) != null)
				{
					continue; // unknown question: nothing to flag; flagged already: one open report
				}
				Data d = searcher.createNewData();
				d.setId(id);
				d.setValue("user", inUserid);
				d.setValue("datecreated", new Date());
				d.setValue("entityquestion", u.get("questionid"));
				d.setValue("entitytutorial", u.get("tutorialid"));
				d.setValue("reason", "content_unavailable");
				d.setValue("note", u.get("problem") + " (component " + u.get("componentid") + ", section " + u.get("sectionid") + ")");
				d.setValue("status", "open");
				searcher.saveData(d, null);
			}
		}
	}

	/**
	 * Canonical difficulty (list mcqcognitivelevel ids): beginner | competent | expert; missing or anything else = beginner.
	 * Source labels (Baja/Media/Alta) are mapped at import (importing/banco2026_load.py, tools/normalize_difficulty.py);
	 * tools/validate_content.py reports non-canonical values.
	 */
	public static String difficultyOf(String inLevel)
	{
		return "expert".equals(inLevel) || "competent".equals(inLevel) ? inLevel : "beginner";
	}

	/** Difficulty weight: beginner 1, competent 2, expert 4. */
	public static int weightOf(String inDifficulty)
	{
		return "expert".equals(inDifficulty) ? 4 : "competent".equals(inDifficulty) ? 2 : 1;
	}

	/**
	 * {int[] {competentmin, expertmin}, reason} from list masterylevel.minpercent. Beginner implicitly starts at 0:
	 * beginner 0..competentmin-1, competent competentmin..expertmin-1, expert expertmin..100. An invalid pair falls back to
	 * DEFAULT_THRESHOLDS (60 / 85) and reason says why; reason is null when the configured pair is used.
	 */
	public Object[] orgThresholds()
	{
		int[] t = DEFAULT_THRESHOLDS.clone();
		try
		{
			for (Object o : fieldArchive.getSearcher("masterylevel").query().all().search())
			{
				Data d = (Data) o;
				if ("competent".equals(d.getId()))
				{
					t[0] = intOr(d.get("minpercent"), t[0]);
				}
				else if ("expert".equals(d.getId()))
				{
					t[1] = intOr(d.get("minpercent"), t[1]);
				}
			}
		}
		catch (Exception e)
		{
			return new Object[] {DEFAULT_THRESHOLDS.clone(), "masterylevel_unavailable"};
		}
		String reason = thresholdsError(t[0], t[1]);
		return new Object[] {reason == null ? t : DEFAULT_THRESHOLDS.clone(), reason};
	}

	/** Band boundary rule 1 <= competentmin < expertmin <= 100; null when valid, else the diagnostic reason. */
	public static String thresholdsError(int inCompetentmin, int inExpertmin)
	{
		if (inCompetentmin < 1)
		{
			return "competentmin_below_1";
		}
		if (inExpertmin > 100)
		{
			return "expertmin_above_100";
		}
		return inCompetentmin < inExpertmin ? null : "competentmin_not_below_expertmin";
	}

	public static final List<String> POLICIES = List.of("open", "sequential_completion", "sequential_mastery");

	/**
	 * {policy, requiredlevel, reason} actually applied for a stored policy row. No row = open (policy_not_configured, today's behavior);
	 * an unknown policy = sequential_completion (policy_invalid: fail safe, never open everything); sequential_mastery without a canonical
	 * level = sequential_completion (requiredlevel_invalid). requiredlevel is null unless the policy is sequential_mastery; reason is null
	 * when the row is used as stored.
	 */
	public static String[] effectivePolicy(String inPolicy, String inLevel)
	{
		if (inPolicy == null || inPolicy.isEmpty())
		{
			return new String[] {"open", null, "policy_not_configured"};
		}
		if (!POLICIES.contains(inPolicy))
		{
			return new String[] {"sequential_completion", null, "policy_invalid"};
		}
		if (!"sequential_mastery".equals(inPolicy))
		{
			return new String[] {inPolicy, null, null};
		}
		return levelIndex(inLevel) < 0 ? new String[] {"sequential_completion", null, "requiredlevel_invalid"} : new String[] {inPolicy, inLevel, null};
	}

	// ---------------------------------------------------------------- learner

	public static class Attempt
	{
		public String questionid, mode, confidence;
		public boolean correct;
		public int hintlevel;
		public Date at;
	}

	public static class Learner
	{
		public String userid;
		public List<Attempt> attempts = new ArrayList<>(); // oldest first
		public Map<String, Attempt> latest = new HashMap<>();
		public Set<String> answeredInSequence = new HashSet<>();
		public Set<String> exposedInSequence = new HashSet<>(); // shown in learn|dailychallenge (exposure rows)
		public Map<String, Date> lastShown = new HashMap<>(); // learning modes only (attempts and exposures)
		public List<Attempt> learningAttempts = new ArrayList<>(); // learn|dailychallenge|improve, oldest first
		public Map<String, Integer> incorrectLearning = new HashMap<>();
		public Set<String> unresolvedHci = new HashSet<>();
		public Collection<String> jobroles = new ArrayList<>();
		public String primaryjobrole; // orders the assignment; null = none (extras alone, by name)
	}

	/** learn | dailychallenge | improve. Legacy (unverified) and evaluation attempts are not learning attempts. */
	public static boolean isLearningMode(String inMode)
	{
		return inMode != null && MODES.contains(inMode);
	}

	public static boolean isSure(Attempt a)
	{
		return a != null && ("confident".equals(a.confidence) || "mostlysure".equals(a.confidence));
	}

	/** High-confidence incorrect. */
	public static boolean isHci(Attempt a)
	{
		return a != null && !a.correct && isSure(a);
	}

	public static Attempt attemptOf(Data a)
	{
		Attempt t = new Attempt();
		t.questionid = a.get("entityquestion");
		String mode = a.get("mode");
		t.mode = mode == null || mode.isEmpty() ? LEGACY : mode; // no mode = not verified by answer.json
		t.confidence = a.get("answerconfidence");
		t.correct = "true".equals(String.valueOf(a.get("iscorrect")));
		t.hintlevel = Math.max(0, Math.min(3, intOr(a.get("hintlevel"), 0)));
		t.at = DateStorageUtil.getStorageUtil().parseFromObject(a.getValue("datecreated"));
		return t;
	}

	public Learner loadLearner(String inUserid, Collection<String> inJobroles)
	{
		List<Attempt> attempts = new ArrayList<>();
		HitTracker hits = fieldArchive.query("tutoranswer").exact("user", inUserid).search();
		hits.enableBulkOperations();
		for (Object o : hits)
		{
			attempts.add(attemptOf((Data) o));
		}
		Learner l = learner(inUserid, attempts);
		HitTracker ex = fieldArchive.query("tutorexposure").exact("user", inUserid).search();
		ex.enableBulkOperations();
		for (Object o : ex)
		{
			Data d = (Data) o;
			if ("learn".equals(d.get("mode")) || "dailychallenge".equals(d.get("mode")))
			{
				l.exposedInSequence.add(d.get("entityquestion"));
			}
			if (isLearningMode(d.get("mode")))
			{
				touch(l.lastShown, d.get("entityquestion"), DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("datecreated")));
			}
		}
		if (inJobroles != null)
		{
			l.jobroles = inJobroles;
		}
		return l;
	}

	public Learner loadLearner(String inUserid, Collection<String> inJobroles, String inPrimary)
	{
		Learner l = loadLearner(inUserid, inJobroles);
		l.primaryjobrole = inPrimary;
		return l;
	}

	public static Learner learner(String inUserid, List<Attempt> inAttempts)
	{
		Learner l = new Learner();
		l.userid = inUserid;
		l.attempts = new ArrayList<>(inAttempts);
		l.attempts.sort(Comparator.comparing(a -> a.at == null ? new Date(0) : a.at));
		for (Attempt a : l.attempts)
		{
			if (a.questionid == null)
			{
				continue;
			}
			l.latest.put(a.questionid, a);
			if ("learn".equals(a.mode) || "dailychallenge".equals(a.mode))
			{
				l.answeredInSequence.add(a.questionid);
			}
			if (isLearningMode(a.mode))
			{
				touch(l.lastShown, a.questionid, a.at);
				l.learningAttempts.add(a);
				if (!a.correct)
				{
					l.incorrectLearning.merge(a.questionid, 1, Integer::sum);
				}
				if (isHci(a))
				{
					l.unresolvedHci.add(a.questionid); // HCI and its resolution both read from learning attempts; resolution clears the flag, history stays
				}
				else if (a.correct && isSure(a) && a.hintlevel == 0)
				{
					l.unresolvedHci.remove(a.questionid);
				}
			}
		}
		return l;
	}

	private static void touch(Map<String, Date> inMap, String inKey, Date inAt)
	{
		if (inKey == null || inAt == null)
		{
			return;
		}
		Date prev = inMap.get(inKey);
		if (prev == null || inAt.after(prev))
		{
			inMap.put(inKey, inAt);
		}
	}

	/** Job role ids of a user record (multi-valued list field). */
	public static Collection<String> jobrolesOf(Data inUser)
	{
		if (inUser == null)
		{
			return Collections.emptyList();
		}
		Object v = inUser.getValue("jobrole");
		List<String> out = new ArrayList<>();
		if (v instanceof Collection)
		{
			for (Object o : (Collection<?>) v)
			{
				out.add(String.valueOf(o));
			}
		}
		else if (v != null)
		{
			for (String r : v.toString().split("\\s*[|,]\\s*"))
			{
				if (!r.isEmpty())
					out.add(r);
			}
		}
		return out;
	}

	/** The user's primary job profile id, or null when unset. */
	public static String primaryJobroleOf(Data inUser)
	{
		if (inUser == null)
		{
			return null;
		}
		String v = inUser.get("primaryjobrole");
		return v == null || v.isEmpty() ? null : v;
	}

	// ---------------------------------------------------------------- mastery

	public static double score(Attempt a)
	{
		if (a == null || !a.correct)
		{
			return 0;
		}
		double conf;
		String c = a.confidence == null ? "" : a.confidence;
		switch (c)
		{
			case "mostlysure":
				conf = 0.85;
				break;
			case "notsure":
				conf = 0.6;
				break;
			case "noidea":
				conf = 0.4;
				break;
			default:
				conf = 1.0; // confident, and legacy rows without confidence
		}
		return conf * (1.0 - 0.25 * a.hintlevel);
	}

	public static class Mastery
	{
		public int percent, questions, answered;
		public String band;
		public boolean evidence;
		public String evidencereason;
	}

	/** Weighted mastery, band and expert evidence of a scope. Band = null when nothing in scope was ever attempted. */
	public static Mastery mastery(Collection<Question> inScope, Learner inLearner, int inCompetentmin, int inExpertmin)
	{
		Mastery m = new Mastery();
		m.percent = percent(inScope, inLearner);
		boolean any = false;
		for (Question q : inScope)
		{
			any |= inLearner.latest.containsKey(q.id);
			if (inLearner.answeredInSequence.contains(q.id))
			{
				m.answered++;
			}
		}
		m.questions = inScope.size();
		m.band = !any ? null : m.percent >= inExpertmin ? "expert" : m.percent >= inCompetentmin ? "competent" : "beginner";
		List<Question> experts = new ArrayList<>();
		for (Question q : inScope)
		{
			if ("expert".equals(q.difficulty))
			{
				experts.add(q);
			}
		}
		if (experts.isEmpty())
		{
			m.evidencereason = "no_expert_questions";
			return m;
		}
		int correct = 0;
		for (Question q : experts)
		{
			Attempt a = inLearner.latest.get(q.id);
			if (a != null && a.correct)
			{
				correct++;
			}
		}
		m.evidence = percent(experts, inLearner) >= inExpertmin && correct >= Math.min(3, experts.size());
		m.evidencereason = m.evidence ? null : "insufficient";
		return m;
	}

	/**
	 * 100 x sum(weight x score) / sum(weight), rounded to an integer. Bands compare the rounded value, so the
	 * percent shown and the band never disagree (59.6 shows 60 and is competent).
	 */
	public static int percent(Collection<Question> inScope, Learner inLearner)
	{
		double w = 0, ws = 0;
		for (Question q : inScope)
		{
			w += q.weight;
			ws += q.weight * score(inLearner.latest.get(q.id));
		}
		return w == 0 ? 0 : (int) Math.round(100.0 * ws / w);
	}

	public static int levelIndex(String inLevel)
	{
		return inLevel == null ? -1 : LEVELS.indexOf(inLevel);
	}

	/** Strictest topicrequirement.requiredlevel across the job roles; null when none applies. */
	public String requiredLevel(String inTopicid, Collection<String> inJobroles)
	{
		if (inJobroles == null || inJobroles.isEmpty())
		{
			return null;
		}
		String strictest = null;
		for (Object o : fieldArchive.query("topicrequirement").exact("entitytopic", inTopicid).search())
		{
			Data r = (Data) o;
			String lvl = r.get("requiredlevel");
			if (inJobroles.contains(r.get("jobrole")) && levelIndex(lvl) > levelIndex(strictest))
			{
				strictest = lvl;
			}
		}
		return strictest;
	}

	// ---------------------------------------------------------------- job profiles (spec 2026-09-16)

	/** One topicrequirement row: profile x topic. */
	public static class ProfileRow
	{
		public String jobrole, topicid, requiredlevel, afterfinish = "keep";
		public int position;
		public boolean mandatory = true, requiresprevious;
	}

	/** Every row of the learner's profiles, and profile id -> name. */
	public static class Profiles
	{
		public List<ProfileRow> rows = new ArrayList<>();
		public Map<String, String> names = new LinkedHashMap<>();
	}

	public static ProfileRow rowOf(Data d)
	{
		ProfileRow r = new ProfileRow();
		r.jobrole = d.get("jobrole");
		r.topicid = d.get("entitytopic");
		String lvl = d.get("requiredlevel");
		r.requiredlevel = lvl == null || lvl.isEmpty() ? null : lvl;
		r.position = intOr(d.get("position"), 0);
		r.mandatory = !"false".equals(String.valueOf(d.get("mandatory")));
		r.requiresprevious = "true".equals(String.valueOf(d.get("requiresprevious")));
		r.afterfinish = "remove".equals(d.get("afterfinish")) ? "remove" : "keep";
		return r;
	}

	public Profiles loadProfiles(Collection<String> inJobroles)
	{
		Profiles p = new Profiles();
		if (inJobroles == null || inJobroles.isEmpty())
		{
			return p;
		}
		for (String id : inJobroles)
		{
			Data d = fieldArchive.getCachedData("jobrole", id);
			p.names.put(id, d == null || d.getName() == null ? id : d.getName());
		}
		for (Object o : fieldArchive.query("topicrequirement").orgroup("jobrole", inJobroles).search())
		{
			p.rows.add(rowOf((Data) o));
		}
		return p;
	}

	/** Primary first, then the other profiles by name (ties by id). Ids the learner does not hold are ignored. */
	public static List<String> profileOrder(Learner l, Map<String, String> inNames)
	{
		List<String> out = new ArrayList<>();
		if (l.primaryjobrole != null && l.jobroles.contains(l.primaryjobrole))
		{
			out.add(l.primaryjobrole);
		}
		List<String> rest = new ArrayList<>();
		for (String id : l.jobroles)
		{
			if (!out.contains(id) && !rest.contains(id))
			{
				rest.add(id);
			}
		}
		rest.sort(Comparator.comparing((String id) -> inNames.getOrDefault(id, id)).thenComparing(id -> id));
		out.addAll(rest);
		return out;
	}

	/** Learn complete AND (no level, or band >= assignedlevel with expert evidence when expert). */
	public static boolean finished(Topic t, Learner l)
	{
		Mastery m = mastery(t.questions, l, t.competentmin, t.expertmin);
		if (m.questions == 0 || m.answered < m.questions)
		{
			return false;
		}
		if (t.assignedlevel == null)
		{
			return true;
		}
		return levelIndex(m.band) >= levelIndex(t.assignedlevel) && (!"expert".equals(t.assignedlevel) || m.evidence);
	}

	/** Any learn/dailychallenge answer or exposure on a question of t. */
	public static boolean started(Topic t, Learner l)
	{
		for (Question q : t.questions)
		{
			if (l.answeredInSequence.contains(q.id) || l.exposedInSequence.contains(q.id))
			{
				return true;
			}
		}
		return false;
	}

	/** loadProfiles + applyProfiles for the learner's own profiles. */
	public void applyProfiles(Content c, Learner l)
	{
		applyProfiles(c, l, loadProfiles(l.jobroles));
	}

	/**
	 * Pure. Reorders c.topics: the learner's assignment first (primary profile rows by position, then each extra profile by name), then
	 * the rest in catalog order. A topic in several profiles keeps its first occurrence for order, gate and previous topic; level =
	 * strictest, mandatory if any, keep beats remove. Rows on topics not in c or without questions are skipped (the gate then points at
	 * the previous visible row of that profile). Sets position (renumbered 1..n over the merged list), finished, locked (requiresprevious
	 * AND previous not finished AND not started), removed (afterfinish=remove AND finished; stripped from c and listed in c.removedtopics).
	 * No rows -> c untouched apart from c.profiles.
	 */
	public static void applyProfiles(Content c, Learner l, Profiles inProfiles)
	{
		List<String> order = profileOrder(l, inProfiles.names);
		c.profiles = new ArrayList<>();
		for (String id : order)
		{
			JSONObject p = new JSONObject();
			p.put("id", id);
			p.put("name", inProfiles.names.getOrDefault(id, id));
			p.put("primary", id.equals(l.primaryjobrole));
			c.profiles.add(p);
		}
		if (inProfiles.rows.isEmpty())
		{
			return;
		}
		Map<String, List<ProfileRow>> byProfile = new HashMap<>();
		for (ProfileRow r : inProfiles.rows)
		{
			byProfile.computeIfAbsent(r.jobrole, k -> new ArrayList<>()).add(r);
		}
		Map<String, Topic> assigned = new LinkedHashMap<>();
		for (String pid : order)
		{
			List<ProfileRow> rows = new ArrayList<>(byProfile.getOrDefault(pid, Collections.emptyList()));
			rows.sort(Comparator.comparingInt((ProfileRow r) -> r.position).thenComparing(r -> r.topicid));
			String prev = null; // previous visible row of this profile
			for (ProfileRow r : rows)
			{
				Topic t = c.topics.get(r.topicid);
				if (t == null || t.questions.isEmpty())
				{
					continue;
				}
				if (assigned.containsKey(t.id))
				{
					if (levelIndex(r.requiredlevel) > levelIndex(t.assignedlevel))
					{
						t.assignedlevel = r.requiredlevel;
					}
					t.mandatory |= r.mandatory;
					if ("keep".equals(r.afterfinish))
					{
						t.afterfinish = "keep";
					}
				}
				else
				{
					t.position = assigned.size() + 1;
					t.profile = pid;
					t.profilename = inProfiles.names.getOrDefault(pid, pid);
					t.assignedlevel = r.requiredlevel;
					t.mandatory = r.mandatory;
					t.afterfinish = r.afterfinish;
					t.requiresprevious = r.requiresprevious && prev != null;
					t.previoustopic = t.requiresprevious ? prev : null;
					assigned.put(t.id, t);
				}
				prev = t.id;
			}
		}
		if (assigned.isEmpty())
		{
			return;
		}
		for (Topic t : assigned.values())
		{
			t.finished = finished(t, l);
		}
		for (Topic t : assigned.values())
		{
			Topic p = t.previoustopic == null ? null : c.topics.get(t.previoustopic);
			t.locked = t.requiresprevious && p != null && !p.finished && !started(t, l);
			t.removed = "remove".equals(t.afterfinish) && t.finished;
		}
		Map<String, Topic> reordered = new LinkedHashMap<>();
		for (Topic t : assigned.values())
		{
			if (t.removed)
			{
				c.removedtopics.add(t.id);
				for (Section s : t.sections)
				{
					c.sections.remove(s.id);
				}
				for (Question q : t.questions)
				{
					c.questions.remove(q.id);
				}
			}
			else
			{
				reordered.put(t.id, t);
			}
		}
		for (Topic t : c.topics.values())
		{
			if (!assigned.containsKey(t.id))
			{
				reordered.put(t.id, t);
			}
		}
		c.topics = reordered;
	}

	/**
	 * Daily Challenge required map: assigned topics -> mandatory; no assignment for the learner -> topicrequirement by job role (v1);
	 * none at all -> every topic with questions (assignment_data_unavailable). inReason (nullable, 1 slot) receives the reason or null.
	 */
	public Map<String, Boolean> requiredTopics(Content c, Learner l, String[] inReason)
	{
		Map<String, Boolean> required = new HashMap<>();
		boolean any = false;
		for (Topic t : c.topics.values())
		{
			boolean r = t.position != null ? t.mandatory : requiredLevel(t.id, l.jobroles) != null;
			required.put(t.id, r);
			any |= r;
		}
		if (!any)
		{
			for (Topic t : c.topics.values())
			{
				required.put(t.id, !t.questions.isEmpty());
			}
			if (inReason != null)
			{
				inReason[0] = "assignment_data_unavailable";
			}
		}
		return required;
	}

	/** Section ids of locked topics: excluded from the Daily Challenge new pool like locked subtopics. */
	public static Set<String> lockedTopicSections(Content c)
	{
		Set<String> out = new HashSet<>();
		for (Topic t : c.topics.values())
		{
			if (t.locked)
			{
				for (Section s : t.sections)
				{
					out.add(s.id);
				}
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- subtopic progression

	/**
	 * Unlock state of each subtopic of t, in order (pure). In order of precedence: the first subtopic, every subtopic under open,
	 * a recorded unlock (inRecorded: section id -> unlock date), the policy condition on the immediately preceding subtopic (every
	 * eligible question answered in learn|dailychallenge, and under sequential_mastery also band >= required level; expert also needs
	 * expert evidence), then a subtopic the learner already started (learn/dailychallenge answer or exposure; reached = never relocked).
	 * A state unlocked by the condition but not recorded carries "newunlock" (the evidence); only recordUnlocks persists it.
	 */
	public static final Set<String> LOCKED_REASONS = Set.of("previous_subtopic_incomplete", "previous_subtopic_below_required_mastery", "previous_subtopic_missing_expert_evidence");

	public static List<JSONObject> subtopicStates(Topic t, Learner l, Map<String, Date> inRecorded)
	{
		List<JSONObject> out = new ArrayList<>();
		Section prev = null;
		for (Section s : t.sections)
		{
			JSONObject o = new JSONObject();
			o.put("id", s.id);
			o.put("position", out.size() + 1);
			o.put("unlockpolicy", t.policy);
			o.put("requiredlevel", t.requiredlevel);
			o.put("previoussubtopicid", prev == null ? null : prev.id);
			Mastery pm = prev == null ? null : mastery(prev.questions, l, t.competentmin, t.expertmin);
			boolean complete = prev != null && l.answeredInSequence.containsAll(ids(prev.questions));
			// an empty previous subtopic has nothing to master
			boolean levelmet = prev != null && (prev.questions.isEmpty() || levelIndex(pm.band) >= levelIndex(t.requiredlevel));
			boolean evidencemet = prev != null && (prev.questions.isEmpty() || !"expert".equals(t.requiredlevel) || pm.evidence);
			boolean mastery = "sequential_mastery".equals(t.policy);
			o.put("previouscomplete", prev == null ? null : complete);
			o.put("previousband", pm == null ? null : pm.band);
			o.put("previousmeetsrequirement", prev == null || !mastery ? null : levelmet && evidencemet);
			Date recorded = inRecorded == null ? null : inRecorded.get(s.id);
			String reason;
			if (prev == null)
				reason = "first_subtopic";
			else if ("open".equals(t.policy))
				reason = "policy_open";
			else if (recorded != null)
				reason = "unlock_recorded";
			else if (complete && !mastery)
				reason = "previous_subtopic_complete";
			else if (complete && levelmet && evidencemet)
				reason = "previous_subtopic_mastery_met";
			else if (started(s, l))
				reason = "subtopic_started";
			else if (!complete)
				reason = "previous_subtopic_incomplete";
			else if (!levelmet)
				reason = "previous_subtopic_below_required_mastery";
			else
				reason = "previous_subtopic_missing_expert_evidence";
			o.put("unlocked", !LOCKED_REASONS.contains(reason));
			o.put("unlockreason", reason);
			o.put("unlockedat", iso(recorded));
			if (reason.equals("previous_subtopic_complete") || reason.equals("previous_subtopic_mastery_met"))
			{
				JSONObject ev = new JSONObject();
				ev.put("previoussection", prev.id);
				ev.put("answered", pm.answered);
				ev.put("questions", prev.questions.size());
				ev.put("percent", pm.percent);
				ev.put("band", pm.band);
				ev.put("expertevidence", pm.evidence);
				ev.put("condition", reason);
				o.put("newunlock", ev);
			}
			out.add(o);
			prev = s;
		}
		return out;
	}

	private static boolean started(Section s, Learner l)
	{
		for (Question q : s.questions)
		{
			if (l.answeredInSequence.contains(q.id) || l.exposedInSequence.contains(q.id))
			{
				return true;
			}
		}
		return false;
	}

	/** Unlock states of every subtopic in c, keyed by section id. Read-only: an unrecorded condition unlock has unlockedat null. */
	public Map<String, JSONObject> subtopicStates(Content c, Learner l)
	{
		Map<String, Date> recorded = recordedUnlocks(l.userid);
		Map<String, JSONObject> out = new HashMap<>();
		for (Topic t : c.topics.values())
		{
			for (JSONObject st : subtopicStates(t, l, recorded))
			{
				st.remove("newunlock");
				out.put((String) st.get("id"), st);
			}
		}
		return out;
	}

	/**
	 * Records every unlock of t that l meets by condition and has no row yet (create-only; an existing row keeps its date and evidence).
	 * Called after an accepted answer or exposure (l already includes it) and by backfillUnlocks. Returns rows created.
	 */
	public int recordUnlocks(Topic t, Learner l)
	{
		if ("open".equals(t.policy))
		{
			return 0;
		}
		List<String[]> fresh = new ArrayList<>();
		for (JSONObject st : subtopicStates(t, l, recordedUnlocks(l.userid)))
		{
			JSONObject ev = (JSONObject) st.get("newunlock");
			if (ev != null)
			{
				fresh.add(new String[] {t.id, (String) st.get("id"), (String) ev.get("previoussection"), String.valueOf(t.policyversion), t.policy, t.requiredlevel,
					(String) ev.get("condition"), ev.toJSONString()});
			}
		}
		return fresh.isEmpty() ? 0 : saveUnlocks(l.userid, fresh, new Date());
	}

	/**
	 * Idempotent backfill: recordUnlocks for every learner (inUserid null) or one, with a tutoranswer or tutorexposure row, every topic.
	 * Returns {learners, rows created}.
	 */
	public int[] backfillUnlocks(String inUserid)
	{
		Content c = loadContent();
		Set<String> users = new java.util.TreeSet<>();
		for (String table : new String[] {"tutoranswer", "tutorexposure"})
		{
			HitTracker hits = inUserid == null ? fieldArchive.query(table).all().search() : fieldArchive.query(table).exact("user", inUserid).search();
			hits.enableBulkOperations();
			for (Object o : hits)
			{
				String u = ((Data) o).get("user");
				if (u != null && !u.isEmpty())
				{
					users.add(u);
				}
			}
		}
		int created = 0;
		for (String u : users)
		{
			Learner l = loadLearner(u, null);
			for (Topic t : c.topics.values())
			{
				created += recordUnlocks(t, l);
			}
		}
		return new int[] {users.size(), created};
	}

	/**
	 * Serializes the engine's create-only writes (policy versions, unlock rows, challenge sets): check by id, then save, under one lock.
	 * searchById is a realtime ES get, so a row saved by the previous holder is always seen.
	 * ponytail: one JVM lock = one Tomcat node; a cluster needs ES op_type=create instead.
	 */
	public static final Object WRITE_LOCK = new Object();

	/** Section id -> unlock date of the learner's recorded unlocks. */
	protected Map<String, Date> recordedUnlocks(String inUserid)
	{
		Map<String, Date> out = new HashMap<>();
		for (Object o : fieldArchive.query("subtopicunlock").exact("user", inUserid).search())
		{
			Data d = (Data) o;
			out.put(d.get("componentsection"), DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("datecreated")));
		}
		return out;
	}

	/** inRows: {topic, section, previous section, policy version, policy, required level, condition, evidence JSON}. */
	protected int saveUnlocks(String inUserid, List<String[]> inRows, Date inNow)
	{
		Searcher searcher = fieldArchive.getSearcher("subtopicunlock");
		synchronized (WRITE_LOCK)
		{
			List<Data> rows = new ArrayList<>();
			for (String[] r : inRows)
			{
				String id = inUserid + "_" + r[1];
				if (searcher.searchById(id) != null)
				{
					continue; // create-only: the first unlock keeps its date and evidence
				}
				rows.add(unlockRow(searcher, id, inUserid, r, inNow));
			}
			if (!rows.isEmpty())
			{
				searcher.saveAllData(rows, null);
			}
			return rows.size();
		}
	}

	private static Data unlockRow(Searcher searcher, String inId, String inUserid, String[] r, Date inNow)
	{
		Data d = searcher.createNewData();
		d.setId(inId);
		d.setValue("user", inUserid);
		d.setValue("entitytopic", r[0]);
		d.setValue("componentsection", r[1]);
		d.setValue("previoussection", r[2]);
		d.setValue("policyversion", Integer.parseInt(r[3]));
		d.setValue("unlockpolicy", r[4]);
		d.setValue("requiredlevel", r[5]);
		d.setValue("unlockcondition", r[6]);
		d.setValue("unlockevidence", r[7]);
		d.setValue("datecreated", inNow);
		return d;
	}

	/** True when the section is unlocked in inStates (a section absent from inStates, or null states, counts as unlocked). */
	public static boolean isUnlocked(Map<String, JSONObject> inStates, String inSectionid)
	{
		JSONObject st = inStates == null ? null : inStates.get(inSectionid);
		return st == null || Boolean.TRUE.equals(st.get("unlocked"));
	}

	/** Section ids locked in inStates. */
	public static Set<String> locked(Map<String, JSONObject> inStates)
	{
		Set<String> out = new HashSet<>();
		for (Map.Entry<String, JSONObject> e : inStates.entrySet())
		{
			if (!isUnlocked(inStates, e.getKey()))
			{
				out.add(e.getKey());
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- selection

	public static final String DC_VERSION = "dc-v0";
	public static final long COOLDOWN_MS = 48L * 3600 * 1000;

	/** 1 latest is HCI, 2 latest correct but notsure|noidea, 3 latest other incorrect, 4 everything else. */
	public static int priorityClass(Attempt a)
	{
		if (a == null)
		{
			return 4;
		}
		if (isHci(a))
		{
			return 1;
		}
		if (a.correct && ("notsure".equals(a.confidence) || "noidea".equals(a.confidence)))
		{
			return 2;
		}
		return a.correct ? 4 : 3;
	}

	/** Base risk (class 1 = 1.0, 2 and 3 = 0.5, 4 = 0) x recurrence 1 + 0.25 x min(incorrect learning attempts - 1, 2). */
	public static double risk(Question q, Learner l)
	{
		int cls = priorityClass(l.latest.get(q.id));
		double base = cls == 1 ? 1.0 : cls <= 3 ? 0.5 : 0;
		int wrong = l.incorrectLearning.getOrDefault(q.id, 0);
		return base * (1 + 0.25 * Math.max(0, Math.min(wrong - 1, 2)));
	}

	public static String reasonOf(Question q, Learner l, String inBand)
	{
		if (!l.answeredInSequence.contains(q.id))
		{
			return "new";
		}
		switch (priorityClass(l.latest.get(q.id)))
		{
			case 1:
				return "highconfwrong";
			case 2:
				return "lowconfright";
			case 3:
				return "wrong";
			default:
				return levelIndex(inBand) >= 1 && !"beginner".equals(q.difficulty) ? "harder" : "stale";
		}
	}

	/**
	 * Deterministic Improve order: class asc, risk desc, required topic first, harder first when the band is >= competent,
	 * oldest last shown, topic order, sequence position, question id.
	 */
	public static Comparator<Question> improveOrder(Learner l, Function<Question, String> inBandOf, Map<String, Boolean> inRequired)
	{
		return Comparator.<Question> comparingInt(q -> priorityClass(l.latest.get(q.id)))
			.thenComparing(q -> -risk(q, l))
			.thenComparingInt(q -> inRequired != null && Boolean.TRUE.equals(inRequired.get(q.topicid)) ? 0 : 1)
			.thenComparingInt(q -> levelIndex(inBandOf.apply(q)) >= 1 ? -q.weight : 0)
			.thenComparingLong(q -> l.lastShown.containsKey(q.id) ? l.lastShown.get(q.id).getTime() : 0L)
			.thenComparingInt(q -> q.topicindex)
			.thenComparingInt(q -> q.position)
			.thenComparing(q -> q.id);
	}

	public static JSONObject item(Question q, String inReason, boolean inDone)
	{
		JSONObject o = new JSONObject();
		o.put("questionid", q.id);
		o.put("componentid", q.componentid);
		o.put("sectionid", q.sectionid);
		o.put("tutorialid", q.tutorialid);
		o.put("topicid", q.topicid);
		o.put("position", q.position);
		o.put("reason", inReason);
		o.put("done", inDone);
		return o;
	}

	public List<Question> scope(Topic inTopic, Section inSection)
	{
		return inSection != null ? inSection.questions : inTopic.questions;
	}

	// ---------------------------------------------------------------- sessions

	public static final long SESSION_TTL_MS = 7L * 86400 * 1000;
	/** An expired session is kept this long for diagnostics (answers keep their learningsession id), then purgeSessions deletes it. */
	public static final long SESSION_RETENTION_MS = 30L * 86400 * 1000;

	/** Oldest expiresat that is still kept at inNow. */
	public static Date purgeBefore(Date inNow)
	{
		return new Date(inNow.getTime() - SESSION_RETENTION_MS);
	}

	/** Idempotent: deletes learningsession rows that expired before purgeBefore(inNow). Run by the computemastery event. Returns rows deleted. */
	public int purgeSessions(Date inNow)
	{
		Searcher searcher = fieldArchive.getSearcher("learningsession");
		HitTracker hits = fieldArchive.query("learningsession").before("expiresat", purgeBefore(inNow)).search();
		hits.enableBulkOperations();
		List<Data> old = new ArrayList<>();
		for (Object o : hits)
		{
			old.add((Data) o);
		}
		for (Data d : old)
		{
			searcher.delete(d, null);
		}
		return old.size();
	}

	/** A server-issued Learn / Improve question snapshot (table learningsession). */
	public static class Session
	{
		public String id, user, mode, scopetype, scopeid, algorithmversion;
		public List<String> questions = new ArrayList<>(); // served order
		public int policyversion;
		public JSONObject inputs = new JSONObject();
		public Date created, expires;
	}

	/**
	 * Saves a session for inNext's items (learn | improve) and puts its id in inNext as sessionid (null when there are no items).
	 * The Daily Challenge's session is its stored set (dailyChallenge puts that id).
	 */
	public JSONObject startSession(String inUserid, String inScopetype, String inScopeid, Topic inTopic, JSONObject inNext)
	{
		JSONArray items = (JSONArray) inNext.get("items");
		if (items == null || items.isEmpty())
		{
			inNext.put("sessionid", null);
			return inNext;
		}
		Session s = new Session();
		s.id = inUserid + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
		s.user = inUserid;
		s.mode = (String) inNext.get("mode");
		s.scopetype = inScopetype;
		s.scopeid = inScopeid;
		s.algorithmversion = s.mode + "-v1";
		for (Object o : items)
		{
			s.questions.add((String) ((Map) o).get("questionid"));
		}
		s.policyversion = inTopic.policyversion;
		s.inputs.put("policy", inTopic.policy);
		s.inputs.put("requiredlevel", inTopic.requiredlevel);
		s.inputs.put("policyversion", inTopic.policyversion);
		s.inputs.put("policyreason", inTopic.policyreason);
		s.inputs.put("lockedquestions", inNext.get("lockedquestions"));
		s.created = new Date();
		s.expires = new Date(s.created.getTime() + SESSION_TTL_MS);
		saveSession(s);
		inNext.put("sessionid", s.id);
		return inNext;
	}

	protected void saveSession(Session s)
	{
		Searcher searcher = fieldArchive.getSearcher("learningsession");
		Data d = searcher.createNewData();
		d.setId(s.id);
		d.setValue("user", s.user);
		d.setValue("mode", s.mode);
		d.setValue("scopetype", s.scopetype);
		d.setValue("scopeid", s.scopeid);
		JSONArray ids = new JSONArray();
		ids.addAll(s.questions);
		d.setValue("questionlist", ids.toJSONString());
		d.setValue("algorithmversion", s.algorithmversion);
		d.setValue("policyversion", s.policyversion);
		d.setValue("inputs", s.inputs.toJSONString());
		d.setValue("datecreated", s.created);
		d.setValue("expiresat", s.expires);
		searcher.saveData(d, null);
	}

	/** Null when no such session. */
	protected Session loadSession(String inId)
	{
		Data d = (Data) fieldArchive.getSearcher("learningsession").searchById(inId);
		if (d == null)
		{
			return null;
		}
		Session s = new Session();
		s.id = d.getId();
		s.user = d.get("user");
		s.mode = d.get("mode");
		s.scopetype = d.get("scopetype");
		s.scopeid = d.get("scopeid");
		Object parsed = JSONValue.parse(String.valueOf(d.get("questionlist")));
		if (parsed instanceof List)
		{
			for (Object o : (List) parsed)
			{
				s.questions.add(String.valueOf(o));
			}
		}
		s.created = DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("datecreated"));
		s.expires = DateStorageUtil.getStorageUtil().parseFromObject(d.getValue("expiresat"));
		return s;
	}

	/** A copy of l with inAttempt added (the learner right after an accepted answer; storage search lags behind the save). */
	public static Learner withAttempt(Learner l, Attempt inAttempt)
	{
		List<Attempt> all = new ArrayList<>(l.attempts);
		all.add(inAttempt);
		Learner out = learner(l.userid, all);
		out.exposedInSequence.addAll(l.exposedInSequence);
		for (Map.Entry<String, Date> e : l.lastShown.entrySet())
		{
			touch(out.lastShown, e.getKey(), e.getValue());
		}
		out.jobroles = l.jobroles;
		return out;
	}

	/** A question resolved against canonical content, or the HTTP status + error word that rejects the request. */
	public static class Resolved
	{
		public Question question;
		public int status;
		public String error;
		public Content content; // what the question was resolved against (set by callers that need it afterwards)
		public Learner learner;

		static Resolved fail(int inStatus, String inError)
		{
			Resolved r = new Resolved();
			r.status = inStatus;
			r.error = inError;
			return r;
		}
	}

	/**
	 * Server check behind answer.json and exposure.json; client values are never stored as sent. In order:
	 * mode is learn|dailychallenge|improve; the question is in c (learner-visible topics, evaluation-reserved excluded);
	 * each claimed hierarchy value (topicid, tutorialid, sectionid, componentid; null = not claimed) equals the canonical one;
	 * every mode needs the sessionid next.json issued:
	 * dailychallenge takes no scope, the session is the learner's stored set for today's org-local date and must contain the question;
	 * learn and improve need scopetype topic|subtopic + scopeid naming the question's own topic / section; learn needs the subtopic
	 * unlocked, improve that scope's learn sequence complete; then a learningsession of this learner, mode and scope, not expired,
	 * containing the question; a learn answer (inAnswer) also needs the question not yet answered in learn|dailychallenge and to be
	 * the first such question of the session.
	 */
	public Resolved resolve(Content c, Learner l, String inMode, String inScopetype, String inScopeid, String inQuestionid, Map<String, String> inClaimed,
		String inSessionid, boolean inAnswer)
	{
		if (inMode == null)
		{
			return Resolved.fail(400, "missing_mode");
		}
		if (!MODES.contains(inMode))
		{
			return Resolved.fail(400, "bad_mode");
		}
		if (inQuestionid == null)
		{
			return Resolved.fail(400, "missing_questionid");
		}
		Question q = c.questions.get(inQuestionid);
		if (q == null)
		{
			return Resolved.fail(404, "unknown_question");
		}
		String[][] canonical = {{"topicid", q.topicid}, {"tutorialid", q.tutorialid}, {"sectionid", q.sectionid}, {"componentid", q.componentid}};
		for (String[] e : canonical)
		{
			String claimed = inClaimed == null ? null : inClaimed.get(e[0]);
			if (claimed != null && !claimed.equals(e[1]))
			{
				return Resolved.fail(409, "hierarchy_mismatch");
			}
		}
		if ("dailychallenge".equals(inMode))
		{
			if (inScopetype != null || inScopeid != null)
			{
				return Resolved.fail(400, "scope_not_allowed");
			}
			if (inSessionid == null)
			{
				return Resolved.fail(400, "missing_sessionid");
			}
			String today = challengeId(l.userid, challengeDate(new Date(), (java.time.ZoneId) orgZone()[0]));
			if (!today.equals(inSessionid))
			{
				return Resolved.fail(409, inSessionid.startsWith(l.userid + "_") ? "session_expired" : "unknown_session");
			}
			if (!todaysChallengeQuestions(l.userid).contains(q.id))
			{
				return Resolved.fail(409, "not_in_daily_challenge");
			}
		}
		else
		{
			if (inScopetype == null || inScopeid == null)
			{
				return Resolved.fail(400, "missing_scope");
			}
			List<Question> scope;
			if ("topic".equals(inScopetype))
			{
				Topic t = c.topics.get(inScopeid);
				if (t == null)
				{
					return Resolved.fail(404, "unknown_scope");
				}
				scope = t.questions;
			}
			else if ("subtopic".equals(inScopetype))
			{
				Section s = c.sections.get(inScopeid);
				if (s == null)
				{
					return Resolved.fail(404, "unknown_scope");
				}
				scope = s.questions;
			}
			else
			{
				return Resolved.fail(400, "bad_scopetype");
			}
			if (!inScopeid.equals("topic".equals(inScopetype) ? q.topicid : q.sectionid))
			{
				return Resolved.fail(409, "scope_mismatch");
			}
			if ("learn".equals(inMode) && !isUnlocked(subtopicStates(c, l), q.sectionid))
			{
				return Resolved.fail(409, "subtopic_locked");
			}
			if ("improve".equals(inMode) && !l.answeredInSequence.containsAll(ids(scope)))
			{
				return Resolved.fail(409, "improve_locked");
			}
			if (inSessionid == null)
			{
				return Resolved.fail(400, "missing_sessionid");
			}
			Session s = loadSession(inSessionid);
			if (s == null)
			{
				return Resolved.fail(404, "unknown_session");
			}
			if (!l.userid.equals(s.user) || !inMode.equals(s.mode) || !inScopetype.equals(s.scopetype) || !inScopeid.equals(s.scopeid))
			{
				return Resolved.fail(409, "session_mismatch");
			}
			if (s.expires != null && s.expires.before(new Date()))
			{
				return Resolved.fail(409, "session_expired");
			}
			if (!s.questions.contains(q.id))
			{
				return Resolved.fail(409, "not_in_session");
			}
			if ("learn".equals(inMode) && inAnswer)
			{
				if (l.answeredInSequence.contains(q.id))
				{
					return Resolved.fail(409, "already_answered");
				}
				for (String id : s.questions)
				{
					if (c.questions.containsKey(id) && !l.answeredInSequence.contains(id))
					{
						if (!id.equals(q.id))
						{
							return Resolved.fail(409, "out_of_order"); // the next unanswered question of the session comes first
						}
						break;
					}
				}
			}
		}
		Resolved r = new Resolved();
		r.question = q;
		r.status = 200;
		return r;
	}

	/**
	 * Unanswered questions of the scope in unlocked subtopics (inStates from subtopicStates; callers reject a locked inSection).
	 * lockedquestions = unanswered questions held back by locked subtopics; complete only when none remain anywhere in scope.
	 * No items but locked questions = blocked: blockedreason / blockedsubtopicid name the first locked subtopic, previoussubtopicid the one
	 * whose condition is unmet (its band = currentband), and recommendedmode + recommendedscope what to do about it (improve that
	 * subtopic when only mastery is missing, else learn it).
	 */
	public static JSONObject learnNext(Topic inTopic, Section inSection, Learner l, Map<String, JSONObject> inStates)
	{
		JSONArray items = new JSONArray();
		int locked = 0;
		String firstLocked = null;
		for (Question q : inSection != null ? inSection.questions : inTopic.questions)
		{
			if (l.answeredInSequence.contains(q.id))
			{
				continue;
			}
			if (isUnlocked(inStates, q.sectionid))
			{
				items.add(item(q, "new", false));
			}
			else
			{
				locked++;
				firstLocked = firstLocked == null ? q.sectionid : firstLocked;
			}
		}
		JSONObject o = next("learn", items.isEmpty() && locked == 0, items);
		o.put("lockedquestions", locked);
		boolean blocked = items.isEmpty() && locked > 0;
		o.put("blocked", blocked);
		if (blocked)
		{
			JSONObject st = inStates.get(firstLocked);
			String reason = (String) st.get("unlockreason");
			String prev = (String) st.get("previoussubtopicid");
			o.put("blockedreason", reason);
			o.put("blockedsubtopicid", firstLocked);
			o.put("previoussubtopicid", prev);
			o.put("requiredlevel", inTopic.requiredlevel);
			o.put("currentband", st.get("previousband"));
			boolean improve = !"previous_subtopic_incomplete".equals(reason);
			o.put("recommendedmode", improve ? "improve" : "learn");
			JSONObject scope = new JSONObject();
			scope.put("scopetype", "subtopic");
			scope.put("scopeid", prev);
			o.put("recommendedscope", scope);
		}
		return o;
	}

	/** Null when the scope's Improve is locked (learn not complete). */
	public JSONObject improveNext(Topic inTopic, Section inSection, Learner l, int inSize)
	{
		List<Question> scope = scope(inTopic, inSection);
		if (!l.answeredInSequence.containsAll(ids(scope)))
		{
			return null;
		}
		String band = mastery(scope, l, inTopic.competentmin, inTopic.expertmin).band;
		List<Question> sorted = new ArrayList<>(scope);
		sorted.sort(improveOrder(l, q -> band, null)); // one topic: the required tiebreak is constant
		JSONArray items = new JSONArray();
		for (Question q : sorted.subList(0, Math.min(inSize, sorted.size())))
		{
			items.add(item(q, reasonOf(q, l, band), false));
		}
		return next("improve", items.isEmpty(), items);
	}

	public static final int DC_DEFAULT_MIN = 5, DC_DEFAULT_MAX = 20, DC_SAFE_MAX = 50;

	/**
	 * Org timezone for the challenge day: catalog setting testu_timezone (IANA id, e.g. America/Lima). Missing or invalid = UTC.
	 * Returns {ZoneId, reason}; reason null when the setting was used.
	 */
	public Object[] orgZone()
	{
		String tz = setting("testu_timezone");
		if (tz == null || tz.trim().isEmpty())
		{
			return new Object[] {java.time.ZoneId.of("UTC"), "timezone_not_configured"};
		}
		try
		{
			return new Object[] {java.time.ZoneId.of(tz.trim()), null};
		}
		catch (Exception e)
		{
			return new Object[] {java.time.ZoneId.of("UTC"), "timezone_invalid"};
		}
	}

	/** Catalog setting read from the table, not MediaArchive's settings cache, so an admin change applies on the next request. */
	protected String setting(String inId)
	{
		Data d = fieldArchive.getCatalogSetting(inId);
		return d == null ? null : d.get("value");
	}

	/** Local challenge date of inNow in inZone; the set id is <user>_<yyyyMMdd of that date>. One rule for build, read and delete. */
	public static java.time.LocalDate challengeDate(Date inNow, java.time.ZoneId inZone)
	{
		return inNow.toInstant().atZone(inZone).toLocalDate();
	}

	public static String challengeId(String inUserid, java.time.LocalDate inDate)
	{
		return inUserid + "_" + inDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
	}

	/** {min, max, reason}: min >= 1, max >= min, both <= DC_SAFE_MAX; otherwise defaults 5 / 20 and the reason. */
	public static Object[] dcSizes(String inMin, String inMax)
	{
		int min = intOr(inMin, DC_DEFAULT_MIN);
		int max = intOr(inMax, DC_DEFAULT_MAX);
		String reason = min < 1 ? "min_below_1" : max < min ? "max_below_min" : max > DC_SAFE_MAX ? "above_safe_max" : null;
		return reason == null ? new Object[] {min, max, null} : new Object[] {DC_DEFAULT_MIN, DC_DEFAULT_MAX, reason};
	}

	/** Question ids of the learner's stored challenge for today's org-local date; empty when none was built. Never builds one. */
	public Set<String> todaysChallengeQuestions(String inUserid)
	{
		Set<String> ids = new HashSet<>();
		Data set = (Data) fieldArchive.getSearcher("dailychallengeset").searchById(challengeId(inUserid, challengeDate(new Date(), (java.time.ZoneId) orgZone()[0])));
		Object parsed = set == null ? null : JSONValue.parse(String.valueOf(set.get("questionlist")));
		if (parsed instanceof List)
		{
			for (Object o : (List) parsed)
			{
				if (o instanceof Map && ((Map) o).get("questionid") != null)
				{
					ids.add(String.valueOf(((Map) o).get("questionid")));
				}
			}
		}
		return ids;
	}

	/** Today's stored set, or a new dc-v0 set saved for today. Items answered since the set was built are done. */
	public JSONObject dailyChallenge(Content c, Learner l)
	{
		Searcher searcher = fieldArchive.getSearcher("dailychallengeset");
		Date now = new Date();
		Object[] zone = orgZone();
		java.time.LocalDate day = challengeDate(now, (java.time.ZoneId) zone[0]);
		String id = challengeId(l.userid, day);
		Data set;
		List<Map> stored = new ArrayList<>();
		Date created;
		String version;
		synchronized (WRITE_LOCK) // create-only: a set, once saved, is never rebuilt for the day
		{
			set = (Data) searcher.searchById(id);
			if (set != null)
			{
				Object parsed = JSONValue.parse(String.valueOf(set.get("questionlist")));
				if (parsed instanceof List)
				{
					for (Object o : (List) parsed)
					{
						if (o instanceof Map)
						{
							stored.add((Map) o);
						}
					}
				}
				created = DateStorageUtil.getStorageUtil().parseFromObject(set.getValue("datecreated"));
				version = set.get("algorithmversion");
			}
			else
			{
				String[] reason = new String[1];
				Map<String, Boolean> required = requiredTopics(c, l, reason);
				Object[] sizes = dcSizes(setting("testu_dailychallenge_min"), setting("testu_dailychallenge_max"));
				boolean enrichment = "true".equals(setting("testu_dailychallenge_enrichment"));
				Set<String> locked = new HashSet<>(locked(subtopicStates(c, l)));
				locked.addAll(lockedTopicSections(c));
				JSONObject built = buildDailyChallenge(c, l, now, required, enrichment, (Integer) sizes[0], (Integer) sizes[1], locked);
				JSONObject inputs = (JSONObject) built.get("inputs");
				inputs.put("lockedsubtopics", locked.size());
				JSONObject progression = new JSONObject(); // policy context of the lock decisions, kept for later explanation
				for (Topic t : c.topics.values())
				{
					JSONObject p = new JSONObject();
					p.put("policy", t.policy);
					p.put("requiredlevel", t.requiredlevel);
					p.put("policyversion", t.policyversion);
					p.put("policyreason", t.policyreason);
					progression.put(t.id, p);
				}
				inputs.put("progression", progression);
				JSONArray lockedIds = new JSONArray();
				lockedIds.addAll(new java.util.TreeSet<>(locked));
				inputs.put("lockedsubtopicids", lockedIds);
				inputs.put("topicsreason", reason[0]);
				inputs.put("min", sizes[0]);
				inputs.put("max", sizes[1]);
				inputs.put("sizesreason", sizes[2]);
				inputs.put("timezonereason", zone[1]);
				stored.addAll((List) built.get("items"));
				set = searcher.createNewData();
				set.setId(id);
				set.setValue("user", l.userid);
				set.setValue("localdate", day.toString()); // org-local challenge date, yyyy-MM-dd
				set.setValue("timezone", ((java.time.ZoneId) zone[0]).getId());
				set.setValue("day", Date.from(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())); // same date as a UTC-midnight date value
				set.setValue("questionlist", ((JSONArray) built.get("items")).toJSONString());
				set.setValue("algorithmversion", DC_VERSION);
				set.setValue("inputs", inputs.toJSONString());
				set.setValue("datecreated", now); // generation instant; eMe stores dates as UTC
				searcher.saveData(set, null);
				created = now;
				version = DC_VERSION;
			}
		}
		JSONArray items = new JSONArray();
		boolean alldone = true;
		int newcount = 0;
		for (Map o : stored)
		{
			Question q = c.questions.get(String.valueOf(o.get("questionid")));
			if (q == null)
			{
				continue; // question removed or evaluation-reserved since the set was built
			}
			boolean done = false;
			for (Attempt a : l.attempts)
			{
				// only a Daily Challenge answer completes a Daily Challenge item (a Learn answer to the same question does not)
				if (q.id.equals(a.questionid) && "dailychallenge".equals(a.mode) && a.at != null && (created == null || !a.at.before(created)))
				{
					done = true;
					break;
				}
			}
			alldone &= done;
			JSONObject it = item(q, String.valueOf(o.get("reason")), done);
			String bucket = o.get("bucket") != null ? String.valueOf(o.get("bucket")) : "new".equals(o.get("reason")) ? "new" : "reinforcement";
			it.put("bucket", bucket);
			newcount += "new".equals(bucket) ? 1 : 0;
			items.add(it);
		}
		JSONObject resp = next("dailychallenge", alldone, items);
		resp.put("newcount", newcount);
		resp.put("reinforcementcount", items.size() - newcount);
		resp.put("algorithmversion", version);
		resp.put("sessionid", id);
		resp.put("localdate", day.toString());
		resp.put("timezone", ((java.time.ZoneId) zone[0]).getId());
		return resp;
	}

	/**
	 * Daily Challenge selection dc-v0 (pure: no storage reads). Returns {"items": [...with bucket + reason], "inputs": {U, R, n, s, ...}}.
	 * inRequired: topic id -> required (callers apply the no-requirements fallback). inLocked: section ids whose unanswered questions
	 * must not be introduced (locked subtopics); null = none.
	 */
	public static JSONObject buildDailyChallenge(Content c, Learner l, Date inNow, Map<String, Boolean> inRequired, boolean inEnrichment, int inMin, int inMax, Set<String> inLocked)
	{
		Set<String> lockedSections = inLocked == null ? Collections.emptySet() : inLocked;
		List<Topic> eligible = new ArrayList<>();
		Map<String, String> bandByTopic = new HashMap<>();
		List<Question> newPool = new ArrayList<>();
		List<Question> reinfPool = new ArrayList<>();
		int barely = 0, topicsWithQuestions = 0;
		for (Topic t : c.topics.values())
		{
			boolean required = Boolean.TRUE.equals(inRequired.get(t.id));
			if (!required && !inEnrichment)
			{
				continue;
			}
			eligible.add(t);
			bandByTopic.put(t.id, mastery(t.questions, l, t.competentmin, t.expertmin).band);
			int answered = 0;
			for (Question q : t.questions)
			{
				if (l.answeredInSequence.contains(q.id))
				{
					reinfPool.add(q);
					answered++;
				}
				else if (!lockedSections.contains(q.sectionid))
				{
					newPool.add(q);
				}
			}
			topicsWithQuestions += t.questions.isEmpty() ? 0 : 1;
			if (required && !t.questions.isEmpty() && answered < 0.1 * t.questions.size())
			{
				barely++;
			}
		}
		int total = newPool.size() + reinfPool.size();
		double sumRisk = 0;
		for (Question q : reinfPool)
		{
			sumRisk += risk(q, l);
		}
		double u = total == 0 ? 0 : 0.5 * clamp(newPool.size() / (double) total + 0.1 * barely, 0, 1);
		double r = reinfPool.isEmpty() ? 0 : 0.5 * clamp(sumRisk / reinfPool.size(), 0, 1);
		List<Attempt> last20 = l.learningAttempts.subList(Math.max(0, l.learningAttempts.size() - 20), l.learningAttempts.size());
		int hci20 = 0;
		for (Attempt a : last20)
		{
			hci20 += isHci(a) ? 1 : 0;
		}
		int recent = last20.size();
		boolean remediation = l.unresolvedHci.size() >= 3 || (recent >= 8 && hci20 >= Math.max(3, (int) Math.ceil(recent * 0.25)));
		int n = Math.min(total, inMin + (int) Math.round((inMax - inMin) * clamp(u + r, 0, 1)));
		double share = clamp(0.5 + u - r, 0.2, 0.7);
		if (remediation)
		{
			share = clamp(share, 0, 0.2);
		}
		if (reinfPool.isEmpty())
		{
			share = 1.0; // cold start
		}
		else if (newPool.isEmpty())
		{
			share = 0;
		}
		int newTarget = (int) Math.round(n * share);
		if (!newPool.isEmpty() && !remediation)
		{
			newTarget = Math.max(newTarget, 1);
		}
		if (!reinfPool.isEmpty())
		{
			newTarget = Math.min(newTarget, n - 1);
		}
		int maxNew = (int) Math.ceil(n * 0.70); // hard cap; exceeded only by fill step 4 (reinforcement exhausted)
		newTarget = Math.max(0, Math.min(Math.min(newTarget, n), maxNew));

		Selection sel = new Selection(eligible, l, inNow, inRequired, inEnrichment, topicsWithQuestions > 1 ? n / 2 : Integer.MAX_VALUE, lockedSections);
		List<Question> reinfOrder = new ArrayList<>(reinfPool);
		reinfOrder.sort(improveOrder(l, q -> bandByTopic.get(q.topicid), inRequired));
		Set<String> relaxations = new java.util.LinkedHashSet<>();
		// Fill order: (1) new target, then reinforcement for the rest (a new-bucket shortfall widens reinforcement), cooldown + cap;
		// (2) reinforcement, cooldown relaxed; (3) reinforcement, topic cap relaxed; (4) only then new fills what is left
		// (cap kept, then relaxed), which is the only way new can exceed maxNew. Every relaxation is recorded.
		sel.pickNew(newTarget, true);
		sel.pickReinf(reinfOrder, n - sel.size(), true, true, remediation);
		sel.pickReinf(reinfOrder, n - sel.size(), false, true, remediation);
		sel.pickReinf(reinfOrder, n - sel.size(), false, false, remediation);
		int newBefore = sel.news.size();
		sel.pickNew(n - sel.size(), true);
		sel.pickNew(n - sel.size(), false);
		if (sel.usedCooldown)
		{
			relaxations.add("cooldown");
		}
		if (sel.usedTopicCap)
		{
			relaxations.add("topiccap");
		}
		if (sel.news.size() > newBefore)
		{
			relaxations.add("newfill");
		}
		if (sel.news.size() > maxNew)
		{
			relaxations.add("newcap");
		}

		boolean[] isNew = interleave(sel.news.size(), sel.reinfs.size(), remediation);
		JSONArray items = new JSONArray();
		int ni = 0, ri = 0;
		for (boolean nw : isNew)
		{
			Question q = nw ? sel.news.get(ni++) : sel.reinfs.get(ri++);
			JSONObject it = item(q, nw ? "new" : reasonOf(q, l, bandByTopic.get(q.topicid)), false);
			it.put("bucket", nw ? "new" : "reinforcement");
			items.add(it);
		}
		JSONObject inputs = new JSONObject();
		inputs.put("U", u);
		inputs.put("R", r);
		inputs.put("n", n);
		inputs.put("s", share);
		inputs.put("newtarget", newTarget);
		inputs.put("maxnew", maxNew);
		inputs.put("reinforcementtarget", n - newTarget);
		inputs.put("remediation", remediation);
		inputs.put("unresolvedhci", l.unresolvedHci.size());
		inputs.put("enrichment", inEnrichment);
		JSONArray relaxed = new JSONArray();
		relaxed.addAll(relaxations);
		inputs.put("relaxations", relaxed);
		JSONObject flags = new JSONObject();
		for (String f : new String[] {"pace", "deadlines", "criticalSkills", "retention", "evaluationFailure", "availableTime"})
		{
			flags.put(f, false);
		}
		inputs.put("flags", flags);
		JSONObject out = new JSONObject();
		out.put("items", items);
		out.put("inputs", inputs);
		return out;
	}

	/** Slot filling state shared by the new and reinforcement buckets (the topic cap counts all slots). */
	static class Selection
	{
		final List<Topic> topics;
		final Learner l;
		final Date now;
		final Map<String, Boolean> required;
		final boolean enrichment;
		final int cap;
		final Set<String> lockedSections;
		final List<Question> news = new ArrayList<>(), reinfs = new ArrayList<>();
		final Set<String> taken = new HashSet<>();
		final Map<String, Integer> perTopic = new HashMap<>(), answered = new HashMap<>(), cursor = new HashMap<>();
		final Map<String, Double> days = new HashMap<>();
		boolean usedCooldown, usedTopicCap; // set when an item is taken that the cooldown / topic cap would have blocked

		Selection(List<Topic> inTopics, Learner inLearner, Date inNow, Map<String, Boolean> inRequired, boolean inEnrichment, int inCap, Set<String> inLocked)
		{
			lockedSections = inLocked;
			topics = inTopics;
			l = inLearner;
			now = inNow;
			required = inRequired;
			enrichment = inEnrichment;
			cap = inCap;
			Map<String, Date> firstInSequence = new HashMap<>();
			for (Attempt a : l.attempts)
			{
				if (a.questionid != null && a.at != null && ("learn".equals(a.mode) || "dailychallenge".equals(a.mode)))
				{
					firstInSequence.putIfAbsent(a.questionid, a.at); // attempts are oldest first
				}
			}
			for (Topic t : topics)
			{
				int count = 0;
				Date lastNew = null;
				for (Question q : t.questions)
				{
					Date first = firstInSequence.get(q.id);
					if (l.answeredInSequence.contains(q.id))
					{
						count++;
						if (first != null && (lastNew == null || first.after(lastNew)))
						{
							lastNew = first;
						}
					}
				}
				answered.put(t.id, count);
				days.put(t.id, lastNew == null ? 7.0 : Math.floor((now.getTime() - lastNew.getTime()) / 86400000.0));
				cursor.put(t.id, 0);
			}
		}

		int size()
		{
			return news.size() + reinfs.size();
		}

		/** New slots one at a time: best topic score, its next non-learning-answered question outside locked subtopics (never skip ahead otherwise), then re-score. */
		int pickNew(int inCount, boolean inCap)
		{
			int picked = 0;
			for (int k = 0; k < inCount; k++)
			{
				Topic best = null;
				double bestScore = -1;
				for (Topic t : topics)
				{
					int i = cursor.get(t.id);
					while (i < t.questions.size() && (l.answeredInSequence.contains(t.questions.get(i).id) || lockedSections.contains(t.questions.get(i).sectionid)))
					{
						i++;
					}
					cursor.put(t.id, i);
					if (i >= t.questions.size() || (inCap && perTopic.getOrDefault(t.id, 0) >= cap))
					{
						continue;
					}
					double gap = 1 - answered.get(t.id) / (double) t.questions.size();
					double score = (enrichment && Boolean.TRUE.equals(required.get(t.id)) ? 1.5 : 1) * (0.6 * gap + 0.4 * Math.min(days.get(t.id), 7) / 7);
					if (score > bestScore)
					{
						best = t;
						bestScore = score;
					}
				}
				if (best == null)
				{
					return picked;
				}
				picked++;
				usedTopicCap |= perTopic.getOrDefault(best.id, 0) >= cap;
				int i = cursor.get(best.id);
				Question q = best.questions.get(i);
				news.add(q);
				taken.add(q.id);
				cursor.put(best.id, i + 1);
				answered.merge(best.id, 1, Integer::sum);
				days.put(best.id, 0.0);
				perTopic.merge(best.id, 1, Integer::sum);
			}
			return picked;
		}

		/** Reinforcement slots in Improve order; cooldown = last shown in a learning mode within 48 h (unresolved HCI exempt during remediation). */
		int pickReinf(List<Question> inOrder, int inCount, boolean inCooldown, boolean inCap, boolean inRemediation)
		{
			int picked = 0;
			for (Question q : inOrder)
			{
				if (picked >= inCount)
				{
					break;
				}
				if (taken.contains(q.id) || (inCap && perTopic.getOrDefault(q.topicid, 0) >= cap))
				{
					continue;
				}
				Date shown = l.lastShown.get(q.id);
				boolean cooling = shown != null && now.getTime() - shown.getTime() < COOLDOWN_MS && !(inRemediation && l.unresolvedHci.contains(q.id));
				if (inCooldown && cooling)
				{
					continue;
				}
				usedCooldown |= cooling;
				usedTopicCap |= perTopic.getOrDefault(q.topicid, 0) >= cap;
				reinfs.add(q);
				taken.add(q.id);
				perTopic.merge(q.topicid, 1, Integer::sum);
				picked++;
			}
			return picked;
		}
	}

	/**
	 * Ratio-aware order (spec "Order"): n = total items, k = minority bucket size (reinforcement on a tie). Minority item j (0-based)
	 * goes to 0-based position floor((j + 1) x (n + 1) / (k + 1) + 0.5) - 1, clamped to [0, n - 1]; a taken position moves to the next
	 * free one (wrapping). Majority fills the rest in order. Remediation: position 0 must be reinforcement (swap with the first one).
	 * 7 new / 3 reinforcement -> N N R N N R N R N N. true = new.
	 */
	public static boolean[] interleave(int inNew, int inReinf, boolean inRemediation)
	{
		int n = inNew + inReinf;
		boolean[] isNew = new boolean[n];
		if (inReinf == 0 || inNew == 0)
		{
			java.util.Arrays.fill(isNew, inReinf == 0);
			return isNew;
		}
		boolean minorityNew = inNew < inReinf;
		int k = Math.min(inNew, inReinf);
		boolean[] minority = new boolean[n];
		for (int j = 0; j < k; j++)
		{
			int pos = Math.max(0, Math.min(n - 1, (int) Math.floor((j + 1) * (n + 1) / (double) (k + 1) + 0.5) - 1));
			while (minority[pos])
			{
				pos = (pos + 1) % n;
			}
			minority[pos] = true;
		}
		for (int i = 0; i < n; i++)
		{
			isNew[i] = minority[i] == minorityNew;
		}
		if (inRemediation && isNew[0])
		{
			for (int i = 1; i < n; i++)
			{
				if (!isNew[i])
				{
					isNew[0] = false;
					isNew[i] = true;
					break;
				}
			}
		}
		return isNew;
	}

	private static double clamp(double v, double lo, double hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	private static JSONObject next(String inMode, boolean inComplete, JSONArray inItems)
	{
		JSONObject o = new JSONObject();
		o.put("ok", Boolean.TRUE);
		o.put("mode", inMode);
		o.put("complete", inComplete);
		o.put("total", inItems.size());
		o.put("items", inItems);
		return o;
	}

	// ---------------------------------------------------------------- state

	/** Without subtopic unlock state (admin analytics). */
	public JSONObject topicState(Topic t, Learner l)
	{
		return topicState(t, l, null);
	}

	/** inStates: subtopicStates(c, l); each section then carries its unlock state and nextquestionid skips locked subtopics. */
	public JSONObject topicState(Topic t, Learner l, Map<String, JSONObject> inStates)
	{
		Mastery m = mastery(t.questions, l, t.competentmin, t.expertmin);
		JSONObject o = new JSONObject();
		o.put("id", t.id);
		o.put("title", t.title);
		o.put("questions", m.questions);
		o.put("answered", m.answered);
		o.put("learncomplete", m.answered == m.questions);
		o.put("improveavailable", m.answered == m.questions);
		o.put("masterypercent", m.percent);
		o.put("band", m.band);
		o.put("competentmin", t.competentmin);
		o.put("expertmin", t.expertmin);
		o.put("thresholdsreason", t.thresholdsreason);
		String required = requiredLevel(t.id, l.jobroles);
		o.put("requiredlevel", required);
		o.put("meetsrequirement", required == null ? null : levelIndex(m.band) >= levelIndex(required) && (!"expert".equals(required) || m.evidence));
		o.put("expertevidence", evidence(m));
		String nextid = null;
		Date last = null;
		for (Question q : t.questions)
		{
			if (nextid == null && !l.answeredInSequence.contains(q.id) && isUnlocked(inStates, q.sectionid))
			{
				nextid = q.id;
			}
		}
		o.put("subtopicunlockpolicy", t.policy);
		o.put("subtopicrequiredlevel", t.requiredlevel);
		o.put("subtopicpolicyversion", t.policyversion);
		o.put("subtopicpolicyreason", t.policyreason);
		Set<String> topicIds = ids(t.questions);
		for (Attempt a : l.attempts)
		{
			if (topicIds.contains(a.questionid) && a.at != null && (last == null || a.at.after(last)))
			{
				last = a.at;
			}
		}
		o.put("nextquestionid", nextid);
		o.put("lastactivity", iso(last));
		JSONArray sections = new JSONArray();
		for (Section s : t.sections)
		{
			Mastery sm = mastery(s.questions, l, t.competentmin, t.expertmin);
			JSONObject so = new JSONObject();
			so.put("id", s.id);
			so.put("title", s.title);
			so.put("tutorialid", s.tutorialid);
			so.put("questions", sm.questions);
			so.put("answered", sm.answered);
			so.put("learncomplete", sm.answered == sm.questions);
			so.put("improveavailable", sm.answered == sm.questions);
			so.put("masterypercent", sm.percent);
			so.put("band", sm.band);
			so.put("expertevidence", evidence(sm));
			JSONObject st = inStates == null ? null : inStates.get(s.id);
			if (st != null)
			{
				so.putAll(st); // same id
			}
			sections.add(so);
		}
		o.put("sections", sections);
		return o;
	}

	private static JSONObject evidence(Mastery m)
	{
		JSONObject e = new JSONObject();
		e.put("ok", m.evidence);
		if (!m.evidence)
		{
			e.put("reason", m.evidencereason);
		}
		return e;
	}

	public static String iso(Date d)
	{
		if (d == null)
		{
			return null;
		}
		SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		return f.format(d);
	}

	// ---------------------------------------------------------------- stored tutormastery (computemastery event, pass 1)

	/**
	 * Full rebuild of tutormastery: one row per user x section (id user_section) and user x topic (id user_topic_topicid,
	 * blank componentsection) the user ever attempted. Legacy counters kept for existing consumers; level = band.
	 * Returns {rows saved, answers read, stale rows pruned}.
	 */
	public int[] recomputeMastery()
	{
		Content c = loadContent();
		Map<String, List<Attempt>> byUser = new HashMap<>();
		int read = 0;
		HitTracker answers = fieldArchive.query("tutoranswer").all().search();
		answers.enableBulkOperations();
		for (Object o : answers)
		{
			Data a = (Data) o;
			read++;
			String user = a.get("user");
			if (user != null && !user.isEmpty())
			{
				byUser.computeIfAbsent(user, k -> new ArrayList<>()).add(attemptOf(a));
			}
		}
		Searcher searcher = fieldArchive.getSearcher("tutormastery");
		Date now = new Date();
		List<Data> tosave = new ArrayList<>();
		Set<String> keep = new HashSet<>();
		for (Map.Entry<String, List<Attempt>> e : byUser.entrySet())
		{
			Learner l = learner(e.getKey(), e.getValue());
			for (Topic t : c.topics.values())
			{
				for (Section s : t.sections)
				{
					Data row = masteryRow(searcher, l.userid + "_" + s.id, s.questions, l, t, now);
					if (row != null)
					{
						row.setValue("componentsection", s.id);
						row.setValue("entitytutorial", s.tutorialid);
						tosave.add(row);
						keep.add(row.getId());
					}
				}
				Data row = masteryRow(searcher, l.userid + "_topic_" + t.id, t.questions, l, t, now);
				if (row != null)
				{
					tosave.add(row);
					keep.add(row.getId());
				}
			}
		}
		if (!tosave.isEmpty())
		{
			searcher.saveAllData(tosave, null);
		}
		// ponytail: full diff every run; derived data, a wrong delete self-heals on the next run.
		List<Data> todelete = new ArrayList<>();
		HitTracker existing = searcher.query().all().search();
		existing.enableBulkOperations();
		for (Object o : existing)
		{
			Data row = (Data) o;
			if (!keep.contains(row.getId()))
			{
				todelete.add(row);
			}
		}
		if (!todelete.isEmpty())
		{
			searcher.deleteAll(todelete, null);
		}
		saveMasteryHistory(c, byUser);
		return new int[] {tosave.size(), read, todelete.size()};
	}

	/**
	 * Full rebuild of tutormasteryday: the topic band history, one row per user x topic x day the band changed
	 * (id user_topicid_yyyyMMdd, server timezone). Replayed from the attempts, so history reaches back to the first
	 * answer. The band on a day is the latest row on or before it. Forecasts read this.
	 * ponytail: replays every active day each run; go incremental when answers reach the 100k range.
	 */
	public int saveMasteryHistory(Content c, Map<String, List<Attempt>> byUser)
	{
		Searcher searcher = fieldArchive.getSearcher("tutormasteryday");
		SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd");
		List<Data> tosave = new ArrayList<>();
		Set<String> keep = new HashSet<>();
		for (Map.Entry<String, List<Attempt>> e : byUser.entrySet())
		{
			Learner all = learner(e.getKey(), e.getValue()); // sorted, oldest first
			Map<String, String> band = new HashMap<>(); // topic -> band so far
			List<Attempt> sorted = all.attempts;
			for (int i = 0; i < sorted.size(); i++)
			{
				Attempt a = sorted.get(i);
				if (a.at == null)
				{
					continue;
				}
				String day = dayKey.format(a.at);
				if (i + 1 < sorted.size() && sorted.get(i + 1).at != null && day.equals(dayKey.format(sorted.get(i + 1).at)))
				{
					continue; // evaluate once, at the day's last attempt
				}
				Set<String> touched = new HashSet<>();
				for (int j = i; j >= 0 && sorted.get(j).at != null && day.equals(dayKey.format(sorted.get(j).at)); j--)
				{
					Question q = c.questions.get(sorted.get(j).questionid);
					if (q != null)
					{
						touched.add(q.topicid);
					}
				}
				if (touched.isEmpty())
				{
					continue;
				}
				Learner upto = learner(e.getKey(), sorted.subList(0, i + 1));
				for (String topicid : touched)
				{
					Topic t = c.topics.get(topicid);
					if (t == null)
					{
						continue;
					}
					String now = mastery(t.questions, upto, t.competentmin, t.expertmin).band;
					if (now == null || now.equals(band.get(topicid)))
					{
						continue;
					}
					band.put(topicid, now);
					String id = e.getKey() + "_" + topicid + "_" + day;
					Data row = (Data) searcher.searchById(id);
					if (row == null)
					{
						row = searcher.createNewData();
						row.setId(id);
					}
					row.setValue("user", e.getKey());
					row.setValue("entitytopic", topicid);
					row.setValue("day", a.at);
					row.setValue("level", now);
					tosave.add(row);
					keep.add(id);
				}
			}
		}
		if (!tosave.isEmpty())
		{
			searcher.saveAllData(tosave, null);
		}
		List<Data> todelete = new ArrayList<>();
		HitTracker existing = searcher.query().all().search();
		existing.enableBulkOperations();
		for (Object o : existing)
		{
			if (!keep.contains(((Data) o).getId()))
			{
				todelete.add((Data) o);
			}
		}
		if (!todelete.isEmpty())
		{
			searcher.deleteAll(todelete, null);
		}
		return tosave.size();
	}

	private Data masteryRow(Searcher inSearcher, String inId, List<Question> inScope, Learner l, Topic t, Date inNow)
	{
		Set<String> ids = ids(inScope);
		int attempts = 0, correct = 0, cc = 0, cw = 0, uc = 0, uw = 0, answered = 0, mastered = 0;
		Date last = null;
		for (Attempt a : l.attempts)
		{
			if (!ids.contains(a.questionid))
			{
				continue;
			}
			attempts++;
			boolean certain = "confident".equals(a.confidence) || "mostlysure".equals(a.confidence);
			if (a.correct)
			{
				correct++;
				if (certain)
					cc++;
				else
					uc++;
			}
			else if (certain)
				cw++;
			else
				uw++;
			if (a.at != null && (last == null || a.at.after(last)))
			{
				last = a.at;
			}
		}
		if (attempts == 0)
		{
			return null;
		}
		for (String id : ids)
		{
			Attempt a = l.latest.get(id);
			if (a != null)
			{
				answered++;
				if (a.correct)
					mastered++;
			}
		}
		Mastery m = mastery(inScope, l, t.competentmin, t.expertmin);
		Data row = (Data) inSearcher.searchById(inId);
		if (row == null)
		{
			row = inSearcher.createNewData();
			row.setId(inId);
		}
		row.setValue("user", l.userid);
		row.setValue("entitytopic", t.id);
		row.setValue("componentsection", null);
		row.setValue("entitytutorial", null);
		row.setValue("questions", inScope.size());
		row.setValue("answered", answered); // legacy: distinct questions with any attempt
		row.setValue("mastered", mastered); // legacy: of those, latest attempt correct
		row.setValue("attempts", attempts);
		row.setValue("correct", correct);
		row.setValue("certaincorrect", cc);
		row.setValue("certainwrong", cw);
		row.setValue("unsurecorrect", uc);
		row.setValue("unsurewrong", uw);
		row.setValue("masterypercent", m.percent);
		row.setValue("band", m.band);
		row.setValue("level", m.band);
		row.setValue("lastactivity", last);
		row.setValue("computedat", inNow);
		return row;
	}

	// ---------------------------------------------------------------- helpers

	public static Set<String> ids(Collection<Question> inQuestions)
	{
		Set<String> ids = new HashSet<>();
		for (Question q : inQuestions)
		{
			ids.add(q.id);
		}
		return ids;
	}

	public static int positiveOr(Object inValue, int inDefault)
	{
		int v = intOr(inValue, 0);
		return v > 0 ? v : inDefault;
	}

	public static int intOr(Object inValue, int inDefault)
	{
		if (inValue == null || inValue.toString().trim().isEmpty())
		{
			return inDefault;
		}
		try
		{
			return (int) Math.round(Double.parseDouble(inValue.toString().trim()));
		}
		catch (NumberFormatException e)
		{
			return inDefault;
		}
	}
}
