"""MCP-Server Websuche (stdio, nur Standardbibliothek, ohne API-Schlüssel). Beschreibungen und Ausgabe englisch.

search  Sucht über die HTML-Ausgabe von DuckDuckGo und liefert die ersten Treffer mit Titel, Adresse und Auszug.
Das Seitenformat ist nicht garantiert; ändert es sich, passt man nur parse_results() an."""
import html
import re
from urllib.parse import parse_qs, urlencode, urlparse

from mini_mcp import ToolError, fetch, serve, tool

LINK_RE = re.compile(r'<a[^>]+class="result__a"[^>]+href="(?P<url>[^"]+)"[^>]*>(?P<title>.*?)</a>', re.S)
SNIPPET_RE = re.compile(r'<a[^>]+class="result__snippet"[^>]*>(?P<text>.*?)</a>', re.S)


def clean(fragment):
    return " ".join(html.unescape(re.sub(r"<[^>]+>", "", fragment)).split())


def real_url(url):
    """DuckDuckGo verpackt Links teils als //duckduckgo.com/l/?uddg=<Ziel>."""
    url = html.unescape(url)
    target = parse_qs(urlparse(url).query).get("uddg")
    return target[0] if target else url


def parse_results(page, limit):
    links = list(LINK_RE.finditer(page))
    results = []
    for index, link in enumerate(links[:limit]):
        end = links[index + 1].start() if index + 1 < len(links) else len(page)
        snippet = SNIPPET_RE.search(page, link.end(), end)
        results.append((clean(link["title"]), real_url(link["url"]), clean(snippet["text"]) if snippet else ""))
    return results


def format_results(results):
    return "\n".join(f"{i}. {title} ({url})\n   {snippet}".rstrip() for i, (title, url, snippet) in enumerate(results, 1))


def search(arguments):
    query = str(arguments.get("query", "")).strip()
    if not query:
        raise ToolError("The search query is missing.")
    try:
        limit = max(1, min(8, int(float(str(arguments.get("count", 5))))))
    except ValueError:
        limit = 5
    page = fetch("https://html.duckduckgo.com/html/", urlencode({"q": query, "kl": "wt-wt"}).encode())
    results = parse_results(page, limit)
    if not results:
        raise ToolError("No results (or the search service rejected the request).")
    return format_results(results)


TOOLS = [tool("search", "Searches the web and returns the top results with a short excerpt.",
              {"query": ("string", "What to search for"), "count": ("integer", "Number of results, default 5")},
              ["query"])]

if __name__ == "__main__":
    serve("websearch", TOOLS, {"search": search})
