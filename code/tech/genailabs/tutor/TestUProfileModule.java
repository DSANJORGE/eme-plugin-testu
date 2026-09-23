package tech.genailabs.tutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;

/**
 * services/testu/personas/{profiles,saveprofile,deleteprofile,setprofiles}.json -- job profiles (spec 2026-09-16).
 * A profile = a `jobrole` list entry + its `topicrequirement` rows (id <jobrole>_<entitytopic>). Permissions are in the .xconf
 * (personas_view reads, personas_manage writes). Every write is audited (jobprofile.save|delete|assign).
 */
public class TestUProfileModule extends TestUBaseModule
{
	static final Set<String> LEVELS = Set.of("beginner", "competent", "expert");
	static final Set<String> AFTERFINISH = Set.of("keep", "remove");
	static final Object LOCK = new Object(); // ponytail: one JVM; saves are rare and small

	public void profiles(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		Map<String, Integer> members = memberCounts(archive);
		JSONArray profiles = new JSONArray();
		for (Data p : profileList(archive))
		{
			profiles.add(profileJson(archive, content, p, members));
		}
		JSONArray topics = new JSONArray();
		for (LearningEngine.Topic t : content.topics.values())
		{
			JSONObject o = new JSONObject();
			o.put("id", t.id);
			o.put("title", t.title);
			o.put("questions", Integer.valueOf(t.questions.size()));
			topics.add(o);
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("profiles", profiles);
		resp.put("topics", topics);
		reply(inReq, resp);
	}

	public void saveProfile(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = trim(inReq.getRequestParameter("id"));
		String name = trim(inReq.getRequestParameter("name"));
		if (name.isEmpty())
		{
			fail(inReq, 400, "missing_name");
			return;
		}
		if (name.length() > 120)
		{
			fail(inReq, 400, "bad_name");
			return;
		}
		Object parsed = JSONValue.parse(inReq.getRequestParameter("rows") == null ? "[]" : inReq.getRequestParameter("rows"));
		if (!(parsed instanceof List))
		{
			fail(inReq, 400, "bad_rows");
			return;
		}
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		List<LearningEngine.ProfileRow> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Set<String> evalomitted = new HashSet<>(); // topics whose posted row carried no evaluationrequired key
		for (Object o : (List) parsed)
		{
			if (!(o instanceof Map))
			{
				fail(inReq, 400, "bad_rows");
				return;
			}
			Map m = (Map) o;
			LearningEngine.ProfileRow r = new LearningEngine.ProfileRow();
			r.topicid = trim(str(m.get("topic")));
			if (!content.topics.containsKey(r.topicid))
			{
				fail(inReq, 400, "unknown_topic");
				return;
			}
			if (!seen.add(r.topicid))
			{
				fail(inReq, 400, "duplicate_topic");
				return;
			}
			String lvl = trim(str(m.get("requiredlevel")));
			if (!lvl.isEmpty() && !LEVELS.contains(lvl))
			{
				fail(inReq, 400, "bad_level");
				return;
			}
			r.requiredlevel = lvl.isEmpty() ? null : lvl;
			r.mandatory = !"false".equals(String.valueOf(m.get("mandatory")));
			r.requiresprevious = "true".equals(String.valueOf(m.get("requiresprevious")));
			r.evaluationrequired = "true".equals(String.valueOf(m.get("evaluationrequired")));
			if (!m.containsKey("evaluationrequired"))
			{
				evalomitted.add(r.topicid); // the console stopped posting the field; carry the stored value forward below
			}
			r.validitymonths = LearningEngine.intOrNull(m.get("validitymonths"));
			if (badNumber(m.get("validitymonths"), r.validitymonths, 0, 120))
			{
				fail(inReq, 400, "bad_validitymonths");
				return;
			}
			r.passpercent = LearningEngine.intOrNull(m.get("passpercent"));
			if (badNumber(m.get("passpercent"), r.passpercent, 1, 100))
			{
				fail(inReq, 400, "bad_passpercent");
				return;
			}
			r.renewalwindowdays = LearningEngine.intOrNull(m.get("renewalwindowdays"));
			if (badNumber(m.get("renewalwindowdays"), r.renewalwindowdays, 0, 365))
			{
				fail(inReq, 400, "bad_renewalwindowdays");
				return;
			}
			String af = trim(str(m.get("afterfinish")));
			if (af.isEmpty())
			{
				af = "keep";
			}
			if (!AFTERFINISH.contains(af))
			{
				fail(inReq, 400, "bad_afterfinish");
				return;
			}
			r.afterfinish = af;
			r.position = rows.size() + 1;
			if (r.position == 1 && r.requiresprevious)
			{
				fail(inReq, 400, "first_row_gate");
				return;
			}
			rows.add(r);
		}
		Searcher list = archive.getSearcher("jobrole");
		JSONObject before = null;
		JSONObject after = null;
		synchronized (LOCK)
		{
			Data p = id.isEmpty() ? null : (Data) list.searchById(id);
			if (!id.isEmpty() && p == null)
			{
				fail(inReq, 404, "unknown_profile");
				return;
			}
			for (Data other : profileList(archive))
			{
				if (!other.getId().equals(id) && name.equalsIgnoreCase(other.getName()))
				{
					fail(inReq, 400, "duplicate_name");
					return;
				}
			}
			if (p == null)
			{
				id = uniqueSlug(list, name);
				p = list.createNewData();
				p.setId(id);
			}
			else
			{
				before = profileJson(archive, content, p, memberCounts(archive));
			}
			p.setName(name);
			p.setValue("name", name);
			list.saveData(p, inReq.getUser());
			Searcher req = archive.getSearcher("topicrequirement");
			Set<String> keep = new HashSet<>();
			for (LearningEngine.ProfileRow r : rows)
			{
				String rid = id + "_" + r.topicid;
				keep.add(rid);
				Data existing = (Data) req.searchById(rid);
				// A row posted without the key keeps whatever is stored: the console no longer sends evaluationrequired, and a save
				// from it must not silently drop a legacy requirement. Only a validity can newly set it.
				boolean evalrequired = evalomitted.contains(r.topicid) ? existing != null && "true".equals(String.valueOf(existing.get("evaluationrequired"))) : r.evaluationrequired;
				Data d = existing;
				if (d == null)
				{
					d = req.createNewData();
					d.setId(rid);
				}
				d.setValue("jobrole", id);
				d.setValue("entitytopic", r.topicid);
				d.setValue("requiredlevel", r.requiredlevel);
				d.setValue("position", String.valueOf(r.position));
				d.setValue("mandatory", r.mandatory ? "true" : "false");
				d.setValue("requiresprevious", r.requiresprevious ? "true" : "false");
				d.setValue("validitymonths", r.validitymonths);
				d.setValue("passpercent", r.passpercent);
				d.setValue("renewalwindowdays", r.renewalwindowdays);
				// compat for one release: evaluationrequired also implied by validitymonths (certification topics require evaluation)
				d.setValue("evaluationrequired", (r.validitymonths != null || evalrequired) ? "true" : "false");
				d.setValue("afterfinish", r.afterfinish);
				req.saveData(d, inReq.getUser());
			}
			for (Object o : req.query().exact("jobrole", id).search())
			{
				Data d = (Data) o;
				if (!keep.contains(d.getId()))
				{
					req.delete(d, inReq.getUser());
				}
			}
			// Built from the row we just saved, still under the lock. Re-reading it after the block could come back null
			// when a concurrent delete wins the race, and the audit "after" payload must not NPE the request.
			after = profileJson(archive, content, p, memberCounts(archive));
		}
		audit(inReq, archive, "jobprofile.save", "jobrole", id, before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("profile", after);
		reply(inReq, resp);
	}

	public void deleteProfile(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = trim(inReq.getRequestParameter("id"));
		Searcher list = archive.getSearcher("jobrole");
		Data p = id.isEmpty() ? null : (Data) list.searchById(id);
		if (p == null)
		{
			fail(inReq, 404, "unknown_profile");
			return;
		}
		LearningEngine.Content content = new LearningEngine(archive).loadContent();
		JSONObject before = profileJson(archive, content, p, new HashMap<>());
		synchronized (LOCK)
		{
			Integer members = memberCounts(archive).get(id);
			if (members != null && members > 0)
			{
				JSONObject err = new JSONObject();
				err.put("ok", Boolean.FALSE);
				err.put("error", "profile_in_use");
				err.put("members", members);
				if (inReq.getResponse() != null)
				{
					inReq.getResponse().setStatus(409);
				}
				reply(inReq, err);
				inReq.setCancelActions(true);
				return;
			}
			Searcher req = archive.getSearcher("topicrequirement");
			for (Object o : req.query().exact("jobrole", id).search())
			{
				req.delete((Data) o, inReq.getUser());
			}
			list.delete(p, inReq.getUser());
		}
		audit(inReq, archive, "jobprofile.delete", "jobrole", id, before, null);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		reply(inReq, resp);
	}

	public void setProfiles(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String userid = trim(inReq.getRequestParameter("user"));
		String primary = trim(inReq.getRequestParameter("primary"));
		Object parsed = JSONValue.parse(inReq.getRequestParameter("extras") == null ? "[]" : inReq.getRequestParameter("extras"));
		if (!(parsed instanceof List))
		{
			fail(inReq, 400, "bad_extras");
			return;
		}
		List<String> extras = new ArrayList<>();
		for (Object o : (List) parsed)
		{
			String e = trim(str(o));
			if (!e.isEmpty() && !extras.contains(e))
			{
				extras.add(e);
			}
		}
		Searcher users = archive.getSearcher("user");
		Data u = (Data) users.searchById(userid);
		if (u == null)
		{
			fail(inReq, 404, "unknown_user");
			return;
		}
		if (extras.contains(primary))
		{
			fail(inReq, 400, "primary_in_extras");
			return;
		}
		if (primary.isEmpty() && !extras.isEmpty())
		{
			fail(inReq, 400, "missing_primary");
			return;
		}
		List<String> all = new ArrayList<>();
		if (!primary.isEmpty())
		{
			all.add(primary);
		}
		all.addAll(extras);
		JSONObject before = new JSONObject();
		synchronized (LOCK)
		{
			for (String id : all)
			{
				if (archive.getCachedData("jobrole", id) == null)
				{
					fail(inReq, 400, "unknown_profile");
					return;
				}
			}
			before.put("primaryjobrole", LearningEngine.primaryJobroleOf(u));
			before.put("jobroles", new ArrayList<>(LearningEngine.jobrolesOf(u)));
			u.setValue("primaryjobrole", primary.isEmpty() ? null : primary);
			u.setValue("jobrole", all.isEmpty() ? null : all);
			users.saveData(u, inReq.getUser());
		}
		JSONObject after = new JSONObject();
		after.put("primaryjobrole", primary.isEmpty() ? null : primary);
		after.put("jobroles", all);
		audit(inReq, archive, "jobprofile.assign", "user", userid, before, after);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("primaryjobrole", primary.isEmpty() ? null : primary);
		resp.put("jobroles", all);
		reply(inReq, resp);
	}

	// ---- helpers

	static String trim(String s)
	{
		return s == null ? "" : s.trim();
	}

	static String str(Object o)
	{
		return o == null ? "" : String.valueOf(o);
	}

	/**
	 * A posted numeric profile field is bad when it is non-blank but did not parse (intOrNull returns null for "abc" exactly as it
	 * does for a blank, which used to save the field blank instead of rejecting it), or when it parsed outside [inMin, inMax].
	 */
	static boolean badNumber(Object inPosted, Integer inParsed, int inMin, int inMax)
	{
		if (inParsed == null)
		{
			return inPosted != null && !String.valueOf(inPosted).isBlank();
		}
		return inParsed < inMin || inParsed > inMax;
	}

	static List<Data> profileList(MediaArchive archive)
	{
		List<Data> out = new ArrayList<>();
		HitTracker hits = archive.query("jobrole").all().sort("name").search();
		if (hits != null)
		{
			for (Object o : hits)
			{
				out.add((Data) o);
			}
		}
		return out;
	}

	/**
	 * profile id -> number of users referencing it, once per user: jobrole contains it OR primaryjobrole equals it. A dangling
	 * primaryjobrole (jobrole cleared without the primary) must still block the delete and show in the headcount.
	 */
	static Map<String, Integer> memberCounts(MediaArchive archive)
	{
		Map<String, Integer> out = new HashMap<>();
		HitTracker hits = archive.query("user").all().search();
		if (hits != null)
		{
			hits.enableBulkOperations();
			for (Object o : hits)
			{
				Set<String> mine = new HashSet<>(LearningEngine.jobrolesOf((Data) o));
				String primary = LearningEngine.primaryJobroleOf((Data) o);
				if (primary != null && !primary.isEmpty())
				{
					mine.add(primary);
				}
				for (String id : mine)
				{
					out.merge(id, 1, Integer::sum);
				}
			}
		}
		return out;
	}

	static JSONObject profileJson(MediaArchive archive, LearningEngine.Content content, Data p, Map<String, Integer> members)
	{
		List<LearningEngine.ProfileRow> rows = new ArrayList<>();
		for (Object o : archive.query("topicrequirement").exact("jobrole", p.getId()).search())
		{
			rows.add(LearningEngine.rowOf((Data) o));
		}
		rows.sort(java.util.Comparator.comparingInt((LearningEngine.ProfileRow r) -> r.position).thenComparing(r -> r.topicid));
		JSONArray arr = new JSONArray();
		int pos = 0;
		for (LearningEngine.ProfileRow r : rows)
		{
			LearningEngine.Topic t = content.topics.get(r.topicid);
			JSONObject o = new JSONObject();
			o.put("topic", r.topicid);
			o.put("topictitle", t == null ? r.topicid : t.title);
			o.put("position", Integer.valueOf(++pos));
			o.put("requiredlevel", r.requiredlevel);
			o.put("mandatory", Boolean.valueOf(r.mandatory));
			o.put("requiresprevious", Boolean.valueOf(r.requiresprevious));
			o.put("validitymonths", r.validitymonths);
			o.put("passpercent", r.passpercent);
			o.put("renewalwindowdays", r.renewalwindowdays);
			o.put("evaluationrequired", Boolean.valueOf(r.validitymonths != null || r.evaluationrequired));
			o.put("afterfinish", r.afterfinish);
			o.put("questions", Integer.valueOf(t == null ? 0 : t.questions.size()));
			arr.add(o);
		}
		JSONObject o = new JSONObject();
		o.put("id", p.getId());
		o.put("name", p.getName() == null ? p.get("name") : p.getName());
		o.put("members", members.getOrDefault(p.getId(), 0));
		o.put("rows", arr);
		return o;
	}

	/** lowercase [a-z0-9-] of the name; "-2", "-3" ... when taken. */
	static String uniqueSlug(Searcher list, String name)
	{
		String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (base.isEmpty())
		{
			base = "profile";
		}
		String id = base;
		for (int n = 2; list.searchById(id) != null; n++)
		{
			id = base + "-" + n;
		}
		return id;
	}
}
