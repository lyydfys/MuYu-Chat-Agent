# Android Permissions

MCA keeps the Android permission surface intentionally small.

## Declared Permissions

| Permission | Why it is used |
|---|---|
| `INTERNET` | Connect to user-configured cloud API endpoints, web search providers, ModelScope downloads, and the optional local API/web surfaces. |
| `ACCESS_NETWORK_STATE` | Detect network availability before cloud calls, downloads, and local API status checks. |

## Files and Models

MCA does not request broad storage permissions in the manifest. File and model
selection should happen through Android system pickers or app-managed storage.
Model files, downloaded assets, and generated images remain on device unless
the user exports, shares, or deletes them.

## Cloud Requests

When a cloud chat or image engine is selected, prompts and request parameters
are sent to the provider endpoint configured by the user. MCA does not bundle
provider keys or model weights.

## Web Search

Web search is disabled until the user configures and enables a search provider
in Settings. MCA supports manual, smart-auto, and always-on trigger modes. When
a turn triggers keyword web search, MCA sends the search query to the configured
SearxNG, Brave Search, Tavily, Jina Search, or custom JSON endpoint, then injects a shortened
source summary into that turn only. If the user provides a direct URL, MCA can
read that page after web search is enabled even when no search API is configured.
Source URLs may be fetched to improve answer quality when page fetching is
enabled. Provider endpoints may be self-hosted, but readable page fetching and
direct URL reading block localhost, private LAN, link-local, and reserved
addresses by default for safety. When Jina Search is selected with a key, MCA
may use Jina Reader to retrieve cleaned Markdown for public pages whose direct
readable content is too weak; this does not bypass the private-network guard.
Multiple direct URLs, expanded search queries, and fetched page bodies are read
concurrently to reduce latency. Successful keyword searches may be kept in a
short in-memory local cache to reduce repeated provider requests; direct URL
reads are not cached, and cache entries do not contain API keys. Search provider API keys are stored on device and encrypted with Android Keystore when
available. Recent search diagnostics are stored locally for troubleshooting and
can be cleared from the web search settings page; they include queries, status
summaries, latency, trigger reasons, partial expanded-query warnings, source
counts, source URLs, provider labels, source snippets, and closed-loop evidence
showing whether prompt context and source-card data were produced, but not API keys.
MCA also computes a local source quality score from
usable source count, readable content length, independent hosts, and safety
blocks; this score is used for diagnostics and prompt caution only. The web
search test in Settings uses the current form values, so it can verify a
provider or direct URL reading before saving the configuration or loading any
chat model. The closed-loop self-test simulates one chat turn and records
provider results, prompt context, source-card data, quality scoring, and cache
status. The optional public JSON self-check filler uses a no-key public endpoint
only to verify the integration path; user questions typed for that test are sent
to that endpoint just like any configured provider. Custom JSON endpoints may return a top-level array, an object
containing `results`, `items`, `data`, `hits`, or `organic_results`, or nested variants
such as `data.results`; common URL and title fields such as `html_url`,
`story_url`, `full_name`, and `story_title` are accepted. When smart query expansion creates multiple searches,
MCA keeps successful sources even if one expanded query fails. Public SearxNG instances may rate-limit, block automated
JSON requests, or change policy without notice; use a self-hosted SearxNG
instance, Brave Search, Tavily, Jina Search, or another trusted endpoint for
reliable keyword search.

## Diagnostics

Diagnostic logs are local files. Review them before sharing because they may
include model names, device information, prompts, generated text, or provider
error messages.
