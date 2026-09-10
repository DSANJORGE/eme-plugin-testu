package tech.genailabs.tutor;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.profile.UserProfile;
import org.openedit.users.User;
import org.openedit.users.UserManager;
import org.openedit.util.DateStorageUtil;

public class TestUSocialModule extends TestUBaseModule
{
	private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[qt]-[A-Za-z0-9_\\-]+$");
	private static final Set<String> REACTION_NAMES = new HashSet<>(Arrays.asList("like", "applause", "support", "love", "idea", "laugh"));
	private static final Set<String> FLAG_REASONS = new HashSet<>(Arrays.asList("wrong", "unclear", "outdated", "other"));
	private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("manager", "training", "orgadmin"));

	public void addComment(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User who = inReq.getUser();
		String me = (who != null) ? who.getId() : null;
		if (me == null || me.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		String channel = inReq.getRequestParameter("channel");
		channel = (channel != null) ? channel.trim() : "";
		if (!CHANNEL_PATTERN.matcher(channel).matches())
		{
			fail(inReq, 400, "bad channel");
			return;
		}

		String entityid = channel.substring(2);
		String entitytype = channel.startsWith("q-") ? "entityquestion" : "entitytutorial";
		if (archive.getData(entitytype, entityid) == null)
		{
			fail(inReq, 404, "no such " + (entitytype.equals("entityquestion") ? "question" : "tutorial"));
			return;
		}

		String message = inReq.getRequestParameter("message");
		message = (message != null) ? message.trim() : "";
		if (message.isEmpty())
		{
			fail(inReq, 400, "empty message");
			return;
		}
		if (message.length() > 2000)
		{
			fail(inReq, 400, "message too long");
			return;
		}

		Searcher chats = archive.getSearcher("chatterbox");
		String replytoid = inReq.getRequestParameter("replytoid");
		replytoid = (replytoid != null) ? replytoid.trim() : "";
		String parentauthor = null;
		if (!replytoid.isEmpty())
		{
			Data parent = (Data) chats.searchById(replytoid);
			if (parent == null || !channel.equals(parent.get("channel")))
			{
				fail(inReq, 400, "bad replytoid");
				return;
			}
			parentauthor = parent.get("user");
			String parentReplyTo = parent.get("replytoid");
			if (parentReplyTo != null && !parentReplyTo.isEmpty())
			{
				replytoid = parentReplyTo;
			}
		}

		List<String> mentions = new ArrayList<>();
		String mentionsParam = inReq.getRequestParameter("mentions");
		if (mentionsParam != null && !mentionsParam.trim().isEmpty())
		{
			try
			{
				Object parsed = JSONValue.parse(mentionsParam);
				if (parsed instanceof List)
				{
					for (Object item : (List<?>) parsed)
					{
						if (item != null)
						{
							String uid = item.toString().trim();
							if (!uid.isEmpty() && !uid.equals(me) && !mentions.contains(uid))
							{
								mentions.add(uid);
							}
						}
					}
				}
				else
				{
					fail(inReq, 400, "bad mentions");
					return;
				}
			}
			catch (Exception e)
			{
				fail(inReq, 400, "bad mentions");
				return;
			}
		}

		UserManager um = archive.getUserManager();
		String myteam = who.get("team");
		myteam = (myteam != null) ? myteam : "";

		List<String> filteredMentions = new ArrayList<>();
		Searcher profileSearcher = archive.getSearcher("userprofile");
		for (String uid : mentions)
		{
			User u = (um != null) ? um.getUser(uid) : null;
			if (u == null)
				continue;
			Data p = (Data) profileSearcher.searchById(uid);
			String role = (p != null && p.get("settingsgroup") != null) ? p.get("settingsgroup") : "users";
			boolean teammate = !myteam.isEmpty() && myteam.equals(u.get("team"));
			if (teammate || STAFF_ROLES.contains(role))
			{
				filteredMentions.add(uid);
				if (filteredMentions.size() >= 20)
					break;
			}
		}
		mentions = filteredMentions;

		Data d = chats.createNewData();
		d.setValue("channel", channel);
		d.setValue("user", me);
		d.setValue("date", new Date());
		d.setValue("message", message);
		d.setValue("messageplain", message);
		d.setValue("functionname", "testu_social");
		d.setValue("moduleid", entitytype);
		d.setValue("entityid", entityid);
		if (!replytoid.isEmpty())
		{
			d.setValue("replytoid", replytoid);
		}
		archive.saveData("chatterbox", d);
		String messageid = d.getId();

		sendNotification(archive, parentauthor, me, "reply", d, null);
		for (String mid : mentions)
		{
			if (!mid.equals(parentauthor))
			{
				sendNotification(archive, mid, me, "mention", d, null);
			}
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("id", messageid);
		resp.put("replytoid", !replytoid.isEmpty() ? replytoid : null);
		JSONArray mentionsArray = new JSONArray();
		mentionsArray.addAll(mentions);
		resp.put("mentions", mentionsArray);
		reply(inReq, resp);
	}

	public void flagQuestion(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String userid = (user != null) ? user.getId() : null;
		if (userid == null || userid.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		String question = inReq.getRequestParameter("entityquestion");
		question = (question != null) ? question.trim() : "";
		if (question.isEmpty())
		{
			fail(inReq, 400, "entityquestion required");
			return;
		}

		String reason = inReq.getRequestParameter("reason");
		reason = (reason != null) ? reason.trim() : "";
		if (!FLAG_REASONS.contains(reason))
		{
			fail(inReq, 400, "bad reason");
			return;
		}

		if (archive.getData("entityquestion", question) == null)
		{
			fail(inReq, 404, "no such question");
			return;
		}

		String note = inReq.getRequestParameter("note");
		note = (note != null) ? note.trim() : "";
		if (note.length() > 1000)
		{
			note = note.substring(0, 1000);
		}
		String tutorial = inReq.getRequestParameter("entitytutorial");
		tutorial = (tutorial != null) ? tutorial.trim() : "";

		Searcher searcher = archive.getSearcher("questionflag");
		Data d = searcher.createNewData();
		d.setValue("user", userid);
		d.setValue("datecreated", new Date());
		d.setValue("entityquestion", question);
		if (!tutorial.isEmpty())
		{
			d.setValue("entitytutorial", tutorial);
		}
		d.setValue("reason", reason);
		d.setValue("note", note);
		d.setValue("status", "open");
		archive.saveData("questionflag", d);

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("id", d.getId());
		reply(inReq, resp);
	}

	public void markRead(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String userid = (user != null) ? user.getId() : null;
		if (userid == null || userid.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		Searcher searcher = archive.getSearcher("learnernotification");
		List<Data> rows = new ArrayList<>();
		if ("true".equals(inReq.getRequestParameter("all")))
		{
			HitTracker hits = archive.query("learnernotification").exact("user", userid).exact("read", false).search();
			if (hits != null)
			{
				hits.enableBulkOperations();
				for (Object hit : hits)
				{
					rows.add((Data) hit);
				}
			}
		}
		else
		{
			String idsParam = inReq.getRequestParameter("ids");
			List<?> idsList = null;
			try
			{
				Object parsed = JSONValue.parse(idsParam != null ? idsParam : "[]");
				if (parsed instanceof List)
				{
					idsList = (List<?>) parsed;
				}
				else
				{
					fail(inReq, 400, "bad ids");
					return;
				}
			}
			catch (Exception e)
			{
				fail(inReq, 400, "bad ids");
				return;
			}

			if (idsList.size() > 200)
			{
				fail(inReq, 400, "too many ids");
				return;
			}

			for (Object idObj : idsList)
			{
				if (idObj != null)
				{
					Data n = (Data) searcher.searchById(idObj.toString());
					if (n != null && userid.equals(n.get("user")) && !"true".equals(String.valueOf(n.get("read"))))
					{
						rows.add(n);
					}
				}
			}
		}

		for (Data n : rows)
		{
			n.setValue("read", Boolean.TRUE);
		}
		if (!rows.isEmpty())
		{
			searcher.saveAllData(rows, null);
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("marked", Integer.valueOf(rows.size()));
		reply(inReq, resp);
	}

	public void loadMentionables(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User who = inReq.getUser();
		String me = (who != null) ? who.getId() : null;
		if (me == null || me.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		String myteam = who.get("team");
		myteam = (myteam != null) ? myteam : "";

		Map<String, String> roles = new HashMap<>();
		HitTracker profiles = archive.query("userprofile").all().search();
		if (profiles != null)
		{
			profiles.enableBulkOperations();
			for (Object p : profiles)
			{
				Data pd = (Data) p;
				roles.put(pd.getId(), pd.get("settingsgroup"));
			}
		}

		List<JSONObject> out = new ArrayList<>();
		HitTracker hits = archive.query("user").all().search();
		if (hits != null)
		{
			hits.enableBulkOperations();
			for (Object hit : hits)
			{
				Data u = (Data) hit;
				String id = u.getId();
				if (id.equals(me) || "false".equals(String.valueOf(u.get("enabled"))))
				{
					continue;
				}
				String role = roles.get(id);
				if (role == null || role.isEmpty())
				{
					role = "users";
				}
				boolean teammate = !myteam.isEmpty() && myteam.equals(u.get("team"));
				if (!teammate && !STAFF_ROLES.contains(role))
				{
					continue;
				}
				String fn = u.get("firstName");
				String ln = u.get("lastName");
				String name = ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
				if (name.isEmpty())
				{
					name = id;
				}
				JSONObject person = new JSONObject();
				person.put("id", id);
				person.put("name", name);
				person.put("role", role);
				out.add(person);
			}
		}

		out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));

		JSONArray peopleArray = new JSONArray();
		peopleArray.addAll(out);

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("people", peopleArray);
		reply(inReq, resp);
	}

	public void loadNotifications(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String userid = (user != null) ? user.getId() : null;
		if (userid == null || userid.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		HitTracker hits = archive.query("learnernotification").exact("user", userid).sort("datecreatedDown").hitsPerPage(50).search();
		Collection<?> rows = (hits != null) ? hits.getPageOfHits() : Collections.emptyList();

		HitTracker unreadHits = archive.query("learnernotification").exact("user", userid).exact("read", false).search();
		int unread = (unreadHits != null) ? unreadHits.size() : 0;

		SimpleDateFormat isoUtc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX");
		isoUtc.setTimeZone(TimeZone.getTimeZone("UTC"));

		JSONArray notifs = new JSONArray();
		for (Object row : rows)
		{
			Data n = (Data) row;
			JSONObject obj = new JSONObject();
			obj.put("id", n.getId());
			obj.put("type", n.get("type"));
			obj.put("actor", n.get("actor"));
			obj.put("actorname", n.get("actorname"));
			obj.put("text", n.get("text"));
			obj.put("channel", n.get("channel"));
			obj.put("messageid", n.get("messageid"));
			obj.put("entitytutorial", n.get("entitytutorial"));
			obj.put("entitytopic", n.get("entitytopic"));
			obj.put("entityquestion", n.get("entityquestion"));
			obj.put("read", Boolean.valueOf("true".equals(String.valueOf(n.get("read")))));
			Date dateCreated = DateStorageUtil.getStorageUtil().parseFromObject(n.getValue("datecreated"));
			obj.put("date", dateCreated != null ? isoUtc.format(dateCreated) : null);
			notifs.add(obj);
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("unread", Integer.valueOf(unread));
		resp.put("notifications", notifs);
		reply(inReq, resp);
	}

	public void toggleReaction(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String me = (user != null) ? user.getId() : null;
		if (me == null || me.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		String messageid = inReq.getRequestParameter("messageid");
		messageid = (messageid != null) ? messageid.trim() : "";
		String name = inReq.getRequestParameter("name");
		name = (name != null) ? name.trim() : "";
		if (!name.isEmpty() && !REACTION_NAMES.contains(name))
		{
			fail(inReq, 400, "bad reaction");
			return;
		}

		Data msg = (Data) archive.getSearcher("chatterbox").searchById(messageid);
		if (msg == null || !"testu_social".equals(msg.get("functionname")))
		{
			fail(inReq, 404, "no such comment");
			return;
		}

		Searcher reactions = archive.getSearcher("chatterboxreaction");
		Data found = (Data) archive.query("chatterboxreaction").exact("messageid", messageid).exact("user", me).searchOne();
		String mine = null;
		if (name.isEmpty() || (found != null && name.equals(found.get("name"))))
		{
			if (found != null)
			{
				reactions.delete(found, inReq.getUser());
			}
		}
		else
		{
			if (found == null)
			{
				found = reactions.createNewData();
				found.setValue("messageid", messageid);
				found.setValue("user", me);
			}
			found.setValue("date", new Date());
			found.setValue("name", name);
			archive.saveData("chatterboxreaction", found);
			mine = name;
		}

		JSONObject counts = new JSONObject();
		HitTracker reactHits = archive.query("chatterboxreaction").exact("messageid", messageid).search();
		if (reactHits != null)
		{
			for (Object rObj : reactHits)
			{
				Data r = (Data) rObj;
				String rName = r.get("name");
				if (rName != null)
				{
					Integer count = (Integer) counts.get(rName);
					counts.put(rName, count == null ? 1 : count + 1);
				}
			}
		}

		String author = msg.get("user");
		String nid = md5(me + "|" + messageid + "|reaction");
		Searcher ns = archive.getSearcher("learnernotification");
		if (mine == null)
		{
			Data old = (Data) ns.searchById(nid);
			if (old != null)
			{
				ns.delete(old, inReq.getUser());
			}
		}
		else
		{
			sendNotification(archive, author, me, "reaction", msg, nid);
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("mine", mine);
		resp.put("reacts", counts);
		reply(inReq, resp);
	}

	public void loadThread(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User currentUser = inReq.getUser();
		String me = (currentUser != null) ? currentUser.getId() : null;
		if (me == null || me.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}

		Map<String, User> userCache = new HashMap<>();
		Map<String, String> roleCache = new HashMap<>();
		UserManager um = archive.getUserManager();
		Searcher profileSearcher = archive.getSearcher("userprofile");
		SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

		java.util.function.Function<String, User> userOf = uid -> userCache.computeIfAbsent(uid, k -> (um != null) ? um.getUser(k) : null);
		java.util.function.Function<String, String> nameOf = uid -> {
			User u = userOf.apply(uid);
			if (u == null)
				return uid;
			String fn = u.get("firstName");
			String ln = u.get("lastName");
			String n = ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
			return !n.isEmpty() ? n : uid;
		};
		java.util.function.Function<String, String> roleOf = uid -> roleCache.computeIfAbsent(uid, k -> {
			Data p = (Data) profileSearcher.searchById(k);
			return (p != null && p.get("settingsgroup") != null) ? p.get("settingsgroup") : "users";
		});

		String channel = inReq.getRequestParameter("channel");
		channel = (channel != null) ? channel.trim() : "";
		if (!channel.isEmpty())
		{
			if (!CHANNEL_PATTERN.matcher(channel).matches())
			{
				fail(inReq, 400, "bad channel");
				return;
			}
			HitTracker rows = archive.query("chatterbox").exact("channel", channel).exact("functionname", "testu_social").sort("dateUp").search();
			if (rows != null)
				rows.enableBulkOperations();
			List<String> ids = new ArrayList<>();
			if (rows != null)
			{
				for (Object o : rows)
				{
					ids.add(((Data) o).getId());
				}
			}
			Map<String, JSONObject> reacts = new HashMap<>();
			Map<String, String> mine = new HashMap<>();
			if (!ids.isEmpty())
			{
				HitTracker reactionHits = archive.query("chatterboxreaction").orgroup("messageid", ids).search();
				if (reactionHits != null)
				{
					for (Object ro : reactionHits)
					{
						Data r = (Data) ro;
						String mid = r.get("messageid");
						String rName = r.get("name");
						JSONObject c = reacts.computeIfAbsent(mid, k -> new JSONObject());
						Integer cnt = (Integer) c.get(rName);
						c.put(rName, cnt == null ? 1 : cnt + 1);
						if (me.equals(r.get("user")))
						{
							mine.put(mid, rName);
						}
					}
				}
			}
			Map<String, JSONObject> byId = new LinkedHashMap<>();
			JSONArray top = new JSONArray();
			if (rows != null)
			{
				for (Object o : rows)
				{
					Data m = (Data) o;
					String uid = m.get("user");
					JSONObject c = new JSONObject();
					c.put("id", m.getId());
					c.put("userId", uid);
					c.put("name", nameOf.apply(uid));
					c.put("role", roleOf.apply(uid));
					Date d = DateStorageUtil.getStorageUtil().parseFromObject(m.getValue("date"));
					c.put("date", d != null ? isoFormat.format(d) : null);
					c.put("text", m.get("message") != null ? m.get("message") : "");
					JSONObject msgReacts = reacts.get(m.getId());
					c.put("reacts", msgReacts != null ? msgReacts : new JSONObject());
					c.put("mine", mine.get(m.getId()));
					c.put("replies", new JSONArray());
					byId.put(m.getId(), c);

					String replyTo = m.get("replytoid");
					JSONObject parentObj = (replyTo != null && !replyTo.isEmpty()) ? byId.get(replyTo) : null;
					if (parentObj == null)
					{
						top.add(c);
					}
					else
					{
						((JSONArray) parentObj.get("replies")).add(c);
					}
				}
			}
			JSONObject resp = new JSONObject();
			resp.put("ok", Boolean.TRUE);
			resp.put("channel", channel);
			resp.put("comments", top);
			reply(inReq, resp);
			return;
		}

		UserProfile userProfile = inReq.getUserProfile();
		if (userProfile == null || !userProfile.hasPermission("personas_view"))
		{
			fail(inReq, 403, "not allowed");
			return;
		}
		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");
		java.util.function.Predicate<String> inScope = uid -> {
			if (scope == null)
				return true;
			User u = userOf.apply(uid);
			return u != null && scope.contains(u.get("team"));
		};
		java.util.function.BiFunction<String, String, String> labelOf = (moduleId, entityId) -> {
			if (moduleId == null || moduleId.isEmpty() || entityId == null || entityId.isEmpty())
			{
				return (entityId != null) ? entityId : "";
			}
			Data d = archive.getData(moduleId, entityId);
			if (d == null)
				return entityId;
			if ("entityquestion".equals(moduleId))
			{
				String q = d.get("question");
				return (q != null && !q.isEmpty()) ? q : d.getName();
			}
			return d.getName() != null ? d.getName() : entityId;
		};

		HitTracker all = archive.query("chatterbox").exact("functionname", "testu_social").sort("dateDown").search();
		if (all != null)
			all.enableBulkOperations();
		JSONArray recent = new JSONArray();
		if (all != null)
		{
			for (Object o : all)
			{
				if (recent.size() >= 50)
					break;
				Data m = (Data) o;
				String uid = m.get("user");
				if (!inScope.test(uid))
					continue;
				JSONObject c = new JSONObject();
				c.put("id", m.getId());
				c.put("userId", uid);
				c.put("name", nameOf.apply(uid));
				c.put("role", roleOf.apply(uid));
				Date d = DateStorageUtil.getStorageUtil().parseFromObject(m.getValue("date"));
				c.put("date", d != null ? isoFormat.format(d) : null);
				c.put("text", m.get("message") != null ? m.get("message") : "");
				c.put("channel", m.get("channel"));
				c.put("moduleid", m.get("moduleid"));
				c.put("entityid", m.get("entityid"));
				c.put("label", labelOf.apply(m.get("moduleid"), m.get("entityid")));
				c.put("replytoid", m.get("replytoid"));
				recent.add(c);
			}
		}

		HitTracker fl = archive.query("questionflag").exact("status", "open").sort("datecreatedDown").search();
		if (fl != null)
			fl.enableBulkOperations();
		JSONArray flags = new JSONArray();
		if (fl != null)
		{
			for (Object o : fl)
			{
				if (flags.size() >= 50)
					break;
				Data f = (Data) o;
				String uid = f.get("user");
				if (!inScope.test(uid))
					continue;
				JSONObject flagObj = new JSONObject();
				flagObj.put("id", f.getId());
				flagObj.put("entityquestion", f.get("entityquestion"));
				flagObj.put("entitytutorial", f.get("entitytutorial"));
				flagObj.put("label", labelOf.apply("entityquestion", f.get("entityquestion")));
				flagObj.put("reason", f.get("reason"));
				flagObj.put("note", f.get("note") != null ? f.get("note") : "");
				flagObj.put("userId", uid);
				flagObj.put("name", nameOf.apply(uid));
				Date d = DateStorageUtil.getStorageUtil().parseFromObject(f.getValue("datecreated"));
				flagObj.put("date", d != null ? isoFormat.format(d) : null);
				flags.add(flagObj);
			}
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("recent", recent);
		resp.put("flags", flags);
		reply(inReq, resp);
	}

	public void sendNotification(MediaArchive archive, String recipient, String actor, String type, Data msg, String id)
	{
		if (recipient == null || recipient.isEmpty() || recipient.equals(actor))
		{
			return; // never notify yourself
		}
		Searcher s = archive.getSearcher("learnernotification");
		Data n = (id != null) ? (Data) s.searchById(id) : null;
		if (n == null)
		{
			n = s.createNewData();
			if (id != null)
			{
				n.setId(id);
			}
		}
		String channel = msg.get("channel") != null ? msg.get("channel").toString() : "";
		String qid = channel.startsWith("q-") ? channel.substring(2) : "";
		String tid = channel.startsWith("t-") ? channel.substring(2) : "";
		if (!qid.isEmpty())
		{
			Data cc = (Data) archive.query("componentcontent").exact("questionid", qid).searchOne();
			if (cc != null)
			{
				String csId = cc.get("componentsectionid");
				Data cs = (csId != null) ? archive.getData("componentsection", csId) : null;
				tid = (cs != null && cs.get("playbackentityid") != null) ? cs.get("playbackentityid") : "";
			}
		}
		Data a = (Data) archive.getSearcher("user").searchById(actor);
		n.setValue("user", recipient);
		n.setValue("actor", actor);
		n.setValue("type", type);
		n.setValue("datecreated", new Date());
		n.setValue("read", Boolean.FALSE);

		String actorName = null;
		if (a != null)
		{
			String fn = a.get("firstName");
			String ln = a.get("lastName");
			StringBuilder nameBuilder = new StringBuilder();
			if (fn != null && !fn.isEmpty())
				nameBuilder.append(fn);
			if (ln != null && !ln.isEmpty())
			{
				if (nameBuilder.length() > 0)
					nameBuilder.append(" ");
				nameBuilder.append(ln);
			}
			if (nameBuilder.length() > 0)
				actorName = nameBuilder.toString();
		}
		n.setValue("actorname", actorName != null ? actorName : actor);

		String text = (msg.get("message") != null) ? msg.get("message").toString() : "";
		text = text.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
		if (text.length() > 120)
			text = text.substring(0, 120);
		n.setValue("text", text);

		n.setValue("channel", channel);
		n.setValue("messageid", msg.getId());
		n.setValue("entityquestion", qid);
		n.setValue("entitytutorial", tid);

		String topic = "";
		if (!tid.isEmpty())
		{
			Data tut = archive.getData("entitytutorial", tid);
			if (tut != null && tut.get("entitytopic") != null)
			{
				topic = tut.get("entitytopic");
			}
		}
		n.setValue("entitytopic", topic);
		s.saveData(n, null);
	}

	public String md5(String s)
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("MD5");
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
			throw new OpenEditException(e);
		}
	}
}
