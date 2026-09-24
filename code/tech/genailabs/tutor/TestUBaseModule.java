package tech.genailabs.tutor;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.entermediadb.websocket.usernotify.UserNotifyManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.users.User;

public class TestUBaseModule extends BaseMediaModule
{
	// Console tab permissions (catalog/data/lists/permissionsapp/testuadmin.xml). They replaced the personas_/analytics_/
	// training_view verbs: the data behind a group of tabs is readable with any of that group's tab permissions.
	static final String[] ANALYTICS_TABS = {"resumen_admin", "actividad_admin", "dominio_admin", "prevision_admin"};
	static final String[] PERSONAS_TABS = {"personas_admin", "conversaciones_admin", "terminos_admin"};
	static final String[] TRAINING_TABS = {"progresion_admin", "certificaciones_admin"};

	static boolean hasAny(org.openedit.profile.UserProfile inProfile, String[] inKeys)
	{
		if (inProfile == null)
		{
			return false;
		}
		for (String key : inKeys)
		{
			if (inProfile.hasPermission(key))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public MediaArchive getMediaArchive(WebPageRequest inReq)
	{
		MediaArchive archive = super.getMediaArchive(inReq);
		if (archive == null)
		{
			archive = (MediaArchive) inReq.getPageValue("mediaarchive");
		}
		return archive;
	}

	public void reply(WebPageRequest inReq, Object responseObj)
	{
		if (responseObj instanceof JSONObject)
		{
			inReq.putPageValue("json", ((JSONObject) responseObj).toJSONString());
		}
		else if (responseObj instanceof JSONArray)
		{
			inReq.putPageValue("json", ((JSONArray) responseObj).toJSONString());
		}
		else if (responseObj instanceof Map)
		{
			inReq.putPageValue("json", JSONObject.toJSONString((Map<?, ?>) responseObj));
		}
		else if (responseObj instanceof String)
		{
			inReq.putPageValue("json", (String) responseObj);
		}
		else if (responseObj != null)
		{
			inReq.putPageValue("json", JSONValue.toJSONString(responseObj));
		}
	}

	public void fail(WebPageRequest inReq, int code, String msg)
	{
		if (inReq.getResponse() != null)
		{
			inReq.getResponse().setStatus(code);
		}
		JSONObject err = new JSONObject();
		err.put("ok", Boolean.FALSE);
		err.put("error", msg);
		reply(inReq, err);
		inReq.setCancelActions(true);
	}

	public void audit(WebPageRequest inReq, MediaArchive archive, String action, String targettype, String targetid, Object before, Object after)
	{
		User actor = inReq.getUser();
		audit(archive, actor != null ? actor.getId() : null, action, targettype, targetid, before, after);
	}

	/** Request-less overload for background jobs (catalog events): actor is a plain id (e.g. "system"), not a signed-in User. */
	public void audit(MediaArchive archive, String actor, String action, String targettype, String targetid, Object before, Object after)
	{
		Searcher s = archive.getSearcher("auditevent");
		Data e = s.createNewData();
		e.setValue("datecreated", new Date());
		e.setValue("actor", actor);
		e.setValue("action", action);
		e.setValue("targettype", targettype);
		e.setValue("targetid", targetid);
		e.setValue("before", before == null ? "" : toJsonString(before));
		e.setValue("after", after == null ? "" : toJsonString(after));
		s.saveData(e, null);
	}

	/** The user's stored record (fresh read by id), falling back to inUser when the lookup misses (deleted mid-request, index lag) so callers never
	 *  get null for a signed-in user. Session User objects are cached at login, so profile assignments made afterwards are only visible here. */
	public Data freshUser(MediaArchive inArchive, User inUser)
	{
		if (inUser == null || inUser.getId() == null || inUser.getId().isEmpty())
		{
			return (Data) inUser;
		}
		Data stored = (Data) inArchive.getSearcher("user").searchById(inUser.getId());
		return stored == null ? (Data) inUser : stored;
	}

	/**
	 * Tells every signed-in device of [inUserId] that its [inType] data changed ({"type": "avatar"|"progress"|"notifications"}
	 * plus [inExtra]) over EnterMedia's per-user websocket (org.entermediadb.websocket.usernotify.UserNotifyConnection).
	 * A hint, not the data: clients refetch. Never fails the request that changed the data.
	 * ponytail: sends inline on the request thread, one JVM only; queue it or relay across nodes if either starts to matter.
	 */
	public void notifyUser(String inUserId, String inType, Map<String, Object> inExtra)
	{
		if (inUserId == null || inUserId.isEmpty())
		{
			return;
		}
		try
		{
			JSONObject event = new JSONObject();
			if (inExtra != null)
			{
				event.putAll(inExtra);
			}
			event.put("type", inType);
			((UserNotifyManager) getModuleManager().getBean("userNotifyManager")).sentNotifications(inUserId, event);
		}
		catch (Exception e)
		{
			org.apache.commons.logging.LogFactory.getLog(TestUBaseModule.class).error("notifyUser " + inType + " " + inUserId, e);
		}
	}

	public JSONObject snapshot(Data d, String... fields)
	{
		if (d == null)
		{
			return null;
		}
		JSONObject map = new JSONObject();
		for (String field : fields)
		{
			map.put(field, d.get(field));
		}
		return map;
	}

	public JSONObject snapshot(Data d, Collection<String> fields)
	{
		if (d == null)
		{
			return null;
		}
		JSONObject map = new JSONObject();
		for (String field : fields)
		{
			map.put(field, d.get(field));
		}
		return map;
	}

	protected String toJsonString(Object obj)
	{
		if (obj == null)
		{
			return "";
		}
		if (obj instanceof JSONObject)
		{
			return ((JSONObject) obj).toJSONString();
		}
		if (obj instanceof JSONArray)
		{
			return ((JSONArray) obj).toJSONString();
		}
		if (obj instanceof Map)
		{
			return JSONObject.toJSONString((Map<?, ?>) obj);
		}
		if (obj instanceof String)
		{
			return (String) obj;
		}
		return JSONValue.toJSONString(obj);
	}

	protected String md5(String s)
	{
		try
		{
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
			byte[] digest = md.digest(s.getBytes("UTF-8"));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest)
			{
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			throw new org.openedit.OpenEditException(e);
		}
	}
}
