import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine;
import tech.genailabs.tutor.LearningEngine.Attempt;
import tech.genailabs.tutor.LearningEngine.Blueprint;
import tech.genailabs.tutor.LearningEngine.EvalAttempt;
import tech.genailabs.tutor.LearningEngine.Content;
import tech.genailabs.tutor.LearningEngine.Learner;
import tech.genailabs.tutor.LearningEngine.Question;
import tech.genailabs.tutor.LearningEngine.Section;
import tech.genailabs.tutor.LearningEngine.Topic;

/**
 * Pure checks of the Daily Challenge dc-v0 selection and Improve order (no server). Run by tools/check_learning.sh:
 * java -cp "build:plugins/system/lib/*:..." plugins/testu/tools/LearningEngineCheck.java
 */
public class LearningEngineCheck
{
	static final Date NOW = new Date();
	static int failures = 0;

	public static void main(String[] args)
	{
		ok("interleave 7 new / 3 reinforcement", pattern(LearningEngine.interleave(7, 3, false)).equals("NNRNNRNRNN"), pattern(LearningEngine.interleave(7, 3, false)));
		ok("interleave remediation starts with reinforcement", pattern(LearningEngine.interleave(7, 3, true)).charAt(0) == 'R', pattern(LearningEngine.interleave(7, 3, true)));

		// cold start: nothing answered -> 100% new, never skipping ahead, topic cap relaxed only to fill
		Content c = content();
		JSONObject dc = build(c, learner());
		ok("cold start all new", count(dc, "new") == size(dc) && size(dc) == 13, dc);
		ok("cold start never skips ahead", prefixes(dc), dc);
		ok("cold start: reinforcement exhausted -> new fills past maxNew (newfill, newcap, topiccap recorded)",
			relaxations(dc).containsAll(List.of("newfill", "newcap", "topiccap")) && maxPerTopic(dc) == 7 && count(dc, "new") > num(dc, "maxnew"), dc);
		ok("interleave 3 new / 7 reinforcement", pattern(LearningEngine.interleave(3, 7, false)).equals("RRNRRNRNRR"), pattern(LearningEngine.interleave(3, 7, false)));
		ok("interleave 5 / 5 (tie: reinforcement is minority)", pattern(LearningEngine.interleave(5, 5, false)).equals("NRNRNRRNRN"), pattern(LearningEngine.interleave(5, 5, false)));
		ok("interleave 11 new / 4 reinforcement", pattern(LearningEngine.interleave(11, 4, false)).equals("NNRNNRNNNRNNRNN"), pattern(LearningEngine.interleave(11, 4, false)));
		ok("interleave repeatable", pattern(LearningEngine.interleave(7, 3, false)).equals(pattern(LearningEngine.interleave(7, 3, false))), "");

		// evaluation-only answer keeps the question in the new bucket
		List<Attempt> at = new ArrayList<>();
		at.add(attempt("t1q1", "evaluation", true, "confident", 0, 3));
		dc = build(c, learner(at));
		ok("evaluation-only answer stays new", bucketOf(dc, "t1q1").equals("new") && count(dc, "reinforcement") == 0, dc);

		// nothing pending -> 100% reinforcement
		at = new ArrayList<>();
		for (Question q : c.questions.values())
		{
			at.add(attempt(q.id, "learn", true, "confident", 0, 120 + q.position));
		}
		dc = build(c, learner(at));
		ok("nothing pending all reinforcement", count(dc, "reinforcement") == size(dc) && size(dc) > 0, dc);

		// mixed: guardrails >= 1 of each
		at = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
		{
			at.add(attempt("t1q" + i, "learn", true, "confident", 0, 120 + i));
		}
		dc = build(c, learner(at));
		ok("guardrails: >= 1 new and >= 1 reinforcement", count(dc, "new") >= 1 && count(dc, "reinforcement") >= 1, dc);
		ok("mixed never skips ahead", prefixes(dc), dc);

		// remediation (a): 3 distinct unresolved HCI among 17 attempts (3 < ceil(17 x 0.25), so (b) is off); resolved by a confident unassisted correct answer
		at = new ArrayList<>();
		for (int i = 1; i <= 17; i++)
		{
			at.add(attempt("t1q" + (i <= 3 ? i : 4 + i % 7), "learn", i > 3, "confident", 0, 200 - i));
		}
		dc = build(c, learner(at));
		ok("(a) 3 unresolved HCI -> remediation, reinforcement first", remediation(c, at) && bucketAt(dc, 0).equals("reinforcement"), dc);
		at.add(attempt("t1q1", "improve", true, "notsure", 0, 90));
		ok("(a) unsure correct does not resolve", remediation(c, at), inputs(build(c, learner(at))));
		at.add(attempt("t1q1", "improve", true, "confident", 1, 89));
		ok("(a) hinted correct does not resolve", remediation(c, at), inputs(build(c, learner(at))));
		at.add(attempt("t1q1", "improve", true, "mostlysure", 0, 88));
		ok("(a) confident unassisted correct resolves -> no remediation", !remediation(c, at), inputs(build(c, learner(at))));
		// remediation (b): recent >= 8 and HCI attempts >= max(3, ceil(recent x 0.25)); HCI repeated on one question so (a) stays off
		ok("(b) 8 recent, 2 HCI -> no", !remediation(c, recent(8, 2)), null);
		ok("(b) 7 recent, 3 HCI -> no (recent < 8)", !remediation(c, recent(7, 3)), null);
		ok("(b) 8 recent, 3 HCI -> yes", remediation(c, recent(8, 3)), null);
		ok("(b) 12 recent, 3 HCI -> yes", remediation(c, recent(12, 3)), null);
		ok("(b) 16 recent, 3 HCI -> no (needs 4)", !remediation(c, recent(16, 3)), null);

		// cooldown across learning modes (improve attempt 1 h ago), evaluation does not cool down; plenty of reinforcement candidates
		at = new ArrayList<>();
		for (int t = 1; t <= 2; t++)
		{
			for (int i = 1; i <= 8; i++)
			{
				at.add(attempt("t" + t + "q" + i, "learn", false, "notsure", 0, 24 * 5 + 10 - i));
			}
		}
		at.add(attempt("t1q1", "improve", false, "notsure", 0, 1));
		at.add(attempt("t1q2", "evaluation", false, "notsure", 0, 1));
		dc = build(c, learner(at));
		ok("improve attempt 1 h ago puts t1q1 in cooldown", !contains(dc, "t1q1") && !relaxations(dc).contains("cooldown"), dc);
		ok("evaluation attempt 1 h ago does not cool t1q2 down", "reinforcement".equals(bucketOf(dc, "t1q2")), dc);
		ok("topic cap: no topic over 50% of slots", maxPerTopic(dc) <= size(dc) / 2 && !relaxations(dc).contains("topiccap"), dc);

		// fill order F1: reinforcement only available in cooldown -> cooldown relaxed, new stays within maxNew = ceil(0.7 n)
		Content one = content(1, 20);
		at = new ArrayList<>();
		for (int i = 1; i <= 6; i++)
		{
			at.add(attempt("t1q" + i, "learn", true, "confident", 0, 2));
		}
		dc = build(one, learner(at));
		ok("F1 cooldown relaxed before new fill; new <= maxNew", relaxations(dc).equals(List.of("cooldown")) && count(dc, "new") <= num(dc, "maxnew") && size(dc) == num(dc, "n"), dc);
		// fill order F2: reinforcement exhausted even after relaxations -> new exceeds maxNew (only case allowed)
		at = new ArrayList<>();
		at.add(attempt("t1q1", "learn", false, "confident", 0, 200));
		at.add(attempt("t1q2", "learn", false, "confident", 0, 199));
		dc = build(one, learner(at));
		ok("F2 reinforcement exhausted -> new > maxNew, newfill + newcap recorded", count(dc, "reinforcement") == 2 && count(dc, "new") > num(dc, "maxnew")
			&& relaxations(dc).containsAll(List.of("newfill", "newcap")) && size(dc) == num(dc, "n"), dc);
		// fill order F3: reinforcement short under the topic cap -> cap relaxed for reinforcement before any new fill
		at = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
		{
			at.add(attempt("t1q" + i, "learn", true, i <= 3 ? "notsure" : "confident", 0, 200 - i)); // n = 11 -> cap 5 per topic
		}
		dc = build(c, learner(at));
		ok("F3 topic cap relaxed for reinforcement, no new fill, new <= maxNew", relaxations(dc).equals(List.of("topiccap")) && count(dc, "new") <= num(dc, "maxnew") && size(dc) == num(dc, "n"), dc);
		// hard cap: 11/15 new is allowed, 14/15 is not while reinforcement can still fill
		ok("maxNew(15) = 11", (int) Math.ceil(15 * 0.70) == 11, "");

		// reinforcement pool all in cooldown: new questions fill, and the set never exceeds n
		at = new ArrayList<>();
		for (int i = 1; i <= 3; i++)
		{
			at.add(attempt("t1q" + i, "learn", i != 2, "confident", 0, 36));
		}
		dc = build(c, learner(at));
		ok("set size = n when a bucket is short", size(dc) == ((Number) inputs(dc).get("n")).intValue() && count(dc, "reinforcement") >= 1, dc);

		// Improve order: class, then recurrence risk
		at = new ArrayList<>();
		at.add(attempt("t1q1", "learn", false, "notsure", 0, 50));
		at.add(attempt("t1q2", "learn", false, "notsure", 0, 49));
		at.add(attempt("t1q2", "learn", false, "notsure", 0, 48));
		at.add(attempt("t1q2", "learn", false, "notsure", 0, 47));
		at.add(attempt("t1q3", "learn", false, "confident", 0, 46));
		at.add(attempt("t1q4", "learn", true, "noidea", 0, 45));
		Learner l = learner(at);
		List<Question> qs = new ArrayList<>();
		for (int i = 1; i <= 4; i++)
		{
			qs.add(c.questions.get("t1q" + i));
		}
		qs.sort(LearningEngine.improveOrder(l, q -> null, null));
		ok("improve order HCI, unsure-right, then wrong by recurrence", ids(qs).equals("t1q3,t1q4,t1q2,t1q1"), ids(qs));

		configChecks(c);
		progressionChecks();
		contentAndRetentionChecks();
		profileChecks();
		evaluationChecks();

		System.out.println(failures == 0 ? "ok: LearningEngineCheck all passed" : "FAIL: " + failures + " LearningEngineCheck assertion(s)");
		System.exit(failures == 0 ? 0 : 1);
	}

	// ---- job profiles (spec 2026-09-16): pure assignment on a 4-topic fixture

	static LearningEngine.Profiles profiles(LearningEngine.ProfileRow... rows)
	{
		LearningEngine.Profiles p = new LearningEngine.Profiles();
		p.names.put("pilot", "Pilot");
		p.names.put("safety", "Safety lead");
		p.names.put("alpha", "Alpha");
		for (LearningEngine.ProfileRow r : rows)
		{
			p.rows.add(r);
		}
		return p;
	}

	static LearningEngine.ProfileRow row(String profile, String topic, int pos, String level, boolean mandatory, boolean reqPrev, String after)
	{
		LearningEngine.ProfileRow r = new LearningEngine.ProfileRow();
		r.jobrole = profile;
		r.topicid = topic;
		r.position = pos;
		r.requiredlevel = level;
		r.mandatory = mandatory;
		r.requiresprevious = reqPrev;
		r.afterfinish = after;
		return r;
	}

	static Learner withProfiles(List<Attempt> at, String primary, String... all)
	{
		Learner l = learner(at);
		l.primaryjobrole = primary;
		l.jobroles = new ArrayList<>(List.of(all));
		return l;
	}

	static List<String> order(Content c)
	{
		return new ArrayList<>(c.topics.keySet());
	}

	static List<Attempt> allCorrect(Content c, String topic)
	{
		List<Attempt> at = new ArrayList<>();
		for (Question q : c.topics.get(topic).questions)
		{
			at.add(attempt(q.id, "learn", true, "confident", 0, 10 + q.position));
		}
		return at;
	}

	static void profileChecks()
	{
		// pilot: t1 (remove after finish), t2 requires previous, t3, t4 optional; safety: t3 expert, t2 keep
		LearningEngine.Profiles p = profiles(
			row("pilot", "t1", 1, "competent", true, false, "remove"),
			row("pilot", "t2", 2, "competent", true, true, "keep"),
			row("pilot", "t3", 3, null, true, false, "keep"),
			row("pilot", "t4", 4, null, false, false, "keep"),
			row("safety", "t3", 1, "expert", true, false, "keep"),
			row("safety", "t2", 2, "beginner", false, true, "remove"));

		Content c = content(4, 3);
		Learner l = withProfiles(new ArrayList<>(), "pilot", "pilot", "safety");
		LearningEngine.applyProfiles(c, l, p);
		ok("profiles: assignment order = primary rows then extras", order(c).equals(List.of("t1", "t2", "t3", "t4")), order(c));
		ok("profiles: positions renumbered 1..n", c.topics.get("t1").position == 1 && c.topics.get("t4").position == 4, c.topics.get("t4").position);
		ok("profiles: shared topic keeps first occurrence's profile", "pilot".equals(c.topics.get("t3").profile) && "Pilot".equals(c.topics.get("t3").profilename), c.topics.get("t3").profile);
		ok("profiles: strictest level across profiles", "expert".equals(c.topics.get("t3").assignedlevel) && "competent".equals(c.topics.get("t2").assignedlevel), c.topics.get("t3").assignedlevel);
		ok("profiles: mandatory if any, keep beats remove", c.topics.get("t2").mandatory && "keep".equals(c.topics.get("t2").afterfinish), c.topics.get("t2").afterfinish);
		ok("profiles: optional topic stays optional", !c.topics.get("t4").mandatory, c.topics.get("t4").mandatory);
		ok("profiles: t2 locked behind unfinished t1 (previous_topic_incomplete)", c.topics.get("t2").locked && "t1".equals(c.topics.get("t2").previoustopic), c.topics.get("t2").previoustopic);
		ok("profiles: t1 not locked (no gate), t3 not locked", !c.topics.get("t1").locked && !c.topics.get("t3").locked, "");
		ok("profiles: nothing finished, nothing removed", !c.topics.get("t1").finished && c.removedtopics.isEmpty(), c.removedtopics);
		ok("profiles: profiles json primary first", c.profiles.size() == 2 && "pilot".equals(c.profiles.get(0).get("id")) && Boolean.TRUE.equals(c.profiles.get(0).get("primary")), c.profiles);

		// extras order by name: alpha (name "Alpha") before safety; primary still first
		Content c2 = content(4, 3);
		LearningEngine.Profiles p2 = profiles(row("safety", "t2", 1, null, true, false, "keep"), row("alpha", "t3", 1, null, true, false, "keep"), row("pilot", "t1", 1, null, true, false, "keep"));
		LearningEngine.applyProfiles(c2, withProfiles(new ArrayList<>(), "pilot", "safety", "alpha", "pilot"), p2);
		ok("profiles: extras sorted by name after primary", order(c2).subList(0, 3).equals(List.of("t1", "t3", "t2")), order(c2));

		// finished = learn complete + level; t1 all correct confident -> competent (100%) -> finished -> removed; t2 unlocks
		Content c3 = content(4, 3);
		LearningEngine.applyProfiles(c3, withProfiles(allCorrect(c, "t1"), "pilot", "pilot", "safety"), p);
		ok("profiles: finished t1 with remove is stripped and listed", !c3.topics.containsKey("t1") && c3.removedtopics.equals(List.of("t1")) && !c3.questions.containsKey("t1q1"), c3.removedtopics);
		ok("profiles: t2 unlocked once t1 finished", !c3.topics.get("t2").locked, c3.topics.get("t2").locked);
		ok("profiles: order after removal starts at t2 with position 2 kept", order(c3).get(0).equals("t2") && c3.topics.get("t2").position == 2, order(c3));

		// learn complete but below level -> not finished (t1 answered all, wrong) -> still present, t2 locked
		List<Attempt> wrong = new ArrayList<>();
		for (Question q : content(4, 3).topics.get("t1").questions)
		{
			wrong.add(attempt(q.id, "learn", false, "confident", 0, 5));
		}
		Content c4 = content(4, 3);
		LearningEngine.applyProfiles(c4, withProfiles(wrong, "pilot", "pilot", "safety"), p);
		ok("profiles: learn complete below required level is not finished", c4.topics.containsKey("t1") && !c4.topics.get("t1").finished && c4.topics.get("t2").locked, c4.topics.get("t1").finished);

		// started never relocks: one learn answer in t2 while t1 unfinished
		List<Attempt> startedT2 = new ArrayList<>();
		startedT2.add(attempt("t2q1", "learn", false, "notsure", 0, 2));
		Content c5 = content(4, 3);
		LearningEngine.applyProfiles(c5, withProfiles(startedT2, "pilot", "pilot", "safety"), p);
		ok("profiles: a started topic is never locked", !c5.topics.get("t2").locked, c5.topics.get("t2").locked);

		// an evaluation-only answer does not count as started
		List<Attempt> evalT2 = new ArrayList<>();
		evalT2.add(attempt("t2q1", "evaluation", true, "confident", 0, 2));
		Content c6 = content(4, 3);
		LearningEngine.applyProfiles(c6, withProfiles(evalT2, "pilot", "pilot", "safety"), p);
		ok("profiles: evaluation answer does not start a topic", c6.topics.get("t2").locked, c6.topics.get("t2").locked);

		// rows on unknown topics are skipped; gate uses the previous *visible* row
		Content c7 = content(2, 3);
		LearningEngine.Profiles p7 = profiles(row("pilot", "t1", 1, null, true, false, "keep"), row("pilot", "ghost", 2, null, true, false, "keep"), row("pilot", "t2", 3, null, true, true, "keep"));
		LearningEngine.applyProfiles(c7, withProfiles(new ArrayList<>(), "pilot", "pilot"), p7);
		ok("profiles: unknown topic skipped, gate falls back to previous visible row", c7.topics.get("t2").locked && "t1".equals(c7.topics.get("t2").previoustopic) && c7.topics.size() == 2, c7.topics.get("t2").previoustopic);

		// requiresprevious on the first row is ignored
		Content c8 = content(2, 3);
		LearningEngine.applyProfiles(c8, withProfiles(new ArrayList<>(), "pilot", "pilot"), profiles(row("pilot", "t1", 1, null, true, true, "keep")));
		ok("profiles: requiresprevious ignored on the first row", !c8.topics.get("t1").locked && !c8.topics.get("t1").requiresprevious, "");

		// no rows -> untouched (fallback path)
		Content c9 = content(2, 3);
		LearningEngine.applyProfiles(c9, withProfiles(new ArrayList<>(), null, "pilot"), profiles());
		ok("profiles: no rows leaves content untouched", order(c9).equals(List.of("t1", "t2")) && c9.topics.get("t1").position == null && c9.removedtopics.isEmpty(), order(c9));

		// unassigned topics follow in catalog order
		Content c10 = content(3, 3);
		LearningEngine.applyProfiles(c10, withProfiles(new ArrayList<>(), "pilot", "pilot"), profiles(row("pilot", "t3", 1, null, true, false, "keep")));
		ok("profiles: unassigned topics follow in catalog order", order(c10).equals(List.of("t3", "t1", "t2")) && c10.topics.get("t1").position == null, order(c10));

		// Daily Challenge: required = mandatory assigned; locked topic's sections excluded from the new pool
		Content c11 = content(4, 3);
		Learner l11 = withProfiles(new ArrayList<>(), "pilot", "pilot", "safety");
		LearningEngine.applyProfiles(c11, l11, p);
		Map<String, Boolean> req = new HashMap<>();
		for (Topic t : c11.topics.values())
		{
			req.put(t.id, t.position != null && t.mandatory);
		}
		ok("profiles: required = mandatory assigned (t4 optional)", Boolean.TRUE.equals(req.get("t1")) && Boolean.TRUE.equals(req.get("t3")) && !Boolean.TRUE.equals(req.get("t4")), req);
		JSONObject dc = LearningEngine.buildDailyChallenge(c11, l11, NOW, req, false, 5, 20, LearningEngine.lockedTopicSections(c11));
		boolean anyT2 = false;
		for (Object o : items(dc))
		{
			anyT2 |= String.valueOf(((JSONObject) o).get("questionid")).startsWith("t2q");
		}
		ok("profiles: locked topic never enters the Daily Challenge", !anyT2, dc);
		String first = String.valueOf(((JSONObject) items(dc).get(0)).get("questionid"));
		ok("profiles: Daily Challenge new fill starts with the first assigned topic", first.startsWith("t1q"), first);

		// requiredTopics falls back only for a learner with no rows at all, not whenever nothing came out required.
		// c.assigned is true in both cases below, so requiredTopics never reaches storage and the stub's null archive is fine.
		Content c12 = content(2, 3);
		Learner l12 = withProfiles(new ArrayList<>(), "pilot", "pilot");
		LearningEngine.applyProfiles(c12, l12, profiles(row("pilot", "t1", 1, null, false, false, "keep"), row("pilot", "t2", 2, null, false, false, "keep")));
		String[] reason12 = new String[1];
		Map<String, Boolean> req12 = stubEngine().requiredTopics(c12, l12, reason12);
		ok("profiles: an all-optional assignment requires nothing and does not fall back", c12.assigned && !req12.containsValue(Boolean.TRUE) && reason12[0] == null, req12 + " " + reason12[0]);

		// every row afterfinish=remove and finished: the topics are stripped, but the learner still has an assignment
		Content base13 = content(2, 3);
		List<Attempt> done13 = allCorrect(base13, "t1");
		done13.addAll(allCorrect(base13, "t2"));
		Content c13 = content(2, 3);
		Learner l13 = withProfiles(done13, "pilot", "pilot");
		LearningEngine.applyProfiles(c13, l13, profiles(row("pilot", "t1", 1, null, true, false, "remove"), row("pilot", "t2", 2, null, true, false, "remove")));
		String[] reason13 = new String[1];
		Map<String, Boolean> req13 = stubEngine().requiredTopics(c13, l13, reason13);
		ok("profiles: a fully removed assignment stays assigned and does not fall back", c13.topics.isEmpty() && c13.removedtopics.equals(List.of("t1", "t2")) && c13.assigned
			&& req13.isEmpty() && reason13[0] == null, c13.removedtopics + " " + reason13[0]);
	}

	/** Unservable content is never put in a session; expired sessions are kept 30 days, then purged. */
	static void contentAndRetentionChecks()
	{
		Map<String, String> q = new HashMap<>(Map.of("question", "¿Q?", "option_a", "A", "option_b", "B", "option_c", "C", "option_d", "D", "correctoption", "b"));
		ok("renderable question: no content problem", LearningEngine.contentProblem(q::get) == null, LearningEngine.contentProblem(q::get));
		q.put("correctoption", " Option_E ");
		ok("correct option naming a missing option_e", "bad_correctoption".equals(LearningEngine.contentProblem(q::get)), LearningEngine.contentProblem(q::get));
		q.put("option_e", "E");
		ok("option_x form with that option present", LearningEngine.contentProblem(q::get) == null, LearningEngine.contentProblem(q::get));
		q.put("option_c", " ");
		ok("blank option_c", "missing_option_c".equals(LearningEngine.contentProblem(q::get)), LearningEngine.contentProblem(q::get));
		q.put("question", "");
		ok("blank question first", "missing_question".equals(LearningEngine.contentProblem(q::get)), LearningEngine.contentProblem(q::get));

		Content c = content(1, 3);
		c.questions.get("t1q2").contentproblem = "missing_option_c";
		JSONObject next = LearningEngine.learnNext(c.topics.get("t1"), null, learner(), null);
		JSONArray bad = LearningEngine.unavailable(c, next);
		ok("unavailable lists the unservable item with its ids", bad.size() == 1 && "t1q2".equals(((Map) bad.get(0)).get("questionid")) && "ct1q2".equals(((Map) bad.get(0)).get("componentid"))
			&& "s1".equals(((Map) bad.get(0)).get("sectionid")) && "missing_option_c".equals(((Map) bad.get(0)).get("problem")), bad);
		List<Attempt> at = new ArrayList<>();
		at.add(attempt("t1q1", "learn", true, "confident", 0, 2));
		at.add(attempt("t1q2", "learn", true, "confident", 0, 1));
		ok("an already answered (done) item does not block", LearningEngine.unavailable(c, LearningEngine.learnNext(c.topics.get("t1"), null, learner(at), null)).isEmpty(), "");

		Date now = new Date();
		long day = 86400L * 1000;
		ok("retention: expired 29 days ago kept, 31 days ago purged", LearningEngine.purgeBefore(now).before(new Date(now.getTime() - 29 * day))
			&& LearningEngine.purgeBefore(now).after(new Date(now.getTime() - 31 * day)) && LearningEngine.SESSION_TTL_MS == 7 * day, LearningEngine.purgeBefore(now));
	}

	/** Each mastery / selection variable validated on its own (spec "Validation"). */
	static void configChecks(Content c)
	{
		// band boundaries: 1 <= competentmin < expertmin <= 100; beginner implicitly 0..competentmin-1
		ok("boundaries 60/85 valid (default)", LearningEngine.thresholdsError(60, 85) == null && LearningEngine.DEFAULT_THRESHOLDS[0] == 60 && LearningEngine.DEFAULT_THRESHOLDS[1] == 85, "");
		ok("boundaries 1/100 and 99/100 valid", LearningEngine.thresholdsError(1, 100) == null && LearningEngine.thresholdsError(99, 100) == null, "");
		ok("boundaries 0/85 invalid", "competentmin_below_1".equals(LearningEngine.thresholdsError(0, 85)), LearningEngine.thresholdsError(0, 85));
		ok("boundaries 85/85 and 90/80 invalid", "competentmin_not_below_expertmin".equals(LearningEngine.thresholdsError(85, 85)) && "competentmin_not_below_expertmin".equals(LearningEngine.thresholdsError(90, 80)), "");
		ok("boundaries 60/101 invalid", "expertmin_above_100".equals(LearningEngine.thresholdsError(60, 101)), "");
		Learner none = learner();
		List<Attempt> at = new ArrayList<>();
		Topic t1 = c.topics.get("t1");
		for (int i = 1; i <= 10; i++)
		{
			at.add(attempt("t1q" + i, "learn", i <= 6, "confident", 0, 50));
		}
		ok("band at 60% with 60/85 = competent, at 59% = beginner", "competent".equals(LearningEngine.mastery(t1.questions, learner(at), 60, 85).band)
			&& "beginner".equals(LearningEngine.mastery(t1.questions, learner(at), 61, 85).band) && LearningEngine.mastery(t1.questions, none, 60, 85).band == null, "");

		// difficulty weights and question weight
		ok("difficulty weights 1 / 2 / 4, unknown = beginner", LearningEngine.weightOf("beginner") == 1 && LearningEngine.weightOf("competent") == 2 && LearningEngine.weightOf("expert") == 4
			&& LearningEngine.weightOf("Alta") == 1 && "beginner".equals(LearningEngine.difficultyOf("Alta")), "");
		// correctness, confidence weights, hint penalties
		double conf = score("confident", 0), most = score("mostlysure", 0), notsure = score("notsure", 0), noidea = score("noidea", 0);
		ok("confidence weights 1 > .85 > .6 > .4 > 0", conf == 1.0 && most == 0.85 && notsure == 0.6 && noidea == 0.4, List.of(conf, most, notsure, noidea));
		ok("incorrect scores 0 at any confidence", LearningEngine.score(attempt("x", "learn", false, "confident", 0, 1)) == 0, "");
		ok("hint penalty 0.25 per level, still > 0 at max level " + LearningEngine.MAX_HINTLEVEL, score("confident", 1) == 0.75 && score("confident", 2) == 0.5
			&& score("confident", LearningEngine.MAX_HINTLEVEL) == 0.25 && score("noidea", LearningEngine.MAX_HINTLEVEL) > 0, "");
		// latest evidence: the newest attempt in any mode, whatever order the rows arrive in
		at = new ArrayList<>();
		at.add(attempt("t1q1", "learn", true, "confident", 0, 1));
		at.add(attempt("t1q1", "evaluation", false, "confident", 0, 5));
		ok("latest evidence = newest attempt (rows out of order)", learner(at).latest.get("t1q1").correct, "");
		at.add(attempt("t1q1", "evaluation", false, "confident", 0, 0));
		ok("latest evidence counts any mode (newer evaluation wrong)", !learner(at).latest.get("t1q1").correct, "");
		at.add(attempt("t1q1", LearningEngine.LEGACY, true, "confident", 0, 0));
		ok("latest evidence still counts a newer legacy answer", learner(at).latest.get("t1q1").correct, "");
		// aggregation: topic % = weighted mean over all its questions, section % over its own
		Content two = content(1, 4);
		Topic t = two.topics.get("t1");
		t.questions.get(3).difficulty = "expert";
		t.questions.get(3).weight = 4;
		at = new ArrayList<>();
		at.add(attempt("t1q4", "learn", true, "confident", 0, 1));
		ok("topic aggregation weights by difficulty: 4 / (1+1+1+4) = 57%", LearningEngine.percent(t.questions, learner(at)) == 57, LearningEngine.percent(t.questions, learner(at)));
		// rounding is on the exact value: 2 x 0.6375 / 3 = 42.5 -> 43 (double accumulation gives 42.49999999999999)
		Content half = content(1, 2);
		Topic ht = half.topics.get("t1");
		ht.questions.get(1).difficulty = "competent";
		ht.questions.get(1).weight = 2;
		List<Attempt> hat = new ArrayList<>();
		hat.add(attempt("t1q2", "learn", true, "mostlysure", 1, 1));
		ok("percent rounds the exact half up: 42.5 -> 43", LearningEngine.percent(ht.questions, learner(hat)) == 43, LearningEngine.percent(ht.questions, learner(hat)));
		// expert evidence: >= expertmin on expert questions and >= min(3, count) latest-correct
		ok("expert evidence with one expert question correct", LearningEngine.mastery(t.questions, learner(at), 60, 85).evidence, "");
		ok("expert evidence reason no_expert_questions", "no_expert_questions".equals(LearningEngine.mastery(c.topics.get("t2").questions, learner(at), 60, 85).evidencereason), "");
		at.set(0, attempt("t1q4", "learn", true, "notsure", 0, 1));
		ok("expert evidence insufficient below expertmin", "insufficient".equals(LearningEngine.mastery(t.questions, learner(at), 60, 85).evidencereason), "");
		// critical-skill evidence is not available yet: always recorded as false
		ok("criticalSkills flag recorded false", Boolean.FALSE.equals(((JSONObject) inputs(build(c, none)).get("flags")).get("criticalSkills")), "");
		// required level: only list levels rank; an unknown level never outranks a real one
		ok("level index: beginner < competent < expert, unknown = -1", LearningEngine.levelIndex("beginner") == 0 && LearningEngine.levelIndex("expert") == 2 && LearningEngine.levelIndex("master") == -1, "");

		// legacy (unverified) attempts: mastery evidence only, never the learning sequence
		at = new ArrayList<>();
		at.add(attempt("t1q1", LearningEngine.LEGACY, true, "confident", 0, 3));
		Learner leg = learner(at);
		ok("legacy attempt does not advance the sequence nor count as learning", !leg.answeredInSequence.contains("t1q1") && leg.learningAttempts.isEmpty() && !LearningEngine.isLearningMode(null)
			&& !LearningEngine.isLearningMode("") && !LearningEngine.isLearningMode(LearningEngine.LEGACY), "");
		ok("legacy answer stays in the new bucket", "new".equals(bucketOf(build(c, leg), "t1q1")), "");

		// Daily Challenge sizes: min >= 1, max >= min, both <= safe max; invalid -> defaults + reason
		ok("dc sizes blank -> 5/20 valid", java.util.Arrays.equals(LearningEngine.dcSizes(null, ""), new Object[] {5, 20, null}), java.util.Arrays.toString(LearningEngine.dcSizes(null, "")));
		ok("dc sizes 0/20 -> defaults, min_below_1", java.util.Arrays.equals(LearningEngine.dcSizes("0", "20"), new Object[] {5, 20, "min_below_1"}), "");
		ok("dc sizes 10/5 -> defaults, max_below_min", java.util.Arrays.equals(LearningEngine.dcSizes("10", "5"), new Object[] {5, 20, "max_below_min"}), "");
		ok("dc sizes 5/51 -> defaults, above_safe_max (" + LearningEngine.DC_SAFE_MAX + ")", java.util.Arrays.equals(LearningEngine.dcSizes("5", "51"), new Object[] {5, 20, "above_safe_max"}), "");
		ok("dc sizes 1/50 and 3/3 kept", java.util.Arrays.equals(LearningEngine.dcSizes("1", "50"), new Object[] {1, 50, null}) && java.util.Arrays.equals(LearningEngine.dcSizes("3", "3"), new Object[] {3, 3, null}), "");

		// challenge day: org-local date of the instant; one id rule
		Date instant = Date.from(java.time.Instant.parse("2026-09-15T03:30:00Z"));
		ok("challenge date in America/Lima (UTC-5) is the previous day", LearningEngine.challengeDate(instant, java.time.ZoneId.of("America/Lima")).toString().equals("2026-09-14")
			&& LearningEngine.challengeDate(instant, java.time.ZoneId.of("UTC")).toString().equals("2026-09-15"), "");
		ok("challenge id = user_yyyyMMdd of the local date", LearningEngine.challengeId("u@x", java.time.LocalDate.of(2026, 9, 14)).equals("u@x_20260914"), "");

		// resolve(): learn / improve scope rules (dailychallenge needs the stored set: server checks)
		LearningEngine engine = stubEngine();
		Map<String, String> claims = new HashMap<>();
		String lt = session("learn", "topic", "t1", "t1q2", "t1q3");
		String ls = session("learn", "subtopic", "s1", "t1q2");
		ok("resolve learn topic scope ok (exposure)", engine.resolve(c, none, "learn", "topic", "t1", "t1q2", claims, lt, false).question != null, "");
		ok("resolve learn subtopic scope ok (exposure)", engine.resolve(c, none, "learn", "subtopic", "s1", "t1q2", claims, ls, false).question != null, "");
		expectReject(engine.resolve(c, none, null, "topic", "t1", "t1q2", claims, lt, true), 400, "missing_mode");
		expectReject(engine.resolve(c, none, "quiz", "topic", "t1", "t1q2", claims, lt, true), 400, "bad_mode"); // evaluation is accepted now (2026-09-16-evaluation-mode)
		expectReject(engine.resolve(c, none, "evaluation", "subtopic", "s1", "t1q2", claims, lt, true), 400, "bad_scopetype"); // evaluation is scoped to the topic
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "nope", claims, lt, true), 404, "unknown_question");
		expectReject(engine.resolve(c, none, "learn", null, null, "t1q2", claims, lt, true), 400, "missing_scope");
		expectReject(engine.resolve(c, none, "learn", "topic", "t2", "t1q2", claims, lt, true), 409, "scope_mismatch");
		expectReject(engine.resolve(c, none, "learn", "subtopic", "s2", "t1q2", claims, lt, true), 409, "scope_mismatch");
		expectReject(engine.resolve(c, none, "learn", "chapter", "t1", "t1q2", claims, lt, true), 400, "bad_scopetype");
		expectReject(engine.resolve(c, none, "improve", "topic", "t1", "t1q2", claims, session("improve", "topic", "t1", "t1q2"), true), 409, "improve_locked");
		expectReject(engine.resolve(c, none, "dailychallenge", "topic", "t1", "t1q2", claims, null, true), 400, "scope_not_allowed");
		claims.put("sectionid", "s2");
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q2", claims, lt, true), 409, "hierarchy_mismatch");
		claims.put("sectionid", "s1");
		claims.put("componentid", "ct1q2");
		ok("resolve accepts matching claimed hierarchy", engine.resolve(c, none, "learn", "topic", "t1", "t1q2", claims, lt, true).question != null, "");
		at = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
		{
			at.add(attempt("t1q" + i, i == 10 ? LearningEngine.LEGACY : "learn", true, "confident", 0, 20));
		}
		String imp = session("improve", "topic", "t1", "t1q2");
		expectReject(engine.resolve(c, learner(at), "improve", "topic", "t1", "t1q2", new HashMap<>(), imp, true), 409, "improve_locked");
		at.set(9, attempt("t1q10", "dailychallenge", true, "confident", 0, 20));
		ok("resolve improve once the scope's sequence is complete (learn + dailychallenge)", engine.resolve(c, learner(at), "improve", "topic", "t1", "t1q2", new HashMap<>(), imp, true).question != null, "");
		sessionChecks(c);
	}

	/** Session membership: every mode needs the session next.json issued; learn answers only the next unanswered question of it. */
	static void sessionChecks(Content c)
	{
		LearningEngine engine = stubEngine();
		Learner none = learner();
		Map<String, String> no = new HashMap<>();
		String lt = session("learn", "topic", "t1", "t1q1", "t1q2", "t1q3");
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q1", no, null, true), 400, "missing_sessionid");
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q1", no, "u_nope", true), 404, "unknown_session");
		Learner other = LearningEngine.learner("someoneelse", new ArrayList<>());
		expectReject(engine.resolve(c, other, "learn", "topic", "t1", "t1q1", no, lt, true), 409, "session_mismatch");
		expectReject(engine.resolve(c, none, "learn", "subtopic", "s1", "t1q1", no, lt, true), 409, "session_mismatch");
		List<Attempt> learned = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
		{
			learned.add(attempt("t1q" + i, "learn", true, "confident", 0, 30));
		}
		expectReject(engine.resolve(c, learner(learned), "improve", "topic", "t1", "t1q1", no, lt, true), 409, "session_mismatch");
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q4", no, lt, true), 409, "not_in_session");
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q4", no, lt, false), 409, "not_in_session");
		LearningEngine.Session old = sessions.get(session("learn", "topic", "t1", "t1q1"));
		old.expires = new Date(NOW.getTime() - 1000);
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q1", no, old.id, true), 409, "session_expired");
		expectReject(engine.resolve(c, none, "learn", "topic", "t1", "t1q2", no, lt, true), 409, "out_of_order");
		ok("session: a later question of the session may be shown (exposure), not answered", engine.resolve(c, none, "learn", "topic", "t1", "t1q2", no, lt, false).question != null, "");
		ok("session: learn answers the first unanswered question", engine.resolve(c, none, "learn", "topic", "t1", "t1q1", no, lt, true).question != null, "");
		Learner one = learner(modeAll("learn", "t1q1"));
		expectReject(engine.resolve(c, one, "learn", "topic", "t1", "t1q1", no, lt, true), 409, "already_answered");
		ok("session: after q1, q2 is next", engine.resolve(c, one, "learn", "topic", "t1", "t1q2", no, lt, true).question != null, "");
		Learner viaDc = learner(modeAll("dailychallenge", "t1q1", "t1q2"));
		ok("session: questions answered in the daily challenge meanwhile are skipped", engine.resolve(c, viaDc, "learn", "topic", "t1", "t1q3", no, lt, true).question != null
			&& "already_answered".equals(engine.resolve(c, viaDc, "learn", "topic", "t1", "t1q2", no, lt, true).error), "");
		List<Attempt> all = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
		{
			all.add(attempt("t1q" + i, "learn", true, "confident", 0, 20));
		}
		String imp = session("improve", "topic", "t1", "t1q5", "t1q7");
		expectReject(engine.resolve(c, learner(all), "improve", "topic", "t1", "t1q6", no, imp, true), 409, "not_in_session");
		ok("session: improve accepts its selected questions in any order, answered before", engine.resolve(c, learner(all), "improve", "topic", "t1", "t1q7", no, imp, true).question != null
			&& engine.resolve(c, learner(all), "improve", "topic", "t1", "t1q5", no, imp, true).question != null, "");
		// daily challenge: the session is today's stored set (stub: u_today holds t1q1)
		expectReject(engine.resolve(c, none, "dailychallenge", null, null, "t1q1", no, null, true), 400, "missing_sessionid");
		expectReject(engine.resolve(c, none, "dailychallenge", null, null, "t1q1", no, "u_20200101", true), 409, "session_expired");
		expectReject(engine.resolve(c, none, "dailychallenge", null, null, "t1q1", no, "x_20200101", true), 409, "unknown_session");
		String today = LearningEngine.challengeId("u", LearningEngine.challengeDate(new Date(), java.time.ZoneId.of("UTC")));
		expectReject(engine.resolve(c, none, "dailychallenge", null, null, "t1q2", no, today, true), 409, "not_in_daily_challenge");
		ok("session: daily challenge accepts a question of today's set", engine.resolve(c, none, "dailychallenge", null, null, "t1q1", no, today, true).question != null, "");

		// startSession: ordered snapshot + policy context; no items = no session
		JSONObject next = LearningEngine.learnNext(c.topics.get("t1"), null, learner(modeAll("learn", "t1q1")), new HashMap<>());
		int before = sessions.size();
		engine.startSession("u", "topic", "t1", c.topics.get("t1"), next);
		LearningEngine.Session s = sessions.get((String) next.get("sessionid"));
		ok("startSession stores the served order, mode, scope, expiry and policy context", s != null && s.questions.size() == 9 && "t1q2".equals(s.questions.get(0)) && "learn".equals(s.mode)
			&& "t1".equals(s.scopeid) && "learn-v1".equals(s.algorithmversion) && s.expires.getTime() - s.created.getTime() == LearningEngine.SESSION_TTL_MS && s.inputs.containsKey("policyversion"), next);
		JSONObject empty = LearningEngine.learnNext(c.topics.get("t1"), null, learner(all), new HashMap<>());
		before = sessions.size();
		engine.startSession("u", "topic", "t1", c.topics.get("t1"), empty);
		ok("startSession: no items -> sessionid null, nothing stored", empty.containsKey("sessionid") && empty.get("sessionid") == null && sessions.size() == before, empty);
	}

	// ---- subtopic progression: topic p with subtopics a, b, c (2 beginner questions each unless built otherwise)

	static Map<String, Date> recorded = new HashMap<>();
	static List<String[]> saved = new ArrayList<>();
	static Map<String, LearningEngine.Session> sessions = new HashMap<>();

	/** Engine without storage: recorded unlocks (create-only, like the real one) and sessions live in memory; org zone UTC; today's challenge holds t1q1. */
	static LearningEngine stubEngine()
	{
		return new LearningEngine(null)
		{
			@Override
			protected Map<String, Date> recordedUnlocks(String inUserid)
			{
				return new HashMap<>(recorded);
			}

			@Override
			protected int saveUnlocks(String inUserid, List<String[]> inRows, Date inNow)
			{
				int n = 0;
				for (String[] r : inRows)
				{
					if (recorded.putIfAbsent(r[1], inNow) == null)
					{
						saved.add(r);
						n++;
					}
				}
				return n;
			}

			@Override
			protected void saveSession(LearningEngine.Session s)
			{
				sessions.put(s.id, s);
			}

			@Override
			protected LearningEngine.Session loadSession(String inId)
			{
				return sessions.get(inId);
			}

			@Override
			protected String setting(String inId)
			{
				return null;
			}

			@Override
			public java.util.Set<String> todaysChallengeQuestions(String inUserid)
			{
				return java.util.Set.of("t1q1");
			}
		};
	}

	/** A stored learner-u session of mode / scope holding the question ids in order; returns its id. */
	static String session(String mode, String scopetype, String scopeid, String... qs)
	{
		LearningEngine.Session s = new LearningEngine.Session();
		s.id = "u_s" + (sessions.size() + 1);
		s.user = "u";
		s.mode = mode;
		s.scopetype = scopetype;
		s.scopeid = scopeid;
		s.questions.addAll(List.of(qs));
		s.created = NOW;
		s.expires = new Date(NOW.getTime() + LearningEngine.SESSION_TTL_MS);
		sessions.put(s.id, s);
		return s.id;
	}

	static void progressionChecks()
	{
		Content c = progression("sequential_completion", null, 2, 2, 2);
		Topic p = c.topics.get("p");
		Learner none = learner();
		ok("progression: first subtopic unlocked, next locked (incomplete)", state(p, none, "a").get("unlocked").equals(true) && "first_subtopic".equals(state(p, none, "a").get("unlockreason"))
			&& reason(p, none, "b").equals("previous_subtopic_incomplete") && state(p, none, "b").get("unlocked").equals(false), LearningEngine.subtopicStates(p, none, null));
		p.policy = "open";
		ok("progression: open unlocks every subtopic", allUnlocked(p, none) && reason(p, none, "c").equals("policy_open"), LearningEngine.subtopicStates(p, none, null));
		p.policy = "sequential_completion";

		List<Attempt> at = new ArrayList<>();
		at.add(attempt("aq1", "learn", false, "noidea", 0, 5));
		at.add(attempt("aq2", "learn", false, "noidea", 0, 4));
		Learner done = learner(at);
		JSONObject b = state(p, done, "b");
		ok("progression: completion unlocks next (even all wrong)", b.get("unlocked").equals(true) && "previous_subtopic_complete".equals(b.get("unlockreason")) && b.get("newunlock") != null
			&& b.get("previouscomplete").equals(true) && "a".equals(b.get("previoussubtopicid")) && reason(p, done, "c").equals("previous_subtopic_incomplete"), b);

		ok("progression: evaluation answers do not complete", reason(p, learner(modeAll("evaluation", "aq1", "aq2")), "b").equals("previous_subtopic_incomplete"), "");
		ok("progression: improve answers do not complete", reason(p, learner(modeAll("improve", "aq1", "aq2")), "b").equals("previous_subtopic_incomplete"), "");
		ok("progression: legacy answers do not complete", reason(p, learner(modeAll(LearningEngine.LEGACY, "aq1", "aq2")), "b").equals("previous_subtopic_incomplete"), "");
		List<Attempt> mixed = modeAll("dailychallenge", "aq1");
		mixed.add(attempt("aq2", "learn", true, "confident", 0, 1));
		ok("progression: daily challenge answers count toward completion", reason(p, learner(mixed), "b").equals("previous_subtopic_complete"), "");

		// sequential_mastery, competent
		p.policy = "sequential_mastery";
		p.requiredlevel = "competent";
		b = state(p, done, "b");
		ok("progression: mastery needs the level, not just completion", b.get("unlocked").equals(false) && "previous_subtopic_below_required_mastery".equals(b.get("unlockreason"))
			&& "beginner".equals(b.get("previousband")) && b.get("previousmeetsrequirement").equals(false) && b.get("previouscomplete").equals(true), b);
		ok("progression: mastery needs completion even at the level", reason(p, learner(modeAll("learn", "aq1")), "b").equals("previous_subtopic_incomplete"), "");
		Learner good = learner(modeAll("learn", "aq1", "aq2"));
		b = state(p, good, "b");
		ok("progression: complete + competent unlocks (mastery_met)", b.get("unlocked").equals(true) && "previous_subtopic_mastery_met".equals(b.get("unlockreason")) && b.get("previousmeetsrequirement").equals(true), b);

		// expert: band expert without expert evidence (30 beginner correct + 1 expert wrong = 88%) stays locked
		Content ce = progression("sequential_mastery", "expert", 31, 2, 2);
		Topic pe = ce.topics.get("p");
		pe.sections.get(0).questions.get(30).difficulty = "expert";
		pe.sections.get(0).questions.get(30).weight = 4;
		List<Attempt> nearly = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			nearly.add(attempt("aq" + i, "learn", true, "confident", 0, 40 - i));
		}
		nearly.add(attempt("aq31", "learn", false, "confident", 0, 1));
		b = state(pe, learner(nearly), "b");
		ok("progression: expert band without expert evidence stays locked", "expert".equals(b.get("previousband")) && "previous_subtopic_missing_expert_evidence".equals(b.get("unlockreason")) && b.get("unlocked").equals(false), b);
		nearly.set(30, attempt("aq31", "learn", true, "confident", 0, 1));
		ok("progression: expert with evidence unlocks", reason(pe, learner(nearly), "b").equals("previous_subtopic_mastery_met"), state(pe, learner(nearly), "b"));

		// Daily Challenge: no new questions from locked subtopics
		p.policy = "sequential_completion";
		p.requiredlevel = null;
		Map<String, Boolean> req = new HashMap<>();
		req.put("p", true);
		Content big = progression("sequential_completion", null, 3, 10, 10);
		Learner fresh = learner();
		recorded.clear();
		java.util.Set<String> lockedNow = LearningEngine.locked(stubEngine().subtopicStates(big, fresh));
		JSONObject dc = LearningEngine.buildDailyChallenge(big, fresh, NOW, req, false, 5, 20, lockedNow);
		boolean onlyA = size(dc) > 0;
		for (Object o : items(dc))
		{
			onlyA &= "a".equals(((JSONObject) o).get("sectionid"));
		}
		ok("progression: daily challenge excludes unanswered questions of locked subtopics", onlyA && lockedNow.equals(new java.util.HashSet<>(List.of("b", "c"))) && size(dc) == 3, items(dc));

		// Learn: topic scope holds back locked questions; resolve rejects them
		JSONObject ln = LearningEngine.learnNext(p, null, none, stubEngine().subtopicStates(c, none));
		ok("progression: learn topic scope only unlocked subtopics, lockedquestions counted, not complete", ((Number) ln.get("total")).intValue() == 2 && ((Number) ln.get("lockedquestions")).intValue() == 4
			&& ln.get("complete").equals(false), ln);
		expectReject(stubEngine().resolve(c, none, "learn", "topic", "p", "bq1", new HashMap<>(), session("learn", "topic", "p", "aq1", "aq2", "bq1"), false), 409, "subtopic_locked");
		ok("progression: resolve learn in the first subtopic ok", stubEngine().resolve(c, none, "learn", "subtopic", "a", "aq1", new HashMap<>(), session("learn", "subtopic", "a", "aq1", "aq2"), true).question != null, "");
		ok("progression: not blocked while unlocked questions remain", ln.get("blocked").equals(false) && !ln.containsKey("blockedreason"), ln);

		// persistence: a met condition is recorded once; lower later mastery does not relock
		p.policy = "sequential_mastery";
		p.requiredlevel = "competent";
		recorded.clear();
		saved.clear();
		Map<String, JSONObject> st = stubEngine().subtopicStates(c, good);
		ok("progression: reading states never records (unlockedat null until recorded)", saved.isEmpty() && recorded.isEmpty() && st.get("b").get("unlocked").equals(true) && st.get("b").get("unlockedat") == null
			&& st.get("b").get("newunlock") == null, st.get("b"));
		int created = stubEngine().recordUnlocks(p, good);
		ok("progression: recordUnlocks records a met unlock with version, condition and evidence", created == 1 && saved.size() == 1 && "b".equals(saved.get(0)[1]) && "a".equals(saved.get(0)[2])
			&& "1".equals(saved.get(0)[3]) && "previous_subtopic_mastery_met".equals(saved.get(0)[6]) && saved.get(0)[7].contains("\"band\":\"expert\"") && saved.get(0)[7].contains("\"answered\":2"),
			saved.isEmpty() ? "none" : java.util.Arrays.toString(saved.get(0)));
		Date first = recorded.get("b");
		ok("progression: recordUnlocks is idempotent and create-only (date and evidence kept)", stubEngine().recordUnlocks(p, good) == 0 && saved.size() == 1 && recorded.get("b") == first, "");
		List<Attempt> worse = modeAll("learn", "aq1", "aq2");
		worse.add(attempt("aq1", "improve", false, "confident", 0, 0));
		worse.add(attempt("aq2", "improve", false, "confident", 0, 0));
		saved.clear();
		st = stubEngine().subtopicStates(c, learner(worse));
		ok("progression: lower later mastery keeps the recorded unlock", st.get("b").get("unlocked").equals(true) && "unlock_recorded".equals(st.get("b").get("unlockreason")) && saved.isEmpty(), st.get("b"));
		recorded.clear();
		// the accepted answer that completes a: withAttempt + recordUnlocks (what answer.json does)
		Learner half = learner(modeAll("learn", "aq1"));
		ok("progression: an accepted answer completing the subtopic records the next unlock", stubEngine().recordUnlocks(p, half) == 0
			&& stubEngine().recordUnlocks(p, LearningEngine.withAttempt(half, attempt("aq2", "learn", true, "confident", 0, 0))) == 1 && recorded.containsKey("b"), recorded);
		recorded.clear();
		// blocked: a complete but below competent; an improve answer that reaches competent unlocks b
		Learner below = learner(modeAll("learn", "aq1"));
		below = LearningEngine.withAttempt(below, attempt("aq2", "learn", false, "confident", 0, 0));
		JSONObject bl = LearningEngine.learnNext(p, null, below, stubEngine().subtopicStates(c, below));
		ok("progression: complete below mastery -> blocked with reason, band and improve recommendation", bl.get("blocked").equals(true) && bl.get("complete").equals(false)
			&& ((Number) bl.get("total")).intValue() == 0 && ((Number) bl.get("lockedquestions")).intValue() == 4 && "previous_subtopic_below_required_mastery".equals(bl.get("blockedreason"))
			&& "b".equals(bl.get("blockedsubtopicid")) && "a".equals(bl.get("previoussubtopicid")) && "competent".equals(bl.get("requiredlevel")) && "beginner".equals(bl.get("currentband"))
			&& "improve".equals(bl.get("recommendedmode")) && "a".equals(((JSONObject) bl.get("recommendedscope")).get("scopeid")), bl);
		ok("progression: an improve answer reaching the level records the unlock", stubEngine().recordUnlocks(p, below) == 0
			&& stubEngine().recordUnlocks(p, LearningEngine.withAttempt(below, attempt("aq2", "improve", true, "confident", 0, 0))) == 1 && recorded.containsKey("b"), recorded);
		recorded.clear();
		p.policy = "open";
		ok("progression: open records nothing", stubEngine().recordUnlocks(p, good) == 0 && recorded.isEmpty(), "");
		p.policy = "sequential_mastery";
		recorded.clear();
		List<Attempt> startedB = modeAll("learn", "bq1");
		ok("progression: a started subtopic stays unlocked after a stricter policy", reason(p, learner(startedB), "b").equals("subtopic_started"), "");
		List<Attempt> startedMet = modeAll("learn", "aq1", "aq2", "bq1");
		ok("progression: a started subtopic whose condition is met reports (and records) the condition", reason(p, learner(startedMet), "b").equals("previous_subtopic_mastery_met")
			&& state(p, learner(startedMet), "b").get("newunlock") != null, "");
		Learner exposedB = learner();
		exposedB.exposedInSequence.add("bq1");
		ok("progression: a learn exposure counts as started", reason(p, exposedB, "b").equals("subtopic_started"), "");

		// reorder a, b, c -> a, c, b after b was unlocked: b keeps its record, c is judged on its new predecessor a
		recorded.clear();
		recorded.put("b", NOW);
		java.util.Collections.swap(p.sections, 1, 2);
		JSONObject cst = state(p, good, "c");
		ok("progression: reorder keeps recorded unlocks and re-evaluates by the new order", reason(p, good, "b").equals("unlock_recorded") && "a".equals(cst.get("previoussubtopicid"))
			&& "previous_subtopic_mastery_met".equals(cst.get("unlockreason")) && ((Number) state(p, good, "b").get("position")).intValue() == 3, cst);
		recorded.clear();

		// configuration fallback
		ok("progression: missing policy -> open (policy_not_configured)", java.util.Arrays.equals(LearningEngine.effectivePolicy(null, null), new String[] {"open", null, "policy_not_configured"}), "");
		ok("progression: unknown policy -> sequential_completion (policy_invalid, fails safe)", java.util.Arrays.equals(LearningEngine.effectivePolicy("bogus", "expert"), new String[] {"sequential_completion", null, "policy_invalid"}), "");
		ok("progression: mastery without a canonical level -> sequential_completion (requiredlevel_invalid)", java.util.Arrays.equals(LearningEngine.effectivePolicy("sequential_mastery", null),
			new String[] {"sequential_completion", null, "requiredlevel_invalid"}) && java.util.Arrays.equals(LearningEngine.effectivePolicy("sequential_mastery", "master"), new String[] {"sequential_completion", null, "requiredlevel_invalid"}), "");
		ok("progression: valid policies kept; level only for mastery", java.util.Arrays.equals(LearningEngine.effectivePolicy("sequential_completion", "expert"), new String[] {"sequential_completion", null, null})
			&& java.util.Arrays.equals(LearningEngine.effectivePolicy("sequential_mastery", "expert"), new String[] {"sequential_mastery", "expert", null}), "");
	}

	static Content progression(String policy, String level, int... sizes)
	{
		Content c = new Content();
		Topic topic = new Topic();
		topic.id = "p";
		topic.competentmin = 60;
		topic.expertmin = 85;
		topic.policy = policy;
		topic.requiredlevel = level;
		topic.policyreason = null;
		topic.policyversion = 1;
		String[] names = {"a", "b", "c"};
		for (int k = 0; k < sizes.length; k++)
		{
			Section s = new Section();
			s.id = names[k];
			s.topicid = "p";
			for (int i = 1; i <= sizes[k]; i++)
			{
				Question q = new Question();
				q.id = s.id + "q" + i;
				q.componentid = "c" + q.id;
				q.sectionid = s.id;
				q.topicid = "p";
				q.difficulty = "beginner";
				q.weight = 1;
				q.position = topic.questions.size() + 1;
				s.questions.add(q);
				topic.questions.add(q);
				c.questions.put(q.id, q);
			}
			topic.sections.add(s);
			c.sections.put(s.id, s);
		}
		c.topics.put("p", topic);
		return c;
	}

	static List<Attempt> modeAll(String mode, String... qs)
	{
		List<Attempt> at = new ArrayList<>();
		for (String q : qs)
		{
			at.add(attempt(q, mode, true, "confident", 0, 10));
		}
		return at;
	}

	static JSONObject state(Topic t, Learner l, String section)
	{
		for (JSONObject o : LearningEngine.subtopicStates(t, l, recorded))
		{
			if (section.equals(o.get("id")))
			{
				return o;
			}
		}
		return null;
	}

	static String reason(Topic t, Learner l, String section)
	{
		return String.valueOf(state(t, l, section).get("unlockreason"));
	}

	static boolean allUnlocked(Topic t, Learner l)
	{
		for (JSONObject o : LearningEngine.subtopicStates(t, l, recorded))
		{
			if (!Boolean.TRUE.equals(o.get("unlocked")))
			{
				return false;
			}
		}
		return true;
	}

	static void expectReject(LearningEngine.Resolved r, int status, String error)
	{
		ok("resolve rejects " + error, r.question == null && r.status == status && error.equals(r.error), r.status + " " + r.error);
	}

	static double score(String confidence, int hint)
	{
		return LearningEngine.score(attempt("x", "learn", true, confidence, hint, 1));
	}

	static boolean remediation(Content c, List<Attempt> at)
	{
		return Boolean.TRUE.equals(inputs(build(c, learner(at))).get("remediation"));
	}

	/** n learning attempts over t1, the first h of them HCI on t1q1 (one distinct question), the rest correct confident. */
	static List<Attempt> recent(int n, int h)
	{
		List<Attempt> at = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			at.add(i < h ? attempt("t1q1", "learn", false, "confident", 0, 300 - i) : attempt("t1q" + (2 + (i - h) % 9), "learn", true, "confident", 0, 300 - i));
		}
		return at;
	}

	// ---- Evaluation Mode (spec 2026-09-16-evaluation-mode-design): pool, sufficiency, selection, scoring, status, Finished conjunct

	static void evaluationChecks()
	{
		Topic t = evalTopic();
		Blueprint b = bp("random", 9, 2, "balanced", 70, 0, 0, 0, 0, true);

		// pool
		ok("eval pool random = renderable sequence questions minus excluded subtopics", LearningEngine.evaluationPool(t, b).size() == 17, LearningEngine.evaluationPool(t, b).size());
		Blueprint ex = bp("random", 9, 2, "balanced", 70, 0, 0, 0, 0, true);
		ex.excludedsections.add("es3");
		ok("eval pool drops an excluded subtopic", LearningEngine.evaluationPool(t, ex).size() == 11, LearningEngine.evaluationPool(t, ex).size());
		ok("eval pool reserved = reserved questions only", LearningEngine.evaluationPool(t, bp("reserved", 5, 0, "proportional", 70, 0, 0, 0, 0, true)).size() == 6, "");

		// sufficiency
		ok("eval pool sufficient", Boolean.TRUE.equals(LearningEngine.poolReport(t, b).get("sufficient")) && LearningEngine.poolReport(t, b).get("shortfall") == null, LearningEngine.poolReport(t, b));
		ok("eval pool below max -> pool_below_max", "pool_below_max".equals(LearningEngine.poolReport(t, bp("random", 40, 0, "proportional", 70, 0, 0, 0, 0, true)).get("shortfall")), "");
		ok("eval subtopic below min -> subtopic_below_min", "subtopic_below_min".equals(LearningEngine.poolReport(t, bp("random", 17, 6, "proportional", 70, 0, 0, 0, 0, true)).get("shortfall")), "");
		ok("eval minimums exceed max -> minimums_exceed_max", "minimums_exceed_max".equals(LearningEngine.poolReport(t, bp("random", 9, 5, "proportional", 70, 0, 0, 0, 0, true)).get("shortfall")), "");
		JSONArray bys = (JSONArray) LearningEngine.poolReport(t, b).get("bysection");
		ok("eval pool report per subtopic (sequence 5, reserved 2, inpool 5 for es1)", bys.size() == 3 && ((JSONObject) bys.get(0)).get("sequence").equals(5) && ((JSONObject) bys.get(0)).get("reserved").equals(2) && ((JSONObject) bys.get(0)).get("inpool").equals(5), bys);

		// selection
		JSONObject e1 = LearningEngine.buildEvaluation(t, b, learner(), 42L);
		ok("eval selection n = maxquestions", items(e1).size() == 9, items(e1).size());
		ok("eval selection min per subtopic honoured", perSection(e1, "es1") >= 2 && perSection(e1, "es2") >= 2 && perSection(e1, "es3") >= 2, sectionsOf(e1));
		ok("eval selection presented in subtopic order", sectionsOf(e1).equals(sortedSections(e1)), sectionsOf(e1));
		ok("eval selection deterministic per seed", qids(items(e1)).equals(qids(items(LearningEngine.buildEvaluation(t, b, learner(), 42L)))), "");
		boolean differs = false;
		for (long s = 1; s <= 5 && !differs; s++)
		{
			differs = !qids(items(LearningEngine.buildEvaluation(t, b, learner(), s))).equals(qids(items(e1)));
		}
		ok("eval selection varies with the seed", differs, "");
		JSONObject bal = LearningEngine.buildEvaluation(t, bp("random", 9, 0, "balanced", 70, 0, 0, 0, 0, true), learner(), 7L);
		ok("eval balanced mix 3/3/3 when every level has enough", perDifficulty(bal, "beginner") == 3 && perDifficulty(bal, "competent") == 3 && perDifficulty(bal, "expert") == 3, difficultiesOf(bal));
		ok("eval no relaxation on a sufficient pool", ((JSONArray) inputs(bal).get("relaxations")).isEmpty(), inputs(bal));
		JSONObject shortMix = LearningEngine.buildEvaluation(t, bp("reserved", 6, 0, "balanced", 70, 0, 0, 0, 0, true), learner(), 7L);
		ok("eval balanced mix over an all-competent reserved pool -> mix_short, still fills n", ((JSONArray) inputs(shortMix).get("relaxations")).contains("mix_short") && items(shortMix).size() == 6, inputs(shortMix));
		JSONObject shortCov = LearningEngine.buildEvaluation(t, bp("random", 17, 6, "proportional", 70, 0, 0, 0, 0, true), learner(), 7L);
		ok("eval coverage short -> minpersubtopic_short", ((JSONArray) inputs(shortCov).get("relaxations")).contains("minpersubtopic_short"), inputs(shortCov));

		// retake avoids earlier questions; exposure counted
		Learner l = learner();
		EvalAttempt prev = new EvalAttempt();
		prev.topicid = "e1";
		prev.status = "submitted";
		prev.questions.addAll(qids(items(e1)));
		l.evaluations.add(prev);
		JSONObject e2 = LearningEngine.buildEvaluation(t, b, l, 43L);
		int overlap = 0;
		for (String id : qids(items(e2)))
		{
			if (prev.questions.contains(id))
			{
				overlap++;
			}
		}
		// 17 in pool, 9 used before, 9 drawn: at least 1 reused; coverage (2 per subtopic) can force up to 2 more when a subtopic was fully used
		ok("eval retake reuses only what the pool and coverage force (1..3 of 9), counted and flagged", overlap >= 1 && overlap <= 3 && e2.get("reused").equals(overlap) && ((JSONArray) inputs(e2).get("relaxations")).contains("reused"), overlap);
		Learner seen = learner(List.of(attempt("e1q1", "learn", true, "confident", 0, 5), attempt("e1q2", "improve", false, "notsure", 0, 4)));
		JSONObject e3 = LearningEngine.buildEvaluation(t, bp("random", 17, 0, "proportional", 70, 0, 0, 0, 0, true), seen, 1L);
		ok("eval exposed counts questions seen in learning modes", e3.get("exposed").equals(2), e3.get("exposed"));
		JSONObject e4 = LearningEngine.buildEvaluation(t, bp("reserved", 6, 0, "proportional", 70, 0, 0, 0, 0, true), seen, 1L);
		ok("eval reserved strategy: exposed 0", e4.get("exposed").equals(0) && items(e4).size() == 6, e4);

		// scoring
		EvalAttempt a = attemptOf(t, e1);
		int i = 0;
		for (String q : a.questions)
		{
			if (i < 7)
			{
				a.answers.put(q, true);
			}
			else if (i < 9)
			{
				a.answers.put(q, false);
			}
			i++;
		}
		a.answers.remove(a.questions.get(8)); // one unanswered
		JSONObject r = LearningEngine.scoreEvaluation(a, b, Map.of());
		ok("eval score 7/9 -> 78, answered 8, passed at 70", r.get("scorepercent").equals(78) && r.get("answered").equals(8) && Boolean.TRUE.equals(r.get("passed")) && r.get("failedrule") == null, r);
		ok("eval score fails at 80 -> failedrule overall", "overall".equals(LearningEngine.scoreEvaluation(a, bp("random", 9, 2, "balanced", 80, 0, 0, 0, 0, true), Map.of()).get("failedrule")), "");
		EvalAttempt sub = attemptOf(t, e1);
		for (String q : sub.questions)
		{
			sub.answers.put(q, !sub.sectionOf.get(q).equals("es3")); // every es3 question wrong
		}
		JSONObject rs = LearningEngine.scoreEvaluation(sub, bp("random", 9, 2, "balanced", 30, 50, 0, 0, 0, true), Map.of("es3", "Three")); // es3 holds 2..5 of 9: overall >= 44 passes 30, es3 at 0 fails the 50 floor
		ok("eval subtopic minimum fails -> failedrule subtopic, weakest first", "subtopic".equals(rs.get("failedrule")) && ((JSONArray) rs.get("weakest")).get(0).equals("es3"), rs);
		ok("eval subtopic titles carried", ((JSONArray) rs.get("subtopics")).toString().contains("Three"), rs.get("subtopics"));
		ok("eval rounding 2/3 -> 67", LearningEngine.scoreEvaluation(third(t), bp("random", 3, 0, "proportional", 67, 0, 0, 0, 0, true), Map.of()).get("scorepercent").equals(67), "");

		// status precedence
		Topic n = evalTopic();
		ok("eval status: no blueprint -> not_available not_configured", "not_available".equals(status(n, learner()).get("status")) && "not_configured".equals(status(n, learner()).get("reason")), status(n, learner()));
		n.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 0, 0, false);
		n.blueprint.active = false;
		LearningEngine.settle(n.blueprint);
		ok("eval status: inactive", "inactive".equals(status(n, learner()).get("reason")), status(n, learner()));
		ok("eval status: not_available carries no blueprint size or timer", !status(n, learner()).containsKey("maxquestions") && !status(n, learner()).containsKey("timerminutes"), status(n, learner()));
		n.blueprint = bp("random", 0, 0, "proportional", 70, 0, 0, 0, 0, false);
		ok("eval status: invalid stored value -> invalid_maxquestions", "invalid_maxquestions".equals(status(n, learner()).get("reason")), status(n, learner()));
		n.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 2, 1, true);
		ok("eval status: learn incomplete -> locked learn_incomplete", "locked".equals(status(n, learner()).get("status")) && "learn_incomplete".equals(status(n, learner()).get("reason")), status(n, learner()));
		Learner done = learner(allLearned(n));
		ok("eval status: available with attemptsleft 1", "available".equals(status(n, done).get("status")) && Boolean.TRUE.equals(status(n, done).get("canstart")) && status(n, done).get("attemptsleft").equals(1), status(n, done));
		n.blueprint.timerminutes = 25;
		ok("eval status: a usable blueprint carries maxquestions and timerminutes", status(n, done).get("maxquestions").equals(9) && status(n, done).get("timerminutes").equals(25), status(n, done));
		n.blueprint.timerminutes = 0;
		n.locked = true;
		ok("eval status: topic lock wins over learn state", "topic_locked".equals(status(n, done).get("reason")), status(n, done));
		n.locked = false;
		EvalAttempt open = new EvalAttempt();
		open.id = "u_x";
		open.topicid = "e1";
		open.total = 9;
		open.expires = new Date(NOW.getTime() + 60_000);
		done.evaluations.add(open);
		ok("eval status: open attempt -> in_progress with attemptid", "in_progress".equals(status(n, done).get("status")) && "u_x".equals(((JSONObject) status(n, done).get("inprogress")).get("attemptid")), status(n, done));
		open.expires = new Date(NOW.getTime() - 60_000);
		ok("eval status: a past expiresat is not open (in progress by row, expired by clock)", !"in_progress".equals(status(n, done).get("status")), status(n, done));
		done.evaluations.clear();
		EvalAttempt failed = new EvalAttempt();
		failed.topicid = "e1";
		failed.status = "submitted";
		failed.scorepercent = 40;
		failed.submitted = new Date(NOW.getTime() - 3600_000L);
		done.evaluations.add(failed);
		ok("eval status: failed 1 h ago with 2 h wait -> waiting with nextallowedat", "waiting".equals(status(n, done).get("status")) && status(n, done).get("nextallowedat") != null && status(n, done).get("scorepercent").equals(40), status(n, done));
		failed.submitted = new Date(NOW.getTime() - 3 * 3600_000L);
		ok("eval status: wait over but maxattempts 1 reached -> exhausted", "exhausted".equals(status(n, done).get("status")), status(n, done));
		n.blueprint = bp("random", 9, 0, "proportional", 70, 0, 0, 0, 0, true);
		ok("eval status: unlimited attempts -> available again, lastresult carried", "available".equals(status(n, done).get("status")) && ((JSONObject) status(n, done).get("lastresult")).get("scorepercent").equals(40), status(n, done));
		EvalAttempt passed = new EvalAttempt();
		passed.topicid = "e1";
		passed.status = "submitted";
		passed.passed = true;
		passed.scorepercent = 90;
		passed.submitted = NOW;
		done.evaluations.add(passed);
		ok("eval status: passed is terminal", "passed".equals(status(n, done).get("status")) && Boolean.FALSE.equals(status(n, done).get("canstart")) && status(n, done).get("passedat") != null, status(n, done));

		// Finished conjunct (job profiles)
		Topic f = evalTopic();
		f.evaluationrequired = true;
		Learner learned = learner(allLearned(f));
		ok("finished: evaluation required and not passed -> false even with Learn complete", !LearningEngine.finished(f, learned), "");
		learned.evaluations.add(passed);
		ok("finished: evaluation required and passed -> true", LearningEngine.finished(f, learned), "");
		f.evaluationrequired = false;
		ok("finished: not required -> unchanged rule", LearningEngine.finished(f, learner(allLearned(f))), "");
	}

	/** Topic e1: 3 subtopics x 6 sequence questions (difficulty cycling beginner/competent/expert), es1q6 unrenderable, plus 2 reserved per subtopic. */
	static Topic evalTopic()
	{
		Topic t = new Topic();
		t.id = "e1";
		t.title = "Eval";
		t.competentmin = 60;
		t.expertmin = 85;
		for (int s = 1; s <= 3; s++)
		{
			Section sec = new Section();
			sec.id = "es" + s;
			sec.title = "Sub " + s;
			sec.topicid = t.id;
			for (int i = 1; i <= 6; i++)
			{
				Question q = new Question();
				q.id = "e1q" + ((s - 1) * 6 + i);
				q.componentid = "c" + q.id;
				q.sectionid = sec.id;
				q.topicid = t.id;
				q.difficulty = LearningEngine.LEVELS.get((i - 1) % 3);
				q.weight = LearningEngine.weightOf(q.difficulty);
				q.position = t.questions.size() + 1;
				if (s == 1 && i == 6)
				{
					q.contentproblem = "missing_option_c";
				}
				sec.questions.add(q);
				t.questions.add(q);
			}
			for (int i = 1; i <= 2; i++)
			{
				Question q = new Question();
				q.id = "e1r" + s + i;
				q.componentid = "c" + q.id;
				q.sectionid = sec.id;
				q.topicid = t.id;
				q.difficulty = "competent";
				q.weight = 2;
				t.reserved.add(q);
			}
			t.sections.add(sec);
		}
		return t;
	}

	static Blueprint bp(String strategy, int max, int min, String mix, int pass, int submin, int timer, int wait, int maxatt, boolean requireLearn)
	{
		Blueprint b = new Blueprint();
		b.topicid = "e1";
		b.version = 1;
		b.active = true;
		b.strategy = strategy;
		b.maxquestions = max;
		b.minpersubtopic = min;
		b.mix = mix;
		b.passpercent = pass;
		b.subtopicminpercent = submin;
		b.timerminutes = timer;
		b.retakewaithours = wait;
		b.maxattempts = maxatt;
		b.requirelearncomplete = requireLearn;
		return LearningEngine.settle(b);
	}

	static JSONObject status(Topic t, Learner l)
	{
		return LearningEngine.evaluationStatus(t, l, NOW);
	}

	static List<Attempt> allLearned(Topic t)
	{
		List<Attempt> at = new ArrayList<>();
		for (Question q : t.questions)
		{
			at.add(attempt(q.id, "learn", true, "confident", 0, 10));
		}
		return at;
	}

	/** An in-progress attempt over built's items. */
	static EvalAttempt attemptOf(Topic t, JSONObject built)
	{
		EvalAttempt a = new EvalAttempt();
		a.id = "u_a";
		a.topicid = t.id;
		for (Object o : items(built))
		{
			JSONObject i = (JSONObject) o;
			a.questions.add((String) i.get("questionid"));
			a.sectionOf.put((String) i.get("questionid"), (String) i.get("sectionid"));
		}
		a.total = a.questions.size();
		return a;
	}

	/** 3 questions, 2 correct. */
	static EvalAttempt third(Topic t)
	{
		EvalAttempt a = new EvalAttempt();
		a.topicid = t.id;
		for (int i = 1; i <= 3; i++)
		{
			a.questions.add("e1q" + i);
			a.sectionOf.put("e1q" + i, "es1");
			a.answers.put("e1q" + i, i < 3);
		}
		a.total = 3;
		return a;
	}

	static List<String> qids(JSONArray inItems)
	{
		List<String> out = new ArrayList<>();
		for (Object o : inItems)
		{
			out.add((String) ((JSONObject) o).get("questionid"));
		}
		return out;
	}

	static List<String> sectionsOf(JSONObject built)
	{
		List<String> out = new ArrayList<>();
		for (Object o : items(built))
		{
			out.add((String) ((JSONObject) o).get("sectionid"));
		}
		return out;
	}

	static List<String> sortedSections(JSONObject built)
	{
		List<String> s = new ArrayList<>(sectionsOf(built));
		java.util.Collections.sort(s);
		return s;
	}

	static int perSection(JSONObject built, String inSection)
	{
		int n = 0;
		for (String s : sectionsOf(built))
		{
			if (s.equals(inSection))
			{
				n++;
			}
		}
		return n;
	}

	static List<String> difficultiesOf(JSONObject built)
	{
		List<String> out = new ArrayList<>();
		for (Object o : items(built))
		{
			out.add((String) ((JSONObject) o).get("difficulty"));
		}
		return out;
	}

	static int perDifficulty(JSONObject built, String inLevel)
	{
		int n = 0;
		for (String d : difficultiesOf(built))
		{
			if (d.equals(inLevel))
			{
				n++;
			}
		}
		return n;
	}

	// ---- fixtures: 2 topics x 10 questions (one section each), both required, min 5 / max 20

	static Content content()
	{
		return content(2, 10);
	}

	static Content content(int topics, int perTopic)
	{
		Content c = new Content();
		for (int t = 1; t <= topics; t++)
		{
			Topic topic = new Topic();
			topic.id = "t" + t;
			topic.index = t - 1;
			topic.competentmin = 60;
			topic.expertmin = 85;
			Section s = new Section();
			s.id = "s" + t;
			s.topicid = topic.id;
			for (int i = 1; i <= perTopic; i++)
			{
				Question q = new Question();
				q.id = topic.id + "q" + i;
				q.componentid = "c" + q.id;
				q.sectionid = s.id;
				q.topicid = topic.id;
				q.difficulty = "beginner";
				q.weight = 1;
				q.position = i;
				q.topicindex = topic.index;
				s.questions.add(q);
				topic.questions.add(q);
				c.questions.put(q.id, q);
			}
			topic.sections.add(s);
			c.topics.put(topic.id, topic);
			c.sections.put(s.id, s);
		}
		return c;
	}

	static Attempt attempt(String q, String mode, boolean correct, String confidence, int hint, int hoursAgo)
	{
		Attempt a = new Attempt();
		a.questionid = q;
		a.mode = mode;
		a.correct = correct;
		a.confidence = confidence;
		a.hintlevel = hint;
		a.at = new Date(NOW.getTime() - hoursAgo * 3600_000L);
		return a;
	}

	static Learner learner()
	{
		return learner(new ArrayList<>());
	}

	static Learner learner(List<Attempt> at)
	{
		return LearningEngine.learner("u", at);
	}

	static JSONObject build(Content c, Learner l)
	{
		Map<String, Boolean> required = new HashMap<>();
		required.put("t1", true);
		required.put("t2", true);
		return LearningEngine.buildDailyChallenge(c, l, NOW, required, false, 5, 20, null);
	}

	// ---- helpers

	static void ok(String name, boolean pass, Object detail)
	{
		System.out.println((pass ? "ok: " : "FAIL: ") + name + (pass ? "" : " -> " + detail));
		failures += pass ? 0 : 1;
	}

	static JSONArray items(JSONObject dc)
	{
		return (JSONArray) dc.get("items");
	}

	static JSONObject inputs(JSONObject dc)
	{
		return (JSONObject) dc.get("inputs");
	}

	static int num(JSONObject dc, String key)
	{
		return ((Number) inputs(dc).get(key)).intValue();
	}

	static int size(JSONObject dc)
	{
		return items(dc).size();
	}

	static int count(JSONObject dc, String bucket)
	{
		int n = 0;
		for (Object o : items(dc))
		{
			n += bucket.equals(((JSONObject) o).get("bucket")) ? 1 : 0;
		}
		return n;
	}

	static String bucketAt(JSONObject dc, int i)
	{
		return String.valueOf(((JSONObject) items(dc).get(i)).get("bucket"));
	}

	static String bucketOf(JSONObject dc, String q)
	{
		for (Object o : items(dc))
		{
			if (q.equals(((JSONObject) o).get("questionid")))
			{
				return String.valueOf(((JSONObject) o).get("bucket"));
			}
		}
		return "absent";
	}

	static boolean contains(JSONObject dc, String q)
	{
		return !"absent".equals(bucketOf(dc, q));
	}

	static int perTopic(JSONObject dc, String topic)
	{
		int n = 0;
		for (Object o : items(dc))
		{
			n += topic.equals(((JSONObject) o).get("topicid")) ? 1 : 0;
		}
		return n;
	}

	static int maxPerTopic(JSONObject dc)
	{
		return Math.max(perTopic(dc, "t1"), perTopic(dc, "t2"));
	}

	static List<?> relaxations(JSONObject dc)
	{
		return (List<?>) inputs(dc).get("relaxations");
	}

	/** New items of each topic, in item order, are that topic's first unanswered positions without gaps. */
	static boolean prefixes(JSONObject dc)
	{
		Map<String, Integer> last = new HashMap<>();
		Map<String, Integer> firstFree = new HashMap<>();
		for (Object o : items(dc))
		{
			JSONObject it = (JSONObject) o;
			if (!"new".equals(it.get("bucket")))
			{
				firstFree.merge(String.valueOf(it.get("topicid")), 0, Math::max);
				continue;
			}
			String t = String.valueOf(it.get("topicid"));
			int pos = ((Number) it.get("position")).intValue();
			Integer prev = last.get(t);
			if (prev != null && pos != prev + 1)
			{
				return false;
			}
			last.put(t, pos);
		}
		return true;
	}

	static String pattern(boolean[] isNew)
	{
		StringBuilder b = new StringBuilder();
		for (boolean n : isNew)
		{
			b.append(n ? 'N' : 'R');
		}
		return b.toString();
	}

	static String ids(List<Question> qs)
	{
		List<String> out = new ArrayList<>();
		for (Question q : qs)
		{
			out.add(q.id);
		}
		return String.join(",", out);
	}
}
