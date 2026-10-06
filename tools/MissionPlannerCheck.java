import java.time.ZoneId;
import java.util.*;
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
		// certification expiry sooner than target wins
		Topic ct = topic("fatiga", 60, 85); ct.validitymonths = 12;
		Learner cl = learner(); target(cl, "fatiga", "2026-12-31");
		CertRow cr = new CertRow(); cr.user = "u"; cr.topicid = "fatiga"; cr.passedat = LearningEngine.parseYmd("2025-10-20");
		cl.certifications.put("fatiga", cr);
		ok("certification expiry wins when sooner", "2026-10-20".equals(LearningEngine.ymd(MissionPlanner.deadline(ct, cl, LIMA), LIMA)), MissionPlanner.deadline(ct, cl, LIMA));
		// remind_later instants
		ok("tomorrow = 09:00 Lima", "2026-10-05T14:00:00Z".equals(MissionPlanner.remindAt("tomorrow", NOW, LIMA).toInstant().toString()), MissionPlanner.remindAt("tomorrow", NOW, LIMA));
		ok("unknown when -> null", MissionPlanner.remindAt("later", NOW, LIMA) == null, "");
		missionChecks();
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
		JSONObject rec = new JSONObject(); rec.put("mode", "learn"); rec.put("topicid", "b"); rec.put("sectionid", "b1");
		JSONObject m = MissionPlanner.mission(c, l, states, rec, NOW, LIMA);
		ok("goal = topic with the earliest deadline over a lower pace topic", "b".equals(((JSONObject) m.get("goal")).get("topic")), m);
		ok("actions: start_session first, remind_later last, no book_evaluation", types(m).equals(List.of("start_session", "remind_later")), m);
		Map<String, JSONObject> empty = new LinkedHashMap<>();
		ok("no required topics -> no_goal, no actions", "no_goal".equals(MissionPlanner.mission(new Content(), learner(), empty, null, NOW, LIMA).get("status")), "");
		states.put("b", state("b", "competent", true, 70, "competent", true));
		m = MissionPlanner.mission(c, l, states, rec, NOW, LIMA);
		ok("ready goal offers book_evaluation first", "book_evaluation".equals(types(m).get(0)), m);
	}

	static List<String> types(JSONObject m)
	{
		List<String> out = new ArrayList<>();
		for (Object o : (JSONArray) m.get("actions")) { out.add(String.valueOf(((JSONObject) o).get("type"))); }
		return out;
	}

	static Topic topic(String id, int comp, int exp) { Topic t = new Topic(); t.id = id; t.title = id; t.competentmin = comp; t.expertmin = exp; return t; }
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
