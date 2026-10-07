import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine;
import tech.genailabs.tutor.LearningEngine.*;
import tech.genailabs.tutor.MissionPlanner;
import tech.genailabs.tutor.TestUAnalyticsModule;

/** Pure checks of MissionPlanner (spec 2026-10-05). Run by tools/check_learning.sh. */
public class MissionPlannerCheck
{
	static int failures = 0;
	static final ZoneId LIMA = ZoneId.of("America/Lima");
	static final Date NOW = LearningEngine.parseYmd("2026-10-05"); // midnight UTC = 19:00 Oct 4 Lima

	public static void main(String[] args)
	{
		Topic t = topic("fatiga", 60, 85);
		// no deadline, below required -> pace
		JSONObject st = state("fatiga", "competent", false, 40, "beginner", false);
		ok("pace without deadline", "pace".equals(MissionPlanner.topicPlan(t, st, learner(), NOW, LIMA).get("status")), st);
		// meets requirement + canstart -> ready, even with a deadline
		Learner l = learner(); target(l, "fatiga", "2026-11-14");
		st = state("fatiga", "competent", true, 70, "competent", true);
		ok("ready when met and canstart", "ready".equals(MissionPlanner.topicPlan(t, st, l, NOW, LIMA).get("status")), st);
		// met, cannot start (passed) -> done
		st = state("fatiga", "competent", true, 70, "competent", false);
		ok("done when met and nothing to book", "done".equals(MissionPlanner.topicPlan(t, st, l, NOW, LIMA).get("status")), st);
		// deadline passed -> overdue
		Learner late = learner(); target(late, "fatiga", "2026-10-01");
		st = state("fatiga", "competent", false, 40, "beginner", false);
		ok("overdue after the deadline", "overdue".equals(MissionPlanner.topicPlan(t, st, late, NOW, LIMA).get("status")), st);
		// insufficient history: gap 20 pts = 5 sessions > 4 days / 2 -> at_risk
		Learner soon = learner(); target(soon, "fatiga", "2026-10-09");
		ok("at_risk when sessions needed exceed half the days left", "at_risk".equals(MissionPlanner.topicPlan(t, st, soon, NOW, LIMA).get("status")), MissionPlanner.topicPlan(t, st, soon, NOW, LIMA));
		// far deadline, small gap -> on_track
		ok("on_track with room", "on_track".equals(MissionPlanner.topicPlan(t, st, l, NOW, LIMA).get("status")), MissionPlanner.topicPlan(t, st, l, NOW, LIMA));
		// certification expiry sooner than target wins (within the renewal window: renewal_due)
		Topic ct = topic("fatiga", 60, 85); ct.validitymonths = 12;
		Learner cl = learner(); target(cl, "fatiga", "2026-12-31");
		CertRow cr = new CertRow(); cr.user = "u"; cr.topicid = "fatiga"; cr.passedat = LearningEngine.parseYmd("2025-10-20");
		cl.certifications.put("fatiga", cr);
		ok("certification expiry wins when sooner", "2026-10-20".equals(LearningEngine.ymd(MissionPlanner.deadline(ct, cl, NOW, LIMA), LIMA)), MissionPlanner.deadline(ct, cl, NOW, LIMA));
		// remind_later instants
		ok("tomorrow = 09:00 Lima", "2026-10-05T14:00:00Z".equals(MissionPlanner.remindAt("tomorrow", NOW, LIMA).toInstant().toString()), MissionPlanner.remindAt("tomorrow", NOW, LIMA));
		ok("unknown when -> null", MissionPlanner.remindAt("later", NOW, LIMA) == null, "");
		// Fix round 1, item 5: a target due the local Lima "today" (NOW = 19:00 Oct 4 Lima, so "today" = Oct 4) is not overdue
		Learner todayDue = learner(); target(todayDue, "fatiga", "2026-10-04");
		JSONObject todayPlan = MissionPlanner.topicPlan(t, st, todayDue, NOW, LIMA);
		ok("a duedate of the local Lima today is not overdue", !"overdue".equals(todayPlan.get("status")), todayPlan);
		missionChecks();
		weekChecks();
		unfinishedChecks();
		missionPushChecks();
		paceOrderCheck();
		certExpiryOutsideWindowCheck();
		renewalReadyCheck();
		doLinesCheck();
		coachDoLinesCheck();
		if (failures > 0) { System.out.println(failures + " FAILED"); System.exit(1); }
		System.out.println("MissionPlannerCheck: all ok");
	}

	static void missionChecks()
	{
		Content c = new Content();
		Topic a = topic("a", 60, 85), b = topic("b", 60, 85);
		c.topics.put("a", a); c.topics.put("b", b);
		Learner l = learner(); target(l, "b", "2026-10-20");
		Map<String, JSONObject> states = new LinkedHashMap<>();
		states.put("a", state("a", "competent", false, 30, "beginner", false));
		states.put("b", state("b", "competent", false, 50, "beginner", false));
		Function<String, JSONObject> recommendOf = tid -> { JSONObject r = new JSONObject(); r.put("mode", "learn"); r.put("topicid", tid); r.put("sectionid", tid + "1"); return r; };
		JSONObject m = MissionPlanner.mission(c, l, states, recommendOf, NOW, LIMA);
		ok("goal = topic with the earliest deadline over a lower pace topic", "b".equals(((JSONObject) m.get("goal")).get("topic")), m);
		ok("actions: start_session first, remind_later last, no book_evaluation", types(m).equals(List.of("start_session", "remind_later")), m);
		Map<String, JSONObject> empty = new LinkedHashMap<>();
		ok("no required topics -> no_goal, no actions", "no_goal".equals(MissionPlanner.mission(new Content(), learner(), empty, null, NOW, LIMA).get("status")), "");
		states.put("b", state("b", "competent", true, 70, "competent", true));
		m = MissionPlanner.mission(c, l, states, recommendOf, NOW, LIMA);
		ok("ready goal offers book_evaluation first", "book_evaluation".equals(types(m).get(0)), m);
	}

	// Fix round 1, item 1: tied pace candidates (no deadlines) order like recommend() -- below required first, then lowest %.
	static void paceOrderCheck()
	{
		Content c = new Content();
		Topic a = topic("a", 60, 85), b = topic("b", 60, 85);
		c.topics.put("a", a); c.topics.put("b", b);
		Learner l = learner(); // no targets, no certifications -> both pace
		Map<String, JSONObject> states = new LinkedHashMap<>();
		states.put("a", state("a", "competent", false, 50, "beginner", false));
		states.put("b", state("b", "competent", false, 30, "beginner", false));
		JSONObject m = MissionPlanner.mission(c, l, states, tid -> null, NOW, LIMA);
		ok("pace tie-break matches recommend(): both below required, lower % (b, 30) wins over a (50)",
			"b".equals(((JSONObject) m.get("goal")).get("topic")), m);
	}

	// Fix round 1, item 2: certified but outside the renewal window (expiry ~11 months out) never drives the plan.
	static void certExpiryOutsideWindowCheck()
	{
		Topic t = topic("faroff", 60, 85); t.validitymonths = 12;
		Learner l = learner();
		CertRow cr = new CertRow(); cr.user = l.userid; cr.topicid = "faroff"; cr.passedat = LearningEngine.parseYmd("2026-09-05"); // expiry ~2027-09-05
		l.certifications.put("faroff", cr);
		JSONObject st = state("faroff", "competent", false, 40, "beginner", false); // mastery below required
		JSONObject plan = MissionPlanner.topicPlan(t, st, l, NOW, LIMA);
		ok("certified far from expiry (outside renewal window) is pace, not on_track/at_risk against that expiry",
			"pace".equals(plan.get("status")), plan);
		ok("far expiry does not surface as the deadline", plan.get("deadline") == null, plan);
	}

	// Fix round 1, item 3: a schedulable renewal (due/expired, not yet booked) is ready even below the required level.
	static void renewalReadyCheck()
	{
		Topic t = topic("renew", 60, 85); t.validitymonths = 1;
		Learner l = learner();
		CertRow cr = new CertRow(); cr.user = l.userid; cr.topicid = "renew"; cr.passedat = LearningEngine.parseYmd("2026-09-10"); // expiry 2026-10-10, 5 days out
		l.certifications.put("renew", cr);
		JSONObject st = state("renew", "competent", false, 40, "beginner", false); // mastery below required
		JSONObject plan = MissionPlanner.topicPlan(t, st, l, NOW, LIMA);
		ok("a schedulable renewal is ready even though mastery is below the required level", "ready".equals(plan.get("status")), plan);

		Content c = new Content(); c.topics.put("renew", t);
		Map<String, JSONObject> states = new LinkedHashMap<>(); states.put("renew", st);
		JSONObject m = MissionPlanner.mission(c, l, states, tid -> null, NOW, LIMA);
		ok("renewal-ready goal offers schedule_certification first", "schedule_certification".equals(types(m).get(0)), m);
	}

	// Task 8 (tutor chat actions): the deterministic gate for the offered-id filter, since the chat reply itself depends on an
	// LLM (check_mission.sh, flaky by nature). MissionPlanner.doLines/stripDoLines/appendDoLines/offerText are the exact
	// pure methods TestULearningModule.offer()/appendActions() (the methods finder actually calls) delegate to.
	static void doLinesCheck()
	{
		JSONObject a1 = action("a1", "start_session", "fatiga", "learn", "fatiga-s1");
		JSONObject a2 = action("a2", "remind_later", "fatiga", null, null);
		JSONObject a3 = action("a3", "book_evaluation", "fatiga", null, null);
		List<JSONObject> offered = List.of(a1, a2, a3);
		ok("doLines: both offered ids kept, in SERVER (offered) order, formatted [[do ...]]",
			List.of("[[do start_session topic=fatiga mode=learn section=fatiga-s1]]", "[[do remind_later topic=fatiga]]")
				.equals(MissionPlanner.doLines(List.of("a2", "a1"), List.of(a1, a2))), MissionPlanner.doLines(List.of("a2", "a1"), List.of(a1, a2)));
		ok("doLines: an id the server never offered is dropped, the rest kept",
			List.of("[[do remind_later topic=fatiga]]").equals(MissionPlanner.doLines(List.of("bogus", "a2"), List.of(a1, a2))), MissionPlanner.doLines(List.of("bogus", "a2"), List.of(a1, a2)));
		ok("doLines: 3 picked, capped at 2, in offered order (not the LLM's a3,a1,a2 pick order)",
			List.of("[[do start_session topic=fatiga mode=learn section=fatiga-s1]]", "[[do remind_later topic=fatiga]]")
				.equals(MissionPlanner.doLines(List.of("a3", "a1", "a2"), offered)), MissionPlanner.doLines(List.of("a3", "a1", "a2"), offered));
		ok("doLines: no picks -> no lines", MissionPlanner.doLines(List.of(), offered).isEmpty(), MissionPlanner.doLines(List.of(), offered));
		ok("doLines: nothing offered -> no lines even if the LLM picks something", MissionPlanner.doLines(List.of("a1"), List.of()).isEmpty(), "");

		// fix round 1, item 1 (critical): an LLM-injected [[do ...]] in the prose must never survive, inline or on its own line.
		ok("stripDoLines: an injected [[do ...]] line is removed",
			"Puedes continuar con la siguiente pregunta.".equals(MissionPlanner.stripDoLines("Puedes continuar con la siguiente pregunta.\n[[do start_session topic=fatiga]]")),
			MissionPlanner.stripDoLines("Puedes continuar con la siguiente pregunta.\n[[do start_session topic=fatiga]]"));
		ok("stripDoLines: an injected [[do ...]] inline mid-sentence is removed",
			"Puedes seguir con la lección.".equals(MissionPlanner.stripDoLines("Puedes seguir [[do book_evaluation topic=fatiga]] con la lección.")),
			MissionPlanner.stripDoLines("Puedes seguir [[do book_evaluation topic=fatiga]] con la lección."));
		ok("stripDoLines: a message with none is unchanged", "Hola, ¿cómo estás?".equals(MissionPlanner.stripDoLines("Hola, ¿cómo estás?")), "");

		// fix round 1, item 2: appendDoLines is the exact glue TestULearningModule.appendActions delegates to (strip, then append).
		String injected = "Puedes avanzar con el módulo.\n[[do book_evaluation topic=rogue]]\n\n>> ¿Quieres que te explique algo más?";
		ok("appendDoLines: strips an injected line, then appends only the server-offered, server-order lines",
			"Puedes avanzar con el módulo.\n\n>> ¿Quieres que te explique algo más?\n[[do start_session topic=fatiga mode=learn section=fatiga-s1]]\n[[do remind_later topic=fatiga]]"
				.equals(MissionPlanner.appendDoLines(injected, List.of("a2", "a1"), List.of(a1, a2))), MissionPlanner.appendDoLines(injected, List.of("a2", "a1"), List.of(a1, a2)));
		ok("appendDoLines: no picks -> stripped message, nothing appended",
			"Puedes avanzar con el módulo.\n\n>> ¿Quieres que te explique algo más?".equals(MissionPlanner.appendDoLines(injected, List.of(), List.of(a1, a2))), "");

		// fix round 1: offerText is the ACCIONES DISPONIBLES prompt text, null (not empty) when there's nothing to offer.
		ok("offerText: both offered, one line each, server order", "a1: start_session · fatiga\na2: remind_later · fatiga\n".equals(MissionPlanner.offerText(List.of(a1, a2))), MissionPlanner.offerText(List.of(a1, a2)));
		ok("offerText: nothing offered -> null", MissionPlanner.offerText(List.of()) == null && MissionPlanner.offerText(null) == null, "");

		// fix round 2, item 2 (N1): the same snapshot in gives the same lines out, every time -- that's exactly why
		// offer()'s one actionsFor() snapshot must be the one appendActions() is handed back, never a fresh second call.
		List<JSONObject> snapshot = List.of(a1, a2);
		ok("doLines: the same snapshot given twice produces identical lines",
			MissionPlanner.doLines(List.of("a2"), snapshot).equals(MissionPlanner.doLines(List.of("a2"), snapshot)), "");
		// A fresh actionsFor() call a moment later, after the mission moved on, could rebind "a2" to a different action --
		// this is what offer()'s single snapshot, reused by appendActions(), prevents.
		JSONObject a2Moved = action("a2", "book_evaluation", "otronivel", null, null);
		ok("doLines: a DIFFERENT snapshot for the same id produces a different line (why one snapshot must be reused)",
			!MissionPlanner.doLines(List.of("a2"), List.of(a1, a2Moved)).equals(MissionPlanner.doLines(List.of("a2"), snapshot)),
			List.of(MissionPlanner.doLines(List.of("a2"), List.of(a1, a2Moved)), MissionPlanner.doLines(List.of("a2"), snapshot)));
	}

	// Task 14 (Ask-IRIS action buttons): coachDoLines is the deterministic gate for the coach-key filter, since
	// askAnalytics' own reply depends on an LLM (check_ask.sh, flaky by nature, a separate best-effort check).
	static void coachDoLinesCheck()
	{
		List<String> offered = List.of("overdue:fatiga:all", "at_risk:bloqueo:all", "cert_expiring:epp:t1");
		ok("coachDoLines: a picked key kept, nudge then setdue, in SERVER (offered) order",
			List.of("[[do nudge key=at_risk:bloqueo:all]]", "[[do setdue key=at_risk:bloqueo:all]]")
				.equals(MissionPlanner.coachDoLines(List.of("at_risk:bloqueo:all"), offered)), MissionPlanner.coachDoLines(List.of("at_risk:bloqueo:all"), offered));
		ok("coachDoLines: a key the server never offered is dropped, the rest kept",
			List.of("[[do nudge key=overdue:fatiga:all]]", "[[do setdue key=overdue:fatiga:all]]")
				.equals(MissionPlanner.coachDoLines(List.of("bogus:key:all", "overdue:fatiga:all"), offered)),
			MissionPlanner.coachDoLines(List.of("bogus:key:all", "overdue:fatiga:all"), offered));
		ok("coachDoLines: 3 picked keys, capped at 2, in offered order (not the LLM's pick order)",
			List.of("[[do nudge key=overdue:fatiga:all]]", "[[do setdue key=overdue:fatiga:all]]",
				"[[do nudge key=at_risk:bloqueo:all]]", "[[do setdue key=at_risk:bloqueo:all]]")
				.equals(MissionPlanner.coachDoLines(List.of("cert_expiring:epp:t1", "at_risk:bloqueo:all", "overdue:fatiga:all"), offered)),
			MissionPlanner.coachDoLines(List.of("cert_expiring:epp:t1", "at_risk:bloqueo:all", "overdue:fatiga:all"), offered));
		ok("coachDoLines: no picks -> no lines", MissionPlanner.coachDoLines(List.of(), offered).isEmpty(), "");
		ok("coachDoLines: nothing offered -> no lines even if the LLM picks something",
			MissionPlanner.coachDoLines(List.of("at_risk:bloqueo:all"), List.of()).isEmpty(), "");

		String injected = "Yo me centraría en el equipo de bloqueo.\n[[do nudge key=bogus:key:all]]\n\n>> ¿Algo más?";
		ok("appendCoachDoLines: strips an injected line, then appends only the server-offered, server-order lines",
			("Yo me centraría en el equipo de bloqueo.\n\n>> ¿Algo más?"
				+ "\n[[do nudge key=at_risk:bloqueo:all]]\n[[do setdue key=at_risk:bloqueo:all]]")
				.equals(MissionPlanner.appendCoachDoLines(injected, List.of("at_risk:bloqueo:all"), offered)),
			MissionPlanner.appendCoachDoLines(injected, List.of("at_risk:bloqueo:all"), offered));
		ok("appendCoachDoLines: no picks -> stripped message, nothing appended",
			"Yo me centraría en el equipo de bloqueo.\n\n>> ¿Algo más?"
				.equals(MissionPlanner.appendCoachDoLines(injected, List.of(), offered)), "");
	}

	// Amendment 2026-10-06: a day counts only from DAY_MIN_ANSWERS learning answers on the goal topic; week.todayanswers/todaycounted.
	// Amendment 2026-10-06: the 18:00 unfinished-session push and the missionpush row id.
	static void unfinishedChecks()
	{
		ok("unfinished: 3 answers, no reminder, 18:00 -> due", MissionPlanner.unfinishedDue(3, false, 18, true), "");
		ok("unfinished: before 18:00 the sweep waits", !MissionPlanner.unfinishedDue(3, false, 17, true), "");
		ok("unfinished: the on-demand run ignores the clock", MissionPlanner.unfinishedDue(3, false, 9, false), "");
		ok("unfinished: 0 answers is not a session", !MissionPlanner.unfinishedDue(0, false, 18, true), "");
		ok("unfinished: 5 answers already counted", !MissionPlanner.unfinishedDue(5, false, 18, true), "");
		ok("unfinished: a pending reminder wins", !MissionPlanner.unfinishedDue(3, true, 18, true), "");
		ok("unfinished text, plural", "Te faltan 2 preguntas para que hoy cuente en tu objetivo de Fatiga.".equals(MissionPlanner.unfinishedText(3, "Fatiga")), MissionPlanner.unfinishedText(3, "Fatiga"));
		ok("unfinished text, singular", "Te falta 1 pregunta para que hoy cuente en tu objetivo de Fatiga.".equals(MissionPlanner.unfinishedText(4, "Fatiga")), MissionPlanner.unfinishedText(4, "Fatiga"));
		ok("missionpush id = <notification>_<yyyyMMddHHmm> UTC", "u_mission_remind_202610050000".equals(MissionPlanner.pushRowId("u_mission_remind", NOW)), MissionPlanner.pushRowId("u_mission_remind", NOW));
	}

	// NOW = 19:00 Sun 4 Oct Lima, so this week runs Mon 28 Sep .. Sun 4 Oct.
	static void weekChecks()
	{
		Content c = new Content();
		Topic a = topic("a", 60, 85);
		c.topics.put("a", a);
		Learner l = learner(); target(l, "a", "2026-10-20");
		attempts(l, "a-q1", "learn", "2026-09-29T15:00:00Z", 5);       // Tue: 5 -> counts
		attempts(l, "a-q1", "improve", "2026-09-30T15:00:00Z", 4);     // Wed: 4 -> does not
		attempts(l, "a-q1", "learn", "2026-09-27T15:00:00Z", 5);       // Sun 27 Sep: last week
		attempts(l, "a-q1", "learn", "2026-10-04T20:00:00Z", 3);       // today, 15:00 Lima: 3
		attempts(l, "a-q1", "evaluation", "2026-10-04T21:00:00Z", 2);  // not a learning mode
		Map<String, JSONObject> states = new LinkedHashMap<>();
		states.put("a", state("a", "competent", false, 30, "beginner", false));
		JSONObject m = MissionPlanner.mission(c, l, states, tid -> null, NOW, LIMA);
		JSONObject w = (JSONObject) m.get("week");
		ok("week: only the 5-answer day counts", Integer.valueOf(1).equals(w.get("sessionsdone")), w);
		ok("week: todayanswers = today's learning answers on the goal topic", Integer.valueOf(3).equals(w.get("todayanswers")), w);
		ok("week: todaycounted false below 5", Boolean.FALSE.equals(w.get("todaycounted")), w);
		ok("goal carries requiredmin", Integer.valueOf(60).equals(((JSONObject) m.get("goal")).get("requiredmin")), m.get("goal"));
		attempts(l, "a-q1", "dailychallenge", "2026-10-04T22:00:00Z", 2);
		w = (JSONObject) MissionPlanner.mission(c, l, states, tid -> null, NOW, LIMA).get("week");
		ok("week: today reaches 5 -> counted", Integer.valueOf(2).equals(w.get("sessionsdone")) && Boolean.TRUE.equals(w.get("todaycounted")), w);
	}

	static void attempts(Learner l, String q, String mode, String iso, int n)
	{
		for (int i = 0; i < n; i++)
		{
			Attempt at = new Attempt(); at.questionid = q; at.mode = mode; at.at = Date.from(java.time.Instant.parse(iso).plusSeconds(60L * i));
			l.attempts.add(at);
		}
	}

	static JSONObject action(String id, String type, String topic, String mode, String section)
	{
		JSONObject a = new JSONObject();
		a.put("id", id); a.put("type", type); a.put("topic", topic);
		if (mode != null) a.put("mode", mode);
		if (section != null) a.put("section", section);
		return a;
	}

	static List<String> types(JSONObject m)
	{
		List<String> out = new ArrayList<>();
		for (Object o : (JSONArray) m.get("actions")) { out.add(String.valueOf(((JSONObject) o).get("type"))); }
		return out;
	}

	static Topic topic(String id, int comp, int exp)
	{
		Topic t = new Topic(); t.id = id; t.title = id; t.competentmin = comp; t.expertmin = exp;
		Question q = new Question(); q.id = id + "-q1"; q.sectionid = id + "-s1"; q.topicid = id; // non-empty, like every real topic recommend() considers
		t.questions.add(q);
		return t;
	}
	static Learner learner() { Learner l = new Learner(); l.userid = "u"; return l; }
	static void target(Learner l, String topic, String ymd) { TargetRow r = new TargetRow(); r.user = l.userid; r.topicid = topic; r.duedate = LearningEngine.parseYmd(ymd); r.source = "profile"; l.targets.put(topic, r); }
	static JSONObject state(String id, String required, boolean meets, int pct, String band, boolean canstart)
	{
		JSONObject s = new JSONObject(); s.put("id", id); s.put("title", id); s.put("requiredlevel", required); s.put("meetsrequirement", meets);
		s.put("masterypercent", pct); s.put("band", band); s.put("competentmin", 60); s.put("expertmin", 85); s.put("locked", false);
		JSONObject e = new JSONObject(); e.put("canstart", canstart); s.put("evaluation", e);
		return s;
	}
	// Amendment 2026-10-06: push effectiveness per kind (engagement.json missionpushes), Lima day 2026-09-21.
	static void missionPushChecks()
	{
		List<TestUAnalyticsModule.DoneRow> sent = new ArrayList<>(), opens = new ArrayList<>(), answers = new ArrayList<>();
		sent.add(row("a", "remind", "t1", "2026-09-21T14:00:00Z"));
		sent.add(row("b", "remind", "t1", "2026-09-21T14:00:00Z"));
		sent.add(row("a", "unfinished", "t1", "2026-09-21T23:00:00Z"));
		sent.add(row("c", "coach", "t2", "2026-09-21T15:00:00Z"));
		opens.add(row("a", "remind", "t1", "2026-09-21T14:10:00Z"));
		opens.add(row("b", "unfinished", "t1", "2026-09-21T14:10:00Z")); // another kind's tap: not this push
		opens.add(row("c", "coach", "t2", "2026-09-23T15:00:00Z"));      // after 24 h
		for (int i = 0; i < 5; i++)
			answers.add(row("a", null, "t1", "2026-09-21T15:0" + i + ":00Z"));
		for (int i = 0; i < 2; i++)
			answers.add(row("a", null, "t1", "2026-09-22T01:0" + i + ":00Z")); // 20:0x Lima, still the 21st
		answers.add(row("b", null, "t2", "2026-09-21T15:00:00Z")); // another topic
		JSONObject p = TestUAnalyticsModule.missionPushes(sent, opens, answers, LIMA);
		ok("pushes: remind", "sent=2 tapped=1 practised=1 counted=1".equals(pushOf(p, "remind")), pushOf(p, "remind"));
		ok("pushes: unfinished counts only answers inside its own 24 h", "sent=1 tapped=0 practised=1 counted=0".equals(pushOf(p, "unfinished")), pushOf(p, "unfinished"));
		ok("pushes: coach, a tap after 24 h is ignored", "sent=1 tapped=0 practised=0 counted=0".equals(pushOf(p, "coach")), pushOf(p, "coach"));
		ok("pushes: remind tapped rate 0.5", Double.valueOf(0.5).equals(((JSONObject) p.get("remind")).get("tappedrate")), p.get("remind"));
		ok("pushes: nothing sent -> rates null", ((JSONObject) p.get("ready")).get("tappedrate") == null, p.get("ready"));
	}

	static TestUAnalyticsModule.DoneRow row(String user, String kind, String topic, String iso)
	{
		return new TestUAnalyticsModule.DoneRow(user, kind, null, topic, Date.from(java.time.Instant.parse(iso)), false);
	}

	static String pushOf(JSONObject p, String kind)
	{
		JSONObject k = (JSONObject) p.get(kind);
		return "sent=" + k.get("sent") + " tapped=" + k.get("tapped") + " practised=" + k.get("practised") + " counted=" + k.get("counted");
	}

	static void ok(String name, boolean cond, Object detail)
	{
		System.out.println((cond ? "ok: " : "FAIL: ") + name + (cond ? "" : " -> " + detail));
		if (!cond) failures++;
	}
}
