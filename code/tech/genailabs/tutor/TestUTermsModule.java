package tech.genailabs.tutor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.entermediadb.asset.MediaArchive;
import org.entermediadb.asset.upload.FileUpload;
import org.entermediadb.asset.upload.FileUploadItem;
import org.entermediadb.asset.upload.UploadRequest;
import org.entermediadb.websocket.usernotify.UserNotifyManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.OpenEditException;
import org.openedit.WebPageRequest;
import org.openedit.data.Searcher;
import org.openedit.hittracker.HitTracker;
import org.openedit.users.User;
import org.openedit.util.DateStorageUtil;

/**
 * Versioned Terms and Conditions. An admin publishes a version (PDF + label + summary, table termsversion; the PDF is the
 * legal record, kept under originals/). Everyone must accept the current version (latest publishedat) before using the apps;
 * each decision is an append-only termsacceptance row. No version published = nothing required.
 */
public class TestUTermsModule extends TestUBaseModule
{
	static final int MAX_PDF_BYTES = 10 * 1024 * 1024;

	/** GET terms.json: {ok, required, current, mine}; ?file=<id> adds data (the PDF as a data URL). */
	public void terms(WebPageRequest inReq)
	{
		User user = inReq.getUser();
		if (user == null)
		{
			fail(inReq, 401, "not signed in");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		JSONObject json = state(archive, user.getId());
		String file = inReq.getRequestParameter("file");
		if (file != null)
		{
			JSONObject current = (JSONObject) json.get("current");
			boolean allowed = current != null && file.equals(current.get("id"))
				|| archive.query("termsacceptance").exact("user", user.getId()).exact("termsversion", file).search().size() > 0;
			File pdf = termsFile(archive, file);
			if (!allowed || !pdf.isFile())
			{
				fail(inReq, 404, "not_found");
				return;
			}
			try
			{
				json.put("data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(pdf.toPath())));
			}
			catch (IOException e)
			{
				throw new OpenEditException(e);
			}
		}
		json.put("ok", Boolean.TRUE);
		reply(inReq, json);
	}

	/** POST termsdecide.json termsversion, decision (accepted|declined), platform, appversion: {ok, required}. */
	public void decide(WebPageRequest inReq)
	{
		User user = inReq.getUser();
		if (user == null)
		{
			fail(inReq, 401, "not signed in");
			return;
		}
		if (!isPost(inReq))
		{
			fail(inReq, 405, "post_only");
			return;
		}
		String decision = inReq.getRequestParameter("decision");
		if (!"accepted".equals(decision) && !"declined".equals(decision))
		{
			fail(inReq, 400, "bad_decision");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		Data current = current(archive);
		if (current == null || !current.getId().equals(inReq.getRequestParameter("termsversion")))
		{
			fail(inReq, 409, "stale");
			return;
		}
		Searcher s = archive.getSearcher("termsacceptance");
		Data row = s.createNewData();
		row.setValue("user", user.getId());
		row.setValue("termsversion", current.getId());
		row.setValue("decision", decision);
		row.setValue("datecreated", new Date());
		row.setValue("filehash", current.get("filehash"));
		row.setValue("platform", clip(inReq.getRequestParameter("platform"), 40));
		row.setValue("appversion", clip(inReq.getRequestParameter("appversion"), 40));
		row.setValue("ipaddress", clientIp(inReq));
		row.setValue("useragent", inReq.getRequest() == null ? null : clip(inReq.getRequest().getHeader("User-Agent"), 500));
		s.saveData(row, user);

		// ponytail: the new row may not be searchable yet (no index refresh), so an accept answers false without re-querying
		boolean required = !"accepted".equals(decision) && (Boolean) state(archive, user.getId()).get("required");
		JSONObject json = new JSONObject();
		json.put("ok", Boolean.TRUE);
		json.put("required", required);
		reply(inReq, json);
	}

	/** POST multipart termspublish.json: file (PDF), version, summary. {ok, current}. */
	public void publish(WebPageRequest inReq)
	{
		if (!isPost(inReq) || inReq.getRequest().getContentType() == null || !inReq.getRequest().getContentType().toLowerCase().startsWith("multipart/"))
		{
			fail(inReq, 400, "bad_file");
			return;
		}
		MediaArchive archive = getMediaArchive(inReq);
		UploadRequest upload = ((FileUpload) archive.getBean("fileUpload")).parseArguments(inReq); // also fills the form fields
		FileUploadItem item = upload.getUploadItemByName("file");
		if (item != null && item.getFileItem().getSize() > MAX_PDF_BYTES)
		{
			fail(inReq, 413, "too_large");
			return;
		}
		byte[] pdf = item == null ? null : item.getFileItem().get();
		String version = trim(inReq.getRequestParameter("version"));
		String summary = trim(inReq.getRequestParameter("summary"));
		Set<String> labels = new HashSet<>();
		for (Data v : versions(archive))
		{
			labels.add(trim(v.get("version")).toLowerCase());
		}
		String error = publishError(pdf, version, summary, labels);
		if (error != null)
		{
			fail(inReq, "too_large".equals(error) ? 413 : "duplicate_version".equals(error) ? 409 : 400, error);
			return;
		}

		Searcher s = archive.getSearcher("termsversion");
		Data v = s.createNewData();
		v.setValue("version", version);
		v.setValue("summary", summary);
		v.setValue("publishedat", new Date());
		v.setValue("publishedby", inReq.getUserName());
		v.setValue("filehash", sha256(pdf));
		v.setValue("filesize", String.valueOf(pdf.length));
		v.setValue("filename", clip(item.getName(), 200));
		s.saveData(v, inReq.getUser());
		File file = termsFile(archive, v.getId());
		try
		{
			file.getParentFile().mkdirs();
			Files.write(file.toPath(), pdf);
		}
		catch (IOException e)
		{
			s.delete(v, inReq.getUser()); // a version without its PDF must never become current
			throw new OpenEditException(e);
		}
		JSONObject current = versionJson(v);
		audit(inReq, archive, "terms.publish", "termsversion", v.getId(), null, current);

		try
		{
			UserNotifyManager notify = (UserNotifyManager) getModuleManager().getBean("userNotifyManager");
			for (String userId : new ArrayList<>(notify.getUserAuthenticatedConnections().keySet()))
			{
				notifyUser(userId, "terms", null);
			}
		}
		catch (Exception e)
		{
			org.apache.commons.logging.LogFactory.getLog(TestUTermsModule.class).error("terms notify", e);
		}

		JSONObject json = new JSONObject();
		json.put("ok", Boolean.TRUE);
		json.put("current", current);
		reply(inReq, json);
	}

	/** GET termsreport.json (after TestUTeamModule.loadScope): {ok, current, versions, counts, rows}. */
	public void report(WebPageRequest inReq)
	{
		MediaArchive archive = getMediaArchive(inReq);
		Set<String> scope = (Set<String>) inReq.getPageValue("scopeteams");
		Map<String, Data> teams = (Map<String, Data>) inReq.getPageValue("allteams");

		List<Data> all = versions(archive);
		Data current = all.isEmpty() ? null : all.get(0);
		JSONArray versions = new JSONArray();
		for (Data v : all)
		{
			JSONObject o = new JSONObject();
			o.put("id", v.getId());
			o.put("version", v.get("version"));
			o.put("summary", v.get("summary"));
			o.put("publishedat", iso(v.getValue("publishedat")));
			User by = v.get("publishedby") == null ? null : archive.getUser(v.get("publishedby"));
			o.put("publishedby", by != null ? by.getScreenName() : v.get("publishedby"));
			o.put("filesize", number(v.get("filesize")));
			versions.add(o);
		}

		Map<String, Data> latest = new HashMap<>(); // user -> their latest row for the current version
		if (current != null)
		{
			HitTracker hits = archive.query("termsacceptance").exact("termsversion", current.getId()).sort("datecreatedDown").search();
			hits.enableBulkOperations();
			for (Object hit : hits)
			{
				Data r = (Data) hit;
				if (r.get("user") != null && !latest.containsKey(r.get("user")))
				{
					latest.put(r.get("user"), r);
				}
			}
		}

		int accepted = 0, declined = 0, pending = 0;
		JSONArray rows = new JSONArray();
		if (current != null)
		{
			HitTracker users = archive.query("user").all().search();
			users.enableBulkOperations();
			for (Object hit : users)
			{
				Data u = (Data) hit;
				String team = u.get("team");
				if ("false".equals(String.valueOf(u.get("enabled"))) || scope != null && !scope.contains(team))
				{
					continue;
				}
				Data r = latest.get(u.getId());
				String decision = r == null ? "pending" : r.get("decision");
				if ("accepted".equals(decision))
					accepted++;
				else if ("declined".equals(decision))
					declined++;
				else
					pending++;
				String name = (trim(u.get("firstName")) + " " + trim(u.get("lastName"))).trim();
				Data t = team == null || teams == null ? null : teams.get(team);
				JSONObject o = new JSONObject();
				o.put("user", u.getId());
				o.put("name", name.isEmpty() ? u.get("email") : name);
				o.put("email", u.get("email"));
				o.put("team", t != null ? t.getName() : null);
				o.put("decision", decision);
				o.put("date", r == null ? null : iso(r.getValue("datecreated")));
				o.put("platform", r == null ? null : r.get("platform"));
				o.put("appversion", r == null ? null : r.get("appversion"));
				rows.add(o);
			}
		}
		JSONObject counts = new JSONObject();
		counts.put("accepted", accepted);
		counts.put("declined", declined);
		counts.put("pending", pending);

		JSONObject json = new JSONObject();
		json.put("ok", Boolean.TRUE);
		json.put("current", versionJson(current));
		json.put("versions", versions);
		json.put("counts", counts);
		json.put("rows", rows);
		reply(inReq, json);
	}

	/** {required, current, mine} for one user; me.json and terms.json share it. */
	static JSONObject state(MediaArchive inArchive, String inUserId)
	{
		Data current = current(inArchive);
		List<String[]> decisions = new ArrayList<>(); // {termsversion, decision, date}, newest first
		for (Object hit : inArchive.query("termsacceptance").exact("user", inUserId).sort("datecreatedDown").search())
		{
			Data r = (Data) hit;
			decisions.add(new String[] {r.get("termsversion"), r.get("decision"), iso(r.getValue("datecreated"))});
		}
		JSONObject mine = null;
		if (!decisions.isEmpty())
		{
			mine = new JSONObject();
			mine.put("termsversion", decisions.get(0)[0]);
			mine.put("decision", decisions.get(0)[1]);
			mine.put("date", decisions.get(0)[2]);
		}
		JSONObject json = new JSONObject();
		json.put("required", required(current == null ? null : current.getId(), decisions));
		json.put("current", versionJson(current));
		json.put("mine", mine);
		return json;
	}

	/** Required = a version is current and this user has no accepted row for it (older accepts and declines don't count). */
	static boolean required(String inCurrentId, Collection<String[]> inDecisions)
	{
		if (inCurrentId == null)
		{
			return false;
		}
		for (String[] d : inDecisions)
		{
			if (inCurrentId.equals(d[0]) && "accepted".equals(d[1]))
			{
				return false;
			}
		}
		return true;
	}

	/** null when publishable, else the error code. inLabels: existing version labels, lower-cased. */
	static String publishError(byte[] inPdf, String inVersion, String inSummary, Set<String> inLabels)
	{
		if (inPdf == null || inPdf.length < 5 || !"%PDF-".equals(new String(inPdf, 0, 5, StandardCharsets.ISO_8859_1)))
		{
			return "bad_file";
		}
		if (inPdf.length > MAX_PDF_BYTES)
		{
			return "too_large";
		}
		if (inVersion.isEmpty() || inVersion.length() > 60)
		{
			return "bad_version";
		}
		if (inSummary.length() > 1000)
		{
			return "bad_summary";
		}
		if (inLabels.contains(inVersion.toLowerCase()))
		{
			return "duplicate_version";
		}
		return null;
	}

	static Data current(MediaArchive inArchive)
	{
		return (Data) inArchive.query("termsversion").all().sort("publishedatDown").search().first();
	}

	static List<Data> versions(MediaArchive inArchive)
	{
		List<Data> out = new ArrayList<>();
		for (Object hit : inArchive.query("termsversion").all().sort("publishedatDown").search())
		{
			out.add((Data) hit);
		}
		return out;
	}

	static JSONObject versionJson(Data v)
	{
		if (v == null)
		{
			return null;
		}
		JSONObject o = new JSONObject();
		o.put("id", v.getId());
		o.put("version", v.get("version"));
		o.put("summary", v.get("summary"));
		o.put("publishedat", iso(v.getValue("publishedat")));
		o.put("filehash", v.get("filehash"));
		o.put("filesize", number(v.get("filesize")));
		o.put("filename", v.get("filename"));
		return o;
	}

	/** Under originals/ (not served, not in git), like avatars. */
	static File termsFile(MediaArchive inArchive, String inVersionId)
	{
		String path = "/WEB-INF/data/" + inArchive.getCatalogId() + "/originals/testu/terms/" + inVersionId.replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf";
		return new File(inArchive.getPageManager().getRepository().getStub(path).getAbsolutePath());
	}

	static String iso(Object inDate)
	{
		Date d = DateStorageUtil.getStorageUtil().parseFromObject(inDate);
		return d == null ? null : d.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
	}

	static Long number(String s)
	{
		try
		{
			return s == null ? null : Long.valueOf(s);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static String sha256(byte[] inBytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inBytes));
		}
		catch (Exception e)
		{
			throw new OpenEditException(e);
		}
	}

	static String trim(String s)
	{
		return s == null ? "" : s.trim();
	}

	static String clip(String s, int max)
	{
		return s == null ? null : s.length() > max ? s.substring(0, max) : s;
	}

	static boolean isPost(WebPageRequest inReq)
	{
		return inReq.getRequest() != null && "POST".equalsIgnoreCase(inReq.getRequest().getMethod());
	}

	static String clientIp(WebPageRequest inReq)
	{
		if (inReq.getRequest() == null)
		{
			return null;
		}
		String fwd = inReq.getRequest().getHeader("X-Forwarded-For");
		if (fwd != null && !fwd.isBlank())
		{
			return clip(fwd.split(",")[0].trim(), 64);
		}
		return inReq.getRequest().getRemoteAddr();
	}

	/** Self-check: java -cp build:<libs> tech.genailabs.tutor.TestUTermsModule */
	public static void main(String[] args)
	{
		check(!required(null, List.of()), "no version published");
		check(!required(null, List.<String[]>of(new String[] {"v1", "declined"})), "no version, stray rows");
		check(!required("v2", List.<String[]>of(new String[] {"v2", "accepted"})), "accepted current");
		check(required("v2", List.<String[]>of(new String[] {"v1", "accepted"})), "accepted only older");
		check(required("v2", List.<String[]>of(new String[] {"v2", "declined"}, new String[] {"v1", "accepted"})), "declined current");
		check(required("v2", List.of()), "never decided");

		byte[] pdf = "%PDF-1.7 ...".getBytes(StandardCharsets.ISO_8859_1);
		Set<String> labels = Set.of("2026-01");
		check(publishError(pdf, "2026-09", "", labels) == null, "valid");
		check("bad_file".equals(publishError(null, "2026-09", "", labels)), "no file");
		check("bad_file".equals(publishError("%PDF".getBytes(), "2026-09", "", labels)), "short");
		check("bad_file".equals(publishError("<html>".getBytes(), "2026-09", "", labels)), "not pdf");
		byte[] big = new byte[MAX_PDF_BYTES + 1];
		System.arraycopy(pdf, 0, big, 0, pdf.length);
		check("too_large".equals(publishError(big, "2026-09", "", labels)), "too large");
		check("bad_version".equals(publishError(pdf, "", "", labels)), "empty version");
		check("bad_version".equals(publishError(pdf, "x".repeat(61), "", labels)), "long version");
		check("bad_summary".equals(publishError(pdf, "2026-09", "x".repeat(1001), labels)), "long summary");
		check("duplicate_version".equals(publishError(pdf, "2026-01", "", labels)), "duplicate");
		check(sha256(new byte[0]).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), "sha-256 hex");
		System.out.println("TestUTermsModule ok");
	}

	private static void check(boolean ok, String what)
	{
		if (!ok)
			throw new AssertionError(what);
	}
}
