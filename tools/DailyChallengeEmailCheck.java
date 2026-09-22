import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.entermediadb.asset.modules.AdminModule;
import org.json.simple.JSONObject;
import tech.genailabs.tutor.LearningEngine;
import tech.genailabs.tutor.LearningEngine.Attempt;
import tech.genailabs.tutor.LearningEngine.Content;
import tech.genailabs.tutor.LearningEngine.Learner;
import tech.genailabs.tutor.LearningEngine.Question;
import tech.genailabs.tutor.LearningEngine.Section;
import tech.genailabs.tutor.LearningEngine.Topic;
import tech.genailabs.tutor.TestUAnalyticsModule;
import tech.genailabs.tutor.TestUAnalyticsModule.DoneRow;
import tech.genailabs.tutor.TestULearningModule;

/**
 * Pure checks of the Daily Challenge email (no server): the weekday / 09:00 / timezone selection and the once-a-day key, the
 * sign-in link token, the email copy, the "what to practise next" recommendation and the "done" screen funnel. From the server root, after bin/compile.sh:
 * java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib tomcat/lib -name '*.jar' | tr '\n' ':')" plugins/testu/tools/DailyChallengeEmailCheck.java
 */
public class DailyChallengeEmailCheck
{
	static int failures = 0;
	static final ZoneId LIMA = ZoneId.of("America/Lima"), MADRID = ZoneId.of("Europe/Madrid"), TOKYO = ZoneId.of("Asia/Tokyo");

	public static void main(String[] args)
	{
		// ---- selection: Monday 2026-09-21 09:00 Lima = 14:00Z
		Instant mon9Lima = Instant.parse("2026-09-21T14:00:00Z");
		ok("Lima Monday 09:00 is due", "u_20260921".equals(TestULearningModule.emailDueKey("u", LIMA, mon9Lima)), TestULearningModule.emailDueKey("u", LIMA, mon9Lima));
		ok("Lima Monday 09:59 is due", TestULearningModule.emailDueKey("u", LIMA, Instant.parse("2026-09-21T14:59:00Z")) != null, "");
		ok("Lima Monday 08:59 is not", TestULearningModule.emailDueKey("u", LIMA, Instant.parse("2026-09-21T13:59:00Z")) == null, "");
		ok("Lima Monday 10:00 is not", TestULearningModule.emailDueKey("u", LIMA, Instant.parse("2026-09-21T15:00:00Z")) == null, "");
		ok("Lima Saturday 09:00 is not", TestULearningModule.emailDueKey("u", LIMA, Instant.parse("2026-09-19T14:00:00Z")) == null, "");
		ok("Lima Sunday 09:00 is not", TestULearningModule.emailDueKey("u", LIMA, Instant.parse("2026-09-20T14:00:00Z")) == null, "");
		ok("same instant is 16:00 in Madrid: not due", TestULearningModule.emailDueKey("u", MADRID, mon9Lima) == null, "");
		ok("Madrid Monday 09:00 (07:00Z, summer time) is due", TestULearningModule.emailDueKey("u", MADRID, Instant.parse("2026-09-21T07:00:00Z")) != null, "");
		// Tokyo Monday 09:00 = Sunday 00:00Z: the key is the learner's local date, and it is a weekday there.
		ok("Tokyo Monday 09:00 keyed by the local date", "u_20260921".equals(TestULearningModule.emailDueKey("u", TOKYO, Instant.parse("2026-09-21T00:00:00Z"))),
			TestULearningModule.emailDueKey("u", TOKYO, Instant.parse("2026-09-21T00:00:00Z")));
		ok("Tokyo Saturday 09:00 (Friday 00:00Z) is not", TestULearningModule.emailDueKey("u", TOKYO, Instant.parse("2026-09-19T00:00:00Z")) == null, "");
		ok("user zone wins", TestULearningModule.zoneOf("Europe/Madrid", LIMA).equals(MADRID), "");
		ok("no user zone -> org", TestULearningModule.zoneOf(" ", LIMA).equals(LIMA) && TestULearningModule.zoneOf(null, LIMA).equals(LIMA), "");
		ok("invalid user zone -> org", TestULearningModule.zoneOf("Mars/Olympus", LIMA).equals(LIMA), "");

		// ---- idempotency: the event every 15 min for a week, the sent-row ids as the store -> one email per weekday, none at weekends
		for (ZoneId z : List.of(LIMA, MADRID, TOKYO))
		{
			Set<String> store = new HashSet<>();
			int sends = 0;
			for (Instant t = Instant.parse("2026-09-19T00:07:00Z"); t.isBefore(Instant.parse("2026-09-26T00:07:00Z")); t = t.plusSeconds(15 * 60))
			{
				String key = TestULearningModule.emailDueKey("u", z, t);
				if (key != null && store.add(key)) // dailyChallengeEmail: searchById(key) == null, then save the row, then send
				{
					sends++;
				}
			}
			ok("a week of 15-min runs sends 5 in " + z, sends == 5 && store.size() == 5, sends + " " + store);
		}

		// ---- sign-in link token
		String a = AdminModule.newLoginLinkToken(), b = AdminModule.newLoginLinkToken();
		ok("token is 43 url-safe chars", a.length() == 43 && a.matches("[A-Za-z0-9_-]+"), a);
		ok("tokens differ", !a.equals(b), "");
		ok("stored form is a hash, not the token", AdminModule.loginLinkHash(a).startsWith("link:") && AdminModule.loginLinkHash(a).length() == 69 && !AdminModule.loginLinkHash(a).contains(a), AdminModule.loginLinkHash(a));
		ok("hash is stable", AdminModule.loginLinkHash(a).equals(AdminModule.loginLinkHash(a)), "");
		ok("a 6-digit code is never a link (rejected before any lookup)", AdminModule.consumeLoginLink(null, "123456", new Date(), 12) == null, "");
		Date now = new Date();
		ok("fresh at 11h59", AdminModule.loginLinkFresh(new Date(now.getTime() - (12 * 3600_000L - 60_000)), now, 12), "");
		ok("expired at 12h", !AdminModule.loginLinkFresh(new Date(now.getTime() - 12 * 3600_000L), now, 12), "");
		ok("future-dated row rejected", !AdminModule.loginLinkFresh(new Date(now.getTime() + 60_000), now, 12), "");
		ok("no date rejected", !AdminModule.loginLinkFresh(null, now, 12), "");

		// ---- email copy: tutor and name from data, escaped, link in both parts
		String link = "https://x.test/site/learn/#/desafio?login=" + a;
		String[] es = TestULearningModule.emailContent(false, "Renzo", "IRIS", link);
		ok("es subject", "Renzo, tu Desafío Diario te espera".equals(es[0]), es[0]);
		ok("es body names the tutor and links", es[1].contains("IRIS") && es[1].contains(link) && es[2].contains(link) && es[1].contains("Empezar mi desafío"), es[2]);
		ok("es never says Reto", !(es[0] + es[1] + es[2]).toLowerCase().contains("reto"), "");
		String[] en = TestULearningModule.emailContent(true, "", "Sully", link);
		ok("en without a name", "Your Daily Challenge is waiting".equals(en[0]) && en[2].startsWith("Hi,"), en[0]);
		ok("en uses the org's tutor, no other", en[1].contains("Sully") && !en[1].contains("IRIS"), "");
		ok("name is escaped", TestULearningModule.emailContent(false, "<b>x", "IRIS", link)[1].contains("&lt;b&gt;x"), "");
		ok("given name", "Renzo".equals(TestULearningModule.givenName("RENZO ALDAIR")) && "".equals(TestULearningModule.givenName(null)), TestULearningModule.givenName("RENZO ALDAIR"));

		// ---- recommendation after the day's challenge
		Content c = content();
		ok("nothing answered: learn the first topic's first subtopic", rec(c, List.of(), t -> null, "learn", "t1", "t1s1"), recommend(c, List.of(), t -> null));
		ok("below required level first: t2 required competent", rec(c, List.of(), t -> "t2".equals(t) ? "competent" : null, "learn", "t2", "t2s1"),
			recommend(c, List.of(), t -> "t2".equals(t) ? "competent" : null));
		List<Attempt> at = new ArrayList<>();
		for (Question q : c.topics.get("t1").questions)
		{
			at.add(attempt(q.id, !q.id.equals("t1q4"), q.sectionid.equals("t1s1") ? "confident" : "notsure")); // t1s2 weak
		}
		for (Question q : c.topics.get("t2").questions)
		{
			at.add(attempt(q.id, true, "confident"));
		}
		ok("all answered: improve the weakest subtopic of the weakest topic", rec(c, at, t -> null, "improve", "t1", "t1s2"), recommend(c, at, t -> null));
		c.topics.get("t1").locked = true;
		ok("locked topics are skipped", rec(c, at, t -> null, "improve", "t2", null), recommend(c, at, t -> null));

		// ---- "done" screen funnel (engagement.json dailydone), Lima day 2026-09-21
		List<DoneRow> ev = new ArrayList<>(), ss = new ArrayList<>();
		// a: from the email, takes the recommendation, completes it
		ev.add(row("a", "dailydone_shown", "email", "t1", "15:00"));
		ev.add(row("a", "dailydone_click", "email", "t1", "15:01"));
		ss.add(session("a", "dailydone", "t1", "15:01", true));
		// b: opens the app, takes it, leaves it unfinished
		ev.add(row("b", "dailydone_shown", "app", "t1", "15:00"));
		ev.add(row("b", "dailydone_click", "app", "t1", "15:02"));
		ss.add(session("b", "dailydone", "t1", "15:02", false));
		// c: dismisses, later starts another topic on their own; d: dismisses, later the recommended topic on their own
		ev.add(row("c", "dailydone_shown", "app", "t1", "15:00"));
		ev.add(row("c", "dailydone_dismiss", "app", null, "15:00"));
		ss.add(session("c", null, "t2", "18:00", false));
		ev.add(row("d", "dailydone_shown", "email", "t1", "15:00"));
		ev.add(row("d", "dailydone_dismiss", "email", null, "15:00"));
		ss.add(session("d", null, "t1", "16:00", true));
		// e: dismisses, a session before the showing and one the next day do not count
		ev.add(row("e", "dailydone_shown", "app", "t1", "15:00"));
		ev.add(row("e", "dailydone_dismiss", "app", null, "15:00"));
		ss.add(session("e", null, "t2", "14:00", true));
		ss.add(new DoneRow("e", "learn", null, "t2", Date.from(Instant.parse("2026-09-22T15:00:00Z")), true));
		// f: shown, no action; its own tagged-less session counts as own
		ev.add(row("f", "dailydone_shown", "email", "t1", "15:00"));
		ss.add(session("f", null, "t2", "15:30", false));
		JSONObject f = TestUAnalyticsModule.dailyDoneFunnel(ev, ss, LIMA);
		ok("funnel email", counts(f, "email").equals("shown=3 clicked=1 dismissed=1 started=1 completed=1 ownstarted=2 ownsame=1 owndifferent=1"), counts(f, "email"));
		ok("funnel app", counts(f, "app").equals("shown=3 clicked=1 dismissed=2 started=1 completed=0 ownstarted=1 ownsame=0 owndifferent=1"), counts(f, "app"));

		System.out.println(failures == 0 ? "all daily challenge email checks passed" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** Two topics x two subtopics x two questions; t1s1 = t1q1,t1q2, t1s2 = t1q3,t1q4. */
	static Content content()
	{
		Content c = new Content();
		for (String tid : List.of("t1", "t2"))
		{
			Topic t = new Topic();
			t.id = tid;
			t.title = tid;
			t.competentmin = 60;
			t.expertmin = 85;
			int n = 0;
			for (String sid : List.of(tid + "s1", tid + "s2"))
			{
				Section s = new Section();
				s.id = sid;
				s.title = sid;
				s.topicid = tid;
				for (int i = 0; i < 2; i++)
				{
					Question q = new Question();
					q.id = tid + "q" + (++n);
					q.sectionid = sid;
					q.topicid = tid;
					q.difficulty = "beginner";
					q.weight = 1;
					q.position = n;
					s.questions.add(q);
					t.questions.add(q);
					c.questions.put(q.id, q);
				}
				t.sections.add(s);
				c.sections.put(sid, s);
			}
			c.topics.put(tid, t);
		}
		return c;
	}

	/** A usage event at HH:mm UTC on 2026-09-21 (10:00 Lima = 15:00Z). */
	static DoneRow row(String user, String type, String source, String topic, String hhmm)
	{
		return new DoneRow(user, type, source, topic, Date.from(Instant.parse("2026-09-21T" + hhmm + ":00Z")), false);
	}

	static DoneRow session(String user, String source, String topic, String hhmm, boolean complete)
	{
		return new DoneRow(user, "improve", source, topic, Date.from(Instant.parse("2026-09-21T" + hhmm + ":00Z")), complete);
	}

	static String counts(JSONObject f, String entry)
	{
		StringBuilder sb = new StringBuilder();
		for (String k : List.of("shown", "clicked", "dismissed", "started", "completed", "ownstarted", "ownsame", "owndifferent"))
		{
			sb.append(sb.length() == 0 ? "" : " ").append(k).append('=').append(((JSONObject) f.get(entry)).get(k));
		}
		return sb.toString();
	}

	static Attempt attempt(String q, boolean correct, String confidence)
	{
		Attempt a = new Attempt();
		a.questionid = q;
		a.mode = "learn";
		a.correct = correct;
		a.confidence = confidence;
		a.at = new Date();
		return a;
	}

	static JSONObject recommend(Content c, List<Attempt> at, java.util.function.Function<String, String> req)
	{
		Learner l = LearningEngine.learner("u", at);
		return LearningEngine.recommend(c, l, null, req);
	}

	static boolean rec(Content c, List<Attempt> at, java.util.function.Function<String, String> req, String mode, String topic, String section)
	{
		JSONObject r = recommend(c, at, req);
		return r != null && mode.equals(r.get("mode")) && topic.equals(r.get("topicid")) && (section == null || section.equals(r.get("sectionid")));
	}

	static void ok(String name, boolean pass, Object detail)
	{
		System.out.println((pass ? "ok: " : "FAIL: ") + name + (pass ? "" : " -> " + detail));
		failures += pass ? 0 : 1;
	}
}
