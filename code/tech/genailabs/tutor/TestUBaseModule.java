package tech.genailabs.tutor;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.modules.BaseMediaModule;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.users.User;

public class TestUBaseModule extends BaseMediaModule
{
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
		Searcher s = archive.getSearcher("auditevent");
		Data e = s.createNewData();
		e.setValue("datecreated", new Date());
		User actor = inReq.getUser();
		e.setValue("actor", actor != null ? actor.getId() : null);
		e.setValue("action", action);
		e.setValue("targettype", targettype);
		e.setValue("targetid", targetid);
		e.setValue("before", before == null ? "" : toJsonString(before));
		e.setValue("after", after == null ? "" : toJsonString(after));
		s.saveData(e, actor);
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
