async function showSize(url, elementId) {
  try {
    const res = await fetch(url, { method: "HEAD" });
    const bytes = Number(res.headers.get("content-length"));
    if (!bytes) return;
    const mb = bytes / (1024 * 1024);
    const label = mb < 1 ? `${Math.round(bytes / 1024)} KB` : `${mb.toFixed(1)} MB`;
    document.getElementById(elementId).textContent = label;
  } catch (e) {
    // size display is best-effort; ignore failures
  }
}

showSize("xml-toolkit.zip", "size-toolkit");
showSize("benchmark.zip", "size-benchmark");
showSize("xml-toolkit.txt", "size-text");
