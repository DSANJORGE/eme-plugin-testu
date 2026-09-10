package tech.genailabs.tutor;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;
import org.openedit.util.DateStorageUtil;

public class TestUUserModule extends TestUBaseModule
{
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final Set<String> VALID_ROLES = new HashSet<>(Arrays.asList("users", "manager", "training", "orgadmin"));

	public void loadMe(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);

		User u = inReq.getUser();
		UserProfile p = inReq.getUserProfile();

		String[] permKeys =
			new String[] {"personas_manage", "personas_operate", "personas_view", "analytics_manage", "analytics_operate", "analytics_view", "training_manage", "training_operate", "training_view"};
		JSONArray perms = new JSONArray();
		if (p != null)
		{
			for (String key : permKeys)
			{
				if (p.hasPermission(key))
				{
					perms.add(key);
				}
			}
		}

		JSONArray modules = new JSONArray();
		HitTracker hits = archive.query("suitemodule").all().sort("ordering").search();
		if (hits != null)
		{
			for (Object hit : hits)
			{
				Data m = (Data) hit;
				JSONObject moduleObj = new JSONObject();
				moduleObj.put("id", m.getId());
				moduleObj.put("name", m.getName());
				Collection<String> surfaces = m.getValues("surfaces");
				JSONArray surfacesList = new JSONArray();
				if (surfaces != null)
				{
					surfacesList.addAll(surfaces);
				}
				moduleObj.put("surfaces", surfacesList);
				moduleObj.put("enabled", Boolean.valueOf("true".equals(String.valueOf(m.get("enabled")))));
				modules.add(moduleObj);
			}
		}

		// The site's tutor, same lookup as ask.groovy: the console shows the
		// organisation's name and the tutor's face, and Iris speaks its language.
		String tutorSetting = archive.getCatalogSettingValue("tutorpersona");
		if (tutorSetting == null || tutorSetting.isEmpty())
		{
			tutorSetting = "iris";
		}
		Data persona = archive.getData("tutorpersona", tutorSetting);
		JSONObject personaObj = null;
		if (persona != null)
		{
			personaObj = new JSONObject();
			personaObj.put("name", persona.getName());
			personaObj.put("avatar", persona.get("avatar"));
			personaObj.put("organization", persona.get("organization"));
			personaObj.put("language", persona.get("tutorlanguage"));
		}

		JSONObject userObj = new JSONObject();
		if (u != null)
		{
			userObj.put("id", u.getId());
			userObj.put("email", u.get("email"));
			userObj.put("firstName", u.get("firstName"));
			userObj.put("lastName", u.get("lastName"));
		}

		String role = "manager";
		if (p != null && p.get("settingsgroup") != null && !p.get("settingsgroup").isEmpty())
		{
			role = p.get("settingsgroup");
		}

		JSONObject json = new JSONObject();
		json.put("user", userObj);
		json.put("role", role);
		json.put("permissions", perms);
		json.put("modules", modules);
		json.put("persona", personaObj);

		reply(inReq, json);
	}

	public void loadUsers(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");

		Map<String, String> roles = new HashMap<>();
		HitTracker profileHits = archive.query("userprofile").all().search();
		if (profileHits != null)
		{
			for (Object hit : profileHits)
			{
				Data p = (Data) hit;
				roles.put(p.getId(), p.get("settingsgroup"));
			}
		}

		Map<String, Date> last = new HashMap<>();
		HitTracker masteryHits = archive.query("tutormastery").all().search();
		if (masteryHits != null)
		{
			masteryHits.enableBulkOperations();
			for (Object hit : masteryHits)
			{
				Data r = (Data) hit;
				Date d = DateStorageUtil.getStorageUtil().parseFromObject(r.getValue("lastactivity"));
				String user = r.get("user");
				if (d != null && user != null)
				{
					Date existing = last.get(user);
					if (existing == null || d.after(existing))
					{
						last.put(user, d);
					}
				}
			}
		}

		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

		JSONArray out = new JSONArray();
		HitTracker userHits = archive.query("user").all().search();
		if (userHits != null)
		{
			userHits.enableBulkOperations();
			for (Object hit : userHits)
			{
				Data u = (Data) hit;
				String team = u.get("team");
				if (scope != null && !scope.contains(team))
				{
					continue;
				}
				JSONObject userObj = new JSONObject();
				userObj.put("id", u.getId());
				userObj.put("email", u.get("email"));
				userObj.put("firstName", u.get("firstName"));
				userObj.put("lastName", u.get("lastName"));
				userObj.put("team", team);
				String role = roles.get(u.getId());
				userObj.put("role", (role != null && !role.isEmpty()) ? role : "users");
				userObj.put("enabled", Boolean.valueOf(!"false".equals(String.valueOf(u.get("enabled")))));
				Date lastDate = last.get(u.getId());
				userObj.put("lastactivity", lastDate != null ? isoFormat.format(lastDate) : null);
				out.add(userObj);
			}
		}

		JSONObject result = new JSONObject();
		result.put("users", out);
		reply(inReq, result);
	}

	public void createUser(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String email = inReq.getRequestParameter("email");
		email = (email != null) ? email.trim().toLowerCase() : "";
		String team = inReq.getRequestParameter("team");
		team = (team != null) ? team.trim() : "";
		String role = inReq.getRequestParameter("role");
		if (role == null || role.trim().isEmpty())
		{
			role = "users";
		}
		else
		{
			role = role.trim();
		}

		if (!EMAIL_PATTERN.matcher(email).matches())
		{
			fail(inReq, 400, "invalid email");
			return;
		}

		if (!VALID_ROLES.contains(role))
		{
			fail(inReq, 400, "invalid role");
			return;
		}

		UserProfile userProfile = inReq.getUserProfile();
		if (("training".equals(role) || "orgadmin".equals(role)) && (userProfile == null || !userProfile.hasPermission("personas_manage")))
		{
			fail(inReq, 403, "role requires personas_manage");
			return;
		}

		if (!team.isEmpty() && archive.getCachedData("team", team) == null)
		{
			fail(inReq, 400, "unknown team");
			return;
		}

		Searcher users = archive.getSearcher("user");
		if (users.searchById(email) != null || (archive.getUserManager() != null && archive.getUserManager().getUserByEmail(email) != null))
		{
			fail(inReq, 400, "user exists");
			return;
		}

		Data u = users.createNewData();
		u.setId(email);
		u.setValue("email", email);
		String firstName = inReq.getRequestParameter("firstName");
		u.setValue("firstName", firstName != null ? firstName : "");
		String lastName = inReq.getRequestParameter("lastName");
		u.setValue("lastName", lastName != null ? lastName : "");
		u.setValue("enabled", "true");
		// Ruling R8: random secret, never returned or logged; eMe sessions need md5(password), OTP stays the only login path
		u.setValue("password", UUID.randomUUID().toString());
		if (!team.isEmpty())
		{
			u.setValue("team", team);
		}
		users.saveData(u, inReq.getUser());

		Searcher profiles = archive.getSearcher("userprofile");
		Data p = (Data) profiles.searchById(email);
		if (p == null)
		{
			p = profiles.createNewData();
		}
		p.setId(email);
		p.setValue("userid", email);
		p.setValue("settingsgroup", role);
		profiles.saveData(p, inReq.getUser());

		JSONObject after = new JSONObject();
		after.put("email", email);
		after.put("team", team);
		after.put("role", role);
		audit(inReq, archive, "user.create", "user", email, null, after);

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		replyObj.put("id", email);
		reply(inReq, replyObj);
	}

	public void disableUser(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String userid = inReq.getRequestParameter("userid");
		userid = (userid != null) ? userid.trim().toLowerCase() : "";

		User currentUser = inReq.getUser();
		if (currentUser != null && userid.equals(currentUser.getId()))
		{
			fail(inReq, 400, "cannot disable yourself");
			return;
		}

		Searcher users = archive.getSearcher("user");
		Data u = (Data) users.searchById(userid);
		if (u == null)
		{
			fail(inReq, 404, "no user");
			return;
		}

		Data userProfileData = (Data) archive.getSearcher("userprofile").searchById(userid);
		String targetrole = (userProfileData != null) ? userProfileData.get("settingsgroup") : null;

		UserProfile userProfile = inReq.getUserProfile();
		if (("orgadmin".equals(targetrole) || "training".equals(targetrole)) && (userProfile == null || !userProfile.hasPermission("personas_manage")))
		{
			fail(inReq, 403, "role requires personas_manage");
			return;
		}

		u.setValue("enabled", "false");
		users.saveData(u, currentUser);

		JSONObject before = new JSONObject();
		before.put("enabled", Boolean.TRUE);
		JSONObject after = new JSONObject();
		after.put("enabled", Boolean.FALSE);
		audit(inReq, archive, "user.disable", "user", userid, before, after);

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		reply(inReq, replyObj);
	}

	public void setRole(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String userid = inReq.getRequestParameter("userid");
		userid = (userid != null) ? userid.trim().toLowerCase() : "";
		String role = inReq.getRequestParameter("role");
		role = (role != null) ? role.trim() : "";

		if (!VALID_ROLES.contains(role))
		{
			fail(inReq, 400, "invalid role");
			return;
		}

		if (archive.getSearcher("user").searchById(userid) == null)
		{
			fail(inReq, 404, "no user");
			return;
		}

		Searcher profiles = archive.getSearcher("userprofile");
		Data p = (Data) profiles.searchById(userid);
		if (p == null)
		{
			p = profiles.createNewData();
		}
		String before = p.get("settingsgroup");
		p.setId(userid);
		p.setValue("userid", userid);
		p.setValue("settingsgroup", role);
		profiles.saveData(p, inReq.getUser());

		// eMe keeps loaded profiles in CacheManager("userprofile"); without this the old role
		// survives until Tomcat restarts (UserProfileManager.setRoleOnUser does the same).
		if (archive.getUserProfileManager() != null)
		{
			archive.getUserProfileManager().clearProfile(archive.getCatalogId(), userid);
		}

		JSONObject beforeObj = new JSONObject();
		beforeObj.put("role", before);
		JSONObject afterObj = new JSONObject();
		afterObj.put("role", role);
		audit(inReq, archive, "user.role", "user", userid, beforeObj, afterObj);

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		reply(inReq, replyObj);
	}

	public void setTeam(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String userid = inReq.getRequestParameter("userid");
		userid = (userid != null) ? userid.trim().toLowerCase() : "";
		String team = inReq.getRequestParameter("team");
		team = (team != null) ? team.trim() : "";

		Searcher users = archive.getSearcher("user");
		Data u = (Data) users.searchById(userid);
		if (u == null)
		{
			fail(inReq, 404, "no user");
			return;
		}

		if (!team.isEmpty() && archive.getCachedData("team", team) == null)
		{
			fail(inReq, 400, "unknown team");
			return;
		}

		String before = u.get("team");
		u.setValue("team", !team.isEmpty() ? team : null);
		users.saveData(u, inReq.getUser());

		JSONObject beforeObj = new JSONObject();
		beforeObj.put("team", before);
		JSONObject afterObj = new JSONObject();
		afterObj.put("team", team);
		audit(inReq, archive, "user.team", "user", userid, beforeObj, afterObj);

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		reply(inReq, replyObj);
	}

	public void importDone(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Object countObj = inReq.getPageValue("importedcount");
		int n = 0;
		if (countObj instanceof Number)
		{
			n = ((Number) countObj).intValue();
		}
		else if (countObj != null)
		{
			try
			{
				n = Integer.parseInt(String.valueOf(countObj));
			}
			catch (Exception ignored)
			{
			}
		}

		JSONObject after = new JSONObject();
		after.put("imported", Integer.valueOf(n));
		audit(inReq, archive, "user.import", "user", "csv", null, after);

		JSONObject replyObj = new JSONObject();
		replyObj.put("ok", Boolean.TRUE);
		replyObj.put("imported", Integer.valueOf(n));
		reply(inReq, replyObj);
	}
}
