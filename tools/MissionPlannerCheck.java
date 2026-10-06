import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine;
import tech.genailabs.tutor.LearningEngine.*;
import tech.genailabs.tutor.MissionPlanner;

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
		paceOrderCheck();
		certExpiryOutsideWindowCheck();
		renewalReadyCheck();
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
	static void ok(String name, boolean cond, Object detail)
	{
		System.out.println((cond ? "ok: " : "FAIL: ") + name + (cond ? "" : " -> " + detail));
		if (!cond) failures++;
	}
}
