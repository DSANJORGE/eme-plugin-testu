package tech.genailabs.tutor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermediadb.ai.llm.BaseAgentContext;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.hittracker.HitTracker;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;
import org.openedit.util.DateStorageUtil;

public class TestUAnalyticsModule extends TestUBaseModule
{
	private static final Log log = LogFactory.getLog(TestUAnalyticsModule.class);

	public void loadAnalytics(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");
		Map<String, Data> allteams = (Map<String, Data>) inReq.getPageValue("allteams");
		if (allteams == null)
		{
			allteams = Collections.emptyMap();
		}

		String topicFilter = inReq.getRequestParameter("entitytopic");
		topicFilter = (topicFilter != null) ? topicFilter.trim() : "";
		String teamFilter = inReq.getRequestParameter("team");
		teamFilter = (teamFilter != null) ? teamFilter.trim() : "";

		if (scope != null && !teamFilter.isEmpty() && !scope.contains(teamFilter))
		{
			fail(inReq, 400, "out of scope");
			return;
		}

		SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");

		Date now = new Date();
		Date toDate;
		String toParam = inReq.getRequestParameter("to");
		if (toParam != null && !toParam.trim().isEmpty())
		{
			try
			{
				toDate = dayFormat.parse(toParam.trim());
			}
			catch (Exception e)
			{
				toDate = now;
			}
		}
		else
		{
			toDate = now;
		}
		Calendar toCal = Calendar.getInstance();
		toCal.setTime(toDate);
		toCal.set(Calendar.HOUR_OF_DAY, 0);
		toCal.set(Calendar.MINUTE, 0);
		toCal.set(Calendar.SECOND, 0);
		toCal.set(Calendar.MILLISECOND, 0);
		toCal.add(Calendar.DAY_OF_MONTH, 1); // exclusive end
		Date to = toCal.getTime();

		Date fromDate;
		String fromParam = inReq.getRequestParameter("from");
		if (fromParam != null && !fromParam.trim().isEmpty())
		{
			try
			{
				fromDate = dayFormat.parse(fromParam.trim());
				Calendar fromCal = Calendar.getInstance();
				fromCal.setTime(fromDate);
				fromCal.set(Calendar.HOUR_OF_DAY, 0);
				fromCal.set(Calendar.MINUTE, 0);
				fromCal.set(Calendar.SECOND, 0);
				fromCal.set(Calendar.MILLISECOND, 0);
				fromDate = fromCal.getTime();
			}
			catch (Exception e)
			{
				Calendar fromCal = Calendar.getInstance();
				fromCal.setTime(to);
				fromCal.add(Calendar.DAY_OF_MONTH, -31);
				fromDate = fromCal.getTime();
			}
		}
		else
		{
			Calendar fromCal = Calendar.getInstance();
			fromCal.setTime(to);
			fromCal.add(Calendar.DAY_OF_MONTH, -31);
			fromDate = fromCal.getTime();
		}
		Date from = fromDate;

		long spanDays = Math.round((to.getTime() - from.getTime()) / (24.0 * 60 * 60 * 1000));
		Calendar prevFromCal = Calendar.getInstance();
		prevFromCal.setTime(from);
		prevFromCal.add(Calendar.DAY_OF_MONTH, -(int) spanDays);
		Date prevFrom = prevFromCal.getTime();
		Date prevTo = from;

		Map<String, Data> allUsers = new HashMap<>();
		HitTracker uh = archive.query("user").all().search();
		if (uh != null)
		{
			uh.enableBulkOperations();
			for (Object o : uh)
			{
				Data u = (Data) o;
				allUsers.put(u.getId(), u);
			}
		}

		Map<String, Data> users = new HashMap<>();
		for (Map.Entry<String, Data> entry : allUsers.entrySet())
		{
			Data u = entry.getValue();
			String id = entry.getKey();
			boolean isLearner = !"admin".equals(id) && !"agent".equals(id) && !"false".equals(String.valueOf(u.get("enabled")));
			String t = u.get("team");
			boolean inScope = (scope == null || (t != null && scope.contains(t))) && (teamFilter.isEmpty() || (t != null && teamFilter.equals(t)));
			if (isLearner && inScope)
			{
				users.put(id, u);
			}
		}

		Map<String, String> topics = new LinkedHashMap<>();
		HitTracker th = archive.query("entitytopic").all().search();
		if (th != null)
		{
			for (Object o : th)
			{
				Data t = (Data) o;
				topics.put(t.getId(), t.getName());
			}
		}

		Map<String, String> sections = new HashMap<>();
		HitTracker sh = archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search();
		if (sh != null)
		{
			for (Object o : sh)
			{
				Data s = (Data) o;
				sections.put(s.getId(), s.getName());
			}
		}

		List<Data> allMastery = new ArrayList<>();
		HitTracker mh = archive.query("tutormastery").all().search();
		if (mh != null)
		{
			mh.enableBulkOperations();
			for (Object o : mh)
			{
				allMastery.add((Data) o);
			}
		}

		List<Data> mastery = new ArrayList<>();
		for (Data r : allMastery)
		{
			if (users.containsKey(r.get("user")) && (topicFilter.isEmpty() || topicFilter.equals(r.get("entitytopic"))))
			{
				mastery.add(r);
			}
		}

		Map<String, Map<String, Object>> perUser = new HashMap<>();
		Map<String, Map<String, Map<String, Integer>>> perUserTopic = new HashMap<>();
		Map<String, Map<String, Object>> perSection = new HashMap<>();

		for (Data r : mastery)
		{
			String u = r.get("user");
			Map<String, Object> pu = perUser.computeIfAbsent(u, k -> {
				Map<String, Object> map = new HashMap<>();
				map.put("mastered", 0);
				map.put("answered", 0);
				map.put("attempts", 0);
				map.put("cw", 0);
				map.put("cc", 0);
				map.put("uc", 0);
				map.put("uw", 0);
				map.put("last", null);
				return map;
			});

			int m = getInt(r, "mastered");
			int a = getInt(r, "answered");
			int att = getInt(r, "attempts");
			int cw = getInt(r, "certainwrong");
			int cc = getInt(r, "certaincorrect");
			int uc = getInt(r, "unsurecorrect");
			int uw = getInt(r, "unsurewrong");

			pu.put("mastered", (Integer) pu.get("mastered") + m);
			pu.put("answered", (Integer) pu.get("answered") + a);
			pu.put("attempts", (Integer) pu.get("attempts") + att);
			pu.put("cw", (Integer) pu.get("cw") + cw);
			pu.put("cc", (Integer) pu.get("cc") + cc);
			pu.put("uc", (Integer) pu.get("uc") + uc);
			pu.put("uw", (Integer) pu.get("uw") + uw);

			Date la = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("lastactivity"));
			if (la != null)
			{
				Date currLast = (Date) pu.get("last");
				if (currLast == null || la.after(currLast))
				{
					pu.put("last", la);
				}
			}

			String topicId = r.get("entitytopic");
			Map<String, Map<String, Integer>> userTopicMap = perUserTopic.computeIfAbsent(u, k -> new HashMap<>());
			Map<String, Integer> pt = userTopicMap.computeIfAbsent(topicId, k -> {
				Map<String, Integer> map = new HashMap<>();
				map.put("mastered", 0);
				map.put("answered", 0);
				return map;
			});
			pt.put("mastered", pt.get("mastered") + m);
			pt.put("answered", pt.get("answered") + a);

			String sectionId = r.get("componentsection");
			Map<String, Object> ps = perSection.computeIfAbsent(sectionId, k -> {
				Map<String, Object> map = new HashMap<>();
				map.put("topic", topicId);
				map.put("levels", new ArrayList<String>());
				map.put("answered", 0);
				map.put("attempts", 0);
				map.put("cw", 0);
				return map;
			});
			((List<String>) ps.get("levels")).add(levelOf(m, a));
			ps.put("answered", (Integer) ps.get("answered") + a);
			ps.put("attempts", (Integer) ps.get("attempts") + att);
			ps.put("cw", (Integer) ps.get("cw") + cw);
		}

		Map<String, String> levelByUser = new HashMap<>();
		for (String u : users.keySet())
		{
			Map<String, Object> pu = perUser.get(u);
			levelByUser.put(u, pu != null ? levelOf((Integer) pu.get("mastered"), (Integer) pu.get("answered")) : null);
		}

		List<Data> allDaily = new ArrayList<>();
		HitTracker dh = archive.query("tutordaily").all().search();
		if (dh != null)
		{
			dh.enableBulkOperations();
			for (Object o : dh)
			{
				allDaily.add((Data) o);
			}
		}

		List<Data> dailyAll = new ArrayList<>();
		Set<String> activatedIds = new HashSet<>();
		for (Data r : allDaily)
		{
			if (users.containsKey(r.get("user")))
			{
				dailyAll.add(r);
				if (getInt(r, "answers") > 0)
				{
					activatedIds.add(r.get("user"));
				}
			}
		}

		List<Data> daily = new ArrayList<>();
		List<Data> prevDaily = new ArrayList<>();
		for (Data r : dailyAll)
		{
			Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("day"));
			if (d != null)
			{
				if (!d.before(from) && d.before(to))
				{
					daily.add(r);
				}
				if (!d.before(prevFrom) && d.before(prevTo))
				{
					prevDaily.add(r);
				}
			}
		}

		List<Map<String, Object>> series = buildDailySeries(from, to, daily, dayFormat);
		List<Map<String, Object>> prevSeries = buildDailySeries(prevFrom, prevTo, prevDaily, dayFormat);

		Set<String> active7dUsers = activeSince(dailyAll, to, 7);
		Set<String> active30dUsers = activeSince(dailyAll, to, 30);

		Map<String, Object> cohort = new HashMap<>();
		cohort.put("total", users.size());
		cohort.put("activated", activatedIds.size());
		cohort.put("active7d", active7dUsers.size());
		cohort.put("active30d", active30dUsers.size());

		Map<String, Object> previous = new HashMap<>();
		int prevAnswers = 0, prevMinutes = 0, prevCw = 0, prevQuestions = 0;
		Calendar prev7Cal = Calendar.getInstance();
		prev7Cal.setTime(prevTo);
		prev7Cal.add(Calendar.DAY_OF_MONTH, -7);
		Date prev7Date = prev7Cal.getTime();
		Set<String> prevActive7d = new HashSet<>();
		for (Data r : prevDaily)
		{
			prevAnswers += getInt(r, "answers");
			prevMinutes += getInt(r, "minutes");
			prevCw += getInt(r, "certainwrong");
			prevQuestions += getInt(r, "questions");
			Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("day"));
			if (d != null && !d.before(prev7Date) && getInt(r, "answers") > 0)
			{
				prevActive7d.add(r.get("user"));
			}
		}
		previous.put("answers", prevAnswers);
		previous.put("minutes", prevMinutes);
		previous.put("certainwrong", prevCw);
		previous.put("questions", prevQuestions);
		previous.put("active7d", prevActive7d.size());

		List<Map<String, Object>> topicStats = new ArrayList<>();
		for (Map.Entry<String, String> entry : topics.entrySet())
		{
			String tid = entry.getKey();
			String tname = entry.getValue();
			if (!topicFilter.isEmpty() && !tid.equals(topicFilter))
			{
				continue;
			}
			List<String> lv = new ArrayList<>();
			for (String u : users.keySet())
			{
				Map<String, Map<String, Integer>> userTopicMap = perUserTopic.get(u);
				Map<String, Integer> pt = (userTopicMap != null) ? userTopicMap.get(tid) : null;
				lv.add(pt != null ? levelOf(pt.get("mastered"), pt.get("answered")) : null);
			}

			Map<String, Object> weakest = null;
			int maxBeginners = 0;
			for (Map.Entry<String, Map<String, Object>> sEntry : perSection.entrySet())
			{
				Map<String, Object> sv = sEntry.getValue();
				if (tid.equals(sv.get("topic")))
				{
					List<String> sLevels = (List<String>) sv.get("levels");
					int countB = 0;
					for (String l : sLevels)
					{
						if ("beginner".equals(l))
							countB++;
					}
					if (countB > maxBeginners)
					{
						maxBeginners = countB;
						weakest = new HashMap<>();
						weakest.put("section", sEntry.getKey());
						weakest.put("name", sections.get(sEntry.getKey()));
						weakest.put("beginners", countB);
					}
				}
			}

			int peopleCount = 0;
			for (String l : lv)
			{
				if (l != null)
					peopleCount++;
			}

			Map<String, Object> ts = new HashMap<>();
			ts.put("id", tid);
			ts.put("name", tname);
			ts.put("people", peopleCount);
			ts.put("levels", levelsOf(lv));
			ts.put("weakest", (maxBeginners > 0) ? weakest : null);
			topicStats.add(ts);
		}

		List<Data> tqAll = new ArrayList<>();
		HitTracker qh = archive.query("tutorquestion").all().search();
		if (qh != null)
		{
			qh.enableBulkOperations();
			for (Object o : qh)
			{
				Data r = (Data) o;
				if (users.containsKey(r.get("user")) && (topicFilter.isEmpty() || topicFilter.equals(r.get("entitytopic"))))
				{
					tqAll.add(r);
				}
			}
		}

		List<Data> tq = new ArrayList<>();
		for (Data r : tqAll)
		{
			Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("datecreated"));
			if (d != null && !d.before(from) && d.before(to))
			{
				tq.add(r);
			}
		}

		Map<String, List<Data>> qBySection = new HashMap<>();
		for (Data r : tq)
		{
			String sid = r.get("componentsection");
			if (sid != null)
			{
				qBySection.computeIfAbsent(sid, k -> new ArrayList<>()).add(r);
			}
		}

		List<Map<String, Object>> sectionStats = new ArrayList<>();
		for (Map.Entry<String, Map<String, Object>> entry : perSection.entrySet())
		{
			String sid = entry.getKey();
			Map<String, Object> v = entry.getValue();
			List<Data> qs = qBySection.getOrDefault(sid, Collections.emptyList());
			List<String> sLevels = (List<String>) v.get("levels");
			int people = sLevels.size();
			int beginners = 0;
			for (String l : sLevels)
			{
				if ("beginner".equals(l))
					beginners++;
			}
			int unanswered = 0;
			int ratedCount = 0;
			int helpfulCount = 0;
			for (Data q : qs)
			{
				if (!"true".equals(q.get("replied")))
					unanswered++;
				String rating = q.get("rating");
				if (rating != null && !rating.isEmpty())
				{
					ratedCount++;
					if ("helpful".equals(rating))
						helpfulCount++;
				}
			}
			int attempts = (Integer) v.get("attempts");
			int cw = (Integer) v.get("cw");

			double score = 3.0 * beginners / Math.max(people, 1) + 2.0 * qs.size() / Math.max(attempts, 1) + 2.0 * unanswered / Math.max(qs.size(), 1) + 3.0 * cw / Math.max(attempts, 1);

			Map<String, Object> ss = new HashMap<>();
			ss.put("section", sid);
			ss.put("name", sections.get(sid));
			String tTopic = (String) v.get("topic");
			ss.put("topic", topics.get(tTopic));
			ss.put("topicId", tTopic);
			ss.put("people", people);
			ss.put("levels", levelsOf(sLevels));
			ss.put("beginners", beginners);
			ss.put("questions", qs.size());
			ss.put("misconceptions", cw);
			ss.put("unanswered", unanswered);
			ss.put("score", Math.round(score * 100) / 100.0);
			ss.put("helpfulShare", ratedCount > 0 ? helpfulCount / (double) ratedCount : null);
			sectionStats.add(ss);
		}

		List<Map<String, Object>> gaps = new ArrayList<>();
		for (Map<String, Object> ss : sectionStats)
		{
			if ((Integer) ss.get("people") > 0)
			{
				gaps.add(ss);
			}
		}
		gaps.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
		if (gaps.size() > 5)
		{
			gaps = new ArrayList<>(gaps.subList(0, 5));
		}

		Map<String, List<Data>> byTeam = new HashMap<>();
		for (Data u : users.values())
		{
			String t = u.get("team");
			byTeam.computeIfAbsent(t != null ? t : "", k -> new ArrayList<>()).add(u);
		}

		List<Map<String, Object>> teamStats = new ArrayList<>();
		for (Map.Entry<String, List<Data>> entry : byTeam.entrySet())
		{
			String tid = entry.getKey();
			List<Data> members = entry.getValue();
			Set<String> memberIds = new HashSet<>();
			List<String> lv = new ArrayList<>();
			int activatedCount = 0;
			int active7dCount = 0;
			for (Data m : members)
			{
				String mId = m.getId();
				memberIds.add(mId);
				lv.add(levelByUser.get(mId));
				if (activatedIds.contains(mId))
					activatedCount++;
				if (active7dUsers.contains(mId))
					active7dCount++;
			}

			String weakestTopicName = null;
			int maxTopicBeginners = 0;
			for (Map<String, Object> ts : topicStats)
			{
				String tName = (String) ts.get("name");
				String tId = (String) ts.get("id");
				int countB = 0;
				for (String mId : memberIds)
				{
					Map<String, Map<String, Integer>> userTopicMap = perUserTopic.get(mId);
					Map<String, Integer> pt = (userTopicMap != null) ? userTopicMap.get(tId) : null;
					if (pt != null && "beginner".equals(levelOf(pt.get("mastered"), pt.get("answered"))))
					{
						countB++;
					}
				}
				if (countB > maxTopicBeginners)
				{
					maxTopicBeginners = countB;
					weakestTopicName = tName;
				}
			}

			Map<String, Object> tObj = new HashMap<>();
			tObj.put("id", tid);
			String teamName = "";
			if (!tid.isEmpty())
			{
				Data teamData = allteams.get(tid);
				teamName = (teamData != null && teamData.getName() != null) ? teamData.getName() : tid;
			}
			tObj.put("name", teamName);
			tObj.put("members", memberIds.size());
			tObj.put("activated", activatedCount);
			tObj.put("active7d", active7dCount);
			tObj.put("levels", levelsOf(lv));
			tObj.put("weakest", maxTopicBeginners > 0 ? weakestTopicName : null);
			teamStats.add(tObj);
		}
		teamStats.sort(Comparator.comparing(a -> (String) a.get("name")));

		Map<String, Integer> calibration = new HashMap<>();
		int totalCc = 0, totalCu = 0, totalIc = 0, totalIu = 0;
		for (Map<String, Object> pu : perUser.values())
		{
			totalCc += (Integer) pu.get("cc");
			totalCu += (Integer) pu.get("uc");
			totalIc += (Integer) pu.get("uw");
			totalIu += (Integer) pu.get("cw");
		}
		calibration.put("cc", totalCc);
		calibration.put("cu", totalCu);
		calibration.put("ic", totalIc);
		calibration.put("iu", totalIu);

		Map<String, Data> orgUsers = new HashMap<>();
		for (Map.Entry<String, Data> entry : allUsers.entrySet())
		{
			Data u = entry.getValue();
			String id = entry.getKey();
			if (!"admin".equals(id) && !"agent".equals(id) && !"false".equals(String.valueOf(u.get("enabled"))))
			{
				orgUsers.put(id, u);
			}
		}

		Map<String, Object> median = null;
		if (orgUsers.size() >= 5)
		{
			Map<String, int[]> orgPer = new HashMap<>();
			for (Data r : allMastery)
			{
				String u = r.get("user");
				if (orgUsers.containsKey(u))
				{
					int[] ma = orgPer.computeIfAbsent(u, k -> new int[2]);
					ma[0] += getInt(r, "mastered");
					ma[1] += getInt(r, "answered");
				}
			}

			Calendar org7Cal = Calendar.getInstance();
			org7Cal.setTime(to);
			org7Cal.add(Calendar.DAY_OF_MONTH, -7);
			Date org7Date = org7Cal.getTime();

			Set<String> orgActive = new HashSet<>();
			for (Data r : allDaily)
			{
				String u = r.get("user");
				Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("day"));
				if (orgUsers.containsKey(u) && d != null && !d.before(org7Date) && d.before(to) && getInt(r, "answers") > 0)
				{
					orgActive.add(u);
				}
			}

			int expertCount = 0;
			for (String u : orgUsers.keySet())
			{
				int[] ma = orgPer.get(u);
				if (ma != null && "expert".equals(levelOf(ma[0], ma[1])))
				{
					expertCount++;
				}
			}

			median = new HashMap<>();
			median.put("activeShare", orgActive.size() / (double) orgUsers.size());
			median.put("expertShare", expertCount / (double) orgUsers.size());
		}

		Calendar inactCal = Calendar.getInstance();
		inactCal.setTime(now);
		inactCal.add(Calendar.DAY_OF_MONTH, -7);
		Date inactiveCutoff = inactCal.getTime();

		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

		List<Map<String, Object>> inactive = new ArrayList<>();
		for (Data u : users.values())
		{
			Map<String, Object> pu = perUser.get(u.getId());
			Date lastDate = (pu != null) ? (Date) pu.get("last") : null;
			if (lastDate == null || lastDate.before(inactiveCutoff))
			{
				Map<String, Object> inactObj = new HashMap<>();
				inactObj.put("user", u.getId());
				inactObj.put("name", formatUserName(u));
				inactObj.put("team", u.get("team"));
				inactObj.put("lastactivity", lastDate != null ? isoFormat.format(lastDate) : null);
				inactive.add(inactObj);
			}
		}
		inactive.sort(Comparator.comparing(a -> (String) a.getOrDefault("lastactivity", "")));

		Map<String, Object> iris = buildIrisAggregates(tq, sections);

		Calendar toInclusiveCal = Calendar.getInstance();
		toInclusiveCal.setTime(to);
		toInclusiveCal.add(Calendar.DAY_OF_MONTH, -1);
		Date toInclusive = toInclusiveCal.getTime();

		Map<String, Object> analytics = new HashMap<>();
		analytics.put("from", from);
		analytics.put("to", to);
		analytics.put("fromDay", dayFormat.format(from));
		analytics.put("toDayInclusive", dayFormat.format(toInclusive));
		analytics.put("topicFilter", topicFilter);
		analytics.put("teamFilter", teamFilter);
		analytics.put("users", users);
		analytics.put("allteams", allteams);
		analytics.put("topics", topics);
		analytics.put("sections", sections);
		analytics.put("mastery", mastery);
		analytics.put("perUser", perUser);
		analytics.put("perUserTopic", perUserTopic);
		analytics.put("perSection", perSection);
		analytics.put("levelByUser", levelByUser);
		analytics.put("dailyAll", dailyAll);
		analytics.put("daily", daily);
		analytics.put("series", series);
		analytics.put("previousSeries", prevSeries);
		analytics.put("cohort", cohort);
		analytics.put("previous", previous);
		analytics.put("levels", levelsOf(levelByUser.values()));
		analytics.put("topicStats", topicStats);
		analytics.put("sectionStats", sectionStats);
		analytics.put("gaps", gaps);
		analytics.put("teamStats", teamStats);
		analytics.put("calibration", calibration);
		analytics.put("median", median);
		analytics.put("inactive", inactive);
		analytics.put("tq", tq);
		analytics.put("iris", iris);

		inReq.putPageValue("analytics", analytics);
	}

	public void loadOverview(WebPageRequest inReq)
	{
		Map<String, Object> a = (Map<String, Object>) inReq.getPageValue("analytics");
		if (a == null)
		{
			loadAnalytics(inReq);
			a = (Map<String, Object>) inReq.getPageValue("analytics");
			if (a == null)
				return;
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("from", a.get("fromDay"));
		resp.put("to", a.get("toDayInclusive"));
		resp.put("cohort", a.get("cohort"));
		resp.put("series", a.get("series"));
		resp.put("previousSeries", a.get("previousSeries"));
		resp.put("levels", a.get("levels"));
		resp.put("topics", a.get("topicStats"));
		resp.put("calibration", a.get("calibration"));
		resp.put("teams", a.get("teamStats"));
		resp.put("median", a.get("median"));
		resp.put("previous", a.get("previous"));
		resp.put("gaps", a.get("gaps"));
		resp.put("iris", a.get("iris"));
		reply(inReq, resp);
	}

	public void loadActivity(WebPageRequest inReq)
	{
		Map<String, Object> a = (Map<String, Object>) inReq.getPageValue("analytics");
		if (a == null)
		{
			loadAnalytics(inReq);
			a = (Map<String, Object>) inReq.getPageValue("analytics");
			if (a == null)
				return;
		}

		MediaArchive archive = getMediaArchive(inReq);
		Date from = (Date) a.get("from");
		Date to = (Date) a.get("to");
		Map<String, Data> users = (Map<String, Data>) a.get("users");
		String topicFilter = (String) a.get("topicFilter");
		Map<String, Map<String, Object>> perSection = (Map<String, Map<String, Object>>) a.get("perSection");

		Map<String, Integer> hours = new HashMap<>();
		HitTracker ah = archive.query("tutoranswer").after("datecreated", from).search();
		if (ah != null)
		{
			ah.enableBulkOperations();
			for (Object o : ah)
			{
				Data x = (Data) o;
				Date d = DateStorageUtil.getStorageUtil().parseFromObject(x.getValue("datecreated"));
				if (d == null || !users.containsKey(x.get("user")) || !d.before(to))
				{
					continue;
				}
				if (!topicFilter.isEmpty() && !perSection.containsKey(x.get("componentsection")))
				{
					continue;
				}
				Calendar c = Calendar.getInstance();
				c.setTime(d);
				int dayOfWeek = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
				int hourOfDay = c.get(Calendar.HOUR_OF_DAY);
				String k = dayOfWeek + "_" + hourOfDay;
				hours.put(k, hours.getOrDefault(k, 0) + 1);
			}
		}

		Map<String, Object> cohort = (Map<String, Object>) a.get("cohort");
		int signedin = 0;
		for (Data u : users.values())
		{
			if (u.get("lastlogin") != null)
				signedin++;
		}

		Map<String, Object> funnel = new HashMap<>();
		funnel.put("cohort", cohort.get("total"));
		funnel.put("signedin", signedin);
		funnel.put("answered", cohort.get("activated"));
		funnel.put("active7d", cohort.get("active7d"));
		funnel.put("active30d", cohort.get("active30d"));

		JSONArray hoursArray = new JSONArray();
		for (Map.Entry<String, Integer> entry : hours.entrySet())
		{
			String[] parts = entry.getKey().split("_");
			JSONArray hRow = new JSONArray();
			hRow.add(Integer.parseInt(parts[0]));
			hRow.add(Integer.parseInt(parts[1]));
			hRow.add(entry.getValue());
			hoursArray.add(hRow);
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("series", a.get("series"));
		resp.put("funnel", funnel);
		resp.put("hours", hoursArray);
		resp.put("inactive", a.get("inactive"));
		resp.put("iris", a.get("iris"));
		reply(inReq, resp);
	}

	public void loadPerson(WebPageRequest inReq)
	{
		Map<String, Object> a = (Map<String, Object>) inReq.getPageValue("analytics");
		if (a == null)
		{
			loadAnalytics(inReq);
			a = (Map<String, Object>) inReq.getPageValue("analytics");
			if (a == null)
				return;
		}

		MediaArchive archive = getMediaArchive(inReq);
		String uid = inReq.getRequestParameter("user");
		uid = (uid != null) ? uid.trim().toLowerCase() : "";

		Map<String, Data> users = (Map<String, Data>) a.get("users");
		Data u = users.get(uid);
		if (u == null)
		{
			fail(inReq, 403, "out of scope");
			return;
		}

		Map<String, String> topics = (Map<String, String>) a.get("topics");
		Map<String, String> sections = (Map<String, String>) a.get("sections");
		List<Data> mastery = (List<Data>) a.get("mastery");
		Map<String, Map<String, Object>> perUser = (Map<String, Map<String, Object>>) a.get("perUser");
		Map<String, Map<String, Map<String, Integer>>> perUserTopic = (Map<String, Map<String, Map<String, Integer>>>) a.get("perUserTopic");
		List<Data> daily = (List<Data>) a.get("daily");
		List<Data> tq = (List<Data>) a.get("tq");
		Date from = (Date) a.get("from");
		Date to = (Date) a.get("to");

		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
		SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");

		JSONArray rows = new JSONArray();
		for (Data r : mastery)
		{
			if (uid.equals(r.get("user")))
			{
				JSONObject rObj = new JSONObject();
				String tid = r.get("entitytopic");
				String sid = r.get("componentsection");
				rObj.put("entitytopic", tid);
				rObj.put("topic", topics.get(tid));
				rObj.put("componentsection", sid);
				rObj.put("section", sections.get(sid));
				rObj.put("questions", getInt(r, "questions"));
				rObj.put("answered", getInt(r, "answered"));
				rObj.put("mastered", getInt(r, "mastered"));
				rObj.put("attempts", getInt(r, "attempts"));
				rObj.put("correct", getInt(r, "correct"));
				rObj.put("level", r.get("level"));
				Date la = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("lastactivity"));
				rObj.put("lastactivity", la != null ? isoFormat.format(la) : null);
				rObj.put("certaincorrect", getInt(r, "certaincorrect"));
				rObj.put("certainwrong", getInt(r, "certainwrong"));
				rObj.put("unsurecorrect", getInt(r, "unsurecorrect"));
				rObj.put("unsurewrong", getInt(r, "unsurewrong"));
				rows.add(rObj);
			}
		}

		Map<String, Object> pu = perUser.getOrDefault(uid, Collections.emptyMap());
		JSONObject calibObj = new JSONObject();
		calibObj.put("cc", pu.getOrDefault("cc", 0));
		calibObj.put("cu", pu.getOrDefault("uc", 0));
		calibObj.put("ic", pu.getOrDefault("uw", 0));
		calibObj.put("iu", pu.getOrDefault("cw", 0));

		Map<String, Map<String, Integer>> userTopicMap = perUserTopic.getOrDefault(uid, Collections.emptyMap());
		JSONArray topicList = new JSONArray();
		for (Map.Entry<String, String> entry : topics.entrySet())
		{
			String tid = entry.getKey();
			String tname = entry.getValue();
			Map<String, Integer> pt = userTopicMap.get(tid);
			if (pt == null)
				continue;

			String weakestSection = null;
			double minRatio = Double.MAX_VALUE;
			for (Object ro : rows)
			{
				JSONObject r = (JSONObject) ro;
				if (tid.equals(r.get("entitytopic")) && ((Integer) r.get("answered")) > 0)
				{
					double ratio = ((Integer) r.get("mastered")) / (double) ((Integer) r.get("answered"));
					if (ratio < minRatio)
					{
						minRatio = ratio;
						weakestSection = (String) r.get("section");
					}
				}
			}

			JSONObject tObj = new JSONObject();
			tObj.put("id", tid);
			tObj.put("name", tname);
			tObj.put("level", levelOf(pt.get("mastered"), pt.get("answered")));
			tObj.put("mastered", pt.get("mastered"));
			tObj.put("answered", pt.get("answered"));
			tObj.put("weakest", weakestSection);
			topicList.add(tObj);
		}

		List<Data> mineDaily = new ArrayList<>();
		int totalSessions = 0, totalMinutes = 0, activeDays = 0;
		for (Data r : daily)
		{
			if (uid.equals(r.get("user")))
			{
				mineDaily.add(r);
				totalSessions += getInt(r, "sessions");
				totalMinutes += getInt(r, "minutes");
				if (getInt(r, "answers") > 0)
					activeDays++;
			}
		}

		Map<String, Map<String, Object>> seriesMap = new LinkedHashMap<>();
		Calendar dCal = Calendar.getInstance();
		dCal.setTime(from);
		while (dCal.getTime().before(to))
		{
			String dStr = dayFormat.format(dCal.getTime());
			Map<String, Object> dayObj = new HashMap<>();
			dayObj.put("day", dStr);
			dayObj.put("answers", 0);
			dayObj.put("correct", 0);
			dayObj.put("minutes", 0);
			dayObj.put("sessions", 0);
			dayObj.put("questions", 0);
			seriesMap.put(dStr, dayObj);
			dCal.add(Calendar.DAY_OF_MONTH, 1);
		}

		for (Data r : mineDaily)
		{
			Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("day"));
			if (d != null)
			{
				String dStr = dayFormat.format(d);
				Map<String, Object> s = seriesMap.get(dStr);
				if (s != null)
				{
					s.put("answers", (Integer) s.get("answers") + getInt(r, "answers"));
					s.put("correct", (Integer) s.get("correct") + getInt(r, "correct"));
					s.put("minutes", (Integer) s.get("minutes") + getInt(r, "minutes"));
					s.put("sessions", (Integer) s.get("sessions") + getInt(r, "sessions"));
					s.put("questions", (Integer) s.get("questions") + getInt(r, "questions"));
				}
			}
		}

		List<Data> qs = new ArrayList<>();
		int rated = 0, helpfulCount = 0;
		Map<String, Integer> qsBySection = new HashMap<>();
		for (Data r : tq)
		{
			if (uid.equals(r.get("user")))
			{
				qs.add(r);
				String rating = r.get("rating");
				if (rating != null && !rating.isEmpty())
				{
					rated++;
					if ("helpful".equals(rating))
						helpfulCount++;
				}
				String sid = r.get("componentsection");
				if (sid != null)
				{
					qsBySection.put(sid, qsBySection.getOrDefault(sid, 0) + 1);
				}
			}
		}

		JSONArray irisSections = new JSONArray();
		for (Map.Entry<String, Integer> entry : qsBySection.entrySet())
		{
			JSONObject secObj = new JSONObject();
			secObj.put("section", entry.getKey());
			secObj.put("name", sections.get(entry.getKey()));
			secObj.put("questions", entry.getValue());
			irisSections.add(secObj);
		}

		Data userProfileData = (Data) archive.getSearcher("userprofile").searchById(uid);
		String role = (userProfileData != null && userProfileData.get("settingsgroup") != null) ? userProfileData.get("settingsgroup") : "users";

		JSONObject userObj = new JSONObject();
		userObj.put("id", uid);
		userObj.put("name", formatUserName(u));
		userObj.put("team", u.get("team"));
		userObj.put("role", role);
		userObj.put("enabled", !"false".equals(String.valueOf(u.get("enabled"))));
		userObj.put("lastlogin", u.get("lastlogin"));
		userObj.put("creationdate", u.get("creationdate"));

		JSONObject usageObj = new JSONObject();
		usageObj.put("sessions", totalSessions);
		usageObj.put("minutes", totalMinutes);
		usageObj.put("activeDays", activeDays);

		JSONObject irisObj = new JSONObject();
		irisObj.put("questions", qs.size());
		irisObj.put("sections", irisSections);
		irisObj.put("helpfulShare", rated > 0 ? helpfulCount / (double) rated : null);

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("user", userObj);
		resp.put("rows", rows);
		resp.put("series", new ArrayList<>(seriesMap.values()));
		resp.put("calibration", calibObj);
		resp.put("topics", topicList);
		resp.put("usage", usageObj);
		resp.put("iris", irisObj);
		reply(inReq, resp);
	}

	public void askAnalytics(WebPageRequest inReq)
	{
		Map<String, Object> a = (Map<String, Object>) inReq.getPageValue("analytics");
		if (a == null)
		{
			loadAnalytics(inReq);
			a = (Map<String, Object>) inReq.getPageValue("analytics");
			if (a == null)
				return;
		}

		MediaArchive archive = getMediaArchive(inReq);
		String period = inReq.getRequestParameter("period");
		period = (period != null) ? period.trim() : "";
		String topic = inReq.getRequestParameter("entitytopic");
		topic = (topic != null) ? topic.trim() : "";
		String team = inReq.getRequestParameter("team");
		team = (team != null) ? team.trim() : "";

		Map<String, Object> base = new HashMap<>();
		base.put("from", a.get("fromDay"));
		base.put("to", a.get("toDayInclusive"));
		if (!period.isEmpty())
			base.put("period", period);
		base.put("entitytopic", topic);
		base.put("team", team);

		List<Map<String, Object>> facts = new ArrayList<>();
		int[] factIdCounter = new int[] {0};

		java.util.function.Consumer<Map<String, Object>> addFact = fMap -> {
			factIdCounter[0]++;
			fMap.put("id", "f" + factIdCounter[0]);
			facts.add(fMap);
		};

		Map<String, Object> cohort = (Map<String, Object>) a.get("cohort");
		Map<String, Object> previous = (Map<String, Object>) a.get("previous");
		List<Map<String, Object>> series = (List<Map<String, Object>>) a.get("series");
		List<Map<String, Object>> topicStats = (List<Map<String, Object>>) a.get("topicStats");
		List<Map<String, Object>> sectionStats = (List<Map<String, Object>>) a.get("sectionStats");
		List<Map<String, Object>> gaps = (List<Map<String, Object>>) a.get("gaps");
		List<Map<String, Object>> teamStats = (List<Map<String, Object>>) a.get("teamStats");
		Map<String, Object> median = (Map<String, Object>) a.get("median");
		List<Map<String, Object>> inactive = (List<Map<String, Object>>) a.get("inactive");
		Map<String, Object> iris = (Map<String, Object>) a.get("iris");
		Map<String, Data> users = (Map<String, Data>) a.get("users");
		Map<String, Map<String, Object>> perUser = (Map<String, Map<String, Object>>) a.get("perUser");
		Map<String, String> levelByUser = (Map<String, String>) a.get("levelByUser");
		Map<String, Data> allteams = (Map<String, Data>) a.get("allteams");
		Map<String, String> topics = (Map<String, String>) a.get("topics");
		List<Data> tq = (List<Data>) a.get("tq");

		addFactHelper(addFact, base, "Personas en el alcance", cohort.get("total"), "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Personas que han respondido alguna vez", cohort.get("activated"), "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Personas activas en los últimos 7 días", cohort.get("active7d"), "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Personas activas en los últimos 30 días", cohort.get("active30d"), "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Activas 7 días en el periodo anterior", previous.get("active7d"), "overview", Collections.emptyMap(), "stat");

		int sumAnswers = 0, sumMinutes = 0, sumCertainWrong = 0;
		for (Map<String, Object> s : series)
		{
			sumAnswers += (Integer) s.get("answers");
			sumMinutes += (Integer) s.get("minutes");
			sumCertainWrong += (Integer) s.get("certainwrong");
		}
		addFactHelper(addFact, base, "Respuestas en el periodo", sumAnswers, "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Respuestas en el periodo anterior", previous.get("answers"), "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Minutos en la app en el periodo", sumMinutes, "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Conceptos erróneos (respuestas seguras y erróneas) en el periodo", sumCertainWrong, "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Personas por nivel (Sin empezar / Principiante / Competente / Experto)", a.get("levels"), "overview", Collections.emptyMap(), "stat");
		addFactHelper(addFact, base, "Calibración de confianza: consolidado, frágil, lagunas conocidas, concepto erróneo", a.get("calibration"), "overview", Collections.emptyMap(), "stat");

		for (Map<String, Object> t : topicStats)
		{
			Map<String, Object> extra = Collections.singletonMap("entitytopic", t.get("id"));
			addFactHelper(addFact, base, "Tema «" + t.get("name") + "»: personas por nivel", t.get("levels"), "overview", extra, "topic");
			Map<String, Object> weakest = (Map<String, Object>) t.get("weakest");
			if (weakest != null)
			{
				addFactHelper(addFact, base, "Tema «" + t.get("name") + "»: subtema más débil", weakest.get("name") + " (Principiante en " + weakest.get("beginners")
					+ " personas)", "mastery", extra, "weakest");
			}
		}

		List<Map<String, Object>> sortedSections = new ArrayList<>(sectionStats);
		sortedSections.sort((s1, s2) -> Integer.compare((Integer) s2.get("beginners"), (Integer) s1.get("beginners")));
		int secLimit = Math.min(15, sortedSections.size());
		for (int k = 0; k < secLimit; k++)
		{
			Map<String, Object> s = sortedSections.get(k);
			Map<String, Object> sVal = new HashMap<>();
			sVal.put("levels", s.get("levels"));
			sVal.put("questions", s.get("questions"));
			sVal.put("misconceptions", s.get("misconceptions"));
			addFactHelper(addFact, base, "Subtema «" + s.get("name") + "» (" + s.get("topic") + "): personas por nivel, preguntas al tutor, conceptos erróneos", sVal, "mastery", Collections
				.singletonMap("entitytopic", s.get("topicId")), "grid");
		}

		for (int k = 0; k < gaps.size(); k++)
		{
			Map<String, Object> g = gaps.get(k);
			String gDesc = "Principiante en " + g.get("beginners") + " de " + g.get("people") + "; " + g.get("questions") + " preguntas al tutor, " + g.get("unanswered") + " sin respuesta; "
				+ g.get("misconceptions") + " conceptos erróneos";
			addFactHelper(addFact, base, "Brecha " + (k + 1) + ": «" + g.get("name") + "» (" + g.get("topic") + ")", gDesc, "overview", Collections.emptyMap(), "gap");
		}

		for (Map<String, Object> t : teamStats)
		{
			Map<String, Object> tVal = new HashMap<>();
			tVal.put("members", t.get("members"));
			tVal.put("activated", t.get("activated"));
			tVal.put("active7d", t.get("active7d"));
			tVal.put("levels", t.get("levels"));
			tVal.put("weakest", t.get("weakest"));
			String tName = (String) t.get("name");
			addFactHelper(addFact, base, "Equipo «" + (!tName.isEmpty() ? tName : "Sin equipo") + "»: personas, han empezado, activas 7 días, niveles, tema más débil", tVal, "team", Collections
				.singletonMap("team", t.get("id")), "stat");
		}

		if (median != null)
		{
			Map<String, Object> medVal = new HashMap<>();
			medVal.put("active", pct((Double) median.get("activeShare")));
			medVal.put("expert", pct((Double) median.get("expertShare")));
			addFactHelper(addFact, base, "Mediana de la organización: cuota de activas 7 días y de expertos (anónima)", medVal, "overview", Collections.emptyMap(), "stat");
		}

		int inactLimit = Math.min(20, inactive.size());
		for (int k = 0; k < inactLimit; k++)
		{
			Map<String, Object> p = inactive.get(k);
			String pTeam = (String) p.get("team");
			Data tData = (pTeam != null) ? allteams.get(pTeam) : null;
			String tName = (tData != null && tData.getName() != null) ? tData.getName() : "sin equipo";
			String lastAct = (String) p.get("lastactivity");
			String actDesc = (lastAct != null && !lastAct.isEmpty()) ? "última actividad " + lastAct.substring(0, Math.min(10, lastAct.length())) : "nunca ha respondido";
			addFactHelper(addFact, base, "Sin actividad: " + p.get("name") + " (" + tName + ")", actDesc, "person", Collections.singletonMap("user", p.get("user")), "inactive");
		}

		List<Map<String, Object>> atRisk = new ArrayList<>();
		for (Data u : users.values())
		{
			Map<String, Object> pu = perUser.get(u.getId());
			if (pu != null)
			{
				int att = (Integer) pu.get("attempts");
				int cw = (Integer) pu.get("cw");
				String lvl = levelByUser.get(u.getId());
				if ((att >= 10 && "beginner".equals(lvl)) || (att > 0 && (cw / (double) att) >= 0.3))
				{
					Map<String, Object> item = new HashMap<>();
					item.put("user", u);
					item.put("pu", pu);
					atRisk.add(item);
				}
			}
		}
		int riskLimit = Math.min(15, atRisk.size());
		for (int k = 0; k < riskLimit; k++)
		{
			Map<String, Object> item = atRisk.get(k);
			Data u = (Data) item.get("user");
			Map<String, Object> pu = (Map<String, Object>) item.get("pu");
			String lvl = levelByUser.get(u.getId());
			addFactHelper(addFact, base, "En riesgo: " + formatUserName(u), "nivel " + (lvl != null ? lvl : "sin empezar") + ", " + pu.get("cw") + " conceptos erróneos en " + pu.get("attempts")
				+ " intentos", "person", Collections.singletonMap("user", u.getId()), "inactive");
		}

		Map<String, Object> irisStats = new HashMap<>();
		irisStats.put("questions", iris.get("questions"));
		irisStats.put("people", iris.get("people"));
		irisStats.put("cited", pct((Double) iris.get("citedShare")));
		irisStats.put("rated", pct((Double) iris.get("ratedShare")));
		irisStats.put("helpful", pct((Double) iris.get("helpfulShare")));
		addFactHelper(addFact, base, "Preguntas al tutor en el periodo: total, personas, con fuente, valoradas, útiles", irisStats, "activity", Collections.emptyMap(), "iris");

		List<Map<String, Object>> themes = (List<Map<String, Object>>) iris.get("themes");
		if (themes != null)
		{
			for (Map<String, Object> t : themes)
			{
				addFactHelper(addFact, base, "Preguntas al tutor de tipo «" + t.get("theme") + "»", t.get("count"), "activity", Collections.emptyMap(), "iris");
			}
		}

		List<Map<String, Object>> irisSecs = (List<Map<String, Object>>) iris.get("sections");
		if (irisSecs != null)
		{
			int secLim = Math.min(10, irisSecs.size());
			for (int k = 0; k < secLim; k++)
			{
				Map<String, Object> s = irisSecs.get(k);
				addFactHelper(addFact, base, "Preguntas al tutor sobre «" + s.get("name") + "»", s.get("questions"), "activity", Collections.emptyMap(), "iris");
			}
		}

		List<Map<String, Object>> labels = (List<Map<String, Object>>) iris.get("labels");
		if (labels != null)
		{
			int labelLim = Math.min(20, labels.size());
			for (int k = 0; k < labelLim; k++)
			{
				Map<String, Object> l = labels.get(k);
				addFactHelper(addFact, base, "Sobre qué preguntan (etiqueta): «" + l.get("label") + "»", l.get("count"), "activity", Collections.emptyMap(), "iris");
			}
		}

		String sel = inReq.getRequestParameter("user");
		sel = (sel != null) ? sel.trim().toLowerCase() : "";
		if (!sel.isEmpty() && users.containsKey(sel))
		{
			Data u = users.get(sel);
			Map<String, Object> pu = perUser.get(sel);
			String t = u.get("team");
			Data tData = (t != null) ? allteams.get(t) : null;
			Map<String, Object> selVal = new HashMap<>();
			selVal.put("team", (tData != null) ? tData.getName() : null);
			selVal.put("level", levelByUser.get(sel));
			selVal.put("answered", (pu != null) ? pu.get("answered") : 0);
			selVal.put("mastered", (pu != null) ? pu.get("mastered") : 0);
			selVal.put("misconceptions", (pu != null) ? pu.get("cw") : 0);
			Date lastDate = (pu != null) ? (Date) pu.get("last") : null;
			SimpleDateFormat ymdFormat = new SimpleDateFormat("yyyy-MM-dd");
			selVal.put("lastactivity", lastDate != null ? ymdFormat.format(lastDate) : null);
			addFactHelper(addFact, base, "Persona seleccionada: " + formatUserName(u), selVal, "person", Collections.singletonMap("user", sel), "stat");

			Map<String, Map<String, Map<String, Integer>>> perUserTopic = (Map<String, Map<String, Map<String, Integer>>>) a.get("perUserTopic");
			Map<String, Map<String, Integer>> uTopicMap = perUserTopic.get(sel);
			if (uTopicMap != null)
			{
				for (Map.Entry<String, Map<String, Integer>> entry : uTopicMap.entrySet())
				{
					String tid = entry.getKey();
					Map<String, Integer> pt = entry.getValue();
					Map<String, Object> extra = new HashMap<>();
					extra.put("user", sel);
					extra.put("entitytopic", tid);
					addFactHelper(addFact, base, formatUserName(u) + " en «" + topics.get(tid)
						+ "»", (levelOf(pt.get("mastered"), pt.get("answered")) != null ? levelOf(pt.get("mastered"), pt.get("answered")) : "sin empezar") + ", " + pt.get("mastered") + " de "
							+ pt.get("answered") + " dominadas", "person", extra, "topic");
				}
			}

			int qsCount = 0;
			for (Data r : tq)
			{
				if (sel.equals(r.get("user")))
					qsCount++;
			}
			addFactHelper(addFact, base, formatUserName(u) + ": preguntas al tutor en el periodo", qsCount, "person", Collections.singletonMap("user", sel), "iris");
		}

		if ("facts".equals(inReq.getRequestParameter("debug")))
		{
			UserProfile userProfile = inReq.getUserProfile();
			if (userProfile == null || !userProfile.hasPermission("analytics_operate"))
			{
				fail(inReq, 403, "operate");
				return;
			}
			JSONObject resp = new JSONObject();
			resp.put("ok", Boolean.TRUE);
			resp.put("facts", facts);
			reply(inReq, resp);
			return;
		}

		String question = inReq.getRequestParameter("question");
		question = (question != null) ? question.trim() : "";
		if (question.length() > 500)
			question = question.substring(0, 500);
		if (question.isEmpty())
		{
			fail(inReq, 400, "no question");
			return;
		}

		String tutorSetting = archive.getCatalogSettingValue("tutorpersona");
		if (tutorSetting == null || tutorSetting.isEmpty())
			tutorSetting = "iris";
		Data persona = archive.getData("tutorpersona", tutorSetting);

		List<?> history = Collections.emptyList();
		String historyParam = inReq.getRequestParameter("history");
		if (historyParam != null && !historyParam.trim().isEmpty())
		{
			try
			{
				Object parsed = JSONValue.parse(historyParam);
				if (parsed instanceof List)
				{
					List<?> hList = (List<?>) parsed;
					if (hList.size() > 6)
					{
						history = new ArrayList<>(hList.subList(hList.size() - 6, hList.size()));
					}
					else
					{
						history = hList;
					}
				}
			}
			catch (Exception ignored)
			{
			}
		}

		BaseAgentContext ctx = new BaseAgentContext();
		ctx.putContextValue("personaname", (persona != null && persona.getName() != null) ? persona.getName() : "Iris");
		ctx.putContextValue("organization", (persona != null && persona.get("organization") != null) ? persona.get("organization") : "");
		ctx.putContextValue("language", (persona != null && persona.get("tutorlanguage") != null) ? persona.get("tutorlanguage") : "es");
		String screen = inReq.getRequestParameter("screen");
		ctx.putContextValue("screen", (screen != null && !screen.isEmpty()) ? screen : "overview");

		List<Map<String, Object>> minimalFacts = new ArrayList<>();
		for (Map<String, Object> fObj : facts)
		{
			Map<String, Object> mf = new HashMap<>();
			mf.put("id", fObj.get("id"));
			mf.put("label", fObj.get("label"));
			mf.put("value", fObj.get("value"));
			minimalFacts.add(mf);
		}

		ctx.putContextValue("facts", JSONValue.toJSONString(minimalFacts));
		ctx.putContextValue("history", JSONValue.toJSONString(history));
		ctx.putContextValue("question", question);

		Map<String, Object> out = null;
		try
		{
			out = (Map<String, Object>) archive.getLlmConnection("thinking").callStructure(ctx, "analytics_ask").getResponsePayload();
		}
		catch (Exception e)
		{
			log.error("analytics ask failed", e);
			fail(inReq, 503, "llm");
			return;
		}

		if (out == null)
		{
			fail(inReq, 503, "llm");
			return;
		}

		Map<String, Map<String, Object>> byId = new HashMap<>();
		for (Map<String, Object> fObj : facts)
		{
			byId.put((String) fObj.get("id"), fObj);
		}

		List<String> cited = new ArrayList<>();
		Object citationsObj = out.get("citations");
		if (citationsObj instanceof List)
		{
			for (Object cItem : (List<?>) citationsObj)
			{
				if (cItem != null)
				{
					String cid = cItem.toString();
					if (byId.containsKey(cid) && !cited.contains(cid))
					{
						cited.add(cid);
					}
				}
			}
		}

		String answer = (out.get("answer") != null) ? out.get("answer").toString() : "";
		Matcher matcher = Pattern.compile("\\[(f\\d+)\\]").matcher(answer);
		while (matcher.find())
		{
			String fid = matcher.group(1);
			if (byId.containsKey(fid) && !cited.contains(fid))
			{
				cited.add(fid);
			}
		}

		final String finalAnswer = answer;
		cited.sort(Comparator.comparingInt(id -> {
			int idx = finalAnswer.indexOf("[" + id + "]");
			return idx < 0 ? Integer.MAX_VALUE : idx;
		}));

		Matcher replaceMatcher = Pattern.compile(" ?\\[(f\\d+)\\]").matcher(answer);
		StringBuffer sb = new StringBuffer();
		while (replaceMatcher.find())
		{
			String fid = replaceMatcher.group(1);
			if (byId.containsKey(fid))
			{
				replaceMatcher.appendReplacement(sb, Matcher.quoteReplacement(replaceMatcher.group(0)));
			}
			else
			{
				replaceMatcher.appendReplacement(sb, "");
			}
		}
		replaceMatcher.appendTail(sb);
		answer = sb.toString();

		JSONObject auditBefore = new JSONObject();
		auditBefore.put("question", question);
		JSONObject auditAfter = new JSONObject();
		auditAfter.put("citations", cited);
		auditAfter.put("ok", Boolean.TRUE);
		audit(inReq, archive, "analytics.ask", "analytics", (screen != null ? screen : ""), auditBefore, auditAfter);

		List<Map<String, Object>> citedFacts = new ArrayList<>();
		for (String cid : cited)
		{
			citedFacts.add(byId.get(cid));
		}

		List<?> followups = Collections.emptyList();
		Object followupsObj = out.get("followups");
		if (followupsObj instanceof List)
		{
			List<?> fList = (List<?>) followupsObj;
			followups = fList.size() > 3 ? fList.subList(0, 3) : fList;
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("answer", answer);
		resp.put("citations", citedFacts);
		resp.put("followups", followups);
		resp.put("model", "thinking");
		reply(inReq, resp);
	}

	public void loadReport(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");

		String topicFilter = inReq.getRequestParameter("entitytopic");
		topicFilter = (topicFilter != null) ? topicFilter.trim() : "";
		String teamFilter = inReq.getRequestParameter("team");
		teamFilter = (teamFilter != null) ? teamFilter.trim() : "";

		if (scope != null && !teamFilter.isEmpty() && !scope.contains(teamFilter))
		{
			fail(inReq, 400, "out of scope");
			return;
		}

		Map<String, Data> users = new HashMap<>();
		HitTracker uh = archive.query("user").all().search();
		if (uh != null)
		{
			uh.enableBulkOperations();
			for (Object o : uh)
			{
				Data u = (Data) o;
				users.put(u.getId(), u);
			}
		}

		Map<String, String> topics = new LinkedHashMap<>();
		HitTracker th = archive.query("entitytopic").all().search();
		if (th != null)
		{
			for (Object o : th)
			{
				Data t = (Data) o;
				topics.put(t.getId(), t.getName());
			}
		}

		Map<String, String> sections = new HashMap<>();
		HitTracker sh = archive.query("componentsection").exact("playbackentitymoduleid", "entitytutorial").search();
		if (sh != null)
		{
			for (Object o : sh)
			{
				Data s = (Data) o;
				sections.put(s.getId(), s.getName());
			}
		}

		Calendar weekCal = Calendar.getInstance();
		weekCal.add(Calendar.DAY_OF_MONTH, -7);
		Date week = weekCal.getTime();

		Set<String> active = new HashSet<>();
		Map<String, Integer> levels = new HashMap<>();
		levels.put("beginner", 0);
		levels.put("competent", 0);
		levels.put("expert", 0);

		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

		JSONArray rows = new JSONArray();
		HitTracker mh = archive.query("tutormastery").all().search();
		if (mh != null)
		{
			mh.enableBulkOperations();
			for (Object o : mh)
			{
				Data r = (Data) o;
				Data u = users.get(r.get("user"));
				if (u == null)
					continue;
				String team = u.get("team");
				if (scope != null && (team == null || !scope.contains(team)))
					continue;
				if (!teamFilter.isEmpty() && !teamFilter.equals(team))
					continue;
				if (!topicFilter.isEmpty() && !topicFilter.equals(r.get("entitytopic")))
					continue;

				Date la = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("lastactivity"));
				if (la != null && la.after(week))
				{
					active.add(u.getId());
				}
				String lvl = r.get("level");
				if (lvl != null && levels.containsKey(lvl))
				{
					levels.put(lvl, levels.get(lvl) + 1);
				}

				JSONObject rObj = new JSONObject();
				rObj.put("user", u.getId());
				rObj.put("name", formatUserName(u));
				rObj.put("team", team);
				rObj.put("entitytopic", r.get("entitytopic"));
				rObj.put("topic", topics.get(r.get("entitytopic")));
				rObj.put("componentsection", r.get("componentsection"));
				rObj.put("section", sections.get(r.get("componentsection")));
				rObj.put("questions", getInt(r, "questions"));
				rObj.put("answered", getInt(r, "answered"));
				rObj.put("mastered", getInt(r, "mastered"));
				rObj.put("attempts", getInt(r, "attempts"));
				rObj.put("correct", getInt(r, "correct"));
				rObj.put("level", lvl);
				rObj.put("lastactivity", la != null ? isoFormat.format(la) : null);
				rObj.put("certaincorrect", getInt(r, "certaincorrect"));
				rObj.put("certainwrong", getInt(r, "certainwrong"));
				rObj.put("unsurecorrect", getInt(r, "unsurecorrect"));
				rObj.put("unsurewrong", getInt(r, "unsurewrong"));
				Date compAt = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("computedat"));
				rObj.put("computedat", compAt != null ? isoFormat.format(compAt) : null);
				rows.add(rObj);
			}
		}

		int answers7d = 0;
		HitTracker ah = archive.query("tutoranswer").after("datecreated", week).search();
		if (ah != null)
		{
			ah.enableBulkOperations();
			for (Object o : ah)
			{
				Data a = (Data) o;
				Data u = users.get(a.get("user"));
				if (u != null)
				{
					String team = u.get("team");
					if ((scope == null || (team != null && scope.contains(team))) && (teamFilter.isEmpty() || teamFilter.equals(team)))
					{
						answers7d++;
					}
				}
			}
		}

		JSONObject summary = new JSONObject();
		summary.put("activeusers7d", active.size());
		summary.put("answers7d", answers7d);
		summary.put("levels", levels);

		JSONArray topicList = new JSONArray();
		for (Map.Entry<String, String> entry : topics.entrySet())
		{
			JSONObject tObj = new JSONObject();
			tObj.put("id", entry.getKey());
			tObj.put("name", entry.getValue());
			topicList.add(tObj);
		}

		JSONObject resp = new JSONObject();
		resp.put("rows", rows);
		resp.put("summary", summary);
		resp.put("topics", topicList);
		reply(inReq, resp);
	}

	// ---------------- Helper Methods ----------------

	private static int getInt(Data d, String field)
	{
		Object val = d.getValue(field);
		if (val instanceof Number)
		{
			return ((Number) val).intValue();
		}
		if (val != null)
		{
			try
			{
				return Integer.parseInt(val.toString().trim());
			}
			catch (Exception ignored)
			{
			}
		}
		return 0;
	}

	private static String levelOf(int mastered, int answered)
	{
		if (answered == 0)
			return null;
		double ratio = mastered / (double) answered;
		if (ratio < 0.5)
			return "beginner";
		if (ratio < 0.9)
			return "competent";
		return "expert";
	}

	private static Map<String, Integer> levelsOf(Collection<String> lv)
	{
		int notstarted = 0, beginner = 0, competent = 0, expert = 0;
		for (String l : lv)
		{
			if (l == null)
				notstarted++;
			else if ("beginner".equals(l))
				beginner++;
			else if ("competent".equals(l))
				competent++;
			else if ("expert".equals(l))
				expert++;
		}
		Map<String, Integer> map = new HashMap<>();
		map.put("notstarted", notstarted);
		map.put("beginner", beginner);
		map.put("competent", competent);
		map.put("expert", expert);
		return map;
	}

	private static String formatUserName(Data u)
	{
		String fn = u.get("firstName");
		String ln = u.get("lastName");
		String name = ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
		return !name.isEmpty() ? name : u.getId();
	}

	private static List<Map<String, Object>> buildDailySeries(Date start, Date end, List<Data> rows, SimpleDateFormat dayFormat)
	{
		Map<String, Map<String, Object>> seriesMap = new LinkedHashMap<>();
		Calendar dCal = Calendar.getInstance();
		dCal.setTime(start);
		while (dCal.getTime().before(end))
		{
			String dStr = dayFormat.format(dCal.getTime());
			Map<String, Object> dayObj = new HashMap<>();
			dayObj.put("day", dStr);
			dayObj.put("people", 0);
			dayObj.put("answers", 0);
			dayObj.put("minutes", 0);
			dayObj.put("sessions", 0);
			dayObj.put("certainwrong", 0);
			dayObj.put("questions", 0);
			seriesMap.put(dStr, dayObj);
			dCal.add(Calendar.DAY_OF_MONTH, 1);
		}

		for (Data r : rows)
		{
			Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("day"));
			if (d != null)
			{
				String dStr = dayFormat.format(d);
				Map<String, Object> e = seriesMap.get(dStr);
				if (e != null)
				{
					if (getInt(r, "answers") > 0)
					{
						e.put("people", (Integer) e.get("people") + 1);
					}
					e.put("answers", (Integer) e.get("answers") + getInt(r, "answers"));
					e.put("minutes", (Integer) e.get("minutes") + getInt(r, "minutes"));
					e.put("sessions", (Integer) e.get("sessions") + getInt(r, "sessions"));
					e.put("certainwrong", (Integer) e.get("certainwrong") + getInt(r, "certainwrong"));
					e.put("questions", (Integer) e.get("questions") + getInt(r, "questions"));
				}
			}
		}
		return new ArrayList<>(seriesMap.values());
	}

	private static Set<String> activeSince(List<Data> rows, Date to, int daysBack)
	{
		Calendar cal = Calendar.getInstance();
		cal.setTime(to);
		cal.add(Calendar.DAY_OF_MONTH, -daysBack);
		Date since = cal.getTime();

		Set<String> active = new HashSet<>();
		for (Data r : rows)
		{
			Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("day"));
			if (d != null && !d.before(since) && d.before(to) && getInt(r, "answers") > 0)
			{
				active.add(r.get("user"));
			}
		}
		return active;
	}

	private static Map<String, Object> buildIrisAggregates(List<Data> qs, Map<String, String> sections)
	{
		int rated = 0, helpfulCount = 0;
		Set<String> people = new HashSet<>();
		Map<String, Integer> themes = new HashMap<>();
		Map<String, List<Data>> secMap = new HashMap<>();
		Map<String, Integer> labelsCount = new HashMap<>();

		for (Data q : qs)
		{
			String uid = q.get("user");
			if (uid != null)
				people.add(uid);
			String rating = q.get("rating");
			if (rating != null && !rating.isEmpty())
			{
				rated++;
				if ("helpful".equals(rating))
					helpfulCount++;
			}
			String theme = q.get("theme");
			theme = (theme != null && !theme.isEmpty()) ? theme : "unclassified";
			themes.put(theme, themes.getOrDefault(theme, 0) + 1);

			String sid = q.get("componentsection");
			if (sid != null)
			{
				secMap.computeIfAbsent(sid, k -> new ArrayList<>()).add(q);
			}

			String label = q.get("topiclabel");
			if (label != null && !label.isEmpty())
			{
				labelsCount.put(label, labelsCount.getOrDefault(label, 0) + 1);
			}
		}

		int citedCount = 0;
		for (Data q : qs)
		{
			if ("true".equals(q.get("cited")))
				citedCount++;
		}

		List<Map<String, Object>> themeList = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : themes.entrySet())
		{
			Map<String, Object> tObj = new HashMap<>();
			tObj.put("theme", entry.getKey());
			tObj.put("count", entry.getValue());
			themeList.add(tObj);
		}
		themeList.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));

		List<Map<String, Object>> secList = new ArrayList<>();
		for (Map.Entry<String, List<Data>> entry : secMap.entrySet())
		{
			String sid = entry.getKey();
			List<Data> sQs = entry.getValue();
			int sRated = 0, sHelpful = 0;
			for (Data q : sQs)
			{
				String r = q.get("rating");
				if (r != null && !r.isEmpty())
				{
					sRated++;
					if ("helpful".equals(r))
						sHelpful++;
				}
			}
			Map<String, Object> sObj = new HashMap<>();
			sObj.put("section", sid);
			sObj.put("name", sections.get(sid));
			sObj.put("questions", sQs.size());
			sObj.put("helpfulShare", sRated > 0 ? sHelpful / (double) sRated : null);
			secList.add(sObj);
		}
		secList.sort((a, b) -> Integer.compare((Integer) b.get("questions"), (Integer) a.get("questions")));

		List<Map<String, Object>> labelList = new ArrayList<>();
		if (people.size() >= 5)
		{
			for (Map.Entry<String, Integer> entry : labelsCount.entrySet())
			{
				if (entry.getValue() >= 3)
				{
					Map<String, Object> lObj = new HashMap<>();
					lObj.put("label", entry.getKey());
					lObj.put("count", entry.getValue());
					labelList.add(lObj);
				}
			}
			labelList.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
			if (labelList.size() > 30)
			{
				labelList = new ArrayList<>(labelList.subList(0, 30));
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("questions", qs.size());
		result.put("people", people.size());
		result.put("citedShare", !qs.isEmpty() ? citedCount / (double) qs.size() : null);
		result.put("ratedShare", !qs.isEmpty() ? rated / (double) qs.size() : null);
		result.put("helpfulShare", rated > 0 ? helpfulCount / (double) rated : null);
		result.put("themes", themeList);
		result.put("sections", secList);
		result.put("labels", labelList);
		return result;
	}

	private static void addFactHelper(java.util.function.Consumer<Map<String, Object>> addFact, Map<String, Object> base, String label, Object value, String view, Map<String, Object> filters, String focus)
	{
		Map<String, Object> mergedFilters = new HashMap<>(base);
		mergedFilters.putAll(filters);

		Map<String, Object> f = new HashMap<>();
		f.put("label", label);
		f.put("value", value);
		f.put("view", view);
		f.put("filters", mergedFilters);
		f.put("focus", focus);
		addFact.accept(f);
	}

	private static String pct(Double x)
	{
		return (x == null) ? "—" : Math.round(x * 100) + " %";
	}
}
