package tech.genailabs.tutor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.users.User;

public class TestUUsageModule extends TestUBaseModule
{
	private static final Set<String> USAGE_TYPES = new HashSet<>(Arrays.asList("open", "resume", "pause", "iris_rate"));
	private static final String[] EXTRA_FIELDS = new String[] {"channel", "componentsection", "entityquestion", "rating", "platform", "appversion"};

	public void trackUsage(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String userid = (user != null) ? user.getId() : null;
		if (userid == null || userid.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		String eventsParam = inReq.getRequestParameter("events");
		Object parsed = null;
		try
		{
			parsed = JSONValue.parse(eventsParam != null ? eventsParam : "[]");
		}
		catch (Exception e)
		{
			fail(inReq, 400, "bad events");
			return;
		}

		if (!(parsed instanceof List))
		{
			fail(inReq, 400, "bad events");
			return;
		}

		List<?> events = (List<?>) parsed;
		if (events.size() > 200)
		{
			fail(inReq, 400, "too many events");
			return;
		}

		Searcher searcher = archive.getSearcher("usageevent");
		List<Data> tosave = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX");

		for (Object item : events)
		{
			if (!(item instanceof Map))
			{
				continue; // unknown shapes are skipped, never fail the whole batch
			}
			Map<?, ?> e = (Map<?, ?>) item;
			Object typeObj = e.get("type");
			String type = (typeObj != null) ? typeObj.toString() : null;
			if (type == null || !USAGE_TYPES.contains(type))
			{
				continue;
			}

			Object atObj = e.get("at");
			String atStr = (atObj != null) ? atObj.toString() : "";
			if (atStr.isEmpty())
			{
				continue;
			}
			Date at = null;
			try
			{
				String cleanAt = atStr.replaceAll("\\.\\d+", "");
				at = isoFormat.parse(cleanAt);
			}
			catch (Exception ex)
			{
				continue;
			}

			Object sessionObj = e.get("sessionid");
			String sessionid = (sessionObj != null) ? sessionObj.toString() : "";

			String id = md5(userid + "|" + sessionid + "|" + type + "|" + atStr);
			if (!seen.add(id))
			{
				continue; // two byte-identical events in one batch share the md5 id: one row, one count
			}
			if (searcher.searchById(id) != null)
			{
				continue; // idempotent: a retried batch never double-counts
			}

			Data d = searcher.createNewData();
			d.setId(id);
			d.setValue("user", userid);
			d.setValue("datecreated", at);
			d.setValue("type", type);
			d.setValue("sessionid", sessionid);
			d.setValue("seconds", Integer.valueOf(parseSeconds(e.get("seconds"))));

			for (String k : EXTRA_FIELDS)
			{
				Object val = e.get(k);
				if (val != null)
				{
					d.setValue(k, val.toString());
				}
			}
			tosave.add(d);
		}

		if (!tosave.isEmpty())
		{
			searcher.saveAllData(tosave, null);
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("saved", Integer.valueOf(tosave.size()));
		reply(inReq, resp);
	}

	private int parseSeconds(Object s)
	{
		if (s instanceof Number)
		{
			return ((Number) s).intValue();
		}
		if (s == null)
		{
			return 0;
		}
		try
		{
			return (int) Double.parseDouble(s.toString());
		}
		catch (Exception ex)
		{
			return 0;
		}
	}
}
