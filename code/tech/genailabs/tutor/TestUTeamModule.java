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
		inReq.putPageValue("scopeteams", managedTeams(teams, (user != null) ? user.getId() : ""));
	}

	/** Team ids inMe manages (team.manager) and every team below them (team.parent); empty = none. Pure. */
	public static Set<String> managedTeams(Map<String, Data> teams, String me)
	{
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
		return scope;
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
	/**
	 * deleteteam.json: id, cascade=true|false. The team goes; its members lose
	 * their team (their records stay). Sub-teams either lift to the deleted
	 * team's parent (default) or, with cascade=true, go too, members included.
	 * Learning records and audit are kept: the console offers a CSV first.
	 */
	public void deleteTeam(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = inReq.getRequestParameter("id");
		id = (id != null) ? id.trim() : "";
		boolean cascade = "true".equals(inReq.getRequestParameter("cascade"));

		Searcher teams = archive.getSearcher("team");
		Data target = (Data) teams.searchById(id);
		if (target == null)
		{
			fail(inReq, 404, "no team");
			return;
		}

		Map<String, Data> all = new HashMap<>();
		HitTracker teamHits = teams.query().all().search();
		if (teamHits != null)
		{
			for (Object hit : teamHits)
			{
				Data t = (Data) hit;
				all.put(t.getId(), t);
			}
		}

		// Everything that goes: the team, and with cascade every team under it.
		// Bounded like loadScope's grow loop: a cycle stops adding once covered.
		Set<String> gone = new HashSet<>();
		gone.add(id);
		if (cascade)
		{
			boolean grew = true;
			while (grew)
			{
				grew = false;
				for (Data t : all.values())
				{
					String parent = t.get("parent");
					if (parent != null && gone.contains(parent) && !gone.contains(t.getId()))
					{
						gone.add(t.getId());
						grew = true;
					}
				}
			}
		}

		// Sub-teams that stay lift to the nearest surviving ancestor.
		String lift = target.get("parent");
		if (lift != null && (lift.isEmpty() || gone.contains(lift) || !all.containsKey(lift)))
		{
			lift = null;
		}
		User currentUser = inReq.getUser();
		String[] fields = new String[] {"name", "parent", "manager", "location", "costcenter"};
		JSONArray lifted = new JSONArray();
		for (Data t : all.values())
		{
			if (gone.contains(t.getId()))
			{
				continue;
			}
			String parent = t.get("parent");
			if (parent != null && gone.contains(parent))
			{
				t.setValue("parent", lift);
				teams.saveData(t, currentUser);
				lifted.add(t.getId());
			}
		}

		// Members of every deleted team lose it; nothing else on the user changes.
		Searcher users = archive.getSearcher("user");
		JSONArray unassigned = new JSONArray();
		HitTracker hits = users.query().all().search();
		if (hits != null)
		{
			hits.enableBulkOperations();
			for (Object hit : hits)
			{
				Data u = (Data) hit;
				String team = u.get("team");
				if (team != null && gone.contains(team))
				{
					u.setValue("team", null);
					users.saveData(u, currentUser);
					unassigned.add(u.getId());
				}
			}
		}

		for (String teamId : gone)
		{
			Data t = all.get(teamId);
			if (t == null)
			{
				continue;
			}
			JSONObject before = snapshot(t, fields);
			teams.delete(t, currentUser);
			JSONObject after = new JSONObject();
			after.put("cascade", Boolean.valueOf(cascade));
			after.put("lifted", teamId.equals(id) ? lifted : new JSONArray());
			after.put("unassigned", unassigned);
			audit(inReq, archive, "team.delete", "team", teamId, before, after);
		}

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		replyObj.put("deleted", gone.size());
		replyObj.put("unassigned", unassigned.size());
		reply(inReq, replyObj);
	}
}
