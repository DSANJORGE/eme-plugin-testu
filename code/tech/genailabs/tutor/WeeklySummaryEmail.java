package tech.genailabs.tutor;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.asset.MediaArchive;
import org.openedit.Data;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;

/**
 * The Friday weekly summary email (catalog event weeklysummaryemail, every 15 min): at 18:00 on Friday in each person's own zone
 * (user.timezone, else the org's testu_timezone). Admins (a role with analytics or personas manage/operate = everyone; a team
 * manager = the teams they manage and every team below) get the admin summary for their scope; everyone else the learner summary.
 * Who: TestULearningModule.mayReceive with PERMISSION as switch (catalog setting testu_weeklysummaryemail: on unless "false"),
 * allowlist (testu_weeklysummaryemail_only) and role permission. Once per person and Friday: a dailychallengeemail row with id
 * <user>_<yyyyMMdd>_weekly is saved before the send, like the Daily Challenge email. Copy: docs/copy/resumen-semanal.es.md.
 */
public class WeeklySummaryEmail
{
	private static final Log log = LogFactory.getLog(WeeklySummaryEmail.class);

	/** Role permission, master switch setting and (+ "_only") allowlist setting. */
	public static final String PERMISSION = "testu_weeklysummaryemail";

	/** Friday, 18:00 local. */
	public static final int HOUR = 18;

	/** Role permissions that make an admin of the whole org (TestUTeamModule.loadScope's rule). */
	static final List<String> ORG_ADMIN = List.of("personas_manage", "personas_operate", "analytics_manage", "analytics_operate");


	final TestULearningModule module;
	final MediaArchive archive;
	final LearningEngine engine;
	final ZoneId orgzone;
	Map<String, Data> teams;

	public WeeklySummaryEmail(TestULearningModule inModule, MediaArchive inArchive)
	{
		module = inModule;
		archive = inArchive;
		engine = new LearningEngine(inArchive);
		orgzone = (ZoneId) engine.orgZone()[0];
	}

	/** Sends every summary due now; returns the number sent. */
	public int run()
	{
		module.grantPermissionOnce(archive, PERMISSION, "Resumen semanal: recibir el email", "931", PERMISSION + "_granted");
		String on = archive.getCatalogSettingValue(PERMISSION);
		String only = archive.getCatalogSettingValue(PERMISSION + "_only");
		if (!TestULearningModule.mayReceive(on, only, null, null, PERMISSION))
		{
			return 0;
		}
		if (TestULearningModule.learnUrl(archive) == null)
		{
			log.error("testu weeklysummaryemail: set catalog setting testu_learnurl (or siteroot); nothing sent");
			return 0;
		}
		Instant now = Instant.now();
		Searcher sent = archive.getSearcher("dailychallengeemail");
		Map<String, Collection> rolePerms = new HashMap<>();
		HitTracker users = archive.query("user").all().search();
		users.enableBulkOperations();
		int n = 0;
		for (Object hit : users)
		{
			Data u = (Data) hit;
			String email = u.get("email");
			if (email == null || email.trim().isEmpty() || "admin".equals(u.getId()) || !TestUAnalyticsModule.countsAsPerson(u.getId(), u))
			{
				continue;
			}
			ZoneId zone = TestULearningModule.zoneOf(u.get("timezone"), orgzone);
			String key = dueKey(u.getId(), zone, now);
			if (key == null || sent.searchById(key) != null)
			{
				continue;
			}
			Collection perms = module.rolePermissions(archive, u.getId(), rolePerms);
			if (!TestULearningModule.mayReceive(on, only, email, perms, PERMISSION))
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
				Set<String> scope = adminScope(u.getId(), perms);
				module.sendMail(archive, email, scope == null ? learnerMail(u, true) : adminMail(u, scope));
				n++;
			}
			catch (Exception e)
			{
				log.error("testu weeklysummaryemail " + u.getId(), e);
				row.setValue("status", "failed");
				sent.saveData(row, null);
			}
		}
		return n;
	}

	/** inNow in inZone is Friday HOUR: the send key <user>_<yyyyMMdd>_weekly; otherwise null. Pure. */
	public static String dueKey(String inUserid, ZoneId inZone, Instant inNow)
	{
		ZonedDateTime t = inNow.atZone(inZone);
		if (t.getHour() != HOUR || t.getDayOfWeek() != DayOfWeek.FRIDAY)
		{
			return null;
		}
		return inUserid + "_" + t.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) + "_weekly";
	}

	/**
	 * The teams inUserid's admin summary covers: an empty set = the whole org (ORG_ADMIN role), the managed teams and every team
	 * below them for a team manager, null = not an admin (learner summary).
	 */
	Set<String> adminScope(String inUserid, Collection inPerms)
	{
		if (inPerms != null)
		{
			for (String p : ORG_ADMIN)
			{
				if (inPerms.contains(p))
				{
					return Collections.emptySet();
				}
			}
		}
		Set<String> managed = TestUTeamModule.managedTeams(teams(), inUserid);
		return managed.isEmpty() ? null : managed;
	}

	Map<String, Data> teams()
	{
		if (teams == null)
		{
			teams = new HashMap<>();
			for (Object o : archive.query("team").all().search())
			{
				teams.put(((Data) o).getId(), (Data) o);
			}
		}
		return teams;
	}

	// ---------------------------------------------------------------- learner

	/** A learner's week, the input of learnerContent. */
	public static class LearnerWeek
	{
		public String name;
		public boolean everActive;
		public int activeDays, answers, minutes, prevActiveDays, prevAnswers, prevMinutes, streak;
		public String[] days; // the 7 days, yyyy-MM-dd, oldest first (null = no chart)
		public int[] dayAnswers = new int[7];
		public List<TopicMove> topics = new ArrayList<>();
		public List<String[]> forecasts = new ArrayList<>(); // {topic, level, yyyy-MM-dd}
		public List<String[]> certs = new ArrayList<>(); // {topic, status expired|renewal_due|available, yyyy-MM-dd or null}
		public String[] focus; // {mode learn|improve, subtopic or null, topic}
	}

	public static class TopicMove
	{
		public String title, band, bandBefore, required;
		public int percent, percentBefore;
		public Boolean meets;
	}

	/**
	 * {subject, html, link, fromname, inline image or null} of u's learner summary; inSignIn = the link carries a freshly minted
	 * sign-in token (which replaces u's previous one), else it only opens the app.
	 */
	Object[] learnerMail(Data u, boolean inSignIn)
	{
		String learnurl = TestULearningModule.learnUrl(archive);
		Data persona = TestULearningModule.persona(archive);
		String tutor = persona == null || persona.getName() == null ? "TestU" : persona.getName();
		String link = learnurl + "#/?src=email&campaign=weeklysummary"
				+ (inSignIn ? "&login=" + org.entermediadb.asset.modules.AdminModule.createLoginLink(archive.getSearcherManager(), u.getId()) : "");
		Object[] avatar = module.avatarRef(archive, persona, learnurl);
		LearnerWeek w = learnerWeek(u);
		String[] m = learnerContent(english(u, persona), w, tutor, (String) avatar[0], link);
		return new Object[] {m[0], m[1], link, tutor, avatar[1]};
	}

	LearnerWeek learnerWeek(Data u)
	{
		String uid = u.getId();
		ZoneId zone = TestULearningModule.zoneOf(u.get("timezone"), orgzone);
		LocalDate today = LocalDate.now(zone);
		LearnerWeek w = new LearnerWeek();
		w.name = TestULearningModule.givenName(u.get("firstName"));
		// Activity: the console's per-day rows (tutordaily, id <user>_<yyyyMMdd>), last 7 days vs the 7 before.
		for (Object o : archive.query("tutordaily").exact("user", uid).search())
		{
			Data d = (Data) o;
			String id = d.getId();
			LocalDate day;
			try
			{
				day = LocalDate.parse(id.substring(id.length() - 8), DateTimeFormatter.BASIC_ISO_DATE);
			}
			catch (Exception e)
			{
				continue;
			}
			int answers = LearningEngine.intOr(d.get("answers"), 0), minutes = LearningEngine.intOr(d.get("minutes"), 0);
			w.everActive |= answers > 0;
			long ago = java.time.temporal.ChronoUnit.DAYS.between(day, today);
			if (ago >= 0 && ago < 7)
			{
				w.answers += answers;
				w.minutes += minutes;
				w.activeDays += answers > 0 ? 1 : 0;
				w.dayAnswers[6 - (int) ago] += answers;
			}
			else if (ago >= 7 && ago < 14)
			{
				w.prevAnswers += answers;
				w.prevMinutes += minutes;
				w.prevActiveDays += answers > 0 ? 1 : 0;
			}
		}
		// ponytail: content per learner (applyProfiles mutates it); one loadContent per recipient, fine at pilot scale.
		LearningEngine.Content content = engine.loadContent();
		LearningEngine.Learner l = engine.loadLearner(uid, LearningEngine.jobrolesOf(u), LearningEngine.primaryJobroleOf(u));
		engine.applyProfiles(content, l);
		w.everActive |= !l.attempts.isEmpty();
		w.days = new String[7];
		for (int i = 0; i < 7; i++)
		{
			w.days[i] = today.minusDays(6 - i).toString();
		}
		w.streak = module.recentChallenges(engine, archive, uid, LearningEngine.challengeDate(new Date(), orgzone), l.attempts)[0];
		Date weekStart = Date.from(today.minusDays(6).atStartOfDay(zone).toInstant());
		LearningEngine.Learner before = learnerBefore(l, weekStart);
		// Mastery per day for the forecast, the last Forecast.FIT days (built lazily, once).
		List<LearningEngine.Learner> days = null;
		Date now = new Date();
		for (LearningEngine.Topic t : content.topics.values())
		{
			LearningEngine.Mastery m = LearningEngine.mastery(t.questions, l, t.competentmin, t.expertmin);
			String required = engine.requiredLevel(t.id, l.jobroles);
			if (m.band == null && required == null || t.questions.isEmpty())
			{
				continue; // never touched and not asked of this learner
			}
			LearningEngine.Mastery b = LearningEngine.mastery(t.questions, before, t.competentmin, t.expertmin);
			TopicMove tm = new TopicMove();
			tm.title = t.title;
			tm.percent = m.percent;
			tm.band = m.band;
			tm.percentBefore = b.percent;
			tm.bandBefore = b.band;
			tm.required = required;
			tm.meets = required == null ? null : LearningEngine.levelIndex(m.band) >= LearningEngine.levelIndex(required);
			w.topics.add(tm);
			// Forecast toward the next level that matters: the requirement while below it, else the next band.
			String target = required != null && !tm.meets ? required : "expert".equals(m.band) ? null : m.band == null || "beginner".equals(m.band) ? "competent" : "expert";
			if (target != null && m.band != null)
			{
				if (days == null)
				{
					days = new ArrayList<>();
					for (int i = Forecast.FIT - 1; i >= 0; i--)
					{
						days.add(learnerBefore(l, Date.from(today.minusDays(i - 1).atStartOfDay(zone).toInstant())));
					}
				}
				List<Double> history = new ArrayList<>();
				for (LearningEngine.Learner d : days)
				{
					history.add(LearningEngine.percent(t.questions, d) / 100.0);
				}
				Map<String, Object> f = Forecast.of(history, ("expert".equals(target) ? t.expertmin : t.competentmin) / 100.0);
				if ("onpace".equals(f.get("status")) && f.get("eta") != null)
				{
					w.forecasts.add(new String[] {t.title, target, today.plusDays(((Number) f.get("eta")).longValue()).toString()});
				}
			}
			org.json.simple.JSONObject c = LearningEngine.certificationStatus(t, l, now, orgzone);
			if (c != null)
			{
				String s = String.valueOf(c.get("status"));
				if ("expired".equals(s) || "renewal_due".equals(s))
				{
					w.certs.add(new String[] {t.title, s, (String) c.get("expiry")});
				}
				else if ("not_certified".equals(s) && Boolean.TRUE.equals(LearningEngine.evaluationStatus(t, l, now, orgzone).get("canstart")))
				{
					w.certs.add(new String[] {t.title, "available", null});
				}
			}
		}
		// Biggest movers first, then the lowest mastery (what is left to do).
		w.topics.sort((a, b2) -> a.percent - a.percentBefore != b2.percent - b2.percentBefore ? (b2.percent - b2.percentBefore) - (a.percent - a.percentBefore) : a.percent - b2.percent);
		org.json.simple.JSONObject rec = LearningEngine.recommend(content, l, engine.subtopicStates(content, l), t -> engine.requiredLevel(t, l.jobroles));
		if (rec != null)
		{
			w.focus = new String[] {(String) rec.get("mode"), (String) rec.get("sectiontitle"), (String) rec.get("topictitle")};
		}
		return w;
	}

	/** inLearner as it stood before inCutoff: only the attempts made earlier count. */
	static LearningEngine.Learner learnerBefore(LearningEngine.Learner inLearner, Date inCutoff)
	{
		List<LearningEngine.Attempt> earlier = new ArrayList<>();
		for (LearningEngine.Attempt a : inLearner.attempts)
		{
			if (a.at != null && a.at.before(inCutoff))
			{
				earlier.add(a);
			}
		}
		return LearningEngine.learner(inLearner.userid, earlier);
	}

	/** {subject, html} of the learner summary: numbers, a day-by-day chart and progress bars first, words only where a chart can't say it. Pure. */
	public static String[] learnerContent(boolean en, LearnerWeek w, String inTutor, String inAvatar, String inLink)
	{
		boolean named = w.name != null && !w.name.isEmpty();
		String subject = en ? (named ? w.name + ", here’s your week in TestU" : "Your week in TestU") : (named ? w.name + ", así fue tu semana en TestU" : "Así fue tu semana en TestU");
		String heading, intro;
		if (!w.everActive)
		{
			heading = en ? "Your learning is waiting" : "Tu aprendizaje te espera";
			intro = en ? "Your first session takes just a few minutes, and I’ll help you every step of the way."
					: "Tu primera sesión toma solo unos minutos y te acompaño en cada paso.";
		}
		else if (w.activeDays == 0)
		{
			heading = en ? "A quiet week" : "Una semana tranquila";
			intro = en ? "We didn’t meet this week, but your progress is still here." : "Esta semana no nos vimos, pero tu avance sigue aquí.";
		}
		else
		{
			heading = w.activeDays >= 4 ? (en ? "What a week!" : "¡Qué gran semana!") : w.activeDays >= 2 ? (en ? "A good week of progress" : "Una buena semana de avance")
					: (en ? "You took a step this week" : "Diste un paso esta semana");
			intro = en ? "Here’s your last 7 days with me." : "Así fueron tus últimos 7 días conmigo.";
		}
		String preheader = w.activeDays > 0
				? (en ? w.activeDays + (w.activeDays == 1 ? " active day" : " active days") + " and " + w.answers + " answers. See how you moved forward."
						: w.activeDays + (w.activeDays == 1 ? " día activo" : " días activos") + " y " + w.answers + " respuestas. Mira cómo avanzaste.")
				: (en ? "Your weekly summary and what comes next." : "Tu resumen semanal y lo que viene.");
		StringBuilder in = new StringBuilder();
		in.append(p((en ? "Hi" : "Hola") + (named ? " " + w.name : "") + (en ? "! " : ". ") + intro));
		if (w.activeDays > 0)
		{
			// Only the good news in the learner's comparison: a dip is not what a Friday email should lead with.
			in.append(tiles(new String[][] {
					{String.valueOf(w.activeDays), en ? "ACTIVE DAYS" : "DÍAS ACTIVOS", upOnly(delta(en, w.activeDays, w.prevActiveDays))},
					{String.valueOf(w.answers), en ? "ANSWERS" : "RESPUESTAS", upOnly(delta(en, w.answers, w.prevAnswers))},
					{String.valueOf(w.minutes), en ? "MINUTES" : "MINUTOS", upOnly(delta(en, w.minutes, w.prevMinutes))}}));
			if (w.days != null)
			{
				in.append(columns(en ? "ANSWERS PER DAY" : "RESPUESTAS POR DÍA", w.dayAnswers, dayLabels(en, w.days)));
			}
		}
		if (w.streak >= 2)
		{
			in.append(callout("🔥 " + (en ? w.streak + " Daily Challenges in a row. Keep it up!" : w.streak + " Desafíos Diarios seguidos. ¡Sigue así!")));
		}
		List<TopicMove> shown = w.topics.size() > 4 ? w.topics.subList(0, 4) : w.topics;
		if (!shown.isEmpty())
		{
			in.append(section(en ? "YOUR PROGRESS" : "TU AVANCE"));
			for (TopicMove t : shown)
			{
				int d = t.percent - t.percentBefore;
				boolean up = t.band != null && LearningEngine.levelIndex(t.band) > LearningEngine.levelIndex(t.bandBefore);
				String change = up ? (en ? "▲ You reached " + band(en, t.band) + "!" : "▲ ¡Subiste a " + band(en, t.band) + "!")
						: d > 0 ? (en ? "▲ +" + d + " points this week" : "▲ +" + d + " puntos esta semana")
								: d < 0 ? (en ? "▼ " + (-d) + " points: worth a review" : "▼ " + (-d) + " puntos: vale la pena repasar")
										: (en ? "No change this week" : "Sin cambios esta semana");
				String goal = t.required == null ? null
						: Boolean.TRUE.equals(t.meets) ? "✓ " + (en ? "Goal: " : "Meta: ") + band(en, t.required)
								: (en ? "Goal: " : "Meta: ") + band(en, t.required);
				in.append(topicRow(t.title, t.percentBefore, t.percent, t.band == null ? (en ? "Not started" : "Sin empezar") : band(en, t.band), change, up || d > 0, goal));
			}
			in.append(legend(new String[][] {{BAR_BEFORE, en ? "Before this week" : "Antes de esta semana"}, {BAR_GAIN, en ? "This week" : "Esta semana"}}));
		}
		List<String[]> next = new ArrayList<>(); // {icon, text}
		for (String[] f : w.forecasts.size() > 2 ? w.forecasts.subList(0, 2) : w.forecasts)
		{
			next.add(new String[] {"📈", en ? "At this pace: " + band(en, f[1]) + " in " + f[0] + " by " + date(en, f[2]) + "."
					: "A este ritmo: " + band(en, f[1]) + " en " + f[0] + " hacia el " + date(en, f[2]) + "."});
		}
		for (String[] c : w.certs)
		{
			next.add(new String[] {"🎓", "expired".equals(c[1]) ? (en ? c[0] + ": certification expired. Renew it in the app." : c[0] + ": tu certificación venció. Renuévala en la app.")
					: "renewal_due".equals(c[1]) ? (en ? c[0] + ": renew it before " + date(en, c[2]) + "." : c[0] + ": renuévala antes del " + date(en, c[2]) + ".")
							: (en ? c[0] + ": you can now take the certification." : c[0] + ": ya puedes rendir la certificación.")});
		}
		if (w.focus != null)
		{
			String where = w.focus[1] == null ? w.focus[2] : w.focus[1] + " (" + w.focus[2] + ")";
			next.add(new String[] {"🎯", "improve".equals(w.focus[0]) ? (en ? "Reinforce " + where + ", where you can improve the most." : "Refuerza " + where + ", donde más puedes mejorar.")
					: (en ? "Continue with " + where + "." : "Sigue con " + where + ".")});
		}
		if (!next.isEmpty())
		{
			in.append(section(en ? "FOR NEXT WEEK" : "PARA LA PRÓXIMA SEMANA"));
			for (String[] n : next)
			{
				in.append(iconRow(n[0], n[1]));
			}
		}
		in.append(button(inLink, w.everActive ? (en ? "Keep learning" : "Seguir aprendiendo") : (en ? "Start now" : "Empezar ahora")));
		String closing = en ? "Have a great weekend. See you on Monday!" : "¡Que tengas un gran fin de semana! Nos vemos el lunes.";
		String foot = en ? "You get this summary every Friday because you have a TestU account. If the button doesn’t work, open the app and sign in with your email."
				: "Recibes este resumen cada viernes porque tienes una cuenta en TestU. Si el botón no funciona, abre la app e ingresa con tu correo.";
		String html = TestULearningModule.emailShell(en, subject, preheader, inTutor, inAvatar, en ? "WEEKLY SUMMARY" : "RESUMEN SEMANAL", heading, in.toString(), closing, foot);
		return new String[] {subject, html};
	}

	// ---------------------------------------------------------------- admin

	/** An admin's week for their scope, the input of adminContent. */
	public static class AdminWeek
	{
		public String name, scope; // scope = team names, or null for the whole org
		public int total, active, prevActive, answers, prevAnswers, minutes, prevMinutes, competentPlus;
		public int expert, competent, beginner, notStarted;
		public String[] days; // yyyy-MM-dd, oldest first (null = no chart)
		public int[] dailyPeople;
		public List<String> lapsed = new ArrayList<>(), neverStarted = new ArrayList<>(), noTour = new ArrayList<>();
		public List<String[]> certs = new ArrayList<>(); // {person, topic, expired|renewal_due, yyyy-MM-dd}
		public List<String[]> teams = new ArrayList<>(); // {team, active, members, topic to reinforce or null}
		public int tutorQuestions, tutorPeople;
		public List<String[]> tutorTopics = new ArrayList<>(); // {label, count}
		public List<String[]> gaps = new ArrayList<>(); // {subtopic, topic, beginners, people}
	}

	/** {subject, html, link, fromname, inline image or null} of u's admin summary for inScope (empty = the whole org). */
	Object[] adminMail(Data u, Set<String> inScope)
	{
		String learnurl = TestULearningModule.learnUrl(archive);
		Data persona = TestULearningModule.persona(archive);
		String tutor = persona == null || persona.getName() == null ? "TestU" : persona.getName();
		String link = adminUrl(learnurl) + "#/?src=email&campaign=weeklyadmin";
		Object[] avatar = module.avatarRef(archive, persona, learnurl);
		String[] m = adminContent(english(u, persona), adminWeek(u, inScope), tutor, (String) avatar[0], link);
		return new Object[] {m[0], m[1], link, tutor, avatar[1]};
	}

	/** The admin console's address: catalog setting testu_adminurl, else the learner app's with /learn/ swapped for /admin/. */
	String adminUrl(String inLearnurl)
	{
		String url = archive.getCatalogSettingValue("testu_adminurl");
		if (url != null && !url.trim().isEmpty())
		{
			return url.trim().endsWith("/") ? url.trim() : url.trim() + "/";
		}
		return inLearnurl.replaceFirst("/learn/$", "/admin/");
	}

	AdminWeek adminWeek(Data u, Set<String> inScope)
	{
		LocalDate today = LocalDate.now(orgzone);
		Set<String> scope = inScope.isEmpty() ? null : inScope;
		Map<String, Object> a = TestUAnalyticsModule.analytics(archive, scope, teams(), "", "", today.minusDays(6).toString(), today.toString());
		AdminWeek w = new AdminWeek();
		w.name = TestULearningModule.givenName(u.get("firstName"));
		if (scope != null)
		{
			List<String> names = new ArrayList<>();
			for (String t : scope)
			{
				Data team = teams().get(t);
				names.add(team == null || team.getName() == null ? t : team.getName());
			}
			Collections.sort(names);
			w.scope = String.join(", ", names);
		}
		Map<String, Object> cohort = (Map<String, Object>) a.get("cohort");
		Map<String, Object> prev = (Map<String, Object>) a.get("previous");
		w.total = num(cohort.get("total"));
		w.active = num(cohort.get("active7d"));
		w.prevActive = num(prev.get("active7d"));
		w.prevAnswers = num(prev.get("answers"));
		w.prevMinutes = num(prev.get("minutes"));
		List<Map<String, Object>> series = (List<Map<String, Object>>) a.get("series");
		w.days = new String[series.size()];
		w.dailyPeople = new int[series.size()];
		for (int i = 0; i < series.size(); i++)
		{
			Map<String, Object> d = series.get(i);
			w.answers += num(d.get("answers"));
			w.minutes += num(d.get("minutes"));
			w.days[i] = String.valueOf(d.get("day"));
			w.dailyPeople[i] = num(d.get("people"));
		}
		Map<String, Integer> levels = (Map<String, Integer>) a.get("levels");
		w.expert = num(levels.get("expert"));
		w.competent = num(levels.get("competent"));
		w.beginner = num(levels.get("beginner"));
		w.notStarted = num(levels.get("notstarted"));
		w.competentPlus = w.competent + w.expert;
		Map<String, Data> users = (Map<String, Data>) a.get("users");
		Set<String> activated = (Set<String>) a.get("activated");
		for (Map<String, Object> i : (List<Map<String, Object>>) a.get("inactive"))
		{
			String id = (String) i.get("user");
			(i.get("lastactivity") == null && (activated == null || !activated.contains(id)) ? w.neverStarted : w.lapsed).add(displayName((String) i.get("name")));
		}
		Searcher onboarding = archive.getSearcher("learneronboarding");
		List<String> sorted = new ArrayList<>(users.keySet());
		Collections.sort(sorted);
		for (String id : sorted)
		{
			Data o = (Data) onboarding.searchById(id);
			if (o == null || o.getValue("finished") == null)
			{
				w.noTour.add(displayName(TestUAnalyticsModule.formatUserName(users.get(id))));
			}
		}
		// Certifications: only people with a certificate can be expired or due to renew.
		Date now = new Date();
		for (Object o : archive.query("certification").all().search())
		{
			Data r = (Data) o;
			Data person = users.get(r.get("user"));
			if (person == null)
			{
				continue;
			}
			LearningEngine.Content content = engine.loadContent();
			LearningEngine.Learner l = engine.loadLearner(person.getId(), LearningEngine.jobrolesOf(person), LearningEngine.primaryJobroleOf(person));
			engine.applyProfiles(content, l);
			LearningEngine.Topic t = content.topics.get(r.get("entitytopic"));
			org.json.simple.JSONObject c = t == null ? null : LearningEngine.certificationStatus(t, l, now, orgzone);
			String s = c == null ? null : String.valueOf(c.get("status"));
			if ("expired".equals(s) || "renewal_due".equals(s))
			{
				w.certs.add(new String[] {displayName(TestUAnalyticsModule.formatUserName(person)), t.title, s, (String) c.get("expiry")});
			}
		}
		List<Map<String, Object>> teamStats = (List<Map<String, Object>>) a.get("teamStats");
		if (teamStats.size() > 1)
		{
			for (Map<String, Object> t : teamStats)
			{
				String name = (String) t.get("name");
				w.teams.add(new String[] {name == null || name.isEmpty() ? null : name, String.valueOf(t.get("active7d")), String.valueOf(t.get("members")), (String) t.get("weakest")});
			}
		}
		Map<String, Object> iris = (Map<String, Object>) a.get("iris");
		w.tutorQuestions = num(iris.get("questions"));
		w.tutorPeople = num(iris.get("people"));
		// The subtopics people ask about most (iris.themes are internal codes such as "concept", not for a reader).
		List<Map<String, Object>> secs = (List<Map<String, Object>>) iris.get("sections");
		for (Map<String, Object> s : secs == null ? Collections.<Map<String, Object>> emptyList() : secs)
		{
			if (w.tutorTopics.size() < 3 && s.get("name") != null && num(s.get("questions")) > 0)
			{
				w.tutorTopics.add(new String[] {String.valueOf(s.get("name")), String.valueOf(s.get("questions"))});
			}
		}
		for (Map<String, Object> g : ((List<Map<String, Object>>) a.get("gaps")))
		{
			if (w.gaps.size() < 3 && num(g.get("beginners")) > 0 && g.get("name") != null)
			{
				w.gaps.add(new String[] {String.valueOf(g.get("name")), String.valueOf(g.get("topic")), String.valueOf(g.get("beginners")), String.valueOf(g.get("people"))});
			}
		}
		return w;
	}

	/** {subject, html} of the admin summary: tiles, charts and counts; names only as a short sample under each count. Pure. */
	public static String[] adminContent(boolean en, AdminWeek w, String inTutor, String inAvatar, String inLink)
	{
		String scope = w.scope == null ? (en ? "your organization" : "tu organización") : w.scope;
		String subject = en ? "Weekly summary: " + w.active + " of " + w.total + " people practiced this week"
				: "Resumen semanal: " + w.active + " de " + w.total + " personas practicaron esta semana";
		int attention = w.lapsed.size() + w.neverStarted.size() + w.certs.size();
		String preheader = en ? scope + ": " + w.active + " of " + w.total + " active" + (attention > 0 ? ", " + attention + " need attention." : ".")
				: scope + ": " + w.active + " de " + w.total + " activas" + (attention > 0 ? ", " + attention + " requieren atención." : ".");
		String heading = en ? w.active + " of " + w.total + " people practiced this week" : w.active + " de " + w.total + " personas practicaron esta semana";
		boolean named = w.name != null && !w.name.isEmpty();
		StringBuilder in = new StringBuilder();
		in.append(p((en ? "Hi" : "Hola") + (named ? " " + w.name : "") + (en ? ". Last 7 days in " : ". Los últimos 7 días en ") + scope + "."));
		in.append(tiles(new String[][] {
				{String.valueOf(w.active), en ? "ACTIVE PEOPLE" : "PERSONAS ACTIVAS", delta(en, w.active, w.prevActive)},
				{String.valueOf(w.answers), en ? "ANSWERS" : "RESPUESTAS", delta(en, w.answers, w.prevAnswers)},
				{String.valueOf(w.minutes), en ? "MINUTES" : "MINUTOS", delta(en, w.minutes, w.prevMinutes)}}));
		if (w.days != null)
		{
			in.append(columns(en ? "ACTIVE PEOPLE PER DAY" : "PERSONAS ACTIVAS POR DÍA", w.dailyPeople, dayLabels(en, w.days)));
		}
		if (w.total > 0)
		{
			in.append(section(en ? "LEVELS" : "NIVELES"));
			in.append(stacked(new int[] {w.expert, w.competent, w.beginner, w.notStarted}, new String[] {LEVEL_EXPERT, LEVEL_COMPETENT, LEVEL_BEGINNER, NEUTRAL}));
			in.append(legend(new String[][] {{LEVEL_EXPERT, (en ? "Expert " : "Experto ") + w.expert}, {LEVEL_COMPETENT, (en ? "Competent " : "Competente ") + w.competent},
					{LEVEL_BEGINNER, (en ? "Beginner " : "Principiante ") + w.beginner}, {NEUTRAL, (en ? "Not started " : "Sin empezar ") + w.notStarted}}));
		}
		in.append(section(en ? "NEED ATTENTION" : "REQUIEREN ATENCIÓN"));
		List<String> certNames = new ArrayList<>();
		for (String[] c : w.certs)
		{
			certNames.add(c[0] + " · " + c[1]);
		}
		if (attention + w.noTour.size() == 0)
		{
			in.append(iconRow("✓", en ? "Nobody needs special attention this week." : "Nadie requiere atención especial esta semana."));
		}
		else
		{
			in.append(countGrid(new Object[][] {
					{w.lapsed.size(), en ? "No activity 7+ days" : "Sin actividad 7+ días", w.lapsed},
					{w.neverStarted.size(), en ? "Haven’t started" : "Aún no empiezan", w.neverStarted},
					{w.noTour.size(), en ? "No welcome tour" : "Sin recorrido inicial", w.noTour},
					{w.certs.size(), en ? "Certifications due" : "Certificaciones por vencer", certNames}}, en));
		}
		if (!w.teams.isEmpty())
		{
			in.append(section(en ? "ACTIVE BY TEAM" : "ACTIVAS POR EQUIPO"));
			for (String[] t : w.teams)
			{
				int members = num(t[2]);
				in.append(hbar(t[0] == null ? (en ? "No team" : "Sin equipo") : t[0], num(t[1]), Math.max(members, 1), t[1] + "/" + t[2],
						t[3] == null ? null : (en ? "To reinforce: " : "A reforzar: ") + t[3]));
			}
		}
		if (w.tutorQuestions > 0)
		{
			in.append(section((en ? "QUESTIONS TO " : "PREGUNTAS A ") + inTutor.toUpperCase() + " · " + w.tutorQuestions + (en ? " FROM " : " DE ") + w.tutorPeople
					+ (en ? (w.tutorPeople == 1 ? " PERSON" : " PEOPLE") : (w.tutorPeople == 1 ? " PERSONA" : " PERSONAS"))));
			int max = 1;
			for (String[] t : w.tutorTopics)
			{
				max = Math.max(max, num(t[1]));
			}
			for (String[] t : w.tutorTopics)
			{
				in.append(hbar(t[0], num(t[1]), max, t[1], null));
			}
		}
		if (!w.gaps.isEmpty())
		{
			in.append(section(en ? "HARDEST SUBTOPICS · SHARE IN BEGINNER" : "SUBTEMAS MÁS DIFÍCILES · % EN PRINCIPIANTE"));
			for (String[] g : w.gaps)
			{
				int people = Math.max(num(g[3]), 1), beginners = num(g[2]);
				in.append(hbar(g[0], beginners, people, Math.round(100f * beginners / people) + "%", g[1] + " · " + beginners + (en ? " of " : " de ") + g[3]));
			}
		}
		in.append(button(inLink, en ? "Open the console" : "Abrir la consola"));
		String closing = en ? "Have a great weekend!" : "¡Que tengas un gran fin de semana!";
		String foot = en ? "You get this summary every Friday because you manage " + scope + " in TestU."
				: "Recibes este resumen cada viernes porque administras " + scope + " en TestU.";
		String html = TestULearningModule.emailShell(en, subject, preheader, inTutor, inAvatar, en ? "WEEKLY SUMMARY · ADMIN" : "RESUMEN SEMANAL · ADMIN", heading, in.toString(), closing, foot);
		return new String[] {subject, html};
	}

	// ---------------------------------------------------------------- html pieces (TestULearningModule.emailShell's tokens)

	// Chart colors on the #121215 card. Levels = one orange ramp, darker = lower (validated: adjacent ΔE ≥ 18.5 normal and CVD);
	// the beginner step is under 3:1 against the card, so every level also carries its label and count in the legend.
	static final String LEVEL_EXPERT = "#F8C6A4", LEVEL_COMPETENT = "#E8703A", LEVEL_BEGINNER = "#8F4A27", NEUTRAL = "#2C2C33";
	static final String BAR_BEFORE = "#8F4A27", BAR_GAIN = "#E8703A";

	/** One-letter weekday labels for inDays (yyyy-MM-dd, oldest first); the last one = today. Pure. */
	static String[] dayLabels(boolean en, String[] inDays)
	{
		String[] out = new String[inDays.length];
		for (int i = 0; i < inDays.length; i++)
		{
			out[i] = LocalDate.parse(inDays[i]).getDayOfWeek().getDisplayName(java.time.format.TextStyle.NARROW, en ? Locale.ENGLISH : new Locale("es")).toUpperCase();
		}
		return out;
	}

	/** A 7-column bar chart (tables only, for mail clients): value on top of each bar, weekday under it; a zero is a 2px stub. */
	static String columns(String inTitle, int[] inValues, String[] inLabels)
	{
		int max = 1;
		for (int v : inValues)
		{
			max = Math.max(max, v);
		}
		StringBuilder s = new StringBuilder("<div style=\"margin:0 0 8px;" + TestULearningModule.MONO + "font-size:10px;font-weight:600;letter-spacing:0.1em;color:#8B8F98\">")
				.append(TestULearningModule.esc(inTitle)).append("</div>")
				.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 20px;table-layout:fixed\"><tr>");
		for (int i = 0; i < inValues.length; i++)
		{
			int h = inValues[i] == 0 ? 2 : Math.max(4, Math.round(64f * inValues[i] / max));
			s.append("<td width=\"" + (100 / inValues.length) + "%\" valign=\"bottom\" align=\"center\" style=\"padding:0 3px;height:84px;vertical-align:bottom\">")
					.append(inValues[i] == 0 ? "" : "<div style=\"" + TestULearningModule.SANS + "font-size:11px;color:#D6D4D0;padding-bottom:3px\">" + inValues[i] + "</div>")
					.append("<div style=\"height:" + h + "px;line-height:" + h + "px;font-size:0;background:" + (inValues[i] == 0 ? NEUTRAL : BAR_GAIN)
							+ ";border-radius:4px 4px 0 0\">&nbsp;</div></td>");
		}
		s.append("</tr><tr>");
		for (String l : inLabels)
		{
			s.append("<td align=\"center\" style=\"padding-top:6px;" + TestULearningModule.MONO + "font-size:10px;color:#8B8F98\">").append(TestULearningModule.esc(l)).append("</td>");
		}
		return s.append("</tr></table>").toString();
	}

	/** A 100% stacked bar of inCounts (zero segments skipped), 2px card-colored gaps between segments. */
	static String stacked(int[] inCounts, String[] inColors)
	{
		int total = 0;
		for (int c : inCounts)
		{
			total += c;
		}
		StringBuilder s = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 10px\"><tr>");
		boolean first = true;
		for (int i = 0; i < inCounts.length; i++)
		{
			if (inCounts[i] <= 0 || total == 0)
			{
				continue;
			}
			s.append(first ? "" : "<td width=\"2\" style=\"width:2px;font-size:0\">&nbsp;</td>")
					.append("<td width=\"" + Math.max(1, Math.round(100f * inCounts[i] / total)) + "%\" height=\"14\" bgcolor=\"" + inColors[i] + "\" style=\"background:" + inColors[i]
							+ ";height:14px;border-radius:4px;font-size:0;line-height:0\">&nbsp;</td>");
			first = false;
		}
		return s.append("</tr></table>").toString();
	}

	/** Swatch + text pairs on one wrapping line. */
	static String legend(String[][] inItems)
	{
		StringBuilder s = new StringBuilder("<div style=\"margin:0 0 16px;" + TestULearningModule.SANS + "font-size:12px;line-height:1.9;color:#8B8F98\">");
		for (String[] it : inItems)
		{
			s.append("<span style=\"white-space:nowrap;margin-right:14px\"><span style=\"display:inline-block;width:9px;height:9px;border-radius:2px;background:").append(it[0])
					.append(";margin-right:6px\"></span>").append(TestULearningModule.esc(it[1])).append("</span>");
		}
		return s.append("</div>").toString();
	}

	/** Label and value on one line, a bar of inValue / inMax under it, and an optional muted note. */
	static String hbar(String inLabel, int inValue, int inMax, String inValueText, String inNote)
	{
		int pct = Math.max(0, Math.min(100, Math.round(100f * inValue / Math.max(inMax, 1))));
		return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 12px\"><tr>"
				+ "<td style=\"" + TestULearningModule.SANS + "font-size:14px;color:#ECEBE7;padding-bottom:5px\">" + TestULearningModule.esc(inLabel) + "</td>"
				+ "<td align=\"right\" style=\"" + TestULearningModule.SANS + "font-size:14px;font-weight:600;color:#ECEBE7;white-space:nowrap;padding:0 0 5px 12px\">" + TestULearningModule.esc(inValueText) + "</td></tr>"
				+ "<tr><td colspan=\"2\">" + track(0, pct, 8) + "</td></tr>"
				+ (inNote == null ? "" : "<tr><td colspan=\"2\" style=\"padding-top:4px;" + TestULearningModule.SANS + "font-size:12px;color:#8B8F98\">" + TestULearningModule.esc(inNote) + "</td></tr>")
				+ "</table>";
	}

	/** A track of inHeight px: inBefore% in BAR_BEFORE, then up to inNow% in BAR_GAIN, the rest NEUTRAL, 2px gaps between. */
	static String track(int inBefore, int inNow, int inHeight)
	{
		int before = Math.max(0, Math.min(inBefore, inNow)), now = Math.max(0, Math.min(100, inNow));
		String cell = "<td width=\"%d%%\" height=\"" + inHeight + "\" bgcolor=\"%s\" style=\"background:%s;height:" + inHeight + "px;border-radius:4px;font-size:0;line-height:0\">&nbsp;</td>";
		String gap = "<td width=\"2\" style=\"width:2px;font-size:0\">&nbsp;</td>";
		StringBuilder s = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
		List<String> parts = new ArrayList<>();
		if (before > 0)
		{
			parts.add(String.format(cell, before, BAR_BEFORE, BAR_BEFORE));
		}
		if (now - before > 0)
		{
			parts.add(String.format(cell, now - before, BAR_GAIN, BAR_GAIN));
		}
		if (now < 100)
		{
			parts.add(String.format(cell, 100 - now, NEUTRAL, NEUTRAL));
		}
		s.append(String.join(gap, parts));
		return s.append("</tr></table>").toString();
	}

	/** 2x2 count cards {count, label, names}: big number, label, up to 3 names and "+N". */
	static String countGrid(Object[][] inCards, boolean en)
	{
		StringBuilder s = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 12px\">");
		for (int i = 0; i < inCards.length; i += 2)
		{
			s.append("<tr>");
			for (int j = i; j < i + 2; j++)
			{
				s.append(j > i ? "<td width=\"8\" style=\"width:8px\"></td>" : "");
				if (j >= inCards.length)
				{
					s.append("<td width=\"50%\"></td>");
					continue;
				}
				int count = (Integer) inCards[j][0];
				List<String> names = (List<String>) inCards[j][2];
				String sample = names.isEmpty() ? "" : String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? (en ? " +" : " +") + (names.size() - 3) : "");
				s.append("<td width=\"50%\" valign=\"top\" bgcolor=\"#17171B\" style=\"background:#17171B;border:1px solid #222227;border-radius:8px;padding:12px;")
						.append(count > 0 ? "border-left:3px solid #E8703A;" : "").append("\">")
						.append("<div style=\"font-family:Sora,-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;font-size:22px;font-weight:700;color:" + (count > 0 ? "#ECEBE7" : "#6B6F78") + "\">")
						.append(count).append("</div>")
						.append("<div style=\"" + TestULearningModule.SANS + "font-size:13px;color:#D6D4D0;padding-top:2px\">").append(TestULearningModule.esc((String) inCards[j][1])).append("</div>")
						.append(sample.isEmpty() ? "" : "<div style=\"" + TestULearningModule.SANS + "font-size:11px;line-height:1.45;color:#8B8F98;padding-top:6px\">" + TestULearningModule.esc(sample) + "</div>")
						.append("</td>");
			}
			s.append("</tr>").append(i + 2 < inCards.length ? "<tr><td colspan=\"3\" height=\"8\" style=\"height:8px;font-size:0\">&nbsp;</td></tr>" : "");
		}
		return s.append("</table>").toString();
	}

	/** An icon and one line of text. */
	static String iconRow(String inIcon, String inText)
	{
		return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 10px\"><tr>"
				+ "<td width=\"30\" valign=\"top\" style=\"width:30px;font-size:17px;line-height:1.4\">" + TestULearningModule.esc(inIcon) + "</td>"
				+ "<td style=\"" + TestULearningModule.SANS + "font-size:15px;line-height:1.5;color:#D6D4D0\">" + TestULearningModule.esc(inText) + "</td></tr></table>";
	}


	static boolean english(Data u, Data inPersona)
	{
		String lang = u.get("language");
		if (lang == null || lang.isEmpty())
		{
			lang = inPersona == null ? null : inPersona.get("tutorlanguage");
		}
		return lang != null && lang.startsWith("en");
	}

	static String band(boolean en, String inBand)
	{
		return "expert".equals(inBand) ? (en ? "Expert" : "Experto") : "competent".equals(inBand) ? (en ? "Competent" : "Competente") : (en ? "Beginner" : "Principiante");
	}

	/** "+3 vs last week" style; "" when both weeks are empty. Pure. */
	static String delta(boolean en, int inNow, int inBefore)
	{
		if (inNow == 0 && inBefore == 0)
		{
			return "";
		}
		int d = inNow - inBefore;
		return d == 0 ? (en ? "same as last week" : "igual que la semana pasada") : (d > 0 ? "+" : "−") + Math.abs(d) + (en ? " vs last week" : " vs. semana pasada");
	}

	static String upOnly(String inDelta)
	{
		return inDelta.startsWith("−") ? "" : inDelta;
	}

	static String date(boolean en, String inYmd)
	{
		try
		{
			return LocalDate.parse(inYmd).format(DateTimeFormatter.ofPattern(en ? "MMMM d" : "d 'de' MMMM", en ? Locale.ENGLISH : new Locale("es")));
		}
		catch (Exception e)
		{
			return String.valueOf(inYmd);
		}
	}

	/** "VICTOR MANUEL DE LA CRUZ" -> "Victor Manuel de la Cruz" (names imported in capitals); any other spelling is kept. Pure. */
	public static String displayName(String inName)
	{
		if (inName == null || !inName.equals(inName.toUpperCase()) || inName.equals(inName.toLowerCase()) || inName.contains("@"))
		{
			return inName;
		}
		StringBuilder out = new StringBuilder();
		for (String word : inName.toLowerCase().split(" "))
		{
			if (!word.isEmpty())
			{
				boolean particle = out.length() > 0 && List.of("de", "del", "la", "las", "los", "y").contains(word);
				out.append(out.length() > 0 ? " " : "").append(particle ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
			}
		}
		return out.toString();
	}

	/** A count from a Number or a numeric string; anything else = 0. Pure. */
	static int num(Object o)
	{
		return o instanceof Number ? ((Number) o).intValue() : LearningEngine.intOr(o == null ? null : o.toString(), 0);
	}

	static String p(String inText)
	{
		return "<p style=\"margin:0 0 16px;" + TestULearningModule.SANS + "font-size:16px;line-height:1.6;color:#D6D4D0\">" + TestULearningModule.esc(inText) + "</p>";
	}

	static String section(String inTitle)
	{
		return "<div style=\"margin:24px 0 12px;padding-top:18px;border-top:1px solid #222227;" + TestULearningModule.MONO
				+ "font-size:11px;font-weight:600;letter-spacing:0.12em;color:#8B8F98\">" + TestULearningModule.esc(inTitle) + "</div>";
	}

	static String callout(String inText)
	{
		return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:4px 0 20px\"><tr>"
				+ "<td bgcolor=\"#17171B\" style=\"background:#17171B;border:1px solid #222227;border-left:3px solid #E8703A;border-radius:8px;padding:12px 14px;"
				+ TestULearningModule.SANS + "font-size:15px;line-height:1.5;color:#ECEBE7\">" + TestULearningModule.esc(inText) + "</td></tr></table>";
	}

	/** Three number tiles {value, LABEL, delta}. */
	static String tiles(String[][] inTiles)
	{
		StringBuilder s = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:4px 0 20px\"><tr>");
		for (int i = 0; i < inTiles.length; i++)
		{
			String[] t = inTiles[i];
			s.append(i > 0 ? "<td width=\"8\" style=\"width:8px\"></td>" : "")
					.append("<td width=\"33%\" valign=\"top\" bgcolor=\"#17171B\" style=\"background:#17171B;border:1px solid #222227;border-radius:8px;padding:12px 10px\">")
					.append("<div style=\"font-family:Sora,-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;font-size:24px;font-weight:700;color:#ECEBE7\">")
					.append(TestULearningModule.esc(t[0])).append("</div>")
					.append("<div style=\"" + TestULearningModule.MONO + "font-size:10px;font-weight:600;letter-spacing:0.1em;color:#8B8F98;padding-top:4px\">")
					.append(TestULearningModule.esc(t[1])).append("</div>")
					.append(t[2].isEmpty() ? "" : "<div style=\"" + TestULearningModule.SANS + "font-size:12px;color:" + (t[2].startsWith("+") ? "#E8703A" : "#6B6F78")
							+ ";padding-top:6px\">" + TestULearningModule.esc(t[2]) + "</div>")
					.append("</td>");
		}
		return s.append("</tr></table>").toString();
	}

	/** A topic: title, band and percent, a two-tone bar (before this week, gained this week), the week's change and the goal. */
	static String topicRow(String inTitle, int inBefore, int inPercent, String inBand, String inChange, boolean inUp, String inGoal)
	{
		int pct = Math.max(0, Math.min(100, inPercent));
		return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 16px\"><tr>"
				+ "<td style=\"" + TestULearningModule.SANS + "font-size:15px;font-weight:600;color:#ECEBE7\">" + TestULearningModule.esc(inTitle) + "</td>"
				+ "<td align=\"right\" style=\"" + TestULearningModule.SANS + "font-size:14px;color:#D6D4D0;white-space:nowrap\">" + TestULearningModule.esc(inBand + " · " + pct + "%") + "</td></tr>"
				+ "<tr><td colspan=\"2\" style=\"padding:8px 0 6px\">" + track(inBefore, pct, 8) + "</td></tr>"
				+ "<tr><td style=\"" + TestULearningModule.SANS + "font-size:13px;color:" + (inUp ? "#ECEBE7" : "#8B8F98") + "\">" + TestULearningModule.esc(inChange) + "</td>"
				+ "<td align=\"right\" style=\"" + TestULearningModule.SANS + "font-size:13px;color:#8B8F98;white-space:nowrap\">" + (inGoal == null ? "" : TestULearningModule.esc(inGoal)) + "</td></tr></table>";
	}

	static String button(String inLink, String inLabel)
	{
		return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:24px 0 28px\"><tr>"
				+ "<td align=\"center\" bgcolor=\"#F4F2EE\" style=\"background:#F4F2EE;border-radius:8px\">"
				+ "<a href=\"" + TestULearningModule.esc(inLink) + "\" style=\"display:block;padding:15px 20px;" + TestULearningModule.SANS
				+ "font-size:15px;font-weight:700;letter-spacing:0.05em;color:#0A0A0B;text-decoration:none;border-radius:8px\">" + TestULearningModule.esc(inLabel) + "</a></td></tr></table>";
	}

}
