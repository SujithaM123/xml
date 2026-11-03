function formatSize(bytes) {
  const mb = bytes / (1024 * 1024);
  return mb < 1 ? `${Math.round(bytes / 1024)} KB` : `${mb.toFixed(1)} MB`;
}

async function showSize(url, elementId) {
  try {
    const res = await fetch(url, { method: "HEAD" });
    const bytes = Number(res.headers.get("content-length"));
    if (!bytes) return;
    document.getElementById(elementId).textContent = formatSize(bytes);
  } catch (e) {
    // size display is best-effort; ignore failures
  }
}

async function showTotalSize(urls, elementId) {
  try {
    const sizes = await Promise.all(urls.map(async (url) => {
      const res = await fetch(url, { method: "HEAD" });
      return Number(res.headers.get("content-length")) || 0;
    }));
    const total = sizes.reduce((a, b) => a + b, 0);
    if (!total) return;
    document.getElementById(elementId).textContent = `${formatSize(total)} total (${urls.length} parts)`;
  } catch (e) {
    // size display is best-effort; ignore failures
  }
}

showSize("xml-toolkit.zip", "size-toolkit");
showSize("benchmark.zip", "size-benchmark");
showSize("xml-toolkit.txt", "size-text");
showTotalSize(
  ["benchmark.txt.part001", "benchmark.txt.part002", "benchmark.txt.part003", "benchmark.txt.part004"],
  "size-benchmark-text"
);
showTotalSize(
  ["xml-full.txt.zip.part001", "xml-full.txt.zip.part002", "xml-full.txt.zip.part003"],
  "size-xml-full"
);
showSize("benchmark-full.txt.zip", "size-benchmark-full");
