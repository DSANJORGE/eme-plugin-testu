package tech.genailabs.tutor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine.Attempt;
import tech.genailabs.tutor.LearningEngine.Content;
import tech.genailabs.tutor.LearningEngine.Learner;
import tech.genailabs.tutor.LearningEngine.Topic;

/**
 * Mission agent (spec 2026-10-05): goal, status, week plan and the actions valid right now, per learner. Pure: callers load
 * Content/Learner and the topicState JSON; nothing here reads or writes storage.
 */
public final class MissionPlanner
{
	public static final int GAIN_PER_SESSION = 4, PACE_SESSIONS = 3, MAX_SESSIONS = 5, HISTORY_DAYS = 28;
	public static final List<String> REMIND_WHEN = List.of("2h", "tonight", "tomorrow");

	private MissionPlanner()
	{
	}

	public static Date deadline(Topic t, Learner l, ZoneId z)
	{
		Date best = null;
		LearningEngine.TargetRow tr = l.targets.get(t.id);
		if (tr != null && tr.duedate != null)
		{
			best = LearningEngine.endOfDay(tr.duedate, z);
		}
		Date expiry = LearningEngine.expiryOf(l.certifications.get(t.id), t, z);
		if (expiry != null && (best == null || expiry.before(best)))
		{
			best = expiry;
		}
		return best;
	}

	static int requiredMin(JSONObject s)
	{
		String req = (String) s.get("requiredlevel");
		if ("expert".equals(req))
		{
			return LearningEngine.intOr(s.get("expertmin"), 85);
		}
		return "competent".equals(req) ? LearningEngine.intOr(s.get("competentmin"), 60) : 0;
	}

	public static JSONObject topicPlan(Topic t, JSONObject s, Learner l, Date now, ZoneId z)
	{
		boolean meets = Boolean.TRUE.equals(s.get("meetsrequirement"));
		JSONObject eval = (JSONObject) s.get("evaluation");
		boolean canstart = eval != null && Boolean.TRUE.equals(eval.get("canstart"));
		int pct = LearningEngine.intOr(s.get("masterypercent"), 0), gap = Math.max(0, requiredMin(s) - pct);
		Date dl = deadline(t, l, z);
		Long daysleft = dl == null ? null : ChronoUnit.DAYS.between(now.toInstant().atZone(z).toLocalDate(), dl.toInstant().atZone(z).toLocalDate());
		String status;
		if (meets && canstart)
			status = "ready";
		else if (meets)
			status = "done";
		else if (dl != null && now.after(dl))
			status = "overdue";
		else if (dl == null)
			status = "pace";
		else
			status = atRisk(t, l, s, gap, daysleft, now, z) ? "at_risk" : "on_track";
		JSONObject o = new JSONObject();
		o.put("topic", t.id);
		o.put("topictitle", t.title);
		o.put("status", status);
		o.put("requiredlevel", s.get("requiredlevel"));
		o.put("band", s.get("band"));
		o.put("masterypercent", pct);
		o.put("gap", gap);
		o.put("deadline", dl == null ? null : LearningEngine.ymd(dl, z));
		o.put("deadlinesource", dl == null ? null : deadlineSource(t, l, z, dl));
		o.put("daysleft", daysleft);
		o.put("canstart", canstart);
		return o;
	}

	static String deadlineSource(Topic t, Learner l, ZoneId z, Date dl)
	{
		Date expiry = LearningEngine.expiryOf(l.certifications.get(t.id), t, z);
		if (expiry != null && expiry.equals(dl))
		{
			return "certification";
		}
		LearningEngine.TargetRow tr = l.targets.get(t.id);
		return tr == null ? null : tr.source;
	}

	static boolean atRisk(Topic t, Learner l, JSONObject s, int gap, long daysleft, Date now, ZoneId z)
	{
		Map<String, Object> f = Forecast.of(history(t, l, now, z, HISTORY_DAYS), requiredMin(s) / 100.0);
		String fs = String.valueOf(f.get("status"));
		if ("insufficient".equals(fs))
		{
			int sessions = (gap + GAIN_PER_SESSION - 1) / GAIN_PER_SESSION;
			return sessions > daysleft / 2.0; // ponytail: one session every other day is the most we plan for
		}
		if ("notonpace".equals(fs))
		{
			return true;
		}
		Object eta = f.get("eta");
		return !"reached".equals(fs) && (eta == null || ((Number) eta).longValue() > daysleft);
	}

	/** ponytail: replays up to HISTORY_DAYS learner() builds per topic; fine at pilot sizes, cache per request if it shows in profiles. */
	public static List<Double> history(Topic t, Learner l, Date now, ZoneId z, int days)
	{
		List<Double> out = new ArrayList<>();
		LocalDate today = now.toInstant().atZone(z).toLocalDate();
		LocalDate first = null;
		for (Attempt a : l.attempts)
		{
			if (a.at != null)
			{
				first = a.at.toInstant().atZone(z).toLocalDate();
				break;
			}
		}
		if (first == null)
		{
			return out;
		}
		LocalDate start = today.minusDays(days - 1);
		if (first.isAfter(start))
		{
			start = first;
		}
		for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1))
		{
			Date cut = Date.from(d.plusDays(1).atStartOfDay(z).toInstant());
			List<Attempt> upto = new ArrayList<>();
			for (Attempt a : l.attempts)
			{
				if (a.at != null && a.at.before(cut))
					upto.add(a);
			}
			out.add(LearningEngine.percent(t.questions, LearningEngine.learner(l.userid, upto)) / 100.0);
		}
		return out;
	}

	public static JSONObject mission(Content c, Learner l, Map<String, JSONObject> inStates, JSONObject inRecommend, Date now, ZoneId z)
	{
		List<JSONObject> plans = new ArrayList<>();
		for (Topic t : c.topics.values())
		{
			JSONObject s = inStates.get(t.id);
			if (s == null || s.get("requiredlevel") == null || Boolean.TRUE.equals(s.get("locked")))
			{
				continue;
			}
			plans.add(topicPlan(t, s, l, now, z));
		}
		JSONObject goal = pick(plans);
		JSONObject o = new JSONObject();
		o.put("status", goal == null ? "no_goal" : goal.get("status"));
		o.put("goal", goal);
		o.put("week", goal == null ? null : week(goal, c.topics.get(goal.get("topic")), l, now, z));
		int others = 0;
		for (JSONObject p : plans)
		{
			if (p != goal && p.get("deadline") != null && !"done".equals(p.get("status")))
				others++;
		}
		o.put("othergoals", others);
		o.put("actions", goal == null ? new JSONArray() : actions(goal, c.topics.get(goal.get("topic")), l, inRecommend, now, z));
		JSONArray all = new JSONArray();
		all.addAll(plans);
		o.put("plans", all); // every required topic's plan: the Topics tab shows per-row deadlines
		return o;
	}

	static int rank(String status)
	{
		return switch (status)
		{
			case "overdue" -> 0;
			case "ready" -> 1;
			case "at_risk", "on_track" -> 2;
			case "pace" -> 3;
			default -> 9;
		};
	}

	/** Lowest rank; ties by earliest deadline; then learner order (stable). */
	static JSONObject pick(List<JSONObject> plans)
	{
		JSONObject best = null;
		for (JSONObject p : plans)
		{
			int r = rank(String.valueOf(p.get("status")));
			if (r == 9)
				continue;
			if (best == null)
			{
				best = p;
				continue;
			}
			int br = rank(String.valueOf(best.get("status")));
			String d = (String) p.get("deadline"), bd = (String) best.get("deadline");
			if (r < br || (r == br && d != null && (bd == null || d.compareTo(bd) < 0)))
				best = p;
		}
		return best;
	}

	static JSONObject week(JSONObject goal, Topic t, Learner l, Date now, ZoneId z)
	{
		int needed;
		Object dl = goal.get("daysleft");
		int gap = LearningEngine.intOr(goal.get("gap"), 0);
		if (dl == null)
			needed = PACE_SESSIONS;
		else
		{
			long weeks = Math.max(1, (((Number) dl).longValue() + 6) / 7);
			needed = (int) Math.max(1, Math.min(MAX_SESSIONS, Math.ceil(Math.ceil(gap / (double) GAIN_PER_SESSION) / weeks)));
		}
		LocalDate monday = now.toInstant().atZone(z).toLocalDate().with(java.time.DayOfWeek.MONDAY);
		java.util.Set<String> ids = LearningEngine.ids(t.questions);
		java.util.Set<LocalDate> days = new java.util.HashSet<>();
		for (Attempt a : l.attempts)
		{
			if (a.at != null && ids.contains(a.questionid) && LearningEngine.isLearningMode(a.mode))
			{
				LocalDate d = a.at.toInstant().atZone(z).toLocalDate();
				if (!d.isBefore(monday))
					days.add(d);
			}
		}
		JSONObject w = new JSONObject();
		w.put("sessionsneeded", needed);
		w.put("sessionsdone", Math.min(days.size(), needed));
		return w;
	}

	static JSONArray actions(JSONObject goal, Topic t, Learner l, JSONObject rec, Date now, ZoneId z)
	{
		JSONArray out = new JSONArray();
		String topic = (String) goal.get("topic");
		if ("ready".equals(goal.get("status")))
		{
			String cs = LearningEngine.certStatus(t, l.certifications.get(topic), now, z);
			LearningEngine.CertRow cr = l.certifications.get(topic);
			boolean renewal = ("renewal_due".equals(cs) || "expired".equals(cs)) && (cr == null || cr.scheduledfor == null);
			out.add(action(renewal ? "schedule_certification" : "book_evaluation", topic, null, null));
		}
		if (rec != null && topic.equals(rec.get("topicid")) && !"ready".equals(goal.get("status")))
		{
			out.add(action("start_session", topic, (String) rec.get("mode"), (String) rec.get("sectionid")));
		}
		out.add(action("remind_later", topic, null, null));
		for (int i = 0; i < out.size(); i++)
		{
			((JSONObject) out.get(i)).put("id", "a" + (i + 1));
		}
		return out;
	}

	static JSONObject action(String type, String topic, String mode, String section)
	{
		JSONObject a = new JSONObject();
		a.put("type", type);
		a.put("topic", topic);
		if (mode != null)
			a.put("mode", mode);
		if (section != null)
			a.put("section", section);
		return a;
	}

	public static Date remindAt(String when, Date now, ZoneId z)
	{
		ZonedDateTime n = now.toInstant().atZone(z);
		return switch (when == null ? "" : when)
		{
			case "2h" -> Date.from(n.plusHours(2).toInstant());
			case "tonight" -> Date.from((n.getHour() >= 19 ? n.plusDays(1) : n).withHour(19).withMinute(0).withSecond(0).withNano(0).toInstant());
			case "tomorrow" -> Date.from(n.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0).toInstant());
			default -> null;
		};
	}
}
