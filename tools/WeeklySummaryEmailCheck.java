import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import tech.genailabs.tutor.WeeklySummaryEmail;
import tech.genailabs.tutor.WeeklySummaryEmail.AdminWeek;
import tech.genailabs.tutor.WeeklySummaryEmail.LearnerWeek;
import tech.genailabs.tutor.WeeklySummaryEmail.TopicMove;

/**
 * Pure checks of the Friday weekly summary email (no server): the Friday 18:00 local selection and its once-a-week key, and the
 * learner and admin copy for a busy, a quiet and a never-started week. From the server root, after bin/compile.sh:
 * java -cp "build:$(find plugins/system/lib plugins/finder/lib plugins/community/lib tomcat/lib -name '*.jar' | tr '\n' ':')" plugins/testu/tools/WeeklySummaryEmailCheck.java [previewdir]
 * With previewdir, also writes the Spanish previews there.
 */
public class WeeklySummaryEmailCheck
{
	static int failures = 0;

	public static void main(String[] args) throws java.io.IOException
	{
		ZoneId lima = ZoneId.of("America/Lima");
		// Friday 2026-09-25 18:00 Lima = 23:00Z
		ok("Lima Friday 18:00 is due", "u_20260925_weekly".equals(WeeklySummaryEmail.dueKey("u", lima, Instant.parse("2026-09-25T23:00:00Z"))), "");
		ok("Lima Friday 18:59 is due", WeeklySummaryEmail.dueKey("u", lima, Instant.parse("2026-09-25T23:59:00Z")) != null, "");
		ok("Lima Friday 17:59 is not", WeeklySummaryEmail.dueKey("u", lima, Instant.parse("2026-09-25T22:59:00Z")) == null, "");
		ok("Lima Thursday 18:00 is not", WeeklySummaryEmail.dueKey("u", lima, Instant.parse("2026-09-24T23:00:00Z")) == null, "");
		ok("Madrid Friday 18:00 is due in its own zone", WeeklySummaryEmail.dueKey("u", ZoneId.of("Europe/Madrid"), Instant.parse("2026-09-25T16:00:00Z")) != null, "");

		LearnerWeek busy = new LearnerWeek();
		busy.name = "Diego";
		busy.everActive = true;
		busy.activeDays = 4; busy.answers = 62; busy.minutes = 48;
		busy.prevActiveDays = 2; busy.prevAnswers = 30; busy.prevMinutes = 48;
		busy.streak = 4;
		busy.topics.add(move("Ciberseguridad", 64, "competent", 51, "beginner", "competent", true));
		busy.topics.add(move("Seguridad en Mina", 38, "beginner", 33, "beginner", "competent", false));
		busy.topics.add(move("Gestión Ambiental", 12, "beginner", 12, "beginner", null, null));
		busy.forecasts.add(new String[] {"Seguridad en Mina", "competent", "2026-10-16"});
		busy.certs.add(new String[] {"Ciberseguridad", "renewal_due", "2026-10-10"});
		busy.focus = new String[] {"improve", "Phishing y correo seguro", "Ciberseguridad"};
		busy.days = new String[] {"2026-09-19", "2026-09-20", "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"};
		busy.dayAnswers = new int[] {0, 0, 18, 0, 22, 9, 13};
		String[] l = WeeklySummaryEmail.learnerContent(false, busy, "IRIS", "cid:tutoravatar", "https://x/learn/#/?src=email&campaign=weeklysummary&login=T");
		ok("learner subject", "Diego, así fue tu semana en TestU".equals(l[0]), l[0]);
		ok("busy heading", l[1].contains("¡Qué gran semana!"), "");
		ok("tiles with deltas", l[1].contains("DÍAS ACTIVOS") && l[1].contains("+2 vs. semana pasada") && l[1].contains("igual que la semana pasada"), "");
		ok("streak line", l[1].contains("4 Desafíos Diarios seguidos. ¡Sigue así!"), "");
		ok("day chart", l[1].contains("RESPUESTAS POR DÍA") && l[1].contains(">V</td>"), "");
		ok("band up", l[1].contains("¡Subiste a Competente!"), "");
		ok("points up", l[1].contains("+5 puntos esta semana"), "");
		ok("no change", l[1].contains("Sin cambios esta semana"), "");
		ok("goal met / goal", l[1].contains("✓ Meta: Competente") && l[1].contains(">Meta: Competente"), "");
		ok("two-tone bar", l[1].contains("width=\"51%\" height=\"8\" bgcolor=\"#8F4A27\"") && l[1].contains("width=\"13%\" height=\"8\" bgcolor=\"#E8703A\""), "");
		ok("forecast date", l[1].contains("A este ritmo: Competente en Seguridad en Mina hacia el 16 de octubre."), "");
		ok("certification renewal", l[1].contains("Ciberseguridad: renuévala antes del 10 de octubre."), "");
		ok("focus", l[1].contains("Refuerza Phishing y correo seguro (Ciberseguridad)"), "");
		ok("link escaped", l[1].contains("campaign=weeklysummary&amp;login=T"), "");
		ok("friday closing", l[1].contains("Nos vemos el lunes"), "");

		LearnerWeek quiet = new LearnerWeek();
		quiet.name = "Ana";
		quiet.everActive = true;
		quiet.prevActiveDays = 3; quiet.prevAnswers = 20; quiet.prevMinutes = 15;
		quiet.topics.add(move("Ciberseguridad", 40, "beginner", 40, "beginner", null, null));
		String[] q = WeeklySummaryEmail.learnerContent(false, quiet, "IRIS", null, "L");
		ok("quiet heading", q[1].contains("Una semana tranquila"), "");
		ok("quiet week: no tiles", !q[1].contains("DÍAS ACTIVOS"), "");
		busy.prevAnswers = 90;
		ok("learner hides a dip", !WeeklySummaryEmail.learnerContent(false, busy, "IRIS", null, "L")[1].contains("−28"), "");
		busy.prevAnswers = 30;
		ok("capitals title-cased", "Victor Manuel de la Cruz".equals(WeeklySummaryEmail.displayName("VICTOR MANUEL DE LA CRUZ")), WeeklySummaryEmail.displayName("VICTOR MANUEL DE LA CRUZ"));
		ok("mixed case kept", "Ana McKay".equals(WeeklySummaryEmail.displayName("Ana McKay")), "");
		ok("no streak, no forecast, no certs", !q[1].contains("Desafíos Diarios seguidos") && !q[1].contains("A este ritmo") && !q[1].contains("🎓"), "");

		LearnerWeek fresh = new LearnerWeek();
		String[] f = WeeklySummaryEmail.learnerContent(false, fresh, "IRIS", null, "L");
		ok("never started: no name subject", "Así fue tu semana en TestU".equals(f[0]), f[0]);
		ok("never started: no tiles, start button", !f[1].contains("DÍAS ACTIVOS") && f[1].contains("Empezar ahora"), "");
		String[] en = WeeklySummaryEmail.learnerContent(true, busy, "IRIS", null, "L");
		ok("english subject", "Diego, here’s your week in TestU".equals(en[0]), en[0]);

		AdminWeek a = new AdminWeek();
		a.name = "Diego";
		a.total = 42; a.active = 27; a.prevActive = 21; a.answers = 1240; a.prevAnswers = 980; a.minutes = 610; a.prevMinutes = 540;
		a.competentPlus = 18; a.expert = 6; a.competent = 12; a.beginner = 14; a.notStarted = 10;
		a.days = new String[] {"2026-09-19", "2026-09-20", "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25"};
		a.dailyPeople = new int[] {2, 1, 19, 22, 17, 20, 14};
		a.lapsed.addAll(List.of("Ana Pérez", "Luis Soto", "María Díaz", "José Ruiz", "Carla Vega", "Pedro Luna", "Rosa Mía"));
		a.neverStarted.addAll(List.of("Juan Quispe", "Elena Ríos"));
		a.noTour.addAll(List.of("Juan Quispe", "Elena Ríos", "Pedro Luna"));
		a.certs.add(new String[] {"Luis Soto", "Ciberseguridad", "expired", "2026-09-20"});
		a.certs.add(new String[] {"Ana Pérez", "Ciberseguridad", "renewal_due", "2026-10-05"});
		a.teams.add(new String[] {"Mina San Rafael", "15", "20", "Seguridad en Mina"});
		a.teams.add(new String[] {"Oficina Lima", "12", "22", null});
		a.tutorQuestions = 38; a.tutorPeople = 14;
		a.tutorTopics.add(new String[] {"Contraseñas y MFA", "9"});
		a.tutorTopics.add(new String[] {"Correos sospechosos", "7"});
		a.gaps.add(new String[] {"Bloqueo y etiquetado", "Seguridad en Mina", "11", "19"});
		String[] ad = WeeklySummaryEmail.adminContent(false, a, "IRIS", "cid:tutoravatar", "https://x/admin/#/?src=email&campaign=weeklyadmin");
		ok("admin subject", "Resumen semanal: 27 de 42 personas practicaron esta semana".equals(ad[0]), ad[0]);
		ok("admin tiles", ad[1].contains("PERSONAS ACTIVAS") && ad[1].contains("+6 vs. semana pasada"), "");
		ok("admin levels", ad[1].contains("NIVELES") && ad[1].contains("Competente 12") && ad[1].contains("Sin empezar 10"), "");
		ok("count card with 3 names", ad[1].contains(">7</div>") && ad[1].contains("Sin actividad 7+ días") && ad[1].contains("Ana Pérez, Luis Soto, María Díaz +4"), "");
		ok("never started + tour", ad[1].contains("Aún no empiezan") && ad[1].contains("Sin recorrido inicial"), "");
		ok("certs", ad[1].contains("Certificaciones por vencer") && ad[1].contains("Luis Soto · Ciberseguridad"), "");
		ok("teams", ad[1].contains("ACTIVAS POR EQUIPO") && ad[1].contains(">15/20<") && ad[1].contains("A reforzar: Seguridad en Mina"), "");
		ok("tutor", ad[1].contains("PREGUNTAS A IRIS · 38 DE 14 PERSONAS") && ad[1].contains("Contraseñas y MFA"), "");
		ok("gaps", ad[1].contains("Bloqueo y etiquetado") && ad[1].contains(">58%<") && ad[1].contains("Seguridad en Mina · 11 de 19"), "");
		ok("org scope foot", ad[1].contains("administras tu organización"), "");
		AdminWeek calm = new AdminWeek();
		calm.total = 3; calm.active = 3; calm.scope = "Oficina Lima";
		String[] c = WeeklySummaryEmail.adminContent(false, calm, "IRIS", null, "L");
		ok("calm week", c[1].contains("Nadie requiere atención especial") && !c[1].contains("POR EQUIPO") && !c[1].contains("PREGUNTAS A"), "");
		ok("team scope foot", c[1].contains("administras Oficina Lima"), "");

		if (args.length > 0)
		{
			java.nio.file.Path dir = java.nio.file.Paths.get(args[0]);
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.writeString(dir.resolve("aprendiz-semana-activa.html"), l[1]);
			java.nio.file.Files.writeString(dir.resolve("aprendiz-semana-tranquila.html"), q[1]);
			java.nio.file.Files.writeString(dir.resolve("aprendiz-sin-empezar.html"), f[1]);
			java.nio.file.Files.writeString(dir.resolve("admin-organizacion.html"), ad[1]);
			java.nio.file.Files.writeString(dir.resolve("admin-equipo-sin-alertas.html"), c[1]);
		}
		System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	static TopicMove move(String inTitle, int inPct, String inBand, int inBefore, String inBandBefore, String inRequired, Boolean inMeets)
	{
		TopicMove t = new TopicMove();
		t.title = inTitle; t.percent = inPct; t.band = inBand; t.percentBefore = inBefore; t.bandBefore = inBandBefore; t.required = inRequired; t.meets = inMeets;
		return t;
	}

	static void ok(String inName, boolean inPass, String inDetail)
	{
		if (!inPass)
		{
			failures++;
			System.out.println("FAIL " + inName + " " + inDetail);
		}
	}
}
