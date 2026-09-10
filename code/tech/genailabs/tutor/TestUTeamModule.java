package tech.genailabs.tutor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;

public class TestUTeamModule extends TestUBaseModule
{
	public void loadScope(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		UserProfile profile = inReq.getUserProfile();
		// "all" is granted on any manage/operate permission of either domain -- fine under the four-role model
		// (orgadmin/training/manager/users), where personas_* and analytics_* always travel together per role;
		// a future split-permission role (e.g. analytics-only operate without personas) would need a per-domain check.
		boolean all = profile != null && (profile.hasPermission("personas_manage")
				|| profile.hasPermission("personas_operate")
				|| profile.hasPermission("analytics_manage")
				|| profile.hasPermission("analytics_operate"));

		Map<String, Data> teams = new HashMap<>();
		HitTracker teamHits = archive.query("team").all().search();
		if (teamHits != null)
		{
			for (Object hit : teamHits)
			{
				Data t = (Data) hit;
				teams.put(t.getId(), t);
			}
		}
		inReq.putPageValue("allteams", teams);

		if (all)
		{
			inReq.putPageValue("scopeteams", null);
			return;
		}

		User user = inReq.getUser();
		String me = (user != null) ? user.getId() : "";
		Set<String> scope = new HashSet<>();
		for (Data t : teams.values())
		{
			if (me.equals(t.get("manager")))
			{
				scope.add(t.getId());
			}
		}

		// ponytail: grew-loop is bounded by teams.size() -- scope only ever grows and never
		// shrinks, and a team can only be added once, so it terminates in at most
		// teams.size() passes even if `parent` contains a cycle (a cycle just stops
		// growing scope once every team in the cycle is already included).
		boolean grew = true;
		while (grew)
		{
			grew = false;
			for (Data t : teams.values())
			{
				String parent = t.get("parent");
				if (parent != null && scope.contains(parent) && !scope.contains(t.getId()))
				{
					scope.add(t.getId());
					grew = true;
				}
			}
		}
		inReq.putPageValue("scopeteams", scope);
	}

	public void loadTeams(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Map<String, Data> teams = (Map<String, Data>) inReq.getPageValue("allteams");
		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");
		if (teams == null)
		{
			loadScope(inReq);
			teams = (Map<String, Data>) inReq.getPageValue("allteams");
			scope = (Set<String>) inReq.getPageValue("scopeteams");
		}

		Map<String, Integer> members = new HashMap<>();
		HitTracker hits = archive.query("user").all().search();
		if (hits != null)
		{
			hits.enableBulkOperations();
			for (Object hit : hits)
			{
				Data u = (Data) hit;
				String t = u.get("team");
				if (t != null && !t.isEmpty())
				{
					Integer count = members.get(t);
					members.put(t, count == null ? 1 : count + 1);
				}
			}
		}

		JSONArray out = new JSONArray();
		if (teams != null)
		{
			for (Data t : teams.values())
			{
				if (scope == null || scope.contains(t.getId()))
				{
					JSONObject teamObj = new JSONObject();
					teamObj.put("id", t.getId());
					teamObj.put("name", t.getName());
					teamObj.put("parent", t.get("parent"));
					teamObj.put("manager", t.get("manager"));
					teamObj.put("location", t.get("location"));
					teamObj.put("costcenter", t.get("costcenter"));
					Integer memberCount = members.get(t.getId());
					teamObj.put("members", memberCount != null ? memberCount : 0);
					out.add(teamObj);
				}
			}
		}

		JSONObject result = new JSONObject();
		result.put("teams", out);
		reply(inReq, result);
	}

	public void saveTeam(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = inReq.getRequestParameter("id");
		id = (id != null) ? id.trim().toLowerCase() : "";

		if (!id.matches("^[a-z0-9]+$"))
		{
			fail(inReq, 400, "id must be [a-z0-9]+");
			return;
		}

		String parent = inReq.getRequestParameter("parent");
		parent = (parent != null) ? parent.trim() : "";
		if (!parent.isEmpty() && parent.equals(id))
		{
			fail(inReq, 400, "a team cannot be its own parent");
			return;
		}

		if (!parent.isEmpty() && archive.getCachedData("team", parent) == null)
		{
			fail(inReq, 400, "unknown parent");
			return;
		}

		String manager = inReq.getRequestParameter("manager");
		manager = (manager != null) ? manager.trim() : "";
		if (!manager.isEmpty() && archive.getSearcher("user").searchById(manager) == null)
		{
			fail(inReq, 400, "unknown manager");
			return;
		}

		Searcher teams = archive.getSearcher("team");
		Data t = (Data) teams.searchById(id);
		String[] fields = new String[] {"name", "parent", "manager", "location", "costcenter"};
		JSONObject before = snapshot(t, fields);

		if (t == null)
		{
			t = teams.createNewData();
			t.setId(id);
		}

		for (String f : fields)
		{
			String val = inReq.getRequestParameter(f);
			t.setValue(f, (val != null && !val.isEmpty()) ? val : null);
		}
		t.setValue("enabled", "true");
		teams.saveData(t, inReq.getUser());

		audit(inReq, archive, "team.save", "team", id, before, snapshot(t, fields));

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		replyObj.put("id", id);
		reply(inReq, replyObj);
	}
}
