package com.crawlmb.recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON parser (objects -> LinkedHashMap, arrays -> ArrayList,
 * numbers -> Long or Double, true/false/null). Pure Java so snapshot handling
 * is unit-testable on the JVM without org.json. Depth-limited for untrusted
 * input.
 */
public final class MiniJson
{
	private static final int MAX_DEPTH = 64;

	private final String s;
	private int pos;

	private MiniJson(String s) { this.s = s; }

	public static Object parse(String json)
	{
		MiniJson p = new MiniJson(json);
		p.ws();
		Object v = p.value(0);
		p.ws();
		if (p.pos != p.s.length())
			throw p.err("trailing data");
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String json)
	{
		Object v = parse(json);
		if (!(v instanceof Map))
			throw new ByteIO.FormatException("expected JSON object");
		return (Map<String, Object>) v;
	}

	// ---- typed accessors (lenient: missing/wrong type -> default) ----

	public static long getLong(Map<String, Object> m, String k, long def)
	{
		Object v = m == null ? null : m.get(k);
		return v instanceof Number ? ((Number) v).longValue() : def;
	}

	public static String getString(Map<String, Object> m, String k, String def)
	{
		Object v = m == null ? null : m.get(k);
		return v instanceof String ? (String) v : def;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> getObject(Map<String, Object> m, String k)
	{
		Object v = m == null ? null : m.get(k);
		return v instanceof Map ? (Map<String, Object>) v : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> getArray(Map<String, Object> m, String k)
	{
		Object v = m == null ? null : m.get(k);
		return v instanceof List ? (List<Object>) v : null;
	}

	// ---- parser ----

	private Object value(int depth)
	{
		if (depth > MAX_DEPTH)
			throw err("nesting too deep");
		if (pos >= s.length())
			throw err("unexpected end");
		char c = s.charAt(pos);
		switch (c)
		{
		case '{': return object(depth);
		case '[': return array(depth);
		case '"': return string();
		case 't': lit("true"); return Boolean.TRUE;
		case 'f': lit("false"); return Boolean.FALSE;
		case 'n': lit("null"); return null;
		default:
			if (c == '-' || (c >= '0' && c <= '9'))
				return number();
			throw err("unexpected character '" + c + "'");
		}
	}

	private Map<String, Object> object(int depth)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		pos++; // {
		ws();
		if (peek() == '}') { pos++; return m; }
		while (true)
		{
			ws();
			if (peek() != '"')
				throw err("expected key");
			String k = string();
			ws();
			expect(':');
			ws();
			m.put(k, value(depth + 1));
			ws();
			char c = next();
			if (c == '}') return m;
			if (c != ',') throw err("expected , or }");
		}
	}

	private List<Object> array(int depth)
	{
		List<Object> a = new ArrayList<>();
		pos++; // [
		ws();
		if (peek() == ']') { pos++; return a; }
		while (true)
		{
			ws();
			a.add(value(depth + 1));
			ws();
			char c = next();
			if (c == ']') return a;
			if (c != ',') throw err("expected , or ]");
		}
	}

	private String string()
	{
		pos++; // "
		StringBuilder b = new StringBuilder();
		while (true)
		{
			char c = next();
			if (c == '"')
				return b.toString();
			if (c != '\\')
			{
				b.append(c);
				continue;
			}
			char e = next();
			switch (e)
			{
			case '"': b.append('"'); break;
			case '\\': b.append('\\'); break;
			case '/': b.append('/'); break;
			case 'b': b.append('\b'); break;
			case 'f': b.append('\f'); break;
			case 'n': b.append('\n'); break;
			case 'r': b.append('\r'); break;
			case 't': b.append('\t'); break;
			case 'u':
				if (pos + 4 > s.length())
					throw err("bad \\u escape");
				try
				{
					b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
				}
				catch (NumberFormatException ex)
				{
					throw err("bad \\u escape");
				}
				pos += 4;
				break;
			default:
				throw err("bad escape");
			}
		}
	}

	private Object number()
	{
		int start = pos;
		if (peek() == '-') pos++;
		while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0)
			pos++;
		String t = s.substring(start, pos);
		try
		{
			if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0)
				return Long.parseLong(t);
			return Double.parseDouble(t);
		}
		catch (NumberFormatException ex)
		{
			throw err("bad number " + t);
		}
	}

	private void lit(String w)
	{
		if (!s.startsWith(w, pos))
			throw err("expected " + w);
		pos += w.length();
	}

	private void ws()
	{
		while (pos < s.length())
		{
			char c = s.charAt(pos);
			if (c != ' ' && c != '\n' && c != '\r' && c != '\t')
				break;
			pos++;
		}
	}

	private char peek()
	{
		if (pos >= s.length()) throw err("unexpected end");
		return s.charAt(pos);
	}

	private char next()
	{
		if (pos >= s.length()) throw err("unexpected end");
		return s.charAt(pos++);
	}

	private void expect(char c)
	{
		if (next() != c) throw err("expected " + c);
	}

	private ByteIO.FormatException err(String m)
	{
		return new ByteIO.FormatException("JSON: " + m + " at " + pos);
	}
}
