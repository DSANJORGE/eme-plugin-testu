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
 * sign-in link token, who receives it (master switch, role permission, test allowlist), the weekday copy with the streak line,
 * the "what to practise next" recommendation and the "done" screen funnel. From the server root, after bin/compile.sh:
 * java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib tomcat/lib -name '*.jar' | tr '\n' ':')" plugins/testu/tools/DailyChallengeEmailCheck.java [previewdir [otppreviewdir]]
 * With previewdir, also writes the Spanish previews (one per weekday, with and without streak data) there; with a second dir, the
 * login-code email previews (es, en).
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

		// ---- who gets it: master switch (on by default, only "false" turns it off), role permission, test allowlist
		List<String> can = List.of("view", TestULearningModule.EMAIL_PERMISSION), cannot = List.of("view");
		ok("switch unset: on for roles with the permission", TestULearningModule.mayReceive(null, null, null, null) && TestULearningModule.mayReceive(null, null, "a@x.pe", can)
			&& TestULearningModule.mayReceive("", "", "a@x.pe", can), "");
		ok("switch false: nobody", !TestULearningModule.mayReceive("false", null, null, null) && !TestULearningModule.mayReceive(" FALSE ", null, "a@x.pe", can), "");
		ok("switch true: roles with the permission", TestULearningModule.mayReceive(" true ", null, null, null) && TestULearningModule.mayReceive("true", null, "a@x.pe", can), "");
		ok("a role without it is skipped", !TestULearningModule.mayReceive("true", null, "a@x.pe", cannot) && !TestULearningModule.mayReceive(null, null, "a@x.pe", null), "");
		ok("allowlist works with the switch off", TestULearningModule.mayReceive("false", "Diego@X.pe, b@x.pe", "diego@x.pe", can) && TestULearningModule.mayReceive(null, "b@x.pe", null, null), "");
		ok("allowlist excludes everyone else, switch on or off", !TestULearningModule.mayReceive("true", "b@x.pe", "a@x.pe", can) && !TestULearningModule.mayReceive(null, "b@x.pe", "a@x.pe", can), "");
		ok("allowlist still needs the permission", !TestULearningModule.mayReceive(null, "a@x.pe", "a@x.pe", cannot), "");

		// ---- streak and last score: workdays in a row ending on the workday before today; weekends neither count nor break it
		java.time.LocalDate mon = java.time.LocalDate.parse("2026-09-21"), thu = java.time.LocalDate.parse("2026-09-24");
		ok("previous workday of Monday is Friday", TestULearningModule.previousWorkday(mon).toString().equals("2026-09-18"), TestULearningModule.previousWorkday(mon));
		java.util.Map<java.time.LocalDate, int[]> done = new java.util.HashMap<>();
		ok("nothing finished: {0,0,0}", java.util.Arrays.toString(TestULearningModule.challengeStats(done, thu)).equals("[0, 0, 0]"), "");
		done.put(java.time.LocalDate.parse("2026-09-17"), new int[] {3, 5}); // Thu
		done.put(java.time.LocalDate.parse("2026-09-18"), new int[] {5, 5}); // Fri
		done.put(java.time.LocalDate.parse("2026-09-21"), new int[] {2, 5}); // Mon
		done.put(java.time.LocalDate.parse("2026-09-23"), new int[] {4, 5}); // Wed
		ok("Thursday after Wednesday only (Tuesday missed): streak 1, 4 of 5", java.util.Arrays.toString(TestULearningModule.challengeStats(done, thu)).equals("[1, 4, 5]"),
			java.util.Arrays.toString(TestULearningModule.challengeStats(done, thu)));
		ok("Tuesday: Thu+Fri+Mon across the weekend = 3, Monday's 2 of 5",
			java.util.Arrays.toString(TestULearningModule.challengeStats(done, java.time.LocalDate.parse("2026-09-22"))).equals("[3, 2, 5]"), "");
		ok("Friday after a missed Thursday: no streak line", TestULearningModule.challengeStats(done, java.time.LocalDate.parse("2026-09-25"))[0] == 0, "");

		// ---- email: subject/body/closing per learner-local weekday, tutor, avatar and name from data, escaped, HTML only
		String link = "https://x.test/site/learn/#/desafio?login=" + a;
		String avatar = TestULearningModule.absoluteUrl("https://x.test/site/learn/", "/site/mediadb/testu/iris.png");
		ok("avatar made absolute from the learn url", "https://x.test/site/mediadb/testu/iris.png".equals(avatar), avatar);
		ok("absolute avatar kept, none stays none", "https://cdn.test/a.png".equals(TestULearningModule.absoluteUrl("https://x.test/", "https://cdn.test/a.png"))
			&& TestULearningModule.absoluteUrl("https://x.test/", null) == null && TestULearningModule.absoluteUrl("https://x.test/", "a.png") == null, "");
		String[] days = {"lunes", "martes", "miércoles", "jueves", "viernes"}, daysEn = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
		Set<String> bodies = new HashSet<>(), closings = new HashSet<>();
		for (int i = 0; i < 5; i++)
		{
			java.time.LocalDate d = mon.plusDays(i);
			String[] es = TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, d, null);
			ok("es subject " + days[i], ("Hola Diego, tu desafío del " + days[i]).equals(es[0]), es[0]);
			ok("es " + days[i] + ": HTML only, button, link, tutor, avatar", es.length == 2 && es[1].contains(link) && es[1].contains("Empezar mi desafío")
				&& es[1].contains(">IRIS<") && es[1].contains(avatar), "");
			ok("es " + days[i] + " never says Reto", !(es[0] + es[1]).toLowerCase().matches("(?s).*\\breto\\b.*"), "");
			ok("en subject " + daysEn[i], ("Hi Diego, your " + daysEn[i] + " challenge").equals(TestULearningModule.emailContent(true, "Diego", "Sully", null, link, d, null)[0]), "");
			bodies.add(es[1].replaceAll("(?s).*<h1[^>]*>", "").replaceAll("(?s)</h1>.*", ""));
			closings.add(es[1].replaceAll("(?s).*</a></td></tr></table><p[^>]*>", "").replaceAll("(?s)</p>.*", ""));
		}
		ok("five different headings", bodies.size() == 5, bodies);
		ok("five different closings", closings.size() == 5, closings);
		String[] monEs = TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, mon, null);
		ok("Monday: back from the weekend", monEs[1].contains("fin de semana"), "");
		ok("Friday: enjoy the weekend", TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, mon.plusDays(4), null)[1].contains("disfrutar"), "");
		ok("no data: no streak line, no numbers", !monEs[1].contains("seguidos") && !monEs[1].contains("acertaste"), "");
		ok("Monday streak 3, Friday 4 of 5", monEs.length == 2 && TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, mon, new int[] {3, 4, 5})[1]
			.contains("Llevas 3 días seguidos completando tu Desafío y el viernes acertaste 4 de 5."), "");
		ok("Thursday streak 1: yesterday", TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, thu, new int[] {1, 4, 5})[1].contains("Ayer acertaste 4 de 5 en tu Desafío."), "");
		ok("0 right: no score, just done", TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, thu, new int[] {1, 0, 5})[1].contains("Ayer completaste tu Desafío.")
			&& !TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, thu, new int[] {1, 0, 5})[1].contains("0 de 5"), "");
		ok("en streak", TestULearningModule.emailContent(true, "Diego", "Sully", null, link, thu, new int[] {3, 4, 5})[1].contains("You’re on a 3-day streak, and yesterday you got 4 of 5 right."), "");
		String[] en = TestULearningModule.emailContent(true, "", "Sully", null, link, thu, null);
		ok("en without a name or avatar", "Your Thursday challenge".equals(en[0]) && en[1].contains("Hi,") && !en[1].contains("<img"), en[0]);
		ok("en uses the org's tutor, no other", en[1].contains("Sully") && !en[1].contains("IRIS"), "");
		ok("name is escaped", TestULearningModule.emailContent(false, "<b>x", "IRIS", null, link, mon, null)[1].contains("&lt;b&gt;x"), "");
		// ---- shared copy: the Hoy card (next.json daycopy) takes title + mood from the same table as the email
		for (int i = 0; i < 7; i++)
		{
			java.time.LocalDate d = mon.plusDays(i);
			String[] c = TestULearningModule.dayCopy(false, d);
			String html = TestULearningModule.emailContent(false, "Diego", "IRIS", null, link, d, null)[1];
			ok("card copy = email copy " + d.getDayOfWeek(), html.contains(">" + c[1] + "</h1>") && html.contains(c[2]) && !c[2].contains("{"), c[1]);
		}
		ok("weekend variant on the card", TestULearningModule.dayCopy(false, mon.plusDays(5))[1].equals("Un Desafío de fin de semana")
			&& TestULearningModule.dayCopy(false, mon.plusDays(6)) == TestULearningModule.dayCopy(false, mon.plusDays(5)), "");
		ok("card mood never repeats the email's 'ready' sentence", !TestULearningModule.dayCopy(false, mon)[2].contains("ya está listo"), "");
		ok("recent line shared, null without data", TestULearningModule.recentLine(false, thu, null) == null && TestULearningModule.recentLine(false, thu, new int[] {0, 0, 0}) == null
			&& "Ayer acertaste 4 de 5 en tu Desafío. ¡Vamos por otro!".equals(TestULearningModule.recentLine(false, thu, new int[] {1, 4, 5})), "");
		ok("given name", "Renzo".equals(TestULearningModule.givenName("RENZO ALDAIR")) && "".equals(TestULearningModule.givenName(null)), TestULearningModule.givenName("RENZO ALDAIR"));

		// ---- App Links / Universal Links: the two .well-known files
		ok(".well-known content type", "application/json".equals(TestULearningModule.WELL_KNOWN_TYPE), "");
		JSONObject aasa = TestULearningModule.appleAppSiteAssociation("VJ8RCF92K4.world.eme.genailabs", "/site/learn/");
		Object parsed = org.json.simple.JSONValue.parse(aasa.toJSONString());
		JSONObject detail = (JSONObject) ((java.util.List) ((JSONObject) ((JSONObject) parsed).get("applinks")).get("details")).get(0);
		ok("AASA: applinks.details[0].appIDs", List.of("VJ8RCF92K4.world.eme.genailabs").equals(detail.get("appIDs")), aasa);
		ok("AASA: scoped to the learn path", "/site/learn/*".equals(((JSONObject) ((java.util.List) detail.get("components")).get(0)).get("/"))
			&& List.of("/site/learn/*").equals(detail.get("paths")), aasa);
		ok("AASA: several app ids", ((java.util.List) ((JSONObject) ((java.util.List) ((JSONObject) TestULearningModule.appleAppSiteAssociation("A.x, B.y", "/l/").get("applinks"))
			.get("details")).get(0)).get("appIDs")).size() == 2, "");
		ok("assetlinks: [] without a signing fingerprint", TestULearningModule.assetLinks("world.eme.genailabs", null).isEmpty()
			&& "[]".equals(TestULearningModule.assetLinks("p", " ").toJSONString()), "");
		Object al = org.json.simple.JSONValue.parse(TestULearningModule.assetLinks("world.eme.genailabs", "aa:bb, CC:DD").toJSONString());
		JSONObject st = (JSONObject) ((java.util.List) al).get(0), tg = (JSONObject) st.get("target");
		ok("assetlinks: statement shape", List.of("delegate_permission/common.handle_all_urls").equals(st.get("relation")) && "android_app".equals(tg.get("namespace"))
			&& "world.eme.genailabs".equals(tg.get("package_name")) && List.of("AA:BB", "CC:DD").equals(tg.get("sha256_cert_fingerprints")), al);

		// ---- login-code (OTP) email: same shell, code big and whole, validity, language, escaping
		String[] otpEs = TestULearningModule.loginCodeEmailContent(false, "Diego", "IRIS", avatar, "482913", "diego@x.pe");
		ok("otp es subject", "Tu código para entrar a TestU".equals(otpEs[0]), otpEs[0]);
		ok("otp es: code whole and big, validity, tutor header", otpEs[1].contains(">482913</span>") && otpEs[1].contains("font-size:34px")
			&& otpEs[1].contains("Vale por 1 hora y solo se puede usar una vez.") && otpEs[1].contains(">IRIS<") && otpEs[1].contains(avatar)
			&& otpEs[1].contains("CÓDIGO DE ACCESO"), "");
		String dcShell = TestULearningModule.emailContent(false, "Diego", "IRIS", avatar, link, mon, null)[1];
		ok("otp shares the Daily Challenge shell", otpEs[1].substring(0, otpEs[1].indexOf("<title>")).equals(dcShell.substring(0, dcShell.indexOf("<title>")))
			&& otpEs[1].contains("class=\"tu-card\"") && dcShell.contains("class=\"tu-card\""), "");
		String[] otpEn = TestULearningModule.loginCodeEmailContent(true, "", "Sully", null, "000123", "a@x.pe");
		ok("otp en", "Your TestU sign-in code".equals(otpEn[0]) && otpEn[1].contains("It works for 1 hour and only once.") && otpEn[1].contains("Hi,")
			&& otpEn[1].contains(">000123</span>") && !otpEn[1].contains("<img"), otpEn[0]);
		ok("otp escapes", TestULearningModule.loginCodeEmailContent(false, "<b>", "IRIS", null, "1<2", "<x>")[1].contains("1&lt;2"), "");

		// ---- previews (optional arg = output dir): the 5 weekdays in Spanish, Minsur/IRIS, Diego, with and without streak data
		if (args.length > 0)
		{
			try
			{
				java.nio.file.Path dir = java.nio.file.Files.createDirectories(java.nio.file.Paths.get(args[0]));
				String[] file = {"1-lunes", "2-martes", "3-miercoles", "4-jueves", "5-viernes"};
				for (int i = 0; i < 5; i++)
				{
					for (boolean streak : new boolean[] {false, true})
					{
						String[] m = TestULearningModule.emailContent(false, "Diego", "IRIS", TestULearningModule.absoluteUrl("http://localhost:8080/site/learn/", "/site/mediadb/testu/iris.png"),
							"http://localhost:8080/site/learn/#/desafio?login=PREVIEW", mon.plusDays(i), streak ? new int[] {3, 4, 5} : null);
						java.nio.file.Files.writeString(dir.resolve(file[i] + (streak ? "-racha" : "") + ".html"), m[1].replace("<title>", "<title>" + "[" + m[0] + "] "));
					}
				}
				System.out.println("previews written to " + dir.toAbsolutePath());
				if (args.length > 1) // the login-code email, es + en
				{
					java.nio.file.Path otp = java.nio.file.Files.createDirectories(java.nio.file.Paths.get(args[1]));
					String av = TestULearningModule.absoluteUrl("http://localhost:8080/site/learn/", "/site/mediadb/testu/iris.png");
					String[] es = TestULearningModule.loginCodeEmailContent(false, "Diego", "IRIS", av, "482913", "diego@genailabs.tech");
					String[] enm = TestULearningModule.loginCodeEmailContent(true, "Diego", "IRIS", av, "482913", "diego@genailabs.tech");
					java.nio.file.Files.writeString(otp.resolve("codigo-es.html"), es[1].replace("<title>", "<title>[" + es[0] + "] "));
					java.nio.file.Files.writeString(otp.resolve("code-en.html"), enm[1].replace("<title>", "<title>[" + enm[0] + "] "));
					System.out.println("login-code previews written to " + otp.toAbsolutePath());
				}
			}
			catch (java.io.IOException e)
			{
				ok("write previews", false, e);
			}
		}

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

		// ---- Daily Challenge opens by entry channel and platform (engagement.json dailyopens), Lima day 2026-09-21
		List<DoneRow> opens = new ArrayList<>();
		opens.add(new DoneRow("a", "iOS", "email", null, Date.from(Instant.parse("2026-09-21T15:00:00Z")), false));
		opens.add(new DoneRow("a", "iOS", "app", null, Date.from(Instant.parse("2026-09-21T18:00:00Z")), false)); // same day: first entry wins
		opens.add(new DoneRow("b", "android", "push", null, Date.from(Instant.parse("2026-09-21T15:00:00Z")), false));
		opens.add(new DoneRow("c", "web", "app", null, Date.from(Instant.parse("2026-09-21T15:00:00Z")), false));
		opens.add(new DoneRow("c", "web", null, null, Date.from(Instant.parse("2026-09-22T15:00:00Z")), false)); // next day, old app: app
		opens.add(new DoneRow("d", "web", "email", null, Date.from(Instant.parse("2026-09-22T04:00:00Z")), false)); // 23:00 Lima on the 21st
		java.util.Map<String, Boolean> complete = new java.util.HashMap<>();
		complete.put("a_20260921", true);
		complete.put("c_20260922", true);
		complete.put("d_20260921", false);
		JSONObject op = TestUAnalyticsModule.dailyOpens(opens, complete, LIMA);
		ok("opens: email 2 (1 done, rate 0.5), push 1, app 2 (1 done)", opensOf(op, "channels").equals("app=2/1/0.5 email=2/1/0.5 push=1/0/0.0"), opensOf(op, "channels"));
		ok("opens by platform", opensOf(op, "platforms").equals("android=1/0/0.0 ios=1/1/1.0 web=3/1/0.333"), opensOf(op, "platforms"));
		ok("no opens: rate null", ((JSONObject) ((JSONObject) TestUAnalyticsModule.dailyOpens(List.of(), complete, LIMA).get("channels")).get("push")).get("rate") == null, "");

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

	static String opensOf(JSONObject o, String k)
	{
		StringBuilder sb = new StringBuilder();
		for (Object e : new java.util.TreeMap<Object, Object>((JSONObject) o.get(k)).entrySet())
		{
			JSONObject c = (JSONObject) ((java.util.Map.Entry) e).getValue();
			sb.append(sb.length() == 0 ? "" : " ").append(((java.util.Map.Entry) e).getKey()).append('=').append(c.get("opens")).append('/').append(c.get("completed")).append('/').append(c.get("rate"));
		}
		return sb.toString();
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
