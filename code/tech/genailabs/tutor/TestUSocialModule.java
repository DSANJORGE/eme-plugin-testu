package tech.genailabs.tutor;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
import org.openedit.users.Group;
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
		// Same scope loadMentionables offers: anyone who has this thread's topic, plus staff.
		Data topic = topicOfChannel(archive, channel);

		List<String> filteredMentions = new ArrayList<>();
		Searcher profileSearcher = archive.getSearcher("userprofile");
		boolean askTutor = mentions.contains(TUTOR);
		for (String uid : mentions)
		{
			User u = (um != null) ? um.getUser(uid) : null;
			if (u == null)
				continue;
			Data p = (Data) profileSearcher.searchById(uid);
			String role = (p != null && p.get("settingsgroup") != null) ? p.get("settingsgroup") : "users";
			if (canSeeTopic(archive, topic, uid, role) || STAFF_ROLES.contains(role))
			{
				filteredMentions.add(uid);
				if (filteredMentions.size() >= 20)
					break;
			}
		}
		mentions = filteredMentions;
		if (askTutor)
			mentions.add(TUTOR);

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
			if (!mid.equals(parentauthor) && !mid.equals(TUTOR))
			{
				sendNotification(archive, mid, me, "mention", d, null);
			}
		}
		if (askTutor)
			tutorReply(archive, d);

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

	/** Upserts one FCM device token for the signed-in learner; `remove=true` drops it (sign-out).
	 *  Row id = md5(token): a phone that changes account moves its token to the new user, never duplicates. */
	public void deviceToken(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String userid = (user != null) ? user.getId() : null;
		if (userid == null || userid.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}
		String token = inReq.getRequestParameter("token");
		token = token == null ? "" : token.trim();
		if (token.isEmpty() || token.length() > 512)
		{
			fail(inReq, 400, "bad token");
			return;
		}
		Searcher s = archive.getSearcher("devicetoken");
		Data d = (Data) s.searchById(md5(token));
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		if ("true".equals(inReq.getRequestParameter("remove")))
		{
			if (d != null)
			{
				s.delete(d, user);
			}
			resp.put("removed", d != null);
			reply(inReq, resp);
			return;
		}
		if (d == null)
		{
			d = s.createNewData();
			d.setId(md5(token));
		}
		String platform = inReq.getRequestParameter("platform");
		platform = platform == null ? "" : platform;
		d.setValue("user", userid);
		d.setValue("token", token);
		d.setValue("platform", platform.length() > 20 ? platform.substring(0, 20) : platform);
		d.setValue("datecreated", new Date());
		s.saveData(d, null);
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
			notifyUser(userid, "notifications", null);
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

		// The composer sends its thread channel; people who have that topic (entitytopic security, the same
		// filter the app's topic list goes through) plus staff. No/unknown channel = staff only.
		String channel = inReq.getRequestParameter("channel");
		Data topic = (channel != null && CHANNEL_PATTERN.matcher(channel.trim()).matches()) ? topicOfChannel(archive, channel.trim()) : null;

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
				if (!canSeeTopic(archive, topic, id, role) && !STAFF_ROLES.contains(role))
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
		// The tutor, first: @IRIS in a thread gets her reply and a notification to the author.
		JSONObject tutor = new JSONObject();
		tutor.put("id", TUTOR);
		tutor.put("name", tutorName(archive));
		tutor.put("role", TUTOR);
		out.add(0, tutor);

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
				notifyUser(old.get("user"), "notifications", null);
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
			if (TUTOR.equals(uid))
				return tutorName(archive);
			User u = userOf.apply(uid);
			if (u == null)
				return uid;
			String fn = u.get("firstName");
			String ln = u.get("lastName");
			String n = ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
			return !n.isEmpty() ? n : uid;
		};
		java.util.function.Function<String, String> roleOf = uid -> roleCache.computeIfAbsent(uid, k -> {
			if (TUTOR.equals(k))
				return TUTOR;
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

		// Inbox facets: the author's team and the tutorial behind each channel (one componentcontent lookup per distinct question).
		java.util.function.Function<String, String> teamOf = uid -> { User u = userOf.apply(uid); return u != null && u.get("team") != null ? u.get("team") : ""; };
		Map<String, String> tutCache = new HashMap<>();
		java.util.function.Function<String, String> tutOf = ch -> ch == null ? "" : tutCache.computeIfAbsent(ch, k -> tutorialOfChannel(archive, k));
		Map<String, String> tutNames = new HashMap<>();
		java.util.function.Function<String, String> tutName = tid -> tid == null || tid.isEmpty() ? "" : tutNames.computeIfAbsent(tid, k -> { Data d = archive.getData("entitytutorial", k); return d != null && d.getName() != null ? d.getName() : k; });

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
				c.put("team", teamOf.apply(uid));
				String rt = tutOf.apply(m.get("channel"));
				c.put("entitytutorial", rt);
				c.put("tutorial", tutName.apply(rt));
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
				String ft = f.get("entitytutorial") != null && !f.get("entitytutorial").isEmpty() ? f.get("entitytutorial") : tutOf.apply("q-" + f.get("entityquestion"));
				flagObj.put("entitytutorial", ft);
				flagObj.put("tutorial", tutName.apply(ft));
				flagObj.put("team", teamOf.apply(uid));
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

	/** The tutor's user id inside threads: a mentionable, never a real account. */
	public static final String TUTOR = "tutor";

	/** The org's tutor persona name (catalog setting tutorpersona, default iris). */
	static String tutorName(MediaArchive archive)
	{
		String personaId = archive.getCatalogSettingValue("tutorpersona");
		Data persona = archive.getData("tutorpersona", personaId == null || personaId.isEmpty() ? "iris" : personaId);
		return persona == null || persona.getName() == null ? "IRIS" : persona.getName();
	}

	/** @tutor in a thread comment: the LLM answers with the question in play as context (call template
	 *  social_tutor_mention), the answer lands as a thread reply from TUTOR and the author gets a "reply"
	 *  notification (push included). On its own thread: the comment POST must not wait 2-10 s on the model.
	 *  A model failure leaves no reply — the comment stands for the humans on the thread. */
	void tutorReply(final MediaArchive archive, final Data comment)
	{
		new Thread(() -> {
			try
			{
				String channel = comment.get("channel");
				Data q = channel.startsWith("q-") ? archive.getData("entityquestion", channel.substring(2)) : null;
				StringBuilder ctxq = new StringBuilder();
				if (q != null)
				{
					ctxq.append("Question: ").append(q.get("question")).append("\n");
					for (String o : new String[] { "a", "b", "c", "d", "e", "f" })
					{
						String t = q.get("option_" + o);
						if (t != null && !t.isEmpty())
							ctxq.append(o).append(") ").append(t).append("\n");
					}
					ctxq.append("Correct option: ").append(q.get("correctoption")).append("\n");
					if (q.get("rationale") != null)
						ctxq.append("Rationale: ").append(q.get("rationale")).append("\n");
				}
				StringBuilder thread = new StringBuilder();
				HitTracker rows = archive.query("chatterbox").exact("channel", channel).exact("functionname", "testu_social").sort("dateUp").search();
				int n = 0;
				for (Object o : rows)
				{
					Data m = (Data) o;
					if (m.getId().equals(comment.getId()))
						continue;
					String text = m.get("message") == null ? "" : m.get("message").replaceAll("<[^>]*>", "");
					thread.append(TUTOR.equals(m.get("user")) ? tutorName(archive) : "Colaborador").append(": ").append(text.length() > 300 ? text.substring(0, 300) : text).append("\n");
					if (++n >= 12)
						break;
				}
				org.entermediadb.ai.llm.BaseAgentContext ctx = new org.entermediadb.ai.llm.BaseAgentContext();
				ctx.putContextValue("questioncontext", ctxq.toString());
				ctx.putContextValue("thread", thread.toString());
				ctx.putContextValue("comment", comment.get("message").replaceAll("<[^>]*>", ""));
				java.util.Map<String, Object> out = (java.util.Map<String, Object>) archive.getLlmConnection("thinking").callStructure(ctx, "social_tutor_mention").getResponsePayload();
				String answer = out == null || out.get("message") == null ? null : String.valueOf(out.get("message")).trim();
				if (answer == null || answer.isEmpty())
					return;
				Searcher chats = archive.getSearcher("chatterbox");
				Data r = chats.createNewData();
				r.setValue("channel", channel);
				r.setValue("user", TUTOR);
				r.setValue("date", new Date());
				r.setValue("message", answer);
				r.setValue("messageplain", answer);
				r.setValue("functionname", "testu_social");
				r.setValue("moduleid", comment.get("moduleid"));
				r.setValue("entityid", comment.get("entityid"));
				// One level of nesting, like a human reply: under the top-level comment.
				String parent = comment.get("replytoid");
				r.setValue("replytoid", parent == null || parent.isEmpty() ? comment.getId() : parent);
				archive.saveData("chatterbox", r);
				sendNotification(archive, comment.get("user"), TUTOR, "reply", r, null);
			}
			catch (Throwable e)
			{
				org.apache.commons.logging.LogFactory.getLog(TestUSocialModule.class).error("testu social: tutor reply failed", e);
			}
		}, "testu-tutor-reply").start();
	}

	private static final String[] Q_FIELDS = {"question", "option_a", "option_b", "option_c", "option_d", "option_e", "option_f", "correctoption", "rationale", "sourcequote", "sourcecite", "sourcepage"};
	private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

	static String trimmed(WebPageRequest inReq, String name)
	{
		String v = inReq.getRequestParameter(name);
		return v == null ? "" : v.trim();
	}

	static String iso(Object stored)
	{
		Date d = DateStorageUtil.getStorageUtil().parseFromObject(stored);
		synchronized (ISO)
		{
			return d != null ? ISO.format(d) : null;
		}
	}

	/** "First Last" of a user, the tutor's name for the tutor, the id when unknown. ponytail: no cache, callers load a handful. */
	String displayName(MediaArchive archive, String uid)
	{
		if (uid == null || uid.isEmpty())
			return "";
		if (TUTOR.equals(uid))
			return tutorName(archive);
		User u = archive.getUserManager() != null ? archive.getUserManager().getUser(uid) : null;
		if (u == null)
			return uid;
		String fn = u.get("firstName");
		String ln = u.get("lastName");
		String n = ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
		return n.isEmpty() ? uid : n;
	}

	JSONObject flagJson(MediaArchive archive, Data f)
	{
		JSONObject o = new JSONObject();
		o.put("id", f.getId());
		o.put("entityquestion", f.get("entityquestion"));
		o.put("entitytutorial", f.get("entitytutorial"));
		o.put("reason", f.get("reason"));
		o.put("note", f.get("note") != null ? f.get("note") : "");
		o.put("userId", f.get("user"));
		o.put("name", displayName(archive, f.get("user")));
		o.put("date", iso(f.getValue("datecreated")));
		o.put("status", f.get("status") != null ? f.get("status") : "open");
		o.put("resolvedby", f.get("resolvedby"));
		o.put("resolvedbyname", f.get("resolvedby") != null ? displayName(archive, f.get("resolvedby")) : null);
		o.put("resolvedate", f.get("resolvedate") != null ? iso(f.getValue("resolvedate")) : null);
		o.put("fixnote", f.get("fixnote") != null ? f.get("fixnote") : "");
		Object chips = f.get("fixchips") != null ? JSONValue.parse(f.get("fixchips")) : null;
		o.put("fixchips", chips instanceof JSONArray ? chips : new JSONArray());
		Object replies = f.get("replies") != null ? JSONValue.parse(f.get("replies")) : null;
		o.put("replies", replies instanceof JSONArray ? replies : new JSONArray());
		return o;
	}

	/** question.json?id=: one question as the app renders it (fields, hosting content's image), how learners answered it
	 *  (per option, correct, confident-and-wrong), every report on it open or closed with staff replies, and its change log
	 *  (auditevent rows on the question, newest first). Console only: personas_view, reports narrowed to the caller's scope teams. */
	public void loadQuestion(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		UserProfile userProfile = inReq.getUserProfile();
		if (userProfile == null || !userProfile.hasPermission("personas_view"))
		{
			fail(inReq, 403, "not allowed");
			return;
		}
		String id = trimmed(inReq, "id");
		Data q = id.isEmpty() ? null : archive.getData("entityquestion", id);
		if (q == null)
		{
			fail(inReq, 404, "no such question");
			return;
		}
		JSONObject question = new JSONObject();
		question.put("id", id);
		for (String f : Q_FIELDS)
			question.put(f, q.get(f) != null ? q.get(f) : "");
		Data cc = (Data) archive.query("componentcontent").exact("questionid", id).searchOne();
		String assetid = cc != null ? cc.get("assetid") : null;
		if (assetid != null && !assetid.isEmpty())
		{
			question.put("assetid", assetid);
			question.put("asseturl", inReq.getSiteRoot() + archive.asLinkToPreview(assetid, "image3000x3000"));
		}
		String tid = tutorialOfChannel(archive, "q-" + id);
		question.put("entitytutorial", tid);
		Data tut = tid.isEmpty() ? null : archive.getData("entitytutorial", tid);
		question.put("tutorial", tut != null && tut.getName() != null ? tut.getName() : tid);

		JSONObject byOption = new JSONObject();
		int total = 0, correct = 0, certainWrong = 0;
		HitTracker answers = archive.query("tutoranswer").exact("entityquestion", id).search();
		if (answers != null)
		{
			answers.enableBulkOperations();
			for (Object o : answers)
			{
				Data a = (Data) o;
				String opt = a.get("selectedoption");
				if (opt == null || opt.isEmpty())
					continue;
				total++;
				opt = opt.toUpperCase();
				Long n = (Long) byOption.get(opt);
				byOption.put(opt, n == null ? 1L : n + 1);
				boolean ok = "true".equals(String.valueOf(a.get("iscorrect")));
				String conf = a.get("answerconfidence");
				if (ok)
					correct++;
				else if ("confident".equals(conf) || "mostlysure".equals(conf))
					certainWrong++;
			}
		}
		JSONObject stats = new JSONObject();
		stats.put("total", total);
		stats.put("correct", correct);
		stats.put("certainwrong", certainWrong);
		stats.put("byoption", byOption);

		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");
		UserManager um = archive.getUserManager();
		JSONArray flags = new JSONArray();
		HitTracker fl = archive.query("questionflag").exact("entityquestion", id).sort("datecreatedDown").search();
		if (fl != null)
		{
			for (Object o : fl)
			{
				Data f = (Data) o;
				if (scope != null)
				{
					User u = um != null ? um.getUser(f.get("user")) : null;
					if (u == null || !scope.contains(u.get("team")))
						continue;
				}
				flags.add(flagJson(archive, f));
			}
		}

		JSONArray log = new JSONArray();
		HitTracker ev = archive.query("auditevent").exact("targettype", "entityquestion").exact("targetid", id).sort("datecreatedDown").search();
		if (ev != null)
		{
			for (Object o : ev)
			{
				Data e = (Data) o;
				JSONObject row = new JSONObject();
				row.put("id", e.getId());
				row.put("date", iso(e.getValue("datecreated")));
				row.put("actor", e.get("actor"));
				row.put("actorname", displayName(archive, e.get("actor")));
				row.put("action", e.get("action"));
				Object before = e.get("before") != null && !e.get("before").isEmpty() ? JSONValue.parse(e.get("before")) : null;
				Object after = e.get("after") != null && !e.get("after").isEmpty() ? JSONValue.parse(e.get("after")) : null;
				row.put("before", before);
				row.put("after", after);
				log.add(row);
			}
		}

		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("question", question);
		resp.put("stats", stats);
		resp.put("flags", flags);
		resp.put("log", log);
		reply(inReq, resp);
	}

	/** savequestion.json (POST id + any of Q_FIELDS): edits the question in place for everyone, the app reads it on its next
	 *  tutorial.json. Only sent fields change; the merged question must still pass LearningEngine.contentProblem. One auditevent
	 *  "question.edit" with before/after of the changed fields only: the change log the console shows and compliance reads.
	 *  Open reports stay open, resolveflag closes them. correctoption is stored as the capital letter answer.json compares. */
	public void saveQuestion(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		String id = trimmed(inReq, "id");
		Data q = id.isEmpty() ? null : archive.getData("entityquestion", id);
		if (q == null)
		{
			fail(inReq, 404, "no such question");
			return;
		}
		Map<String, String> next = new LinkedHashMap<>();
		for (String f : Q_FIELDS)
		{
			String v = inReq.getRequestParameter(f);
			if (v == null)
				continue;
			v = v.trim();
			if ("correctoption".equals(f))
				v = v.toUpperCase().replaceFirst("^OPTION_", "");
			next.put(f, v);
		}
		String bad = LearningEngine.contentProblem(f -> next.containsKey(f) ? next.get(f) : q.get(f));
		if (bad != null)
		{
			fail(inReq, 400, bad);
			return;
		}
		JSONObject before = new JSONObject();
		JSONObject after = new JSONObject();
		for (Map.Entry<String, String> e : next.entrySet())
		{
			String old = q.get(e.getKey()) != null ? q.get(e.getKey()) : "";
			if (!old.equals(e.getValue()))
			{
				before.put(e.getKey(), old);
				after.put(e.getKey(), e.getValue());
				q.setValue(e.getKey(), e.getValue());
			}
		}
		if (!after.isEmpty())
		{
			archive.getSearcher("entityquestion").saveData(q, inReq.getUser());
			audit(inReq, archive, "question.edit", "entityquestion", id, before, after);
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("changed", new JSONArray() {{ addAll(after.keySet()); }});
		reply(inReq, resp);
	}

	/** resolveflag.json (POST entityquestion, verdict fixed|dismissed, chips = comma list of what changed, note): closes every
	 *  open report on the question (status resolved|dismissed + who/when/what), tells each reporter once in their bell
	 *  (type fixed|reviewed, text = chips + note, so they can judge and re-report) and logs one auditevent
	 *  "question.fixed"|"question.dismissed" with the same summary and the report ids it closed. */
	public void resolveFlags(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String me = user != null ? user.getId() : null;
		if (me == null || me.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}
		String qid = trimmed(inReq, "entityquestion");
		String verdict = trimmed(inReq, "verdict");
		if (qid.isEmpty() || !("fixed".equals(verdict) || "dismissed".equals(verdict)))
		{
			fail(inReq, 400, "entityquestion and verdict fixed|dismissed required");
			return;
		}
		JSONArray chips = new JSONArray();
		for (String c : trimmed(inReq, "chips").split(","))
			if (!c.trim().isEmpty() && chips.size() < 12)
				chips.add(c.trim());
		String note = trimmed(inReq, "note");
		if (note.length() > 1000)
			note = note.substring(0, 1000);

		HitTracker open = archive.query("questionflag").exact("entityquestion", qid).exact("status", "open").search();
		List<Data> tosave = new ArrayList<>();
		Set<String> reporters = new java.util.LinkedHashSet<>();
		JSONArray ids = new JSONArray();
		Date now = new Date();
		if (open != null)
		{
			for (Object o : open)
			{
				Data f = (Data) o;
				f.setValue("status", "fixed".equals(verdict) ? "resolved" : "dismissed");
				f.setValue("resolvedby", me);
				f.setValue("resolvedate", now);
				f.setValue("fixchips", chips.toJSONString());
				f.setValue("fixnote", note);
				tosave.add(f);
				reporters.add(f.get("user"));
				ids.add(f.getId());
			}
		}
		if (!tosave.isEmpty())
		{
			archive.getSearcher("questionflag").saveAllData(tosave, user);
			String summary = String.join(" · ", (List<String>) (List<?>) chips) + (note.isEmpty() ? "" : (chips.isEmpty() ? "" : ". ") + note);
			for (String r : reporters)
				sendNotification(archive, r, me, "fixed".equals(verdict) ? "fixed" : "reviewed", "q-" + qid, summary, (String) ids.get(0), null);
			JSONObject after = new JSONObject();
			after.put("verdict", verdict);
			after.put("chips", chips);
			after.put("note", note);
			after.put("flags", ids);
			audit(inReq, archive, "question." + verdict, "entityquestion", qid, null, after);
		}
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("closed", ids.size());
		resp.put("notified", new JSONArray() {{ addAll(reporters); }});
		reply(inReq, resp);
	}

	/** replyflag.json (POST flagid, text): a staff reply to one report, private to its reporter: appended to the report's
	 *  replies and delivered to the reporter's bell (type flagreply, deep-links to the question). The report stays open. */
	public void replyFlag(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		User user = inReq.getUser();
		String me = user != null ? user.getId() : null;
		if (me == null || me.isEmpty())
		{
			fail(inReq, 401, "not signed in");
			return;
		}
		String flagid = trimmed(inReq, "flagid");
		String text = trimmed(inReq, "text");
		if (text.length() > 1000)
			text = text.substring(0, 1000);
		Searcher s = archive.getSearcher("questionflag");
		Data f = flagid.isEmpty() ? null : (Data) s.searchById(flagid);
		if (f == null)
		{
			fail(inReq, 404, "no such report");
			return;
		}
		if (text.isEmpty())
		{
			fail(inReq, 400, "text required");
			return;
		}
		Object parsed = f.get("replies") != null ? JSONValue.parse(f.get("replies")) : null;
		JSONArray replies = parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
		JSONObject r = new JSONObject();
		r.put("by", me);
		r.put("name", displayName(archive, me));
		r.put("date", iso(new Date()));
		r.put("text", text);
		replies.add(r);
		f.setValue("replies", replies.toJSONString());
		s.saveData(f, user);
		sendNotification(archive, f.get("user"), me, "flagreply", "q-" + f.get("entityquestion"), text, flagid, null);
		JSONObject resp = new JSONObject();
		resp.put("ok", Boolean.TRUE);
		resp.put("reply", r);
		reply(inReq, resp);
	}

	public void sendNotification(MediaArchive archive, String recipient, String actor, String type, Data msg, String id)
	{
		sendNotification(archive, recipient, actor, type, msg.get("channel") != null ? msg.get("channel").toString() : "",
			msg.get("message") != null ? msg.get("message").toString() : "", msg.getId(), id);
	}

	public void sendNotification(MediaArchive archive, String recipient, String actor, String type, String channel, String message, String messageid, String id)
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
		String qid = channel.startsWith("q-") ? channel.substring(2) : "";
		String tid = tutorialOfChannel(archive, channel);
		Data a = TUTOR.equals(actor) ? null : (Data) archive.getSearcher("user").searchById(actor);
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
		n.setValue("actorname", actorName != null ? actorName : TUTOR.equals(actor) ? tutorName(archive) : actor);

		String text = message != null ? message : "";
		text = text.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
		if (text.length() > 120)
			text = text.substring(0, 120);
		n.setValue("text", text);

		n.setValue("channel", channel);
		n.setValue("messageid", messageid);
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
		notifyUser(recipient, "notifications", null);
		push(archive, n);
	}

	/** Tutorial behind a channel: "t-<tutorial>" directly; "q-<question>" via componentcontent -> componentsection.playbackentityid
	 *  (componentsection stores its tutorial there, not in entityid: same as computemastery/report/aggregate). "" when unknown. */
	static String tutorialOfChannel(MediaArchive archive, String channel)
	{
		if (channel.startsWith("t-"))
			return channel.substring(2);
		if (!channel.startsWith("q-"))
			return "";
		Data cc = (Data) archive.query("componentcontent").exact("questionid", channel.substring(2)).searchOne();
		String csId = (cc != null) ? cc.get("componentsectionid") : null;
		Data cs = (csId != null) ? archive.getData("componentsection", csId) : null;
		String tid = (cs != null) ? cs.get("playbackentityid") : null;
		return (tid != null) ? tid : "";
	}

	static Data topicOfChannel(MediaArchive archive, String channel)
	{
		String tid = tutorialOfChannel(archive, channel);
		Data tut = tid.isEmpty() ? null : archive.getData("entitytutorial", tid);
		String topicid = (tut != null) ? tut.get("entitytopic") : null;
		return (topicid != null && !topicid.isEmpty()) ? archive.getData("entitytopic", topicid) : null;
	}

	/** Who has a topic: the rule BaseSearchSecurity.attachStandardSecurity applies when the app lists entitytopic
	 *  (TestULearningModule.visibleTopics / topics.json): securityenabled off = everyone; else owner, viewusers,
	 *  viewroles (userprofile.settingsgroup) or viewgroups (user groups); administrator always. null topic = nobody. */
	static boolean canSeeTopic(MediaArchive archive, Data topic, String uid, String role)
	{
		if (topic == null)
			return false;
		if ("administrator".equals(role) || !"true".equals(String.valueOf(topic.get("securityenabled"))))
			return true;
		if (uid.equals(topic.get("owner")) || has(topic, "viewusers", uid) || has(topic, "viewroles", role))
			return true;
		Collection viewgroups = topic.getValues("viewgroups");
		if (viewgroups == null || viewgroups.isEmpty())
			return false;
		// ponytail: one user load per candidate, only for group-secured topics; pilot rosters are tens of users.
		User u = archive.getUserManager().getUser(uid);
		if (u != null)
			for (Group g : u.getGroups())
				if (viewgroups.contains(g.getId()))
					return true;
		return false;
	}

	private static boolean has(Data d, String field, String value)
	{
		Collection v = d.getValues(field);
		return v != null && v.contains(value);
	}

	/** Mobile push (FCM HTTP v1) for the learnernotification row just saved. Off until the catalog setting or
	 *  -Dtestu.fcm.serviceaccount names the Firebase service-account JSON. Fire-and-forget on a thread with
	 *  5 s timeouts: a push failure never fails the comment. The data payload is the row itself, so the app
	 *  routes a tap with the bell's table. FCM 404 (UNREGISTERED) deletes the token row.
	 *  ponytail: a fresh OAuth token per push; cache it in archive.getCacheManager() if volume ever matters. */
	public void push(final MediaArchive archive, Data n)
	{
		String path = archive.getCatalogSettingValue("testu.fcm.serviceaccount");
		if (path == null || path.isEmpty())
			path = System.getProperty("testu.fcm.serviceaccount");
		if (path == null || path.isEmpty())
			return;
		String recipient = n.get("user");
		HitTracker hits = archive.query("devicetoken").exact("user", recipient == null ? "" : recipient).search();
		final List<Data> tokens = new ArrayList<>();
		if (hits != null)
		{
			for (Object hit : hits)
			{
				tokens.add((Data) hit);
			}
		}
		if (tokens.isEmpty())
			return;
		final JSONObject data = new JSONObject();
		for (String k : new String[] { "type", "channel", "messageid", "entitytopic", "entityquestion", "entitytutorial", "actorname", "text" })
		{
			String v = n.get(k);
			data.put(k, v == null ? "" : v);
		}
		data.put("id", n.getId());
		final String saPath = path;
		final Searcher ds = archive.getSearcher("devicetoken");
		new Thread(new Runnable()
		{
			public void run()
			{
				try
				{
					JSONObject sa = (JSONObject) JSONValue.parse(new String(Files.readAllBytes(new File(saPath).toPath()), StandardCharsets.UTF_8));
					String bearer = fcmBearer(sa);
					for (Data t : tokens)
					{
						JSONObject notification = new JSONObject();
						notification.put("title", data.get("actorname"));
						notification.put("body", data.get("text"));
						JSONObject aps = new JSONObject();
						aps.put("sound", "default");
						JSONObject apnsPayload = new JSONObject();
						apnsPayload.put("aps", aps);
						JSONObject apns = new JSONObject();
						apns.put("payload", apnsPayload);
						JSONObject message = new JSONObject();
						message.put("token", t.get("token"));
						message.put("notification", notification);
						message.put("data", data);
						message.put("apns", apns);
						JSONObject body = new JSONObject();
						body.put("message", message);
						String[] r = httpPost("https://fcm.googleapis.com/v1/projects/" + sa.get("project_id") + "/messages:send", "application/json; charset=UTF-8", body.toJSONString(), bearer);
						int code = Integer.parseInt(r[0]);
						if (code == 404)
							ds.delete(t, null); // UNREGISTERED: the app was removed or the token rotated
						else if (code >= 400)
							System.err.println("testu push " + code + " " + (r[1].length() > 300 ? r[1].substring(0, 300) : r[1]));
					}
				}
				catch (Exception e)
				{
					System.err.println("testu push failed: " + e);
				}
			}
		}, "testu-push").start();
	}

	/** OAuth2 access token for the service account: RS256 JWT (java.security) traded at the token endpoint. */
	static String fcmBearer(JSONObject sa) throws Exception
	{
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		long now = System.currentTimeMillis() / 1000;
		JSONObject header = new JSONObject();
		header.put("alg", "RS256");
		header.put("typ", "JWT");
		JSONObject claims = new JSONObject();
		claims.put("iss", sa.get("client_email"));
		claims.put("scope", "https://www.googleapis.com/auth/firebase.messaging");
		claims.put("aud", "https://oauth2.googleapis.com/token");
		claims.put("iat", now);
		claims.put("exp", now + 3600);
		String unsigned = b64.encodeToString(header.toJSONString().getBytes(StandardCharsets.UTF_8)) + "." + b64.encodeToString(claims.toJSONString().getBytes(StandardCharsets.UTF_8));
		String pem = String.valueOf(sa.get("private_key")).replaceAll("-----[A-Z ]+-----", "");
		PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pem)));
		Signature sig = Signature.getInstance("SHA256withRSA");
		sig.initSign(key);
		sig.update(unsigned.getBytes(StandardCharsets.UTF_8));
		String jwt = unsigned + "." + b64.encodeToString(sig.sign());
		String[] r = httpPost("https://oauth2.googleapis.com/token", "application/x-www-form-urlencoded", "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt, null);
		JSONObject tok = (JSONObject) JSONValue.parse(r[1]);
		Object access = tok == null ? null : tok.get("access_token");
		if (access == null)
			throw new OpenEditException("no access_token: " + r[0] + " " + r[1]);
		return access.toString();
	}

	/** POST with 5 s timeouts; returns {status, body}. */
	static String[] httpPost(String url, String contentType, String body, String bearer) throws Exception
	{
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(5000);
		c.setReadTimeout(5000);
		c.setDoOutput(true);
		c.setRequestMethod("POST");
		c.setRequestProperty("Content-Type", contentType);
		if (bearer != null)
			c.setRequestProperty("Authorization", "Bearer " + bearer);
		try (OutputStream out = c.getOutputStream())
		{
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int code = c.getResponseCode();
		InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
		String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		return new String[] { String.valueOf(code), text };
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
