"""MCP-Server Wetter (stdio, nur Standardbibliothek, ohne API-Schlüssel).

forecast   Vorhersage für heute/morgen/übermorgen in Stundenschritten (wttr.in)
historical Wetter an einem vergangenen Tag (Open-Meteo-Archiv)

Kleine Modelle rechnen nicht zuverlässig mit Daten: 'historical' versteht deshalb 'gestern', 'vorgestern',
'25.11.2020', '25.11.' und '2020-11-25' selbst."""
import re
from datetime import date, datetime, timedelta
from urllib.parse import quote

from mini_mcp import ToolError, fetch_json, serve, tool

DIRECTIONS = ["N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
DAYS = {"heute": 0, "morgen": 1, "uebermorgen": 2, "übermorgen": 2}
WEEKDAYS = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]
WMO = {0: "klar", 1: "überwiegend klar", 2: "teils bewölkt", 3: "bedeckt", 45: "Nebel", 48: "Reifnebel",
       51: "leichter Nieselregen", 53: "Nieselregen", 55: "starker Nieselregen", 56: "gefrierender Nieselregen",
       57: "gefrierender Nieselregen", 61: "leichter Regen", 63: "Regen", 65: "starker Regen",
       66: "gefrierender Regen", 67: "gefrierender Regen", 71: "leichter Schneefall", 73: "Schneefall",
       75: "starker Schneefall", 77: "Schneegriesel", 80: "leichte Regenschauer", 81: "Regenschauer",
       82: "heftige Regenschauer", 85: "leichte Schneeschauer", 86: "Schneeschauer", 95: "Gewitter",
       96: "Gewitter mit Hagel", 99: "Gewitter mit Hagel"}


def compass(degrees):
    return DIRECTIONS[round(float(degrees) / 22.5) % 16]


def parse_date(text, today=None):
    """'gestern', 'vorgestern', 'heute', '2020-11-25', '25.11.2020', '25.11.20', '25.11.' -> date."""
    today = today or date.today()
    word = str(text or "").strip().lower().rstrip(".")
    if word in ("", "gestern"):
        return today - timedelta(days=1)
    if word == "heute":
        return today
    if word == "vorgestern":
        return today - timedelta(days=2)
    match = re.fullmatch(r"(\d{4})-(\d{1,2})-(\d{1,2})", word)
    if match:
        year, month, day = map(int, match.groups())
    else:
        match = re.fullmatch(r"(\d{1,2})\.\s*(\d{1,2})\.?\s*(\d{2,4})?", str(text).strip().lower())
        if not match:
            raise ToolError(f"Datum '{text}' nicht verstanden. Erlaubt: gestern, vorgestern, 25.11.2020, 2020-11-25.")
        day, month = int(match[1]), int(match[2])
        year = int(match[3]) if match[3] else today.year
        if year < 100:
            year += 2000
        if not match[3] and date(year, month, day) > today:
            year -= 1  # '25.11.' ohne Jahr meint das letzte Mal, nicht die Zukunft
    try:
        return date(year, month, day)
    except ValueError as exc:
        raise ToolError(f"Datum '{text}' gibt es nicht.") from exc


def german_date(day):
    return f"{WEEKDAYS[day.weekday()]}, {day.day}.{day.month}.{day.year}"


def forecast_text(data, day_index, start, end, step):
    areas = (data.get("nearest_area") or [{}])[0]
    parts = [v[0]["value"] for v in (areas.get("areaName"), areas.get("region"), areas.get("country"))
             if v and v[0].get("value")]
    place = ", ".join(dict.fromkeys(parts))  # "Berlin, Berlin, Germany" -> "Berlin, Germany"
    days = data.get("weather") or []
    if day_index >= len(days):
        raise ToolError("Für diesen Tag gibt es keine Vorhersage.")
    day = days[day_index]
    lines = [f"Wetter für {place}, {german_date(datetime.strptime(day['date'], '%Y-%m-%d').date())}: "
             f"{day['mintempC']} bis {day['maxtempC']}°C"]
    for hour in day.get("hourly", []):
        clock = int(hour["time"]) // 100
        if not start <= clock < end or (clock - start) % step:
            continue
        desc = (hour.get("lang_de") or hour.get("weatherDesc") or [{"value": "?"}])[0]["value"]
        lines.append(f"{clock:02d}:00 {desc}, {hour['tempC']}°C (gefühlt {hour['FeelsLikeC']}°C), "
                     f"Regen {hour['chanceofrain']}%, Wind {compass(hour['winddirDegree'])} {hour['windspeedKmph']} km/h")
    if len(lines) == 1:
        raise ToolError("Im gewünschten Zeitraum gibt es keine Daten.")
    return "\n".join(lines)


def historical_text(place, day, daily):
    def value(key):
        return (daily.get(key) or [None])[0]

    if value("temperature_2m_max") is None:
        raise ToolError(f"Für {day.day}.{day.month}.{day.year} liegen noch keine Daten vor (das Archiv hinkt einige Tage hinterher).")
    code = value("weathercode")
    wind = value("winddirection_10m_dominant")
    return "\n".join([
        f"Wetter in {place} am {german_date(day)}: {WMO.get(code, 'unbekannt')}",
        f"Höchsttemperatur {value('temperature_2m_max')}°C, Tiefsttemperatur {value('temperature_2m_min')}°C",
        f"Niederschlag {value('precipitation_sum')} mm",
        f"Wind bis {value('windspeed_10m_max')} km/h" + (f" aus {compass(wind)}" if wind is not None else ""),
    ])


def _int(arguments, key, default, low, high):
    try:
        return max(low, min(high, int(float(str(arguments.get(key, default)).replace(",", ".")))))
    except ValueError:
        return default


def forecast(arguments):
    place = str(arguments.get("ort", "")).strip()
    if not place:
        raise ToolError("Es fehlt der Ort.")
    day = DAYS.get(str(arguments.get("tag") or "heute").strip().lower())
    if day is None:
        raise ToolError("Tag muss heute, morgen oder uebermorgen sein.")
    start, end = _int(arguments, "von", 6, 0, 23), _int(arguments, "bis", 21, 1, 24)
    data = fetch_json(f"https://wttr.in/{quote(place)}?format=j1&lang=de")
    return forecast_text(data, day, start, max(end, start + 1), _int(arguments, "schritt", 3, 1, 12))


def historical(arguments):
    place = str(arguments.get("ort", "")).strip()
    if not place:
        raise ToolError("Es fehlt der Ort.")
    day = parse_date(arguments.get("datum"))
    if day > date.today():
        raise ToolError("Das Datum liegt in der Zukunft; dafür gibt es die Vorhersage.")
    coords = re.fullmatch(r"([-+]?\d{1,2}(?:\.\d+)?),\s*([-+]?\d{1,3}(?:\.\d+)?)", place)
    if coords:
        lat, lon, name = coords[1], coords[2], place
    else:
        geo = fetch_json(f"https://geocoding-api.open-meteo.com/v1/search?name={quote(place)}&count=1&language=de&format=json")
        if not geo.get("results"):
            raise ToolError(f"Ort '{place}' nicht gefunden.")
        found = geo["results"][0]
        lat, lon = found["latitude"], found["longitude"]
        name = ", ".join(filter(None, (found.get("name"), found.get("admin1"))))
    daily_keys = ("temperature_2m_max,temperature_2m_min,precipitation_sum,weathercode,"
                  "windspeed_10m_max,winddirection_10m_dominant")
    data = fetch_json(f"https://archive-api.open-meteo.com/v1/archive?latitude={lat}&longitude={lon}"
                      f"&start_date={day.isoformat()}&end_date={day.isoformat()}&daily={daily_keys}&timezone=auto")
    return historical_text(name, day, data.get("daily") or {})


TOOLS = [
    tool("forecast", "Wettervorhersage für einen Ort, stündlich (heute, morgen oder übermorgen).",
         {"ort": ("string", "Stadt, Postleitzahl oder 'Breite, Länge'"),
          "tag": ("string", "heute, morgen oder uebermorgen"),
          "von": ("integer", "Erste Stunde (0-23), Standard 6"), "bis": ("integer", "Letzte Stunde, Standard 21"),
          "schritt": ("integer", "Stunden zwischen den Zeilen, Standard 3")}, ["ort"]),
    tool("historical", "Wetter an einem vergangenen Tag (Temperatur, Niederschlag, Wind).",
         {"ort": ("string", "Stadt oder 'Breite, Länge'"),
          "datum": ("string", "gestern, vorgestern, 25.11.2020 oder 2020-11-25")}, ["ort", "datum"]),
]

if __name__ == "__main__":
    serve("wetter", TOOLS, {"forecast": forecast, "historical": historical})
