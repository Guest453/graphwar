//  Pollinations AI integration for Graphwar.
//
//  This file is part of Graphwar.
//
//  Graphwar is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  Graphwar is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.

//  You should have received a copy of the GNU General Public License
//  along with Graphwar.  If not, see <http://www.gnu.org/licenses/>.

package Graphwar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Semaphore;

/**
 * Minimal client for the Pollinations text API, which speaks the OpenAI chat
 * completion dialect at https://text.pollinations.ai/openai .
 *
 * The API key is optional: without one the request is served on the anonymous
 * tier, which only exposes a small model. A key unlocks the rest and is read
 * from, in order of precedence:
 *
 *   1. the system property  graphwar.pollinations.key
 *   2. the environment      POLLINATIONS_API_KEY / POLLINATIONS_TOKEN
 *   3. the properties file  ~/.graphwar/pollinations.properties
 *
 * The properties file also accepts "model", "referrer" and "endpoint" keys, so
 * a whole bot setup can live outside the source tree.
 *
 * Deliberately written against HttpURLConnection and without a JSON library so
 * that the game keeps building with nothing but javac and jar.
 */
public class PollinationsClient
{
	public static final String DEFAULT_ENDPOINT = "https://text.pollinations.ai/openai";
	public static final String DEFAULT_MODEL = "openai";

	private static final String CONFIG_DIR = ".graphwar";
	private static final String CONFIG_FILE = "pollinations.properties";

	private static Properties config = null;

	/**
	 * The free tier accepts one request per IP at a time and answers the rest
	 * with 429, so a game with several AI bots would starve itself. Unkeyed
	 * requests queue here instead of racing.
	 */
	private static final Semaphore FREE_TIER_GATE = new Semaphore(1, true);

	private final String endpoint;
	private final String model;
	private final String apiKey;
	private final String referrer;

	public PollinationsClient(String model)
	{
		Properties props = getConfig();

		this.endpoint = firstNonEmpty(props.getProperty("endpoint"), DEFAULT_ENDPOINT);
		this.apiKey = resolveApiKey(props);
		// Left null on purpose. Sending an app referrer gets the request
		// attributed to that app's account, and an unrecognised one lands on a
		// zero budget key, which the API answers with 402.
		this.referrer = props.getProperty("referrer");

		String chosen = firstNonEmpty(model, props.getProperty("model"));
		this.model = firstNonEmpty(chosen, DEFAULT_MODEL);
	}

	public String getModel()
	{
		return model;
	}

	public boolean hasApiKey()
	{
		return apiKey != null && apiKey.length() > 0;
	}

	/**
	 * Sends a single system+user exchange and returns the assistant's reply.
	 * Blocks for at most timeoutMs, and throws on anything that is not a clean
	 * answer so the caller can fall back to another strategy.
	 */
	public String chat(String systemPrompt, String userPrompt, double temperature, int timeoutMs) throws IOException
	{
		IOException last = null;

		// The free tier fails intermittently with 402 and 429, so a couple of
		// quick retries turn a lost turn into a slightly slower one.
		for(int attempt = 0; attempt < 3; attempt++)
		{
			if(attempt > 0)
			{
				try
				{
					Thread.sleep(1200L * attempt);
				}
				catch(InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}

			try
			{
				return send(systemPrompt, userPrompt, temperature, timeoutMs);
			}
			catch(IOException e)
			{
				last = e;

				if(!isWorthRetrying(e))
				{
					throw e;
				}
			}
		}

		throw last != null ? last : new IOException("pollinations request failed");
	}

	private static boolean isWorthRetrying(IOException e)
	{
		String message = e.getMessage();

		if(message == null)
		{
			return true;
		}

		return message.indexOf("http 402") >= 0 || message.indexOf("http 429") >= 0
				|| message.indexOf("http 5") >= 0 || message.indexOf("timed out") >= 0;
	}

	private String send(String systemPrompt, String userPrompt, double temperature, int timeoutMs) throws IOException
	{
		if(hasApiKey())
		{
			return doSend(systemPrompt, userPrompt, temperature, timeoutMs);
		}

		FREE_TIER_GATE.acquireUninterruptibly();

		try
		{
			return doSend(systemPrompt, userPrompt, temperature, timeoutMs);
		}
		finally
		{
			FREE_TIER_GATE.release();
		}
	}

	private String doSend(String systemPrompt, String userPrompt, double temperature, int timeoutMs) throws IOException
	{
		StringBuilder body = new StringBuilder();
		body.append("{\"model\":").append(Json.quote(model));

		// The free anonymous tier only serves the plainest possible request.
		// Asking for a temperature, or using a separate system role, is treated
		// as a paid feature and comes back as 402 with no key to bill. With a
		// key we send the real thing; without one we fold the character and the
		// rules into the single user message instead of losing them.
		boolean full = hasApiKey();

		if(full)
		{
			body.append(",\"temperature\":").append(temperature);
		}

		if(referrer != null && referrer.trim().length() > 0)
		{
			body.append(",\"referrer\":").append(Json.quote(referrer.trim()));
		}

		body.append(",\"messages\":[");

		if(full)
		{
			body.append("{\"role\":\"system\",\"content\":").append(Json.quote(systemPrompt)).append("},");
			body.append("{\"role\":\"user\",\"content\":").append(Json.quote(userPrompt)).append("}");
		}
		else
		{
			body.append("{\"role\":\"user\",\"content\":").append(Json.quote(systemPrompt + "\n\n" + userPrompt)).append("}");
		}

		body.append("]}");

		byte[] payload = body.toString().getBytes("UTF-8");

		HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setConnectTimeout(timeoutMs);
		connection.setReadTimeout(timeoutMs);
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("User-Agent", "Graphwar-Pollinations/1.0");

		if(hasApiKey())
		{
			connection.setRequestProperty("Authorization", "Bearer " + apiKey);
		}

		OutputStream out = connection.getOutputStream();
		try
		{
			out.write(payload);
			out.flush();
		}
		finally
		{
			out.close();
		}

		int status = connection.getResponseCode();
		String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());

		if(status < 200 || status >= 300)
		{
			throw new IOException("pollinations http " + status + ": " + trim(response, 200));
		}

		String content = extractContent(response);

		if(content == null)
		{
			throw new IOException("pollinations response had no content: " + trim(response, 200));
		}

		return content;
	}

	/** Digs choices[0].message.content out of an OpenAI style response. */
	private static String extractContent(String response)
	{
		Object parsed = Json.parse(response);

		if(!(parsed instanceof Map))
		{
			return null;
		}

		Object choices = ((Map<?, ?>) parsed).get("choices");

		if(!(choices instanceof List) || ((List<?>) choices).isEmpty())
		{
			return null;
		}

		Object first = ((List<?>) choices).get(0);

		if(!(first instanceof Map))
		{
			return null;
		}

		Object message = ((Map<?, ?>) first).get("message");

		if(!(message instanceof Map))
		{
			return null;
		}

		Object content = ((Map<?, ?>) message).get("content");

		if(content instanceof String)
		{
			return (String) content;
		}

		return null;
	}

	private static String resolveApiKey(Properties props)
	{
		String key = System.getProperty("graphwar.pollinations.key");

		if(isEmpty(key))
		{
			key = System.getenv("POLLINATIONS_API_KEY");
		}
		if(isEmpty(key))
		{
			key = System.getenv("POLLINATIONS_TOKEN");
		}
		if(isEmpty(key))
		{
			key = props.getProperty("api_key");
		}
		if(isEmpty(key))
		{
			key = props.getProperty("token");
		}

		return isEmpty(key) ? null : key.trim();
	}

	/** Where the key and model settings are kept between sessions. */
	public static File getConfigFile()
	{
		return new File(new File(System.getProperty("user.home", "."), CONFIG_DIR), CONFIG_FILE);
	}

	/** True when a key has been configured anywhere we look for one. */
	public static synchronized boolean isApiKeyConfigured()
	{
		return resolveApiKey(getConfig()) != null;
	}

	/**
	 * True once the player has been asked for a key, whether or not they gave
	 * one, so the game does not nag on every bot.
	 */
	public static synchronized boolean wasApiKeyRequested()
	{
		return isApiKeyConfigured() || "true".equalsIgnoreCase(getConfig().getProperty("prompted"));
	}

	/**
	 * Stores the key for next time. An empty key just records that the question
	 * was asked, which is how the anonymous tier is chosen deliberately.
	 */
	public static synchronized void saveApiKey(String key) throws IOException
	{
		Properties props = getConfig();

		if(isEmpty(key))
		{
			props.remove("api_key");
		}
		else
		{
			props.setProperty("api_key", key.trim());
		}

		props.setProperty("prompted", "true");

		File file = getConfigFile();
		File directory = file.getParentFile();

		if(directory != null && !directory.isDirectory() && !directory.mkdirs())
		{
			throw new IOException("could not create " + directory);
		}

		OutputStream out = new FileOutputStream(file);

		try
		{
			props.store(out, "Graphwar Pollinations settings");
		}
		finally
		{
			out.close();
		}

		// An API key is a secret, so do not leave it world readable.
		try
		{
			file.setReadable(false, false);
			file.setReadable(true, true);
			file.setWritable(false, false);
			file.setWritable(true, true);
		}
		catch(SecurityException e)
		{
			// Best effort: some platforms will not allow this.
		}
	}

	private static synchronized Properties getConfig()
	{
		if(config != null)
		{
			return config;
		}

		config = new Properties();

		File file = new File(new File(System.getProperty("user.home", "."), CONFIG_DIR), CONFIG_FILE);

		if(file.isFile())
		{
			InputStream in = null;
			try
			{
				in = new FileInputStream(file);
				config.load(in);
			}
			catch(IOException e)
			{
				System.err.println("could not read " + file + ": " + e.getMessage());
			}
			finally
			{
				if(in != null)
				{
					try
					{
						in.close();
					}
					catch(IOException e)
					{
						// nothing useful to do here
					}
				}
			}
		}

		return config;
	}

	private static String readAll(InputStream in) throws IOException
	{
		if(in == null)
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

		try
		{
			String line;
			while((line = reader.readLine()) != null)
			{
				sb.append(line).append('\n');
			}
		}
		finally
		{
			reader.close();
		}

		return sb.toString();
	}

	private static boolean isEmpty(String s)
	{
		return s == null || s.trim().length() == 0;
	}

	private static String firstNonEmpty(String a, String b)
	{
		return isEmpty(a) ? (isEmpty(b) ? null : b.trim()) : a.trim();
	}

	private static String trim(String s, int max)
	{
		if(s == null)
		{
			return "";
		}

		s = s.replace('\n', ' ').trim();

		return s.length() <= max ? s : s.substring(0, max) + "...";
	}


	/**
	 * A very small JSON reader. It only needs to handle what the API returns,
	 * but it handles it properly: escapes, unicode and nesting included.
	 */
	static class Json
	{
		private final String src;
		private int pos;

		private Json(String src)
		{
			this.src = src;
			this.pos = 0;
		}

		static Object parse(String text)
		{
			if(text == null)
			{
				return null;
			}

			try
			{
				Json json = new Json(text);
				json.skipWhitespace();
				Object value = json.readValue();
				return value;
			}
			catch(RuntimeException e)
			{
				return null;
			}
		}

		static String quote(String value)
		{
			if(value == null)
			{
				return "null";
			}

			StringBuilder sb = new StringBuilder(value.length() + 16);
			sb.append('"');

			for(int i = 0; i < value.length(); i++)
			{
				char c = value.charAt(i);

				switch(c)
				{
					case '"':
						sb.append("\\\"");
						break;
					case '\\':
						sb.append("\\\\");
						break;
					case '\n':
						sb.append("\\n");
						break;
					case '\r':
						sb.append("\\r");
						break;
					case '\t':
						sb.append("\\t");
						break;
					case '\b':
						sb.append("\\b");
						break;
					case '\f':
						sb.append("\\f");
						break;
					default:
						if(c < 0x20)
						{
							sb.append(String.format("\\u%04x", (int) c));
						}
						else
						{
							sb.append(c);
						}
				}
			}

			sb.append('"');

			return sb.toString();
		}

		private Object readValue()
		{
			skipWhitespace();

			if(pos >= src.length())
			{
				throw new RuntimeException("unexpected end of json");
			}

			char c = src.charAt(pos);

			switch(c)
			{
				case '{':
					return readObject();
				case '[':
					return readArray();
				case '"':
					return readString();
				case 't':
					expect("true");
					return Boolean.TRUE;
				case 'f':
					expect("false");
					return Boolean.FALSE;
				case 'n':
					expect("null");
					return null;
				default:
					return readNumber();
			}
		}

		private Map<String, Object> readObject()
		{
			Map<String, Object> map = new HashMap<String, Object>();

			pos++;	// consume {
			skipWhitespace();

			if(pos < src.length() && src.charAt(pos) == '}')
			{
				pos++;
				return map;
			}

			while(true)
			{
				skipWhitespace();
				String key = readString();
				skipWhitespace();

				if(pos >= src.length() || src.charAt(pos) != ':')
				{
					throw new RuntimeException("expected :");
				}

				pos++;
				map.put(key, readValue());
				skipWhitespace();

				if(pos >= src.length())
				{
					throw new RuntimeException("unterminated object");
				}

				char c = src.charAt(pos++);

				if(c == '}')
				{
					return map;
				}
				if(c != ',')
				{
					throw new RuntimeException("expected , or }");
				}
			}
		}

		private List<Object> readArray()
		{
			List<Object> list = new ArrayList<Object>();

			pos++;	// consume [
			skipWhitespace();

			if(pos < src.length() && src.charAt(pos) == ']')
			{
				pos++;
				return list;
			}

			while(true)
			{
				list.add(readValue());
				skipWhitespace();

				if(pos >= src.length())
				{
					throw new RuntimeException("unterminated array");
				}

				char c = src.charAt(pos++);

				if(c == ']')
				{
					return list;
				}
				if(c != ',')
				{
					throw new RuntimeException("expected , or ]");
				}
			}
		}

		private String readString()
		{
			if(pos >= src.length() || src.charAt(pos) != '"')
			{
				throw new RuntimeException("expected string");
			}

			pos++;	// consume opening quote

			StringBuilder sb = new StringBuilder();

			while(true)
			{
				if(pos >= src.length())
				{
					throw new RuntimeException("unterminated string");
				}

				char c = src.charAt(pos++);

				if(c == '"')
				{
					return sb.toString();
				}

				if(c != '\\')
				{
					sb.append(c);
					continue;
				}

				char escape = src.charAt(pos++);

				switch(escape)
				{
					case '"':
						sb.append('"');
						break;
					case '\\':
						sb.append('\\');
						break;
					case '/':
						sb.append('/');
						break;
					case 'n':
						sb.append('\n');
						break;
					case 'r':
						sb.append('\r');
						break;
					case 't':
						sb.append('\t');
						break;
					case 'b':
						sb.append('\b');
						break;
					case 'f':
						sb.append('\f');
						break;
					case 'u':
						sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
						pos += 4;
						break;
					default:
						throw new RuntimeException("bad escape \\" + escape);
				}
			}
		}

		private Double readNumber()
		{
			int start = pos;

			while(pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0)
			{
				pos++;
			}

			if(start == pos)
			{
				throw new RuntimeException("expected number at " + pos);
			}

			return Double.valueOf(src.substring(start, pos));
		}

		private void expect(String literal)
		{
			if(!src.startsWith(literal, pos))
			{
				throw new RuntimeException("expected " + literal);
			}

			pos += literal.length();
		}

		private void skipWhitespace()
		{
			while(pos < src.length() && Character.isWhitespace(src.charAt(pos)))
			{
				pos++;
			}
		}
	}
}
