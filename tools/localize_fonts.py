"""Bundle the pages' web fonts into the APK so the app never waits on Google Fonts.

Run at build time (GitHub Actions). For every page in app/src/main/assets/www it:
  * downloads the Latin families from Google Fonts into www/fonts/ and rewrites the
    stylesheet link to the local copy;
  * maps the Chinese families (Noto Sans SC / Noto Serif SC) to the phone's own
    Noto CJK fonts with local(), since the full CJK files are tens of MB;
  * if anything cannot be downloaded, drops that link, so a page never blocks on it.
The source pages stay exactly as published; only the copies inside the build change.
"""
import hashlib
import os
import pathlib
import re
import sys
import urllib.parse
import urllib.request

WWW = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/www")
FONTS = WWW / "fonts"
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")

# Chinese families -> names Android (and other systems) install them under
CJK = {
    "Noto Sans SC": ["Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC",
                     "Source Han Sans CN", "NotoSansCJKsc-Regular"],
    "Noto Serif SC": ["Noto Serif CJK SC", "Noto Serif SC", "Source Han Serif SC",
                      "Source Han Serif CN", "NotoSerifCJKsc-Regular"],
}

LINK = re.compile(r'<link rel="stylesheet" href="(https://fonts\.googleapis\.com/css2\?[^"]+)">')
PRECONNECT = re.compile(r'<link rel="preconnect" href="https://fonts\.googleapis\.com">\n?')


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def weights(spec):
    # "wght@400;500" -> ["400", "500"];  no axis -> ["400"]
    m = re.search(r"wght@([\d;]+)", spec)
    return m.group(1).split(";") if m else ["400"]


def cjk_faces(name, ws):
    src = ", ".join('local("%s")' % n for n in CJK[name])
    return "".join(
        "@font-face{font-family:'%s';font-style:normal;font-weight:%s;font-display:swap;src:%s}\n"
        % (name, w, src) for w in ws)


def localize(page):
    html = page.read_text(encoding="utf-8")
    m = LINK.search(html)
    if not m:
        return
    url = m.group(1).replace("&amp;", "&")
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
    latin, css = [], ""
    for fam in q.get("family", []):
        name = fam.split(":")[0].replace("+", " ")
        if name in CJK:
            css += cjk_faces(name, weights(fam))
        else:
            latin.append(fam)

    if latin:
        gurl = "https://fonts.googleapis.com/css2?" + "&".join(
            "family=" + urllib.parse.quote(f, safe=":;@,+") for f in latin) + "&display=swap"
        try:
            gcss = get(gurl).decode("utf-8")

            def save(mm):
                furl = mm.group(1)
                ext = pathlib.PurePosixPath(urllib.parse.urlsplit(furl).path).suffix or ".woff2"
                fname = hashlib.sha1(furl.encode()).hexdigest()[:16] + ext
                out = FONTS / fname
                if not out.exists():
                    out.write_bytes(get(furl))
                return "url(%s)" % fname

            gcss = re.sub(r"url\((https://fonts\.gstatic\.com/[^)]+)\)", save, gcss)
            # unicode-range subsets the page never draws (cyrillic, greek, vietnamese) are tiny and only load on demand
            css = gcss + css
        except Exception as e:  # never let a font block the page
            if os.environ.get("CI"):
                raise  # on the build machine a failed download should stop the build, not ship without fonts
            print("  ! could not fetch %s: %s (system fonts will be used)" % (gurl, e))

    sheet = FONTS / (page.stem + ".css")
    sheet.write_text(css, encoding="utf-8")
    html = LINK.sub('<link rel="stylesheet" href="fonts/%s">' % sheet.name, html, count=1)
    html = PRECONNECT.sub("", html)
    page.write_text(html, encoding="utf-8")
    print("  %s -> fonts/%s" % (page.name, sheet.name))


def main():
    FONTS.mkdir(exist_ok=True)
    for page in sorted(WWW.glob("*.html")):
        localize(page)
    left = [p.name for p in WWW.glob("*.html") if "fonts.googleapis.com" in p.read_text(encoding="utf-8")]
    if left:
        sys.exit("Google Fonts still referenced in: " + ", ".join(left))
    total = sum(f.stat().st_size for f in FONTS.iterdir())
    print("bundled %d font files, %.0f KB" % (len(list(FONTS.iterdir())), total / 1024))


if __name__ == "__main__":
    main()
