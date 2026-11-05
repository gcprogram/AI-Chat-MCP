"""MCP-Server Wetter (stdio, nur Standardbibliothek, ohne API-Schlüssel). Beschreibungen und Ausgabe englisch.

forecast   Vorhersage für heute/morgen/übermorgen in Stundenschritten (wttr.in)
historical Wetter an einem vergangenen Tag (Open-Meteo-Archiv)

Kleine Modelle rechnen nicht zuverlässig mit Daten: 'historical' versteht deshalb 'yesterday', 'the day before
yesterday', '2020-11-25', '25.11.2020' und '25.11.' selbst (deutsche Wörter wie 'gestern' gehen auch)."""
import json
import re
from datetime import date, datetime, timedelta
from urllib.parse import quote

from mini_mcp import ToolError, fetch, fetch_json, serve, tool

# Klare Anweisung an das Modell, sonst deutet es den Fehler als "kein Zugriff auf Wetterdaten"
NOT_FOUND = ("Place '%s' not found. Tell the user that this place was not found and ask them to check the spelling. "
             "Do not call the tool again.")

DIRECTIONS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
DAYS = {"today": 0, "heute": 0, "tomorrow": 1, "morgen": 1, "day after tomorrow": 2, "uebermorgen": 2,
        "übermorgen": 2}
YESTERDAY = ("yesterday", "gestern")
TODAY = ("today", "heute")
DAY_BEFORE_YESTERDAY = ("day before yesterday", "vorgestern")
WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
WMO = {0: "clear", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "fog", 48: "rime fog",
       51: "light drizzle", 53: "drizzle", 55: "heavy drizzle", 56: "freezing drizzle", 57: "freezing drizzle",
       61: "light rain", 63: "rain", 65: "heavy rain", 66: "freezing rain", 67: "freezing rain",
       71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains", 80: "light rain showers",
       81: "rain showers", 82: "violent rain showers", 85: "light snow showers", 86: "snow showers",
       95: "thunderstorm", 96: "thunderstorm with hail", 99: "thunderstorm with hail"}


def compass(degrees):
    return DIRECTIONS[round(float(degrees) / 22.5) % 16]


def parse_date(text, today=None):
    """'yesterday', 'day before yesterday', 'today', '2020-11-25', '25.11.2020', '25.11.20', '25.11.' -> date."""
    today = today or date.today()
    word = " ".join(str(text or "").strip().lower().rstrip(".").split())
    if word == "" or word in YESTERDAY:
        return today - timedelta(days=1)
    if word in TODAY:
        return today
    if word in DAY_BEFORE_YESTERDAY:
        return today - timedelta(days=2)
    match = re.fullmatch(r"(\d{4})-(\d{1,2})-(\d{1,2})", word)
    if match:
        year, month, day = map(int, match.groups())
    else:
        match = re.fullmatch(r"(\d{1,2})\.\s*(\d{1,2})\.?\s*(\d{2,4})?", str(text).strip().lower())
        if not match:
            raise ToolError(f"Date '{text}' not understood. Allowed: yesterday, day before yesterday, 2020-11-25, 25.11.2020.")
        day, month = int(match[1]), int(match[2])
        year = int(match[3]) if match[3] else today.year
        if year < 100:
            year += 2000
        try:
            if not match[3] and date(year, month, day) > today:
                year -= 1  # '25.11.' ohne Jahr meint das letzte Mal, nicht die Zukunft
        except ValueError:
            pass  # unten als ungültiges Datum gemeldet
    try:
        return date(year, month, day)
    except ValueError as exc:
        raise ToolError(f"Date '{text}' does not exist.") from exc


def long_date(day):
    return f"{WEEKDAYS[day.weekday()]}, {day.isoformat()}"


def forecast_text(data, day_index, start, end, step):
    areas = (data.get("nearest_area") or [{}])[0]
    parts = [v[0]["value"] for v in (areas.get("areaName"), areas.get("region"), areas.get("country"))
             if v and v[0].get("value")]
    place = ", ".join(dict.fromkeys(parts))  # "Berlin, Berlin, Germany" -> "Berlin, Germany"
    days = data.get("weather") or []
    if day_index >= len(days):
        raise ToolError("There is no forecast for that day.")
    day = days[day_index]
    lines = [f"Weather for {place}, {long_date(datetime.strptime(day['date'], '%Y-%m-%d').date())}: "
             f"{day['mintempC']} to {day['maxtempC']}°C"]
    for hour in day.get("hourly", []):
        clock = int(hour["time"]) // 100
        if not start <= clock < end or (clock - start) % step:
            continue
        desc = (hour.get("weatherDesc") or [{"value": "?"}])[0]["value"].strip()
        lines.append(f"{clock:02d}:00 {desc}, {hour['tempC']}°C (feels like {hour['FeelsLikeC']}°C), "
                     f"rain {hour['chanceofrain']}%, wind {compass(hour['winddirDegree'])} {hour['windspeedKmph']} km/h")
    if len(lines) == 1:
        raise ToolError("There is no data for the requested hours.")
    return "\n".join(lines)


def historical_text(place, day, daily):
    def value(key):
        return (daily.get(key) or [None])[0]

    if value("temperature_2m_max") is None:
        raise ToolError(f"No data for {day.isoformat()} yet (the archive lags a few days behind).")
    code = value("weathercode")
    wind = value("winddirection_10m_dominant")
    return "\n".join([
        f"Weather in {place} on {long_date(day)}: {WMO.get(code, 'unknown')}",
        f"High {value('temperature_2m_max')}°C, low {value('temperature_2m_min')}°C",
        f"Precipitation {value('precipitation_sum')} mm",
        f"Wind up to {value('windspeed_10m_max')} km/h" + (f" from {compass(wind)}" if wind is not None else ""),
    ])


def _int(arguments, key, default, low, high):
    try:
        return max(low, min(high, int(float(str(arguments.get(key, default)).replace(",", ".")))))
    except ValueError:
        return default


def forecast(arguments):
    place = str(arguments.get("place", "")).strip()
    if not place:
        raise ToolError("The place is missing.")
    day = DAYS.get(" ".join(str(arguments.get("day") or "today").strip().lower().split()))
    if day is None:
        raise ToolError("day must be today, tomorrow or day after tomorrow.")
    start, end = _int(arguments, "from_hour", 6, 0, 23), _int(arguments, "to_hour", 21, 1, 24)
    # wttr.in antwortet bei einem unbekannten Ort mit Klartext ("location not found"), nicht mit JSON
    raw = fetch(f"https://wttr.in/{quote(place)}?format=j1")
    try:
        data = json.loads(raw)
    except ValueError:
        if "not found" in raw.lower() or "unknown location" in raw.lower():
            raise ToolError(NOT_FOUND % place)
        raise ToolError("The weather service returned no valid answer.")
    return forecast_text(data, day, start, max(end, start + 1), _int(arguments, "step", 3, 1, 12))


def pick_place(results, hint):
    """Mehrdeutiger Ortsname ('Langen'): bei Zusatz ('Langen, Hessen') den passenden Treffer wählen, sonst den ersten."""
    hint = hint.strip().lower()
    for found in results:
        if hint and hint in " ".join(str(found.get(k, "")) for k in ("admin1", "admin2", "country")).lower():
            return found
    return results[0]


def historical(arguments):
    place = str(arguments.get("place", "")).strip()
    if not place:
        raise ToolError("The place is missing.")
    day = parse_date(arguments.get("date"))
    if day > date.today():
        raise ToolError("That date is in the future; use the forecast instead.")
    coords = re.fullmatch(r"([-+]?\d{1,2}(?:\.\d+)?),\s*([-+]?\d{1,3}(?:\.\d+)?)", place)
    if coords:
        lat, lon, name = coords[1], coords[2], place
    else:
        name_part, _, hint = place.partition(",")
        geo = fetch_json(f"https://geocoding-api.open-meteo.com/v1/search?name={quote(name_part.strip())}"
                         f"&count=10&language=en&format=json")
        if not geo.get("results"):
            raise ToolError(NOT_FOUND % place)
        found = pick_place(geo["results"], hint)
        lat, lon = found["latitude"], found["longitude"]
        name = ", ".join(filter(None, (found.get("name"), found.get("admin1"))))
    daily_keys = ("temperature_2m_max,temperature_2m_min,precipitation_sum,weathercode,"
                  "windspeed_10m_max,winddirection_10m_dominant")
    data = fetch_json(f"https://archive-api.open-meteo.com/v1/archive?latitude={lat}&longitude={lon}"
                      f"&start_date={day.isoformat()}&end_date={day.isoformat()}&daily={daily_keys}&timezone=auto")
    return historical_text(name, day, data.get("daily") or {})


TOOLS = [
    tool("forecast", "Weather forecast for a place, hour by hour (today, tomorrow or day after tomorrow).",
         {"place": ("string", "City, postal code or 'latitude, longitude'"),
          "day": ("string", "today, tomorrow or day after tomorrow"),
          "from_hour": ("integer", "First hour (0-23), default 6"), "to_hour": ("integer", "Last hour, default 21"),
          "step": ("integer", "Hours between lines, default 3")}, ["place"]),
    tool("historical", "Weather on a past day (temperature, precipitation, wind).",
         {"place": ("string", "City, optionally with region ('Langen, Hesse') or 'latitude, longitude'"),
          "date": ("string", "yesterday, day before yesterday, 2020-11-25 or 25.11.2020")}, ["place", "date"]),
]

if __name__ == "__main__":
    serve("weather", TOOLS, {"forecast": forecast, "historical": historical})
