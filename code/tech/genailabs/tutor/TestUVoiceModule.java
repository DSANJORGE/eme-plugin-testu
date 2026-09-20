package tech.genailabs.tutor;

import java.util.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.entermediadb.ai.llm.LlmConnection;
import org.entermediadb.asset.MediaArchive;
import org.json.simple.JSONObject;
import org.openedit.Data;
import org.openedit.WebPageRequest;
import org.openedit.users.User;
import org.openedit.util.HttpSharedConnection;

/**
 * The tutor's spoken voice. The app posts the reply it is about to say and gets the audio back; the provider and its key
 * live in the `aiserver` record with aiservertype "speak", never in the app, so a client can never spend our credits
 * directly and a new org can pick its own voice without a release.
 *
 * Unconfigured or failing provider answers 503, which the app reads as "speak with the device voice instead" — voice mode
 * keeps working, it just sounds worse.
 */
public class TestUVoiceModule extends TestUBaseModule
{
	private static final Log log = LogFactory.getLog(TestUVoiceModule.class);

	/** Longest reply we pay to speak. Spoken answers are 2-3 sentences; anything longer is a prompt bug, not a tutor turn. */
	static final int MAX_CHARS = 600;

	/** Voices are per org, so a missing one is a setup mistake; this keeps the pilot talking until someone fixes it. */
	static final String DEFAULT_VOICE = "5vkxOzoz40FrElmLP4P7"; // Gaby, es-PE

	/** POST speak.json {text} -> {ok, audio (base64 mp3), mime}. */
	public void speak(WebPageRequest inReq)
	{
		User user = inReq.getUser();
		if (user == null)
		{
			fail(inReq, 401, "not signed in");
			return;
		}
		String text = inReq.getRequestParameter("text");
		if (text == null || text.trim().isEmpty())
		{
			fail(inReq, 400, "no text");
			return;
		}
		text = clip(text.trim());
		MediaArchive archive = getMediaArchive(inReq);
		LlmConnection connection = archive.getLlmConnection("speak");
		if (connection == null || connection.getApiKey() == null || connection.getApiKey().isEmpty())
		{
			fail(inReq, 503, "no voice configured");
			return;
		}
		byte[] audio = render(connection, text);
		if (audio == null)
		{
			fail(inReq, 503, "voice unavailable");
			return;
		}
		JSONObject json = new JSONObject();
		json.put("ok", Boolean.TRUE);
		json.put("mime", "audio/mpeg");
		json.put("audio", Base64.getEncoder().encodeToString(audio));
		reply(inReq, json);
	}

	/**
	 * Caps what we pay to speak, at the last sentence that fits rather than mid-word, so an over-long reply still ends
	 * like a sentence. No sentence end in the first MAX_CHARS (a wall of text) falls back to a hard cut.
	 */
	protected String clip(String inText)
	{
		if (inText.length() <= MAX_CHARS)
		{
			return inText;
		}
		String head = inText.substring(0, MAX_CHARS);
		int cut = -1;
		for (int i = 0; i < head.length(); i++)
		{
			char c = head.charAt(i);
			if (c == '.' || c == '!' || c == '?' || c == '…')
			{
				cut = i;
			}
		}
		return cut > 0 ? head.substring(0, cut + 1) : head;
	}

	/**
	 * ElevenLabs text-to-speech, whole reply in one call. Returns null when the provider says no, so the caller can fall
	 * back rather than fail the turn.
	 *
	 * ponytail: the whole clip is bought, held and base64'd before a word plays. Fine for 2-3 sentences (~1s on Flash);
	 * stream the response through to the app if longer answers ever get spoken.
	 */
	protected byte[] render(LlmConnection inConnection, String inText)
	{
		Data server = inConnection.getAiServerData();
		String voice = server.get("voiceid");
		if (voice == null || voice.isEmpty())
		{
			voice = DEFAULT_VOICE;
		}
		String model = inConnection.getModelName();
		if (model == null || model.isEmpty())
		{
			model = "eleven_flash_v2_5";
		}
		JSONObject body = new JSONObject();
		body.put("text", inText);
		body.put("model_id", model);

		// 64kbps mono is the cheapest tier that still sounds clean on a phone speaker.
		HttpPost post = new HttpPost(inConnection.getServerRoot() + "/text-to-speech/" + voice + "?output_format=mp3_44100_64");
		post.addHeader("xi-api-key", inConnection.getApiKey());
		post.addHeader("Content-Type", "application/json");
		post.setEntity(new StringEntity(body.toJSONString(), "UTF-8"));

		HttpSharedConnection http = new HttpSharedConnection();
		CloseableHttpResponse response = null;
		try
		{
			response = http.sharedPost(post);
			int status = response.getStatusLine().getStatusCode();
			if (status != 200)
			{
				log.error("speak: provider returned " + status + " " + EntityUtils.toString(response.getEntity()));
				return null;
			}
			return EntityUtils.toByteArray(response.getEntity());
		}
		catch (Exception ex)
		{
			log.error("speak: could not reach the voice provider", ex);
			return null;
		}
		finally
		{
			http.release(response);
		}
	}
}
